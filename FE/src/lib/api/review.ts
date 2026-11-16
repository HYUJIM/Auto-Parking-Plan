import { content } from '@/lib/content';
import type { BeParkingSimulation, BeParkingSimulationRequest, BeReviewCreated, BeReviewResponse } from './beTypes';
import { isBeReviewResult } from './beTypes';
import { isUuid, toParkingSimulation, toReviewProgress, toRuleReview } from './beAdapters';
import { apiUrl, postJson, request } from './base';
import { ApiError, type ExistingBuildingsMode, type Parcel, type ParkingSimulation, type ReviewProgress, type RuleReview } from './types';

export type ReviewAudience = 'owner' | 'architect';

export interface ReviewOptions {
  audience?: ReviewAudience;
  signal?: AbortSignal;
  /** 폴링 중 진행 상태(단계별)를 받아 화면에 보여 주고 싶을 때 */
  onProgress?: (progress: ReviewProgress) => void;
  /** 전체 대기 상한 (ms). 기본 60초 */
  timeoutMs?: number;
}

/** 폴링 간격 — BE 의 Retry-After(초)를 따르되 1~10초로 제한 */
const POLL_DEFAULT_MS = 1000;
const POLL_MAX_MS = 10_000;
const TIMEOUT_MS = 60_000;
/** 폴링 중에 만나면 기다렸다가 다시 확인하는 오류 (그 밖의 오류는 바로 올립니다) */
const POLL_RETRY_CODES: ReadonlySet<string> = new Set(['RATE_LIMITED', 'BUSY', 'UPSTREAM', 'NETWORK']);

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    // 이미 중단된 신호는 abort 이벤트가 다시 오지 않습니다 — 기다리지 않고 바로 끝냅니다
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export function pollDelayMs(retryAfterHeader: string | null): number {
  const sec = Number(retryAfterHeader);
  if (!Number.isFinite(sec) || sec <= 0) return POLL_DEFAULT_MS;
  return Math.min(POLL_MAX_MS, Math.max(POLL_DEFAULT_MS, Math.round(sec * 1000)));
}

/**
 * 법규 검토 — `POST /v1/reviews { pnu, audience }` → 202 { review_id } → `GET /v1/reviews/{id}` 를 Retry-After 간격으로 폴링.
 * 바디에 items 가 오면 완료(review_result@1.0), status=failed 면 ApiError('SERVER'), 60초를 넘기면 ApiError('SERVER')(다시 시도 가능).
 * 시범 지역 밖 필지는 BE 가 빈 항목 + REGION_NOT_SUPPORTED 로 답하므로 supported=false 가 됩니다.
 */
export async function getRuleReview(parcel: Parcel, opts: ReviewOptions = {}): Promise<RuleReview> {
  const { signal } = opts;
  const created = await postJson<BeReviewCreated>('/v1/reviews', { pnu: parcel.pnu, audience: opts.audience ?? 'owner' }, { signal });
  if (typeof created?.review_id !== 'string' || !isUuid(created.review_id)) throw new ApiError(content.status.reviewFailed, 'SERVER');
  return pollReview(created.review_id, opts);
}

/** 이미 만든 검토를 id 로 다시 받습니다 (결과 페이지를 새로 열었거나 주소로 바로 들어왔을 때) — 새 검토를 만들지 않습니다 */
export async function getReviewById(reviewId: string, opts: ReviewOptions = {}): Promise<RuleReview> {
  if (!isUuid(reviewId)) throw new ApiError(content.status.reviewFailed, 'BAD_REQUEST');
  return pollReview(reviewId, opts);
}

