import { readFileSync } from 'node:fs';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { PNU_RE, type AddressHit, type AddressSearchResult, type Parcel, type ParcelAtResult } from '../shared/types.ts';
import { BusyError, TtlCache, createBudgetedFetch, type FetchLike } from './budget.ts';
import { JusoError, searchJuso } from './juso.ts';
import { VworldError, fetchVworldParcel, getParcelAtPoint, type VworldKeys } from './vworld.ts';

/**
 * `/api/*` 핸들러 — Vite 개발 서버 미들웨어(vitePlugin.ts)와 독립 실행 서버(standalone.ts)가 함께 씁니다.
 * 공공 API 승인키는 이 프로세스 안에서만 쓰이고 브라우저로 나가지 않습니다.
 *
 *   GET /api/health                   키 설정 여부
 *   GET /api/address?q=&limit=&page=  주소 검색 → AddressSearchResult (키 없으면 목업, source: 'mock')
 *   GET /api/parcel?pnu=              필지 → Parcel  (목업에 있으면 목업, 아니면 브이월드; 키 없으면 501 NOT_READY, 없는 필지면 404)
 *   GET /api/parcel/at?lng=&lat=      지도 클릭 → { pnu } (브이월드 연속지적도, 키 없으면 501)
 *
 * 보호 장치
 *  - 공공 API 호출마다 시간 제한(UPSTREAM_TIMEOUT_MS), 브라우저가 요청을 끊으면 함께 취소
 *  - 프로세스 전체 공공 API 예산(분당 호출 수·동시 호출 수) — 넘으면 503 BUSY (budget.ts)
 *  - 필지·클릭 지점 조회 결과는 TTL+LRU 캐시 (지적 데이터는 거의 바뀌지 않음)
 *  - 오류 응답의 message 는 사용자에게 그대로 보이므로 고정 문구만 쓰고, 원인은 서버 로그에만 남깁니다.
 *
 * 이 서버는 BE(Spring) 연동 전까지의 FE 자체 개발용 API 입니다. 응답 모양은 shared/types.ts 가 기준입니다.
 */
export interface ApiOptions {
  jusoKey?: string;
  coordKey?: string;
  vworldKey?: string;
  /** 브이월드 키 발급 때 등록한 서비스 도메인 (기본 localhost:5173) */
  vworldDomain?: string;
  /** src/mock/parcels.json 경로 (기본 DEFAULT_MOCK_PARCELS_URL) */
  mockParcelsPath?: string | URL;
  /** 공공 API 한 요청의 시간 제한 (ms). 기본 8000 */
  upstreamTimeoutMs?: number;
  /** 프로세스 전체 공공 API 호출 예산 — 분당 (기본 600, 0 이면 제한 없음) */
  upstreamPerMinute?: number;
  /** 프로세스 전체 공공 API 동시 호출 상한 (기본 32, 0 이면 제한 없음) */
  upstreamMaxInflight?: number;
  /** 필지 캐시 항목 수 (기본 500) · 보관 시간 (기본 24h) */
  cacheMax?: number;
  cacheTtlMs?: number;
  /** 테스트용 fetch 대체 */
  fetchImpl?: FetchLike;
}

export type ApiHandler = (req: IncomingMessage, res: ServerResponse) => Promise<void>;

/** 목업 필지 파일 — vite.config.ts 와 standalone.ts 가 같은 기본값을 씁니다 */
export const DEFAULT_MOCK_PARCELS_URL = new URL('../src/mock/parcels.json', import.meta.url);

const DEFAULT_VWORLD_DOMAIN = 'localhost:5173';
const DEFAULT_UPSTREAM_TIMEOUT_MS = 8000;
const DEFAULT_UPSTREAM_PER_MINUTE = 600;
const DEFAULT_UPSTREAM_MAX_INFLIGHT = 32;
const DEFAULT_CACHE_MAX = 500;
const DEFAULT_CACHE_TTL_MS = 24 * 60 * 60_000;
const MAX_QUERY_LENGTH = 100;
const BUSY_RETRY_AFTER_SECONDS = 30;

/** 사용자에게 보이는 고정 문구 — 공공 API 가 돌려준 원문은 절대 섞지 않습니다 */
export const MESSAGES = {
  internal: '서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.',
  upstream: '공공 API 응답에 문제가 있습니다. 잠시 후 다시 시도하세요.',
  timeout: '공공 API 응답이 지연되고 있습니다. 잠시 후 다시 시도하세요.',
  busy: '요청이 많아 잠시 대기 중입니다. 잠시 후 다시 시도하세요.',
  jusoConfig: '도로명주소 API 설정에 문제가 있습니다. 관리자에게 알려 주세요.',
  vworldConfig: '브이월드 API 설정에 문제가 있습니다. 관리자에게 알려 주세요.',
  methodNotAllowed: 'GET 만 지원합니다.',
  unknownPath: '없는 API 경로입니다.',
  badQuery: `검색어는 ${MAX_QUERY_LENGTH}자 이하여야 합니다.`,
  badPnu: 'PNU 는 숫자 19자리여야 합니다.',
  badPoint: 'lng·lat 는 대한민국 범위의 위경도여야 합니다.',
  parcelNotReady: '필지 경계·면적·용도지역 조회에는 브이월드 인증키(VWORLD_API_KEY)가 필요합니다.',
  pointNotReady: '지도 클릭 필지 조회에는 브이월드 인증키(VWORLD_API_KEY)가 필요합니다.',
  parcelNotFound: '브이월드 연속지적도에서 해당 PNU 를 찾지 못했습니다.',
  pointNotFound: '이 위치에서 필지를 찾지 못했습니다.',
} as const;

