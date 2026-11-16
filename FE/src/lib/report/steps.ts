import type { AsyncStatus } from '@/lib/useAsync';
import { content } from '@/lib/content';

/**
 * 결과 리포트 머리의 진행 단계 (대지 입력 › 자동 배치 › 법규 검토 › 결과 리포트).
 * 순서는 디자인 시안을 따르고, 각 단계의 상태는 실제 요청 상태(필지 조회 · 법규 검토 · 배치 계산)에서 정합니다.
 * 데이터가 실제로 흐르는 순서는 필지 → 법규 검토 → 배치 계산이므로, '자동 배치'는 법규 검토가 끝나야 진행됩니다.
 */
export type StepState = 'done' | 'running' | 'pending' | 'failed' | 'skipped';
export type StepId = 'site' | 'layout' | 'rules' | 'report';

export interface ReportStep {
  id: StepId;
  label: string;
  state: StepState;
}

export interface StepInput {
  /** 필지 조회 (resolveSite) */
  site: AsyncStatus;
  /** 필지 데이터가 없음 (PNU 는 확정, 연속지적도에 없음) — 다음 단계로 가지 못합니다 */
  siteMissing?: boolean;
  /**
   * 필지 조회는 실패(또는 대기 필지)했지만, 받은 검토의 필지 스냅숏(경계·면적·토지이용계획)으로 대지를 그렸음.
   * 화면에 대지 정보가 다 있으므로 대지 입력은 완료로 봅니다.
   */
  siteFromSnapshot?: boolean;
  /** 법규 검토 (POST /v1/reviews → 폴링, 또는 id 로 다시 받기) */
  review: AsyncStatus;
  /** 법규 검토를 하지 않음 (시범 지역 밖) */
  reviewSkipped?: boolean;
  /** 배치 계산 (POST /v1/reviews/{id}/parking-simulations) */
  layout: AsyncStatus;
  /** 배치를 계산하지 않음 (검토 미지원 · 대지 경계 없음 · BE 가 계산하지 않음) */
  layoutSkipped?: boolean;
}

function fromAsync(status: AsyncStatus): StepState {
  switch (status) {
    case 'loading':
      return 'running';
    case 'error':
      return 'failed';
    case 'success':
      return 'done';
    default:
      return 'pending';
  }
}

/** '하지 않음' 표시는 요청이 없거나 끝난 뒤에만 — 진행 중·실패는 요청 상태가 우선입니다 */
function withSkip(status: AsyncStatus, skipped: boolean | undefined): StepState {
  return skipped && (status === 'idle' || status === 'success') ? 'skipped' : fromAsync(status);
}

export function reportSteps(input: StepInput): ReportStep[] {
  const t = content.report.steps;
  const siteRaw: StepState = input.site === 'success' && input.siteMissing ? 'failed' : fromAsync(input.site);
  const site: StepState = siteRaw === 'failed' && input.siteFromSnapshot ? 'done' : siteRaw;
  const rules = withSkip(input.review, input.reviewSkipped);
  const layout = withSkip(input.layout, input.layoutSkipped);

  const states = [site, rules, layout];
  const report: StepState = states.includes('failed')
    ? 'failed'
    : states.some((s) => s === 'running' || s === 'pending')
      ? 'pending'
      : 'done';

  return [
    { id: 'site', label: t.site, state: site },
    { id: 'layout', label: t.layout, state: layout },
    { id: 'rules', label: t.rules, state: rules },
    { id: 'report', label: t.report, state: report },
  ];
}

/** 진행 중인 단계가 있는지 (PDF 저장은 모든 단계가 멈춘 뒤에) */
export function hasRunningStep(steps: ReportStep[]): boolean {
  return steps.some((s) => s.state === 'running');
}