async function pollReview(reviewId: string, opts: ReviewOptions): Promise<RuleReview> {
  const { signal, onProgress } = opts;
  const deadline = Date.now() + (opts.timeoutMs ?? TIMEOUT_MS);

  for (;;) {
    let res: Response;
    try {
      res = await request(apiUrl(`/v1/reviews/${encodeURIComponent(reviewId)}`), { signal });
    } catch (err) {
      // 폴링 중 한 번의 일시 오류(요청 제한·서버 바쁨·연결 끊김)로 검토 전체를 실패로 만들지 않습니다.
      // 이미 만든 검토를 버리고 새로 만들면 서버 일만 늘어납니다 — 기다렸다가 같은 검토를 이어서 확인합니다
      if (!(err instanceof ApiError) || !POLL_RETRY_CODES.has(err.code)) throw err;
      const wait = pollDelayMs(err.retryAfter === undefined ? null : String(err.retryAfter));
      if (Date.now() + wait > deadline) throw err;
      await sleep(wait, signal);
      continue;
    }
    const body = (await res.json()) as BeReviewResponse;
    if (isBeReviewResult(body)) return toRuleReview(body);

    const progress = toReviewProgress(body);
    if (progress.status === 'failed') throw new ApiError(content.status.reviewFailed, 'SERVER', { beCode: 'REVIEW_FAILED' });
    if (!signal?.aborted) onProgress?.(progress); // 떠난 화면의 진행 표시가 다음 필지에 나타나지 않게

    const delay = pollDelayMs(res.headers.get('Retry-After'));
    if (Date.now() + delay > deadline) throw new ApiError(content.status.reviewTimeout, 'SERVER', { beCode: 'REVIEW_TIMEOUT' });
    await sleep(delay, signal);
  }
}

// ── 검토·배치 결과 기억 (이 탭 안에서만) ──────────────────────────────
//
// 이용화면 → 결과 페이지 → 법규 상세 페이지로 옮겨 다녀도 같은 검토를 다시 만들지 않도록 기억합니다.
// 검토 생성은 IP 당 분당 10회로 제한되어 있고, 배치 계산도 매번 다시 하면 서버 일만 늘어납니다.
// 진행 중인 요청은 Promise 로 기억해 두 화면이 동시에 불러도 한 번만 보냅니다. 실패하면 잊습니다.

/** 기억해 두는 시간 — 기준일이 바뀌거나 규칙이 새로 승인되면 다시 검토하도록 */
const CACHE_TTL_MS = 30 * 60 * 1000;

interface CacheEntry<T> {
  at: number;
  promise: Promise<T>;
}

const reviewsByPnu = new Map<string, CacheEntry<RuleReview>>();
const reviewsById = new Map<string, CacheEntry<RuleReview>>();
const simulations = new Map<string, CacheEntry<ParkingSimulation>>();

function fresh<T>(map: Map<string, CacheEntry<T>>, key: string): Promise<T> | null {
  const e = map.get(key);
  if (!e) return null;
  if (Date.now() - e.at > CACHE_TTL_MS) {
    map.delete(key);
    return null;
  }
  return e.promise;
}

function remember<T>(map: Map<string, CacheEntry<T>>, key: string, promise: Promise<T>): Promise<T> {
  map.set(key, { at: Date.now(), promise });
  promise.catch(() => {
    if (map.get(key)?.promise === promise) map.delete(key);
  });
  return promise;
}

/** 중단 신호를 따로 걸 수 있게 감쌉니다 — 기억해 둔 요청 자체는 한 화면이 떠나도 끊지 않습니다 */
function withSignal<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(new DOMException('Aborted', 'AbortError'));
    signal.addEventListener('abort', onAbort, { once: true });
    promise.then(
      (v) => {
        signal.removeEventListener('abort', onAbort);
        resolve(v);
      },
      (e) => {
        signal.removeEventListener('abort', onAbort);
        reject(e);
      },
    );
  });
}

/** 진행 상태 알림을 여러 화면이 함께 받도록 */
const progressListeners = new Map<string, Set<(p: ReviewProgress) => void>>();

function listen(pnu: string, fn?: (p: ReviewProgress) => void): () => void {
  if (!fn) return () => {};
  const set = progressListeners.get(pnu) ?? new Set();
  set.add(fn);
  progressListeners.set(pnu, set);
  return () => set.delete(fn);
}

/**
 * 필지의 법규 검토 — 이 탭에서 같은 필지를 이미 검토했거나 검토 중이면 그 결과를 함께 씁니다.
 * 화면이 떠나도(signal) 진행 중인 검토는 계속되어 다음 화면이 이어받습니다 (서버에서는 어차피 끝까지 돕니다).
 */
export function reviewForParcel(parcel: Parcel, opts: ReviewOptions = {}): Promise<RuleReview> {
  const off = listen(parcel.pnu, opts.onProgress);
  let p = fresh(reviewsByPnu, parcel.pnu);
  if (!p) {
    const created = getRuleReview(parcel, {
      audience: opts.audience,
      timeoutMs: opts.timeoutMs,
      onProgress: (progress) => progressListeners.get(parcel.pnu)?.forEach((fn) => fn(progress)),
    });
    p = remember(reviewsByPnu, parcel.pnu, created);
    void created.then(
      (r) => remember(reviewsById, r.reviewId, Promise.resolve(r)),
      () => undefined,
    );
  }
  const out = withSignal(p, opts.signal);
  out.then(off, off);
  return out;
}

