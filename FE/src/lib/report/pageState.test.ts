import { describe, expect, it } from 'vitest';
import type { AsyncStatus } from '@/lib/useAsync';
import { ApiError, type Parcel, type RuleReview, type SiteData } from '@/lib/api/types';
import type { BeReviewResult } from '@/lib/api/beTypes';
import { isUuid, toRuleReview } from '@/lib/api/beAdapters';
import reviewS01 from '@/lib/api/__fixtures__/review_S01.json';
import {
  addressCandidate,
  checklistPhase,
  layoutBlocker,
  outsidePilot,
  ownToRemember,
  reviewOutcome,
  reviewSource,
  reviewUrlFix,
  sitePolygon,
  type BlockerInput,
  type ChecklistInput,
  type RequestState,
  type ReviewSource,
} from './pageState';

const PNU = '2726010100190010000';
const OTHER_PNU = '2726010100190020000';
const REVIEW = toRuleReview(reviewS01 as unknown as BeReviewResult);
const RID = REVIEW.reviewId;
const OTHER_ID = '01a1c5cf-0000-7000-8000-000000000001';

/**
 * 화면이 실제로 도는 순서를 흉내 냅니다: 주소 → 불러올 검토 → 받은 결과 → 기억할 id → 주소 정리.
 * own 과 주소(rawReview)만 다음 렌더로 넘기면 ReportPage 와 같은 흐름입니다.
 */
function step(input: {
  pnu: string;
  rawReview: string;
  own: { pnu: string; id: string } | null;
  parcelInSuseong: boolean | null;
  respond: (source: ReviewSource) => { status: AsyncStatus; data: RuleReview | null; error: unknown };
}) {
  const reviewParam = isUuid(input.rawReview) ? input.rawReview : '';
  const source = reviewSource({ pnu: input.pnu, reviewParam, own: input.own, parcelInSuseong: input.parcelInSuseong });
  const state = input.respond(source);
  const outcome = reviewOutcome(input.pnu, source, state);
  // ReportPage 와 같이: 받은 검토는 다른 필지의 것이 아닐 때만 씁니다
  const loaded = state.status === 'success' ? state.data : null;
  const review = loaded && !outcome.mismatch ? loaded : null;
  const own = ownToRemember(input.pnu, source, review, input.own) ?? input.own;
  const href = reviewUrlFix({
    pnu: input.pnu,
    rawReview: input.rawReview,
    reviewId: review?.reviewId ?? null,
    mismatch: outcome.mismatch,
    gone: outcome.gone,
  });
  return { source, outcome, review, own, href };
}

const ok = (data: RuleReview) => ({ status: 'success' as const, data, error: null });
const idle = { status: 'idle' as const, data: null, error: null };

