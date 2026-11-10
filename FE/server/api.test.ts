import { readFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import type { Parcel } from '../shared/types.ts';
import { MESSAGES, createApiHandler, type ApiOptions } from './api.ts';
import type { FetchLike } from './budget.ts';

const mockParcelsPath = new URL('../src/mock/parcels.json', import.meta.url);
const mockParcels = JSON.parse(readFileSync(mockParcelsPath, 'utf8')) as Parcel[];
const UNKNOWN_PNU = '2726010100199990001';
const SECRET = 'upstream-secret-detail-9f3a';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

const vworldNotFound: FetchLike = async () => jsonResponse({ response: { status: 'NOT_FOUND' } });

const servers: Server[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => new Promise<void>((resolve) => s.close(() => resolve()))));
});

async function serve(opts: Partial<ApiOptions> = {}) {
  const handler = createApiHandler({ mockParcelsPath, ...opts });
  const server = createServer((req, res) => {
    handler(req, res).catch(() => {
      res.statusCode = 500;
      res.end();
    });
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
  const { port } = server.address() as AddressInfo;
  return async (path: string) => {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, { headers: { Accept: 'application/json' } });
    const text = await res.text();
    return { status: res.status, headers: res.headers, text, body: (text ? JSON.parse(text) : null) as Record<string, unknown> | null };
  };
}

describe('/api 핸들러 — 입력 검증', () => {
  it('PNU 형식이 아니면 400 BAD_PNU, 범위 밖 좌표는 400 BAD_POINT, 긴 검색어는 400 BAD_QUERY', async () => {
    const get = await serve();
    expect((await get('/api/parcel?pnu=abc')).body).toMatchObject({ error: 'BAD_PNU' });
    expect((await get('/api/parcel?pnu=abc')).status).toBe(400);
    expect((await get('/api/parcel/at?lng=0&lat=0')).body).toMatchObject({ error: 'BAD_POINT' });
    expect((await get(`/api/address?q=${'가'.repeat(101)}`)).body).toMatchObject({ error: 'BAD_QUERY' });
  });

  it('GET 외 메서드는 405, 모르는 경로는 404 (경로를 되비치지 않음)', async () => {
    const get = await serve();
    const unknown = await get('/api/<script>');
    expect(unknown.status).toBe(404);
    expect(unknown.text).not.toContain('script');
  });
});

describe('/api 핸들러 — 목업 경로', () => {
  it('목업 PNU 는 provider mock 으로 200', async () => {
    const get = await serve();
    const res = await get(`/api/parcel?pnu=${mockParcels[0].pnu}`);
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ pnu: mockParcels[0].pnu, areaSource: 'mock', source: { provider: 'mock' } });
  });

  it('브이월드 키가 없으면 모르는 PNU·지도 클릭은 501 NOT_READY', async () => {
    const get = await serve();
    expect((await get(`/api/parcel?pnu=${UNKNOWN_PNU}`)).status).toBe(501);
    expect((await get('/api/parcel/at?lng=128.63&lat=35.86')).body).toMatchObject({ error: 'NOT_READY' });
  });

  it('주소 승인키가 없으면 목업 주소에서 검색 (source mock)', async () => {
    const get = await serve();
    const res = await get('/api/address?q=범어');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ source: 'mock' });
    expect((res.body!.hits as unknown[]).length).toBeGreaterThan(0);
    expect(res.headers.get('cache-control')).toBe('no-store');
  });
});

describe('/api 핸들러 — 공공 API 오류를 고정 문구로 바꾼다', () => {
  it('시간 초과는 504 UPSTREAM_TIMEOUT', async () => {
    const timeout: FetchLike = async () => {
      const err = new Error('The operation was aborted due to timeout');
      err.name = 'TimeoutError';
      throw err;
    };
    const get = await serve({ vworldKey: 'KEY', fetchImpl: timeout });
    const res = await get(`/api/parcel?pnu=${UNKNOWN_PNU}`);
    expect(res.status).toBe(504);
    expect(res.body).toEqual({ error: 'UPSTREAM_TIMEOUT', message: MESSAGES.timeout });
  });

  it('인증키 오류는 502 UPSTREAM_CONFIG 이고 공공 API 원문은 응답에 들어가지 않는다', async () => {
    const badKey: FetchLike = async () =>
      jsonResponse({ response: { status: 'ERROR', error: { level: '1', code: 'INCORRECT_KEY', text: `등록되지 않은 인증키 ${SECRET}` } } });
    const get = await serve({ vworldKey: 'KEY', fetchImpl: badKey });
    const res = await get(`/api/parcel?pnu=${UNKNOWN_PNU}`);
    expect(res.status).toBe(502);
    expect(res.body).toMatchObject({ error: 'UPSTREAM_CONFIG', code: 'INCORRECT_KEY', message: MESSAGES.vworldConfig });
    expect(res.text).not.toContain(SECRET);
  });

  it('예상 밖 예외는 500 INTERNAL 이고 예외 문구는 새지 않는다', async () => {
    const boom: FetchLike = async () => {
      throw new Error(SECRET);
    };
    const get = await serve({ vworldKey: 'KEY', fetchImpl: boom });
    const res = await get(`/api/parcel?pnu=${UNKNOWN_PNU}`);
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'INTERNAL', message: MESSAGES.internal });
    expect(res.text).not.toContain(SECRET);
  });

  it('HTTP 오류 응답은 502 UPSTREAM 고정 문구', async () => {
    const http500: FetchLike = async () => new Response(SECRET, { status: 500 });
    const get = await serve({ vworldKey: 'KEY', fetchImpl: http500 });
    const res = await get('/api/parcel/at?lng=128.63&lat=35.86');
    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'UPSTREAM', message: MESSAGES.upstream });
  });
});

describe('/api 핸들러 — 호출 예산과 캐시', () => {
  it('분당 예산을 넘으면 503 BUSY + Retry-After', async () => {
    const get = await serve({ vworldKey: 'KEY', fetchImpl: vworldNotFound, upstreamPerMinute: 1 });
    expect((await get('/api/parcel/at?lng=128.63&lat=35.86')).status).toBe(404);
    const busy = await get('/api/parcel/at?lng=128.64&lat=35.87');
    expect(busy.status).toBe(503);
    expect(busy.body).toEqual({ error: 'BUSY', message: MESSAGES.busy });
    expect(busy.headers.get('retry-after')).toBe('30');
  });

  it('같은 PNU·같은 지점은 캐시에서 답해 공공 API 를 다시 부르지 않는다', async () => {
    let calls = 0;
    const counting: FetchLike = async (input) => {
      calls += 1;
      return vworldNotFound(input);
    };
    const get = await serve({ vworldKey: 'KEY', fetchImpl: counting });
    await get(`/api/parcel?pnu=${UNKNOWN_PNU}`);
    const afterFirst = calls;
    expect(afterFirst).toBeGreaterThan(0);
    await get(`/api/parcel?pnu=${UNKNOWN_PNU}`);
    expect(calls).toBe(afterFirst);

    await get('/api/parcel/at?lng=128.6300001&lat=35.8600001');
    const afterPoint = calls;
    await get('/api/parcel/at?lng=128.6300004&lat=35.8600004'); // 약 1m 격자 안 → 캐시
    expect(calls).toBe(afterPoint);
  });
});
