import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Parcel } from './types';
import { resetReviewCache, reviewById, reviewForParcel, simulationFor } from './review';
import reviewS02 from './__fixtures__/review_S02.json';
import reviewProgress from './__fixtures__/review_progress.json';
import simulationS02 from './__fixtures__/simulation_S02.json';

const REVIEW_ID = reviewS02.review_id;
const parcel = { pnu: reviewS02.parcel.pnu } as Parcel;

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

/** 요청 경로·메서드에 따라 답하는 가짜 서버 — 호출 기록은 calls 에 */
function stubServer(handler: (method: string, path: string) => Response) {
  const calls: { method: string; path: string }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const path = new URL(url, 'http://test.invalid').pathname;
      calls.push({ method, path });
      return handler(method, path);
    }),
  );
  return calls;
}

beforeEach(() => resetReviewCache());
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('검토 기억 — 이용화면 → 결과 페이지 → 상세 페이지에서 검토를 다시 만들지 않는다', () => {
  it('같은 필지는 동시에 두 화면이 불러도 POST 한 번, 그 뒤 id 로 불러도 서버를 다시 부르지 않는다', async () => {
    const calls = stubServer((method, path) => {
      if (method === 'POST' && path === '/v1/reviews') return json(202, { review_id: REVIEW_ID });
      if (path === `/v1/reviews/${REVIEW_ID}`) return json(200, reviewS02);
      return json(404, {});
    });
    const [a, b] = await Promise.all([reviewForParcel(parcel), reviewForParcel(parcel)]);
    expect(a).toBe(b);
    expect(a.reviewId).toBe(REVIEW_ID);
    expect(a.parcel.landCategory).toBe(reviewS02.parcel.land_category);
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts).toHaveLength(1);
    const before = calls.length;
    const byId = await reviewById(REVIEW_ID);
    expect(byId).toBe(a);
    expect(calls).toHaveLength(before);
  });

  it('id 로 처음 부르면 GET 폴링만 하고 POST 하지 않으며, 그 필지로 다시 부르면 그 결과를 쓴다', async () => {
    let n = 0;
    const calls = stubServer((_method, path) => {
      if (path === `/v1/reviews/${REVIEW_ID}`) return n++ === 0 ? json(200, reviewProgress, { 'Retry-After': '0' }) : json(200, reviewS02);
      return json(500, {});
    });
    const progress = vi.fn();
    const r = await reviewById(REVIEW_ID, { onProgress: progress });
    expect(progress).toHaveBeenCalledTimes(1);
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
    const again = await reviewForParcel(parcel);
    expect(again).toBe(r);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('한 화면이 떠나도(중단) 진행 중인 검토는 계속되어 다음 화면이 이어받는다', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => (release = r));
    const calls = stubServer((method, path) => {
      if (method === 'POST') return json(202, { review_id: REVIEW_ID });
      if (path === `/v1/reviews/${REVIEW_ID}`) return json(200, reviewS02);
      return json(404, {});
    });
    const controller = new AbortController();
    const first = reviewForParcel(parcel, { signal: controller.signal });
    controller.abort();
    await expect(first).rejects.toMatchObject({ name: 'AbortError' });
    release();
    await gate;
    const second = await reviewForParcel(parcel);
    expect(second.reviewId).toBe(REVIEW_ID);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('실패한 검토는 기억하지 않아 다시 시도할 수 있다', async () => {
    let fail = true;
    stubServer((method, path) => {
      if (method === 'POST')
        return fail ? json(503, { error: { code: 'SERVICE_BUSY', message: '잠시 후' } }) : json(202, { review_id: REVIEW_ID });
      if (path === `/v1/reviews/${REVIEW_ID}`) return json(200, reviewS02);
      return json(404, {});
    });
    await expect(reviewForParcel(parcel)).rejects.toBeTruthy();
    fail = false;
    await expect(reviewForParcel(parcel)).resolves.toMatchObject({ reviewId: REVIEW_ID });
  });

  it('옛 검토를 id 로 다시 열어도 그 필지의 더 새 검토를 밀어내지 않는다 (뒤로 가기)', async () => {
    const OLD = '01a1e3a6-0000-7000-8000-000000000001';
    const old = { ...reviewS02, review_id: OLD, as_of_date: '2026-10-01' };
    stubServer((method, path) => {
      if (method === 'POST' && path === '/v1/reviews') return json(202, { review_id: REVIEW_ID });
      if (path === `/v1/reviews/${REVIEW_ID}`) return json(200, reviewS02);
      if (path === `/v1/reviews/${OLD}`) return json(200, old);
      return json(404, {});
    });
    const current = await reviewForParcel(parcel);
    const reopened = await reviewById(OLD);
    expect(reopened.reviewId).toBe(OLD);
    expect(await reviewForParcel(parcel)).toBe(current);
  });

  it('id 모양이 아니면 서버를 부르지 않는다', async () => {
    const calls = stubServer(() => json(200, reviewS02));
    await expect(reviewById('../../admin')).rejects.toBeTruthy();
    expect(calls).toHaveLength(0);
  });
});

describe('배치 계산 기억', () => {
  it('같은 검토·같은 조건은 한 번만 계산하고, 참고 배치(include_unverified)는 따로 계산한다', async () => {
    const calls = stubServer((method, path) => {
      if (method === 'POST' && path.endsWith('/parking-simulations')) return json(200, simulationS02);
      return json(404, {});
    });
    const [a, b] = await Promise.all([simulationFor(REVIEW_ID), simulationFor(REVIEW_ID)]);
    expect(a).toBe(b);
    expect(a.legalBasis.every((l) => Array.isArray(l.ruleIds))).toBe(true);
    await simulationFor(REVIEW_ID, { includeUnverified: true });
    expect(calls).toHaveLength(2);
  });
});
