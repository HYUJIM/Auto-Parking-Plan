import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Parcel } from './types';
import { ApiError } from './types';
import { getParkingSimulation, getRuleReview, pollDelayMs } from './review';
import reviewProgress from './__fixtures__/review_progress.json';
import reviewS01 from './__fixtures__/review_S01.json';
import simulationS02 from './__fixtures__/simulation_S02.json';

const parcel = { pnu: '2726010100190010000' } as Parcel;

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

/** fetch 를 순서대로 응답하는 가짜로 바꿉니다 — 요청 기록은 calls 에 */
function stubFetch(responses: Array<() => Response>) {
  const calls: { url: string; init?: RequestInit }[] = [];
  let i = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      const next = responses[Math.min(i, responses.length - 1)];
      i++;
      return next();
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('pollDelayMs — Retry-After 를 따르되 1~10초', () => {
  it('없거나 0 이하면 1초, 소수·큰 값은 범위 안으로', () => {
    expect(pollDelayMs(null)).toBe(1000);
    expect(pollDelayMs('0')).toBe(1000);
    expect(pollDelayMs('abc')).toBe(1000);
    expect(pollDelayMs('1')).toBe(1000);
    expect(pollDelayMs('0.5')).toBe(1000);
    expect(pollDelayMs('2')).toBe(2000);
    expect(pollDelayMs('30')).toBe(10000);
  });
});

describe('getRuleReview — 중단과 일시 오류', () => {
  it('폴링 중에 중단하면 AbortError 로 끝나고 더는 서버를 부르지 않는다 (떠난 화면을 위해 계속 묻지 않음)', async () => {
    const calls = stubFetch([
      () => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }),
      () => json(200, reviewProgress, { 'Retry-After': '1' }),
    ]);
    const controller = new AbortController();
    const progress = vi.fn(() => controller.abort()); // 첫 진행 상태를 받은 직후 화면을 떠난다
    await expect(getRuleReview(parcel, { signal: controller.signal, onProgress: progress })).rejects.toMatchObject({ name: 'AbortError' });
    const seen = calls.length;
    await new Promise((r) => setTimeout(r, 30));
    expect(calls).toHaveLength(seen); // 중단 뒤 추가 호출 없음
    expect(progress).toHaveBeenCalledTimes(1);
  });

  it('폴링 중 429·503 은 Retry-After 만큼 기다렸다가 같은 검토를 이어서 확인한다 (새 검토를 만들지 않음)', async () => {
    const calls = stubFetch([
      () => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }),
      () => json(429, { error: { code: 'RATE_LIMITED', message: '요청이 너무 많습니다.' } }, { 'Retry-After': '0.01' }),
      () => json(503, { error: { code: 'SERVICE_BUSY', message: '바쁩니다.' } }, { 'Retry-After': '0.01' }),
      () => json(200, reviewS01),
    ]);
    vi.useFakeTimers();
    const pending = getRuleReview(parcel);
    await vi.advanceTimersByTimeAsync(5000);
    const review = await pending;
    expect(review.reviewId).toBeTruthy();
    expect(calls.filter((c) => c.init?.method === 'POST')).toHaveLength(1);
    expect(calls).toHaveLength(4);
  });

  it('일시 오류가 아닌 실패(404 등)는 바로 올린다', async () => {
    stubFetch([
      () => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }),
      () => json(404, { error: { code: 'REVIEW_NOT_FOUND', message: '없습니다.' } }),
    ]);
    await expect(getRuleReview(parcel)).rejects.toMatchObject({ code: 'NOT_FOUND' });
  });
});

describe('getRuleReview — POST /v1/reviews → 202 → GET 폴링', () => {
  it('진행 중 응답을 onProgress 로 알리고 items 가 오면 RuleReview 로 끝낸다', async () => {
    const calls = stubFetch([
      () => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }),
      () => json(200, reviewProgress, { 'Retry-After': '0.01' }),
      () => json(200, reviewS01),
    ]);
    const progress = vi.fn();
    const review = await getRuleReview(parcel, { onProgress: progress, audience: 'owner' });

    expect(calls).toHaveLength(3);
    expect(calls[0].url).toMatch(/\/v1\/reviews$/);
    expect(calls[0].init?.method).toBe('POST');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ pnu: parcel.pnu, audience: 'owner' });
    expect(calls[1].url).toMatch(/\/v1\/reviews\/01a1c5cf-d680-719d-a82c-612c939d4dbd$/);
    expect(progress).toHaveBeenCalledTimes(1);
    expect(progress.mock.calls[0][0].steps[2].state).toBe('running');
    expect(review.reviewId).toBe('01a1c5cf-d680-719d-a82c-612c939d4dbd');
    expect(review.items).toHaveLength(6);
  });

  it('status=failed 면 고정 문구의 ApiError(SERVER)', async () => {
    stubFetch([
      () => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }),
      () => json(200, { ...reviewProgress, status: 'failed', error: { code: 'INTERNAL_ERROR', message: 'NullPointerException' } }),
    ]);
    const err = await getRuleReview(parcel).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('SERVER');
    expect((err as ApiError).beCode).toBe('REVIEW_FAILED');
    // BE 내부 예외 이름은 화면에 내보내지 않습니다
    expect((err as ApiError).message).not.toContain('NullPointerException');
  });

  it('BE 가 PARCEL_NOT_FOUND(404) 로 거절하면 그 ApiError 를 그대로 던진다', async () => {
    stubFetch([() => json(404, { error: { code: 'PARCEL_NOT_FOUND', message: '해당 필지를 찾을 수 없습니다.' } })]);
    const err = await getRuleReview(parcel).catch((e: unknown) => e);
    expect((err as ApiError).code).toBe('NOT_FOUND');
    expect((err as ApiError).beCode).toBe('PARCEL_NOT_FOUND');
  });

  it('상한 시간을 넘기면 ApiError(SERVER, REVIEW_TIMEOUT)', async () => {
    stubFetch([() => json(202, { review_id: '01a1c5cf-d680-719d-a82c-612c939d4dbd' }), () => json(200, reviewProgress)]);
    const err = await getRuleReview(parcel, { timeoutMs: 500 }).catch((e: unknown) => e);
    expect((err as ApiError).beCode).toBe('REVIEW_TIMEOUT');
  });
});

describe('getParkingSimulation — POST /v1/reviews/{id}/parking-simulations', () => {
  it('include_unverified 는 켰을 때만 본문에 넣고, 응답을 ParkingSimulation 으로', async () => {
    const calls = stubFetch([() => json(200, simulationS02)]);
    const sim = await getParkingSimulation('01a1c5d5-18ef-7287-aac7-1996fd5e7470');
    expect(calls[0].url).toMatch(/\/v1\/reviews\/01a1c5d5-18ef-7287-aac7-1996fd5e7470\/parking-simulations$/);
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({});
    expect(sim.result!.alternatives).toHaveLength(3);

    await getParkingSimulation('01a1c5d5-18ef-7287-aac7-1996fd5e7470', { includeUnverified: true });
    expect(JSON.parse(String(calls[1].init?.body))).toEqual({ include_unverified: true });
  });
});