export function createApiHandler(opts: ApiOptions): ApiHandler {
  // 목업 필지는 시작할 때 한 번 읽습니다 — 파일 문제가 있으면 요청마다가 아니라 시작 시점에 드러나도록
  const mockParcels = JSON.parse(readFileSync(opts.mockParcelsPath ?? DEFAULT_MOCK_PARCELS_URL, 'utf8')) as Parcel[];
  const jusoKey = opts.jusoKey?.trim() || undefined;
  const coordKey = opts.coordKey?.trim() || undefined;
  const vworld: VworldKeys | undefined = opts.vworldKey?.trim()
    ? { key: opts.vworldKey.trim(), domain: opts.vworldDomain?.trim() || DEFAULT_VWORLD_DOMAIN }
    : undefined;
  const timeoutMs = opts.upstreamTimeoutMs ?? DEFAULT_UPSTREAM_TIMEOUT_MS;

  // 모든 공공 API 호출은 이 fetch 를 지나갑니다 (예산·동시성)
  const upstream = createBudgetedFetch(opts.fetchImpl ?? ((input, init) => fetch(input, init)), {
    perMinute: opts.upstreamPerMinute ?? DEFAULT_UPSTREAM_PER_MINUTE,
    maxInflight: opts.upstreamMaxInflight ?? DEFAULT_UPSTREAM_MAX_INFLIGHT,
  });
  const upstreamFetch = upstream.fetch as unknown as typeof fetch;
  const parcelCache = new TtlCache<Parcel | null>(opts.cacheMax ?? DEFAULT_CACHE_MAX, opts.cacheTtlMs ?? DEFAULT_CACHE_TTL_MS);
  const pointCache = new TtlCache<string | null>(opts.cacheMax ?? DEFAULT_CACHE_MAX, opts.cacheTtlMs ?? DEFAULT_CACHE_TTL_MS);

  return async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    res.setHeader('Cache-Control', 'no-store');

    // 브라우저가 떠나면 공공 API 호출도 함께 취소, 아니면 시간 제한까지만 기다립니다
    const clientGone = new AbortController();
    req.once('close', () => clientGone.abort());
    const signal = AbortSignal.any([clientGone.signal, AbortSignal.timeout(timeoutMs)]);

    try {
      if (req.method !== 'GET') return json(res, 405, { error: 'METHOD_NOT_ALLOWED', message: MESSAGES.methodNotAllowed });

      if (url.pathname === '/api/health') {
        return json(res, 200, { ok: true, juso: Boolean(jusoKey), coord: Boolean(coordKey), vworld: Boolean(vworld) });
      }

      if (url.pathname === '/api/address') {
        const q = (url.searchParams.get('q') ?? '').trim();
        const limit = clamp(Number(url.searchParams.get('limit')) || 10, 1, 50);
        const page = clamp(Number(url.searchParams.get('page')) || 1, 1, 100);
        if (q.length > MAX_QUERY_LENGTH) return json(res, 400, { error: 'BAD_QUERY', message: MESSAGES.badQuery });
        if (q.length < 2) return json(res, 200, { hits: [], total: 0, source: jusoKey ? 'juso' : 'mock' } satisfies AddressSearchResult);

        const result = jusoKey
          ? await searchJuso(q, { searchKey: jusoKey, coordKey }, { page, count: limit, signal }, upstreamFetch)
          : searchMock(mockParcels, q, limit);
        return json(res, 200, result);
      }

      if (url.pathname === '/api/parcel') {
        const pnu = url.searchParams.get('pnu') ?? '';
        if (!PNU_RE.test(pnu)) return json(res, 400, { error: 'BAD_PNU', message: MESSAGES.badPnu });

        const found = mockParcels.find((p) => p.pnu === pnu);
        if (found)
          return json(res, 200, { ...found, areaSource: 'mock', source: { provider: 'mock', queriedAt: today() } } satisfies Parcel);

        if (!vworld) return json(res, 501, { error: 'NOT_READY', message: MESSAGES.parcelNotReady });

        let parcel = parcelCache.get(pnu);
        if (parcel === undefined) {
          parcel = await fetchVworldParcel(pnu, vworld, upstreamFetch, signal);
          // 토지특성이 빠진(일시 장애로 비워진) 결과는 하루 동안 굳히지 않습니다
          if (parcel === null || parcel.land !== undefined) parcelCache.set(pnu, parcel);
        }
        if (!parcel) return json(res, 404, { error: 'NOT_FOUND', message: MESSAGES.parcelNotFound });
        res.setHeader('Cache-Control', 'private, max-age=3600');
        return json(res, 200, parcel);
      }

      if (url.pathname === '/api/parcel/at') {
        const lng = Number(url.searchParams.get('lng'));
        const lat = Number(url.searchParams.get('lat'));
        if (!Number.isFinite(lng) || !Number.isFinite(lat) || lat < 33 || lat > 39 || lng < 124 || lng > 132) {
          return json(res, 400, { error: 'BAD_POINT', message: MESSAGES.badPoint });
        }
        if (!vworld) return json(res, 501, { error: 'NOT_READY', message: MESSAGES.pointNotReady });

        // 약 1m 격자로 묶어 같은 자리를 다시 클릭하면 캐시에서 답합니다
        const cacheKey = `${lng.toFixed(5)},${lat.toFixed(5)}`;
        let pnu = pointCache.get(cacheKey);
        if (pnu === undefined) {
          const feature = await getParcelAtPoint({ lng, lat }, vworld, upstreamFetch, signal);
          pnu = feature?.pnu ?? null;
          pointCache.set(cacheKey, pnu);
        }
        if (!pnu) return json(res, 404, { error: 'NOT_FOUND', message: MESSAGES.pointNotFound });
        return json(res, 200, { pnu } satisfies ParcelAtResult);
      }

      return json(res, 404, { error: 'NOT_FOUND', message: MESSAGES.unknownPath });
    } catch (err) {
      if (clientGone.signal.aborted) {
        // 브라우저가 이미 떠남 — 응답을 쓰지 않고 연결만 정리
        res.destroy();
        return;
      }
      if (err instanceof BusyError) {
        console.warn('[api] 공공 API 예산 초과:', err.reason, url.pathname);
        res.setHeader('Retry-After', String(BUSY_RETRY_AFTER_SECONDS));
        return json(res, 503, { error: 'BUSY', message: MESSAGES.busy });
      }
      if (isTimeout(err)) {
        console.warn('[api] 공공 API 시간 초과:', url.pathname);
        return json(res, 504, { error: 'UPSTREAM_TIMEOUT', message: MESSAGES.timeout });
      }
      if (err instanceof JusoError && err.kind === 'config') {
        console.error('[api] 도로명주소 API 승인키 오류:', err.code, err.message);
        return json(res, 502, { error: 'UPSTREAM_CONFIG', code: err.code, message: MESSAGES.jusoConfig });
      }
      if (err instanceof VworldError && err.kind === 'config') {
        console.error('[api] 브이월드 인증 오류:', err.code, err.message);
        return json(res, 502, { error: 'UPSTREAM_CONFIG', code: err.code, message: MESSAGES.vworldConfig });
      }
      if (err instanceof JusoError || err instanceof VworldError) {
        console.warn('[api] 공공 API 오류:', url.pathname, err.code, err.message);
        return json(res, 502, { error: 'UPSTREAM', message: MESSAGES.upstream });
      }
      console.error('[api] 처리 중 오류:', url.pathname, err);
      return json(res, 500, { error: 'INTERNAL', message: MESSAGES.internal });
    }
  };
}

