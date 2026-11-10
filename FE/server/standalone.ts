import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { fileURLToPath } from 'node:url';
import { createApiHandler, writeInternalError } from './api.ts';

/**
 * 독립 실행 API 서버 — 정적 호스팅(S3+CloudFront, Amplify 등) 뒤에서 `/api/*` 만 담당합니다.
 *
 *   npm run api              # http://127.0.0.1:8787
 *   API_PORT=9000 npm run api
 *
 * FE/.env 의 JUSO_API_KEY / JUSO_COORD_API_KEY / VWORLD_API_KEY / VWORLD_DOMAIN 을 읽습니다
 * (실행 위치와 무관하게 이 파일 기준 ../.env). FE 빌드에는 VITE_API_BASE=https://api.example.com 처럼 이 서버 주소를 넣어 주세요.
 *
 * 환경 변수 (.env.example 참고)
 *   API_HOST            바인드 주소. 기본은 개발 127.0.0.1 / 운영(NODE_ENV=production) 0.0.0.0
 *   CORS_ORIGIN         허용 origin (쉼표 구분). 비우면 어떤 origin 에도 CORS 헤더를 주지 않습니다(브라우저 차단). '*' 는 직접 적어야 하고 운영에서는 거부
 *   RATE_LIMIT_PER_MIN  IP 당 분당 요청 수 (기본 60, 0 이면 제한 끔 — WAF 등 바깥 장치가 대신할 때)
 *   TRUST_PROXY         앞단 프록시(CloudFront·ALB·nginx) 단계 수. 0(기본)이면 TCP 상대 주소를, N 이면 X-Forwarded-For 의 오른쬭에서 N 번째를 클라이언트 IP 로 봅니다
 *   UPSTREAM_PER_MIN / UPSTREAM_MAX_INFLIGHT   공공 API 호출 예산 (api.ts)
 */
const envPath = fileURLToPath(new URL('../.env', import.meta.url));
try {
  process.loadEnvFile?.(envPath);
} catch {
  console.warn(`[api] ${envPath} 를 찾지 못해 프로세스 환경 변수만 사용합니다.`);
}

const isProduction = process.env.NODE_ENV === 'production';
const port = Number(process.env.API_PORT) || 8787;
const host = process.env.API_HOST?.trim() || (isProduction ? '0.0.0.0' : '127.0.0.1');
const ratePerMinute = readNonNegativeInt(process.env.RATE_LIMIT_PER_MIN, 60);
const trustProxy = readNonNegativeInt(process.env.TRUST_PROXY, 0);

