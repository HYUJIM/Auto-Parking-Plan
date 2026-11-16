import {
  ApiError,
  isPnu,
  type AddressHit,
  type LatLng,
  type Parcel,
  type ReviewProgress,
  type RuleReview,
  type SiteData,
} from '@/lib/api/types';
import { isUuid } from '@/lib/api/beAdapters';
import type { AsyncStatus } from '@/lib/useAsync';
import { content } from '@/lib/content';
import type { AddressLike } from './exportNames';
import { reportHref } from './links';
import { reviewProgressText } from './summary';

/**
 * 결과 리포트 화면의 상태 판단 — 어떤 검토를 불러올지, 주소(&review=)를 어떻게 정리할지, 법규 검토 카드에 무엇을 보여 줄지,
 * 어떤 경계를 그릴지, 제목 주소를 어디서 가져올지, 배치 카드에 무엇을 대신 보여 줄지. 모두 순수 함수라 화면 없이 시험합니다.
 */

// ── 어떤 검토를 불러올지 ──

/** 이 탭에서 필지로 새로 만든 검토 (주소에 그 id 를 덧붙여도 같은 검토로 봅니다) */
export interface OwnReview {
  pnu: string;
  id: string;
}

/** byId: 주소의 검토 id 로 GET · parcel: 필지로 새 검토(탭 안에서 기억) · none: 아직(또는 끝내) 시작하지 않음 */
export type ReviewSource = { kind: 'none' } | { kind: 'byId'; id: string } | { kind: 'parcel' };

export interface ReviewSourceInput {
  /** 모양을 검사한 PNU ('' = 없음) */
  pnu: string;
  /** 모양을 검사한 주소의 검토 id ('' = 없음 또는 잘못된 값) */
  reviewParam: string;
  own: OwnReview | null;
  /** 받은 필지가 시범 지역 안인지 (필지를 아직·끝내 받지 못했으면 null) */
  parcelInSuseong: boolean | null;
}

/**
 * 불러올 검토. 주소에 검토 id 가 있으면 그것을(필지 조회를 기다리지 않음), 없으면 시범 지역 안의 필지로 새 검토를 만듭니다.
 * 필지로 만든 검토의 id 를 주소에 덧붙인 뒤에도 'parcel' 로 남아 같은 요청을 다시 보내지 않습니다.
 */
export function reviewSource({ pnu, reviewParam, own, parcelInSuseong }: ReviewSourceInput): ReviewSource {
  if (!pnu) return { kind: 'none' };
  const ownId = own && own.pnu === pnu ? own.id : null;
  if (reviewParam && reviewParam !== ownId) return { kind: 'byId', id: reviewParam };
  return parcelInSuseong ? { kind: 'parcel' } : { kind: 'none' };
}

/** 필지로 만든 검토를 받았으면 기억할 값 — 이미 기억하고 있으면 null (렌더 중 상태 조정이 한 번만 일어나게) */
export function ownToRemember(pnu: string, source: ReviewSource, review: RuleReview | null, own: OwnReview | null): OwnReview | null {
  if (source.kind !== 'parcel' || !review) return null;
  if (own && own.pnu === pnu && own.id === review.reviewId) return null;
  return { pnu, id: review.reviewId };
}

export interface ReviewOutcome {
  /** 받은 검토가 다른 필지의 것 — 쓰지 않고(화면은 받은 검토를 mismatch 가 아닐 때만 씀) 주소에서 뺀 뒤 필지로 다시 검토합니다 */
  mismatch: boolean;
  /** 주소의 검토 id 가 서버에 없음(404 · 보관 기간 지남) — 주소에서 빼고 필지로 다시 검토합니다 */
  gone: boolean;
}

/**
 * 받은 검토를 쓸 수 있는지. 검토 객체 자체는 돌려주지 않습니다 — 화면이 훅 결과(reviewState.data)를 그대로 쓰게 해서
 * React Compiler 가 그 값을 '바뀌지 않는 값'으로 보고 ReportPage 의 useCallback 메모를 지킬 수 있게 합니다.
 */
