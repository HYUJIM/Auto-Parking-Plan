import { RULE_STATUSES, type ReviewProgress, type RuleReview, type RuleStatus } from '@/lib/api/types';

/**
 * 이용화면 패널의 '법규 검토' 카드에 쓰는 순수 계산.
 * 패널은 검토 결과를 다 펼치지 않고 상태별 개수 한 줄만 보여 준 뒤 결과 리포트(/report)로 넘깁니다.
 */

export interface ReviewCounts {
  /** 핵심 법규 항목 수 */
  total: number;
  /** 항목 상태별 개수 (검토 가능 · 조건부 검토 · 별도 확인 필요) */
  byStatus: Record<RuleStatus, number>;
  /** 자동 판정하지 않는 별도 확인 조건 수 (도로 · 건축선 · 높이 · 추가 규제) */
  manualChecks: number;
}

export function countReviewStatuses(review: Pick<RuleReview, 'items' | 'manual'>): ReviewCounts {
  const byStatus: Record<RuleStatus, number> = { REVIEWABLE: 0, CONDITIONAL: 0, MANUAL_CHECK: 0 };
  for (const item of review.items) byStatus[item.status] += 1;
  return { total: review.items.length, byStatus, manualChecks: review.manual.length };
}

/** 화면에 늘어놓을 순서(검토 가능 → 조건부 → 별도 확인)대로 상태와 개수 */
export function statusCountList(counts: ReviewCounts): { status: RuleStatus; n: number }[] {
  return RULE_STATUSES.map((status) => ({ status, n: counts.byStatus[status] }));
}

/** 요약 문구 자리표시자 — fmt(content.site.review.summary, reviewSummaryVars(counts)) */
export function reviewSummaryVars(counts: ReviewCounts): { total: number; ok: number; cond: number; manual: number } {
  return {
    total: counts.total,
    ok: counts.byStatus.REVIEWABLE,
    cond: counts.byStatus.CONDITIONAL,
    manual: counts.byStatus.MANUAL_CHECK,
  };
}

export type StepState = 'done' | 'running' | 'pending' | 'failed';

export interface ProgressStep {
  key: string;
  label: string;
  state: StepState;
}

const STEP_STATES: ReadonlySet<string> = new Set<StepState>(['done', 'running', 'pending', 'failed']);

/** BE 가 알려 주는 검토 단계 (대지정보 → 용도지역 → 건축 법규 → 주차 기준 → 쉬운 설명). 모르는 상태는 '대기'로 봅니다 */
export function progressSteps(progress: ReviewProgress | null): ProgressStep[] {
  if (!progress) return [];
  return progress.steps.map((s, i) => ({
    key: s.step || String(i),
    label: s.label,
    state: STEP_STATES.has(s.state) ? (s.state as StepState) : 'pending',
  }));
}

/** 지금 진행 중인 단계 — 실행 중인 단계가 없으면 첫 대기 단계 */
export function currentStep(steps: ProgressStep[]): ProgressStep | null {
  return steps.find((s) => s.state === 'running') ?? steps.find((s) => s.state === 'pending') ?? null;
}