describe('어떤 검토를 불러올지 · 주소 정리', () => {
  it('주소에 검토 id 가 없으면: 필지로 새 검토 → 받은 id 를 주소에 덧붙임 → 덧붙인 뒤에도 다시 요청하지 않음', () => {
    // 필지를 받기 전에는 시작하지 않습니다
    expect(reviewSource({ pnu: PNU, reviewParam: '', own: null, parcelInSuseong: null })).toEqual({ kind: 'none' });

    const first = step({ pnu: PNU, rawReview: '', own: null, parcelInSuseong: true, respond: () => ok(REVIEW) });
    expect(first.source).toEqual({ kind: 'parcel' });
    expect(first.own).toEqual({ pnu: PNU, id: RID });
    expect(first.href).toBe(`/report?pnu=${PNU}&review=${RID}`);

    // 주소에 자기 id 가 붙은 다음 렌더: 여전히 'parcel'(같은 요청) — id 로 다시 받지 않습니다
    const second = step({ pnu: PNU, rawReview: RID, own: first.own, parcelInSuseong: true, respond: () => ok(REVIEW) });
    expect(second.source).toEqual({ kind: 'parcel' });
    expect(second.href).toBeNull();
    expect(ownToRemember(PNU, second.source, REVIEW, first.own)).toBeNull();
  });

  it('주소에 검토 id 가 있으면 필지 조회를 기다리지 않고 그 id 로 받고, 주소는 그대로', () => {
    const r = step({ pnu: PNU, rawReview: RID, own: null, parcelInSuseong: null, respond: () => ok(REVIEW) });
    expect(r.source).toEqual({ kind: 'byId', id: RID });
    expect(r.outcome).toEqual({ mismatch: false, gone: false });
    expect(r.review).toBe(REVIEW);
    expect(r.own).toBeNull();
    expect(r.href).toBeNull();
  });

  it('다른 필지의 검토 id 면 쓰지 않고 주소에서 뺀 뒤 필지로 다시 검토', () => {
    const r = step({ pnu: OTHER_PNU, rawReview: RID, own: null, parcelInSuseong: true, respond: () => ok(REVIEW) });
    expect(r.source).toEqual({ kind: 'byId', id: RID });
    expect(r.outcome).toEqual({ mismatch: true, gone: false });
    expect(r.review).toBeNull();
    expect(r.href).toBe(`/report?pnu=${OTHER_PNU}`);
    // 주소에서 빠진 다음 렌더는 필지로
    expect(reviewSource({ pnu: OTHER_PNU, reviewParam: '', own: null, parcelInSuseong: true })).toEqual({ kind: 'parcel' });
  });

  it('없는 검토(404)면 주소에서 빼고, 다른 오류(네트워크 등)는 주소를 두고 다시 시도하게', () => {
    const notFound = new ApiError('gone', 'NOT_FOUND', { status: 404 });
    const gone = step({
      pnu: PNU,
      rawReview: OTHER_ID,
      own: null,
      parcelInSuseong: true,
      respond: () => ({ status: 'error', data: null, error: notFound }),
    });
    expect(gone.outcome).toEqual({ mismatch: false, gone: true });
    expect(gone.review).toBeNull();
    expect(gone.href).toBe(`/report?pnu=${PNU}`);

    const network = step({
      pnu: PNU,
      rawReview: OTHER_ID,
      own: null,
      parcelInSuseong: true,
      respond: () => ({ status: 'error', data: null, error: new ApiError('down', 'NETWORK') }),
    });
    expect(network.outcome.gone).toBe(false);
    expect(network.href).toBeNull();

    // 필지로 만든 검토의 404 는 '없는 검토'가 아닙니다 (주소의 id 가 아니므로)
    expect(reviewOutcome(PNU, { kind: 'parcel' }, { status: 'error', data: null, error: notFound }).gone).toBe(false);
  });

  it('모양이 잘못된 review 값은 쓰지 않고(필지로 검토) 주소에서 뺌', () => {
    const r = step({ pnu: PNU, rawReview: '<script>', own: null, parcelInSuseong: null, respond: () => idle });
    expect(r.source).toEqual({ kind: 'none' });
    expect(r.href).toBe(`/report?pnu=${PNU}`);
    // 필지를 받으면 필지로 검토하고, 받은 id 로 바꿔 씁니다
    const later = step({ pnu: PNU, rawReview: '<script>', own: null, parcelInSuseong: true, respond: () => ok(REVIEW) });
    expect(later.source).toEqual({ kind: 'parcel' });
    expect(later.href).toBe(`/report?pnu=${PNU}&review=${RID}`);
  });

  it('PNU 가 없거나 잘못됐으면 아무것도 하지 않음, 다른 필지에서 기억한 id 는 쓰지 않음', () => {
    expect(reviewSource({ pnu: '', reviewParam: RID, own: null, parcelInSuseong: true })).toEqual({ kind: 'none' });
    expect(reviewUrlFix({ pnu: 'abc', rawReview: 'x', reviewId: RID, mismatch: false, gone: false })).toBeNull();
    // 같은 id 라도 다른 필지에서 기억한 것이면 주소의 id 로 받습니다
    expect(reviewSource({ pnu: PNU, reviewParam: RID, own: { pnu: OTHER_PNU, id: RID }, parcelInSuseong: true })).toEqual({
      kind: 'byId',
      id: RID,
    });
    // 시범 지역 밖 필지는 검토를 만들지 않습니다
    expect(reviewSource({ pnu: PNU, reviewParam: '', own: null, parcelInSuseong: false })).toEqual({ kind: 'none' });
  });
});