/** 고정 문구의 내부 오류 응답 — 미들웨어·독립 서버가 핸들러 바깥의 예외에 씁니다 */
export function writeInternalError(res: ServerResponse, err: unknown): void {
  console.error('[api] 처리 중 오류:', err);
  if (res.headersSent) {
    res.destroy();
    return;
  }
  json(res, 500, { error: 'INTERNAL', message: MESSAGES.internal });
}

/** 승인키가 없을 때: 목업 필지 목록에서 부분 일치 검색 (FE 1차 목업과 같은 규칙) */
export function searchMock(parcels: Parcel[], query: string, limit: number): AddressSearchResult {
  const tokens = normalize(query).split(' ').filter(Boolean);
  const matched = parcels.filter((p) => {
    const hay = normalize(`${p.address.road ?? ''} ${p.address.jibun} ${p.address.dong ?? ''} ${p.pnu}`);
    return tokens.every((t) => hay.includes(t));
  });
  const hits: AddressHit[] = matched.slice(0, limit).map((p) => ({
    pnu: p.pnu,
    roadAddress: p.address.road ?? p.address.jibun,
    jibunAddress: p.address.jibun,
    center: p.center,
    inSuseong: p.inSuseong,
  }));
  return { hits, total: matched.length, source: 'mock' };
}

function isTimeout(err: unknown): boolean {
  return err instanceof Error && (err.name === 'TimeoutError' || (err.name === 'AbortError' && /timeout/i.test(err.message)));
}

function normalize(s: string): string {
  return s
    .toLowerCase()
    .replace(/대구광역시|대구시/g, '대구')
    .replace(/\s+/g, ' ')
    .trim();
}

function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.floor(n)));
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function json(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}