export function reviewOutcome(
  pnu: string,
  source: ReviewSource,
  state: { status: AsyncStatus; data: Pick<RuleReview, 'parcel'> | null; error: unknown },
): ReviewOutcome {
  const loaded = state.status === 'success' ? state.data : null;
  const mismatch = Boolean(loaded && loaded.parcel.pnu && loaded.parcel.pnu !== pnu);
  const gone = source.kind === 'byId' && state.status === 'error' && state.error instanceof ApiError && state.error.code === 'NOT_FOUND';
  return { mismatch, gone };
}

// ── 주소 정리 ──

export interface ReviewUrlInput {
  /** 모양을 검사한 PNU */
  pnu: string;
  /** 주소에 실제로 적힌 review 값 (검사 전) */
  rawReview: string;
  /** 이 필지의 검토 id (받았으면) */
  reviewId: string | null;
  mismatch: boolean;
  gone: boolean;
}

/**
 * 주소를 바꿔야 하면 그 주소(replace 로 이동), 아니면 null.
 * 검토를 받으면 그 id 를 덧붙이고(새로고침·인쇄·상세 링크가 같은 검토를 쓰도록), 다른 필지의 검토 · 없는 검토 · 모양이 잘못된 값은 뺍니다.
 */
export function reviewUrlFix({ pnu, rawReview, reviewId, mismatch, gone }: ReviewUrlInput): string | null {
  if (!isPnu(pnu)) return null;
  // 주소에 둘 검토 id ('' = 빼기, null = 그대로)
  let want: string | null = null;
  if (mismatch || gone) want = '';
  else if (reviewId && isUuid(reviewId)) want = reviewId;
  else if (rawReview && !isUuid(rawReview)) want = '';
  if (want === null || want === rawReview) return null;
  return reportHref(pnu, want || null);
}

// ── 시범 지역 · 법규 검토 카드 ──

/** 시범 지역 밖 — 검토가 미지원이라고 했거나, 받은 필지(주소에 검토 id 가 없을 때) · 대기 필지의 검색 결과가 시범 지역 밖 */
export function outsidePilot(site: SiteData | null, review: Pick<RuleReview, 'supported'> | null, byId: boolean): boolean {
  if (review && !review.supported) return true;
  if (site?.kind === 'parcel') return !byId && !site.parcel.inSuseong;
  if (site?.kind === 'pending') return site.hit?.inSuseong === false;
  return false;
}

/**
 * 오른쪽 '법규 검토' 카드에 보여 줄 것.
 * ready: 체크리스트 · loading: 검토 중(진행 단계) · waiting: 필지를 기다리는 중(뼈대만) · error: 검토 실패(다시 시도)
 * · pendingParcel: 대기 필지라 시작하지 않음 · notStarted: 필지 조회 실패로 시작하지 못함 · unsupported: 시범 지역 밖
 */
export type ChecklistPhase = 'unsupported' | 'ready' | 'loading' | 'waiting' | 'error' | 'pendingParcel' | 'notStarted';

export interface ChecklistInput {
  unsupported: boolean;
  hasReview: boolean;
  review: AsyncStatus;
  mismatch: boolean;
  gone: boolean;
  site: AsyncStatus;
  pendingParcel: boolean;
}

export function checklistPhase(i: ChecklistInput): ChecklistPhase {
  if (i.unsupported) return 'unsupported';
  if (i.hasReview) return 'ready';
  // 다른 필지의 검토 · 없는 검토는 곧 주소에서 빠지고 필지로 다시 검토합니다
  if (i.review === 'loading' || i.mismatch || i.gone) return 'loading';
  if (i.review === 'error') return 'error';
  // 검토 요청이 없음(idle) — '검토 중'이라고 하지 않고 이유를 보여 줍니다
  if (i.site === 'loading' || i.site === 'idle') return 'waiting';
  if (i.pendingParcel) return 'pendingParcel';
  return 'notStarted';
}