describe('시범 지역 밖', () => {
  const parcel = (inSuseong: boolean): SiteData => ({ kind: 'parcel', parcel: { inSuseong } as Parcel });
  const pending = (inSuseong: boolean | null): SiteData => ({
    kind: 'pending',
    pnu: PNU,
    hit: inSuseong === null ? null : { pnu: PNU, roadAddress: 'x', jibunAddress: 'x', center: null, inSuseong },
  });
  it('검토가 미지원이라고 하거나, 필지·대기 필지의 검색 결과가 시범 지역 밖', () => {
    expect(outsidePilot(null, { supported: false }, true)).toBe(true);
    expect(outsidePilot(parcel(false), null, false)).toBe(true);
    // 주소에 검토 id 가 있으면 필지가 아닌 검토가 판단합니다
    expect(outsidePilot(parcel(false), null, true)).toBe(false);
    expect(outsidePilot(parcel(true), { supported: true }, false)).toBe(false);
    expect(outsidePilot(pending(false), null, false)).toBe(true);
    expect(outsidePilot(pending(true), null, false)).toBe(false);
    expect(outsidePilot(pending(null), null, false)).toBe(false);
    expect(outsidePilot(null, null, false)).toBe(false);
  });
});

describe('법규 검토 카드에 보여 줄 것', () => {
  const base: ChecklistInput = {
    unsupported: false,
    hasReview: false,
    review: 'idle',
    mismatch: false,
    gone: false,
    site: 'success',
    pendingParcel: false,
  };
  const phase = (over: Partial<ChecklistInput>) => checklistPhase({ ...base, ...over });

  it('미지원 → 체크리스트 → 검토 중 → 실패', () => {
    expect(phase({ unsupported: true, review: 'loading' })).toBe('unsupported');
    expect(phase({ hasReview: true, review: 'success' })).toBe('ready');
    expect(phase({ review: 'loading' })).toBe('loading');
    expect(phase({ review: 'error' })).toBe('error');
  });

  it('다른 필지의 검토 · 없는 검토는 곧 다시 검토하므로 검토 중', () => {
    expect(phase({ review: 'success', mismatch: true })).toBe('loading');
    expect(phase({ review: 'error', gone: true })).toBe('loading');
  });

  it('검토 요청이 없으면(idle) 검토 중이라고 하지 않음: 필지 대기 · 대기 필지 · 필지 조회 실패', () => {
    expect(phase({ site: 'loading' })).toBe('waiting');
    expect(phase({ site: 'success', pendingParcel: true })).toBe('pendingParcel');
    expect(phase({ site: 'error' })).toBe('notStarted');
  });
});

const RING = [
  { lat: 35.86, lng: 128.62 },
  { lat: 35.86, lng: 128.63 },
  { lat: 35.87, lng: 128.63 },
];

const req = (status: AsyncStatus, error: unknown = null): RequestState => ({ status, error, reload: () => {} });

function input(over: Partial<BlockerInput> = {}): BlockerInput {
  return {
    site: req('success'),
    review: req('success'),
    sim: req('success'),
    hasReview: true,
    hasPolygon: true,
    unsupported: false,
    pendingParcel: false,
    progress: null,
    ...over,
  };
}

const reviewWith = (polygon: typeof RING) => ({ parcel: { polygon } }) as unknown as RuleReview;

