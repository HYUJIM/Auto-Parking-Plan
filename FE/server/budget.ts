/**
 * 공공 API 호출 예산 — 승인키 일일 한도를 몇 분 만에 태우는 일을 막습니다.
 *
 *  - createBudgetedFetch: 프로세스 전체 토큰 버킷(분당 호출 수) + 동시 호출 상한. 넘으면 BusyError (api.ts 가 503 BUSY 로 응답)
 *  - TtlCache: 필지 조회 결과 같은 거의 바뀌지 않는 데이터를 TTL + LRU 로 보관
 *
 * 매개변수 프로퍼티·enum 은 쓰지 않습니다 (Node 타입 스트리핑).
 */

export type FetchLike = (input: string | URL, init?: RequestInit) => Promise<Response>;

export class BusyError extends Error {
  reason: 'rate' | 'inflight';

  constructor(reason: 'rate' | 'inflight') {
    super(reason === 'rate' ? '공공 API 호출 분당 예산을 넘었습니다.' : '공공 API 동시 호출 상한에 닿았습니다.');
    this.name = 'BusyError';
    this.reason = reason;
  }
}

export interface BudgetOptions {
  /** 분당 공공 API 호출 수 (0 이면 제한 없음) */
  perMinute: number;
  /** 동시에 진행 중일 수 있는 공공 API 호출 수 (0 이면 제한 없음) */
  maxInflight: number;
  now?: () => number;
}

export interface BudgetedFetch {
  fetch: FetchLike;
  /** 테스트·상태 표시용 */
  stats(): { tokens: number; inflight: number };
}

export function createBudgetedFetch(fetchImpl: FetchLike, options: BudgetOptions): BudgetedFetch {
  const { perMinute, maxInflight } = options;
  const now = options.now ?? Date.now;
  let tokens = perMinute;
  let updatedAt = now();
  let inflight = 0;

  const budgeted: FetchLike = async (input, init) => {
    if (perMinute > 0) {
      const t = now();
      tokens = Math.min(perMinute, tokens + ((t - updatedAt) / 60_000) * perMinute);
      updatedAt = t;
      if (tokens < 1) throw new BusyError('rate');
      tokens -= 1;
    }
    if (maxInflight > 0 && inflight >= maxInflight) throw new BusyError('inflight');
    inflight += 1;
    try {
      return await fetchImpl(input, init);
    } finally {
      inflight -= 1;
    }
  };

  return { fetch: budgeted, stats: () => ({ tokens, inflight }) };
}

interface Entry<V> {
  value: V;
  expiresAt: number;
}

/** TTL + LRU 캐시 (Map 의 삽입 순서를 LRU 순서로 씁니다) */
export class TtlCache<V> {
  readonly max: number;
  readonly ttlMs: number;
  private readonly now: () => number;
  private readonly map = new Map<string, Entry<V>>();

  constructor(max: number, ttlMs: number, now: () => number = Date.now) {
    this.max = Math.max(0, Math.floor(max));
    this.ttlMs = Math.max(0, ttlMs);
    this.now = now;
  }

  get size(): number {
    return this.map.size;
  }

  get(key: string): V | undefined {
    const entry = this.map.get(key);
    if (!entry) return undefined;
    if (entry.expiresAt <= this.now()) {
      this.map.delete(key);
      return undefined;
    }
    // 최근 사용 → 맨 뒤로
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  has(key: string): boolean {
    return this.get(key) !== undefined;
  }

  set(key: string, value: V): void {
    if (this.max === 0) return;
    this.map.delete(key);
    this.map.set(key, { value, expiresAt: this.now() + this.ttlMs });
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next().value;
      if (oldest === undefined) break;
      this.map.delete(oldest);
    }
  }

  clear(): void {
    this.map.clear();
  }
}