// ── CORS: 적어 준 origin 만 허용 (입력은 URL 로 정규화 — 끝 슬래시·대문자 때문에 조용히 실패하지 않도록) ──
const rawOrigins = (process.env.CORS_ORIGIN ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const allowAny = rawOrigins.includes('*');
const allowedOrigins = new Set<string>();
for (const raw of rawOrigins) {
  if (raw === '*') continue;
  try {
    const normalized = new URL(raw.includes('://') ? raw : `https://${raw}`).origin;
    if (normalized !== raw) console.warn(`[api] CORS_ORIGIN '${raw}' 를 '${normalized}' 로 정규화했습니다.`);
    allowedOrigins.add(normalized);
  } catch {
    console.error(`[api] CORS_ORIGIN 항목을 origin 으로 읽을 수 없습니다: '${raw}' (예: https://app.example.com)`);
    process.exit(2);
  }
}
if (isProduction && allowAny) {
  console.error(
    '[api] 운영(NODE_ENV=production)에서는 CORS_ORIGIN 에 FE 도메인을 지정해야 합니다 (예: https://app.example.com). "*" 는 쓸 수 없습니다.',
  );
  process.exit(2);
}
if (!allowAny && allowedOrigins.size === 0) {
  console.error(
    '[api] CORS_ORIGIN 이 비어 있어 브라우저에서 오는 교차 출처 요청은 모두 차단됩니다. 개발 중이면 CORS_ORIGIN=http://localhost:5173 을 지정하세요.',
  );
}

const handler = createApiHandler({
  jusoKey: process.env.JUSO_API_KEY,
  coordKey: process.env.JUSO_COORD_API_KEY,
  vworldKey: process.env.VWORLD_API_KEY,
  vworldDomain: process.env.VWORLD_DOMAIN,
  upstreamPerMinute: readNonNegativeInt(process.env.UPSTREAM_PER_MIN, 600),
  upstreamMaxInflight: readNonNegativeInt(process.env.UPSTREAM_MAX_INFLIGHT, 32),
});

// ── IP 당 토큰 버킷 (분당 ratePerMinute) ─────────────────────────────
const MAX_BUCKETS = 10_000;
const buckets = new Map<string, { tokens: number; updatedAt: number }>();
function allowRequest(ip: string): boolean {
  if (ratePerMinute === 0) return true;
  const now = Date.now();
  const bucket = buckets.get(ip) ?? { tokens: ratePerMinute, updatedAt: now };
  bucket.tokens = Math.min(ratePerMinute, bucket.tokens + ((now - bucket.updatedAt) / 60_000) * ratePerMinute);
  bucket.updatedAt = now;
  buckets.delete(ip);
  buckets.set(ip, bucket);
  while (buckets.size > MAX_BUCKETS) {
    const oldest = buckets.keys().next().value;
    if (oldest === undefined) break;
    buckets.delete(oldest);
  }
  if (bucket.tokens < 1) return false;
  bucket.tokens -= 1;
  return true;
}
setInterval(() => {
  const cutoff = Date.now() - 5 * 60_000;
  for (const [ip, b] of buckets) if (b.updatedAt < cutoff) buckets.delete(ip);
}, 60_000).unref();

/** 클라이언트 IP — TRUST_PROXY 단계만큼 X-Forwarded-For 를 신뢰합니다 */
export function clientIp(req: IncomingMessage, hops: number): string {
  const peer = normalizeIp(req.socket.remoteAddress ?? 'unknown');
  if (hops <= 0) return peer;
  const header = req.headers['x-forwarded-for'];
  const raw = Array.isArray(header) ? header.join(',') : (header ?? '');
  const chain = raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const picked = chain[chain.length - hops];
  return picked ? normalizeIp(picked) : peer;
}

function normalizeIp(ip: string): string {
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip;
}

function readNonNegativeInt(raw: string | undefined, fallback: number): number {
  if (raw === undefined || raw.trim() === '') return fallback;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : fallback;
}

function applyCors(req: IncomingMessage, res: ServerResponse): void {
  const origin = req.headers.origin;
  const value = allowAny ? '*' : origin && allowedOrigins.has(origin) ? origin : undefined;
  if (value) res.setHeader('Access-Control-Allow-Origin', value);
  res.setHeader('Vary', 'Origin');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Accept, Content-Type');
}

function jsonError(res: ServerResponse, status: number, error: string, message: string): void {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ error, message }));
}

const server = createServer((req, res) => {
  applyCors(req, res);
  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    res.end();
    return;
  }
  if (!req.url?.startsWith('/api/')) {
    jsonError(res, 404, 'NOT_FOUND', '/api/ 아래 경로만 제공합니다.');
    return;
  }
  if (!allowRequest(clientIp(req, trustProxy))) {
    res.setHeader('Retry-After', '60');
    jsonError(res, 429, 'RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시 시도하세요.');
    return;
  }
  handler(req, res).catch((err: unknown) => writeInternalError(res, err));
});

server.listen(port, host, () => {
  const juso = process.env.JUSO_API_KEY ? 'juso.go.kr 연동' : '목업 주소 (JUSO_API_KEY 미설정)';
  const vworld = process.env.VWORLD_API_KEY ? '브이월드 연동' : '목업 필지만 (VWORLD_API_KEY 미설정)';
  const cors = allowAny
    ? 'CORS: 모든 origin (개발용)'
    : allowedOrigins.size
      ? `CORS: ${[...allowedOrigins].join(', ')}`
      : 'CORS: 없음(차단)';
  const limit = ratePerMinute ? `${ratePerMinute}req/min/IP` : 'IP 제한 끔';
  const proxy = trustProxy ? `TRUST_PROXY=${trustProxy}` : '프록시 신뢰 없음';
  console.log(`[api] http://${host}:${port}/api  ·  ${juso}  ·  ${vworld}  ·  ${cors}  ·  ${limit}  ·  ${proxy}`);
});