describe('대지 경계 고르기', () => {
  it('필지 경계 → 검토 스냅숏 → 빈 배열(같은 참조)', () => {
    expect(sitePolygon({ polygon: RING }, null)).toBe(RING);
    expect(sitePolygon({ polygon: [] }, reviewWith(RING))).toBe(RING);
    const a = sitePolygon(null, null);
    expect(a).toEqual([]);
    expect(sitePolygon({ polygon: RING.slice(0, 2) }, null)).toBe(a);
  });
});

describe('제목 주소 후보', () => {
  const hit = { pnu: 'p', roadAddress: '도로명 1', jibunAddress: '지번 1', center: null, inSuseong: true };
  it('필지 → 검토 스냅숏 → 대기 필지의 검색 결과 → 세션 검색 결과', () => {
    const parcel = { address: { jibun: '범어동 1', road: '범어로 1' } } as Parcel;
    expect(addressCandidate({ kind: 'parcel', parcel }, null, hit)).toEqual({ jibun: '범어동 1', road: '범어로 1' });
    const review = { parcel: { roadAddress: null, jibunAddress: '범어동 2' } } as unknown as RuleReview;
    expect(addressCandidate(null, review, hit)).toEqual({ road: null, jibun: '범어동 2' });
    expect(addressCandidate({ kind: 'pending', pnu: 'p', hit }, null, null)).toEqual({ road: '도로명 1', jibun: '지번 1' });
    expect(addressCandidate(null, null, hit)).toEqual({ road: '도로명 1', jibun: '지번 1' });
    expect(addressCandidate(null, null, null)).toBeNull();
  });
});

describe('배치 카드에 대신 보여 줄 상태', () => {
  it('모두 받았으면 없음', () => {
    expect(layoutBlocker(input())).toBeNull();
  });

  it('시범 지역 밖이 먼저', () => {
    expect(layoutBlocker(input({ unsupported: true, hasPolygon: false }))).toMatchObject({ kind: 'notice', tone: 'warning' });
  });

  it('경계가 없으면: 필지 불러오는 중 → 검토 중 → 조회 실패 → 대기 필지 → 경계 없음', () => {
    expect(layoutBlocker(input({ hasPolygon: false, site: req('loading') }))).toMatchObject({
      kind: 'loading',
      text: '대지 정보를 불러오는 중…',
    });
    expect(layoutBlocker(input({ hasPolygon: false, site: req('error', new Error('x')), review: req('loading') }))).toMatchObject({
      kind: 'loading',
      text: '법규 검토 중…',
    });
    expect(layoutBlocker(input({ hasPolygon: false, site: req('error', new Error('x')), review: req('idle') }))).toMatchObject({
      kind: 'error',
    });
    expect(layoutBlocker(input({ hasPolygon: false, pendingParcel: true, review: req('idle') }))).toMatchObject({
      kind: 'notice',
      title: '필지 경계·면적을 아직 받지 못했습니다.',
    });
    expect(layoutBlocker(input({ hasPolygon: false }))).toMatchObject({
      kind: 'notice',
      text: '필지 경계를 받지 못해 배치안을 계산할 수 없습니다.',
    });
  });

  it('경계가 있으면: 검토 실패 → 검토 중(진행 단계) → 계산 실패 → 계산 중', () => {
    const err = new Error('x');
    expect(layoutBlocker(input({ review: req('error', err), hasReview: false }))).toMatchObject({ kind: 'error', error: err });
    expect(
      layoutBlocker(
        input({
          review: req('loading'),
          hasReview: false,
          progress: { status: 'running', steps: [{ step: 'zoning', label: '용도지역', state: 'running' }] },
        }),
      ),
    ).toMatchObject({ kind: 'loading', text: '법규 검토 중 — 용도지역' });
    expect(layoutBlocker(input({ sim: req('error', err) }))).toMatchObject({ kind: 'error', error: err });
    expect(layoutBlocker(input({ sim: req('loading') }))).toMatchObject({ kind: 'loading', text: '배치안 계산 중…' });
  });
});
