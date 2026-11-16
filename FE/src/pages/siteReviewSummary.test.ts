import { describe, expect, it } from 'vitest';
import { toReviewProgress, toRuleReview } from '@/lib/api/beAdapters';
import type { BeReviewProgress, BeReviewResult } from '@/lib/api/beTypes';
import reviewProgress from '@/lib/api/__fixtures__/review_progress.json';
import reviewS01 from '@/lib/api/__fixtures__/review_S01.json';
import reviewS02 from '@/lib/api/__fixtures__/review_S02.json';
import reviewU02 from '@/lib/api/__fixtures__/review_U02_unsupported.json';
import { content, fmt } from '@/lib/content';
import { countReviewStatuses, currentStep, progressSteps, reviewSummaryVars, statusCountList } from './siteReviewSummary';

const s01 = toRuleReview(reviewS01 as unknown as BeReviewResult);
const s02 = toRuleReview(reviewS02 as unknown as BeReviewResult);
const u02 = toRuleReview(reviewU02 as unknown as BeReviewResult);

describe('countReviewStatuses', () => {
  it('S01 — 별도 확인 4 · 검토 가능 2, 별도 확인 조건 4', () => {
    const c = countReviewStatuses(s01);
    expect(c).toEqual({ total: 6, byStatus: { REVIEWABLE: 2, CONDITIONAL: 0, MANUAL_CHECK: 4 }, manualChecks: 4 });
  });

  it('S02 — 검토 가능 5 · 별도 확인 1 (주차대수는 승인 규칙이 없어 별도 확인)', () => {
    const c = countReviewStatuses(s02);
    expect(c.byStatus).toEqual({ REVIEWABLE: 5, CONDITIONAL: 0, MANUAL_CHECK: 1 });
    expect(c.total).toBe(6);
  });

  it('시범 지역 밖 — 항목 없음', () => {
    expect(countReviewStatuses(u02)).toEqual({ total: 0, byStatus: { REVIEWABLE: 0, CONDITIONAL: 0, MANUAL_CHECK: 0 }, manualChecks: 0 });
  });
});

describe('statusCountList · reviewSummaryVars', () => {
  it('검토 가능 → 조건부 → 별도 확인 순서, 0건도 빠뜨리지 않음', () => {
    const c = countReviewStatuses(s02);
    expect(statusCountList(c)).toEqual([
      { status: 'REVIEWABLE', n: 5 },
      { status: 'CONDITIONAL', n: 0 },
      { status: 'MANUAL_CHECK', n: 1 },
    ]);
  });

  it('요약 문구 자리표시자를 모두 채움', () => {
    const text = fmt(content.site.review.summary, reviewSummaryVars(countReviewStatuses(s01)));
    expect(text).not.toMatch(/\{\w+\}/);
    expect(text).toContain('6');
  });

  it('상태별 문구가 세 상태 모두 있음', () => {
    for (const { status } of statusCountList(countReviewStatuses(s01))) {
      expect(fmt(content.site.review.counts[status], { n: 3 })).toContain('3');
    }
  });
});

describe('progressSteps · currentStep', () => {
  const progress = toReviewProgress(reviewProgress as unknown as BeReviewProgress);

  it('BE 단계 순서와 상태를 그대로', () => {
    const steps = progressSteps(progress);
    expect(steps.map((s) => s.state)).toEqual(['done', 'done', 'running', 'pending', 'pending']);
    expect(steps[2]).toEqual({ key: 'building', label: '건축 법규 검토', state: 'running' });
  });

  it('진행 중 단계 → 없으면 첫 대기 단계 → 없으면 null', () => {
    const steps = progressSteps(progress);
    expect(currentStep(steps)?.key).toBe('building');
    const queued = steps.map((s) => (s.state === 'running' ? { ...s, state: 'done' as const } : s));
    expect(currentStep(queued)?.key).toBe('parking');
    expect(currentStep(steps.map((s) => ({ ...s, state: 'done' as const })))).toBeNull();
  });

  it('진행 상태가 없거나 모르는 상태 값이면 빈 목록 · 대기', () => {
    expect(progressSteps(null)).toEqual([]);
    const odd = progressSteps({ status: 'running', steps: [{ step: '', label: '새 단계', state: 'paused' }] });
    expect(odd).toEqual([{ key: '0', label: '새 단계', state: 'pending' }]);
  });
});