/** id 로 검토 결과 — 기억해 둔 것이 있으면 그것, 없으면 GET 폴링 (새 검토를 만들지 않음) */
export function reviewById(reviewId: string, opts: ReviewOptions = {}): Promise<RuleReview> {
  let p = fresh(reviewsById, reviewId);
  if (!p) {
    p = remember(reviewsById, reviewId, getReviewById(reviewId, { timeoutMs: opts.timeoutMs, onProgress: opts.onProgress }));
    // 그 필지에 더 새 검토(진행 중 포함)를 이미 기억하고 있으면 덮어쓰지 않습니다 — 뒤로 가기로 연 옛 검토가 새 검토를 밀어내지 않게
    void p.then(
      (r) => {
        if (!fresh(reviewsByPnu, r.parcel.pnu)) remember(reviewsByPnu, r.parcel.pnu, Promise.resolve(r));
      },
      () => undefined,
    );
  }
  return withSignal(p, opts.signal);
}

/**
 * 배치 계산 결과 — 같은 검토·같은 조건(참고 배치 여부 · 기존 건물 유지/철거 가정)이면 한 번만 계산합니다.
 * 기존 건물을 생략하면 keep 과 같은 계산이라 같은 자리에 기억합니다 — 철거 가정을 보고 돌아와도 다시 부르지 않습니다.
 */
export function simulationFor(reviewId: string, opts: SimulationOptions = {}): Promise<ParkingSimulation> {
  const buildings = opts.existingBuildings ?? 'keep';
  const key = `${reviewId}:${opts.includeUnverified ? 'all' : 'verified'}:${buildings}`;
  let p = fresh(simulations, key);
  if (!p) {
    p = remember(
      simulations,
      key,
      getParkingSimulation(reviewId, { includeUnverified: opts.includeUnverified, existingBuildings: buildings }),
    );
  }
  return withSignal(p, opts.signal);
}

/** 테스트용 */
export function resetReviewCache(): void {
  reviewsByPnu.clear();
  reviewsById.clear();
  simulations.clear();
  progressListeners.clear();
}

/**
 * BE 가 '주차장 건설 불가'로 판단한 결과인지 — 지목이 도로·하천 같은 공공시설 용지이거나, 승인된 용도 목록에 주차장 관련 용도가 없을 때입니다.
 * 이때는 배치를 계산하지 않고(simulated=false) 참고 배치도 제공하지 않습니다.
 */
export function isParkingNotPermitted(sim: Pick<ParkingSimulation, 'eligibility' | 'constraintReasons'>): boolean {
  return sim.eligibility.status === 'NOT_PERMITTED' || sim.constraintReasons.includes('PARKING_NOT_PERMITTED');
}

export interface SimulationOptions {
  /** 주차장 설치 가능 여부가 확인되지 않아도 참고 배치를 계산 */
  includeUnverified?: boolean;
  /** 기존 건물: keep(피해서 배치, 기본) · ignore(철거 가정) */
  existingBuildings?: ExistingBuildingsMode;
  signal?: AbortSignal;
}

/**
 * 주차장 배치 시뮬레이션 — `POST /v1/reviews/{id}/parking-simulations`. 결과의 layers 는 지도에 그릴 GeoJSON 입니다.
 * existing_buildings 는 철거 가정(ignore)일 때만 보냅니다 — 생략이 곧 keep 이라, 기본 요청은 이 키를 모르는 BE 에도 그대로 통합니다.
 */
export async function getParkingSimulation(reviewId: string, opts: SimulationOptions = {}): Promise<ParkingSimulation> {
  const body: BeParkingSimulationRequest = {};
  if (opts.includeUnverified) body.include_unverified = true;
  if (opts.existingBuildings === 'ignore') body.existing_buildings = 'ignore';
  const res = await postJson<BeParkingSimulation>(`/v1/reviews/${encodeURIComponent(reviewId)}/parking-simulations`, body, {
    signal: opts.signal,
  });
  return toParkingSimulation(res);
}
