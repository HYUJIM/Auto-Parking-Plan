import { describe, expect, it } from 'vitest';
import { BusyError, TtlCache, createBudgetedFetch } from './budget.ts';

const okFetch = async () => new Response('ok');

describe('createBudgetedFetch', () => {
  it('분당 예산을 넘으면 BusyError(rate), 시간이 지나면 다시 찬다', async () => {
    let now = 0;
    const budget = createBudgetedFetch(okFetch, { perMinute: 2, maxInflight: 0, now: () => now });
    await budget.fetch('https://example.com/a');
    await budget.fetch('https://example.com/b');
    await expect(budget.fetch('https://example.com/c')).rejects.toBeInstanceOf(BusyError);
    await expect(budget.fetch('https://example.com/c')).rejects.toMatchObject({ reason: 'rate' });
    now = 30_000; // 30초 → 토큰 1개 회복
    await budget.fetch('https://example.com/d');
    await expect(budget.fetch('https://example.com/e')).rejects.toBeInstanceOf(BusyError);
  });

  it('동시 호출 상한을 넘으면 BusyError(inflight), 끝나면 풀린다', async () => {
    let release!: () => void;
    const pending = new Promise<Response>((resolve) => {
      release = () => resolve(new Response('late'));
    });
    const budget = createBudgetedFetch(() => pending, { perMinute: 0, maxInflight: 1 });
    const first = budget.fetch('https://example.com/slow');
    await expect(budget.fetch('https://example.com/second')).rejects.toMatchObject({ reason: 'inflight' });
    expect(budget.stats().inflight).toBe(1);
    release();
    await first;
    expect(budget.stats().inflight).toBe(0);
  });

  it('0 이면 제한 없음', async () => {
    const budget = createBudgetedFetch(okFetch, { perMinute: 0, maxInflight: 0 });
    for (let i = 0; i < 50; i++) await budget.fetch('https://example.com/x');
    expect(budget.stats().inflight).toBe(0);
  });
});

describe('TtlCache', () => {
  it('TTL 이 지나면 사라지고, 가장 오래 안 쓴 항목부터 밀려난다', () => {
    let now = 0;
    const cache = new TtlCache<string>(2, 1000, () => now);
    cache.set('a', 'A');
    cache.set('b', 'B');
    expect(cache.get('a')).toBe('A'); // a 를 최근 사용으로
    cache.set('c', 'C'); // 용량 2 → 가장 오래된 b 가 밀려남
    expect(cache.get('b')).toBeUndefined();
    expect(cache.get('a')).toBe('A');
    expect(cache.get('c')).toBe('C');
    now = 1001;
    expect(cache.get('a')).toBeUndefined();
    expect(cache.size).toBe(1);
  });

  it('null 값도 캐시한다 (없는 필지 404 를 다시 묻지 않음)', () => {
    const cache = new TtlCache<string | null>(10, 1000);
    cache.set('k', null);
    expect(cache.has('k')).toBe(true); // null 도 '있는 값' — undefined(미조회)와 구분
    expect(cache.get('k')).toBeNull();
    expect(cache.get('missing')).toBeUndefined();
  });
});