// ── 지도 · 제목 · 배치 카드 ──

const NO_POLYGON: LatLng[] = [];

/** 지도에 그릴 대지 경계 — 필지 조회 결과, 없으면 검토 당시의 스냅숏 (같은 배열을 돌려줘 지도 계산을 다시 하지 않게) */
export function sitePolygon(parcel: Pick<Parcel, 'polygon'> | null, review: Pick<RuleReview, 'parcel'> | null): LatLng[] {
  if (parcel && parcel.polygon.length >= 3) return parcel.polygon;
  if (review && review.parcel.polygon.length >= 3) return review.parcel.polygon;
  return NO_POLYGON;
}

/** 제목 주소 후보: 필지 → 검토 스냅숏 → 검색 결과(대기 필지, 또는 조회 실패 때 세션에 남은 검색 결과) */
export function addressCandidate(site: SiteData | null, review: RuleReview | null, cachedHit: AddressHit | null): AddressLike | null {
  if (site?.kind === 'parcel') return site.parcel.address;
  if (review) return { road: review.parcel.roadAddress, jibun: review.parcel.jibunAddress };
  const hit = site?.kind === 'pending' ? site.hit : cachedHit;
  return hit ? { road: hit.roadAddress, jibun: hit.jibunAddress } : null;
}

/** 배치 결과 대신 보여 줄 상태 — 불러오는 중(지금 단계) · 오류(다시 시도) · 안내(미지원·경계 없음) */
export type LayoutBlocker =
  | { kind: 'loading'; text: string }
  | { kind: 'error'; error: unknown; onRetry: () => void }
  | { kind: 'notice'; text: string; title?: string; tone?: 'info' | 'warning' };

/** useAsync 상태 중 여기서 보는 부분 */
export interface RequestState {
  status: AsyncStatus;
  error: unknown;
  reload: () => void;
}

export interface BlockerInput {
  site: RequestState;
  review: RequestState;
  sim: RequestState;
  /** 이 필지의 검토 결과를 받았는지 */
  hasReview: boolean;
  /** 그릴 경계가 있는지 (꼭짓점 3개 이상) */
  hasPolygon: boolean;
  /** 시범 지역 밖 */
  unsupported: boolean;
  /** PNU 는 확정, 필지 데이터 없음 */
  pendingParcel: boolean;
  progress: ReviewProgress | null;
}

/**
 * 카드 02(주차 배치 결과)에서 배치 대신 보여 줄 것. null 이면 배치 결과(또는 건설 불가·계산 안 함 안내)를 그립니다.
 * 순서: 시범 지역 밖 → 경계 없음(불러오는 중 · 검토 중 · 조회 실패 · 대기 필지 · 경계 없음) → 검토 실패 → 검토 중 → 배치 계산 실패 → 계산 중.
 */
export function layoutBlocker(i: BlockerInput): LayoutBlocker | null {
  const l = content.site.layout;
  if (i.unsupported) return { kind: 'notice', tone: 'warning', text: l.unsupported };
  if (!i.hasPolygon) {
    if (i.site.status === 'loading' || i.site.status === 'idle') return { kind: 'loading', text: content.report.layout.loadingSite };
    if (i.review.status === 'loading') return { kind: 'loading', text: reviewProgressText(i.progress) };
    if (i.site.status === 'error') return { kind: 'error', error: i.site.error, onRetry: i.site.reload };
    if (i.pendingParcel) return { kind: 'notice', title: content.site.pending.title, text: content.site.pending.desc };
    return { kind: 'notice', text: l.pending };
  }
  if (i.review.status === 'error') return { kind: 'error', error: i.review.error, onRetry: i.review.reload };
  if (!i.hasReview) return { kind: 'loading', text: reviewProgressText(i.progress) };
  if (i.sim.status === 'error') return { kind: 'error', error: i.sim.error, onRetry: i.sim.reload };
  if (i.sim.status !== 'success') return { kind: 'loading', text: l.running };
  return null;
}
