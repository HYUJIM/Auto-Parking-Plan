import { content } from '@/lib/content';
import type { BeParkingSimulation, BeParkingSimulationRequest, BeReviewCreated, BeReviewResponse } from './beTypes';
import { isBeReviewResult } from './beTypes';
import { toParkingSimulation, toReviewProgress, toRuleReview } from './beAdapters';
import { apiUrl, postJson, request } from './base';
import { ApiError, type Parcel, type ParkingSimulation, type ReviewProgress, type RuleReview } from './types';

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
  const { signal, onProgress } = opts;
  const created = await postJson<BeReviewCreated>('/v1/reviews', { pnu: parcel.pnu, audience: opts.audience ?? 'owner' }, { signal });
  if (typeof created?.review_id !== 'string' || !created.review_id) throw new ApiError(content.status.reviewFailed, 'SERVER');
  const reviewId = created.review_id;
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
  signal?: AbortSignal;
}

/** 주차장 배치 시뮬레이션 — `POST /v1/reviews/{id}/parking-simulations`. 결과의 layers 는 지도에 그릴 GeoJSON 입니다. */
export async function getParkingSimulation(reviewId: string, opts: SimulationOptions = {}): Promise<ParkingSimulation> {
  const body: BeParkingSimulationRequest = {};
  if (opts.includeUnverified) body.include_unverified = true;
  const res = await postJson<BeParkingSimulation>(`/v1/reviews/${encodeURIComponent(reviewId)}/parking-simulations`, body, {
    signal: opts.signal,
  });
  return toParkingSimulation(res);
}
