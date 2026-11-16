import { describe, expect, it } from 'vitest';
import { hasRunningStep, reportSteps } from './steps';

const states = (steps: ReturnType<typeof reportSteps>) => Object.fromEntries(steps.map((s) => [s.id, s.state]));

describe('reportSteps', () => {
  it('시안 순서(대지 입력 › 자동 배치 › 법규 검토 › 결과 리포트)로 돌려준다', () => {
    const steps = reportSteps({ site: 'success', review: 'success', layout: 'success' });
    expect(steps.map((s) => s.id)).toEqual(['site', 'layout', 'rules', 'report']);
    expect(steps.map((s) => s.label)).toEqual(['대지 입력', '자동 배치', '법규 검토', '결과 리포트']);
    expect(states(steps)).toEqual({ site: 'done', layout: 'done', rules: 'done', report: 'done' });
  });

  it('요청 상태를 그대로 옮긴다: 불러오는 중 → 진행 중, 아직 요청 전 → 대기', () => {
    const steps = reportSteps({ site: 'success', review: 'loading', layout: 'idle' });
    expect(states(steps)).toEqual({ site: 'done', layout: 'pending', rules: 'running', report: 'pending' });
    expect(hasRunningStep(steps)).toBe(true);
  });

  it('하나라도 실패하면 결과 리포트도 실패', () => {
    const steps = reportSteps({ site: 'success', review: 'error', layout: 'idle' });
    expect(states(steps)).toMatchObject({ rules: 'failed', layout: 'pending', report: 'failed' });
    expect(hasRunningStep(steps)).toBe(false);
  });

  it('필지 데이터가 없으면(대기 필지) 대지 입력은 실패', () => {
    const steps = reportSteps({ site: 'success', siteMissing: true, review: 'idle', layout: 'idle' });
    expect(states(steps)).toMatchObject({ site: 'failed', report: 'failed' });
  });

  it('필지 조회가 실패해도 검토의 필지 스냅숏으로 대지를 그렸으면 대지 입력·결과 리포트는 완료', () => {
    const failed = reportSteps({ site: 'error', review: 'success', layout: 'success' });
    expect(states(failed)).toMatchObject({ site: 'failed', report: 'failed' });
    const fromSnapshot = reportSteps({ site: 'error', siteFromSnapshot: true, review: 'success', layout: 'success' });
    expect(states(fromSnapshot)).toEqual({ site: 'done', layout: 'done', rules: 'done', report: 'done' });
    const pending = reportSteps({ site: 'success', siteMissing: true, siteFromSnapshot: true, review: 'success', layout: 'success' });
    expect(states(pending)).toMatchObject({ site: 'done', report: 'done' });
    // 아직 조회 중이면 진행 중 그대로
    expect(states(reportSteps({ site: 'loading', siteFromSnapshot: true, review: 'success', layout: 'idle' })).site).toBe('running');
  });

  it('시범 지역 밖·계산하지 않은 배치는 해당 없음, 나머지가 끝나면 리포트 완료', () => {
    const steps = reportSteps({ site: 'success', review: 'idle', reviewSkipped: true, layout: 'idle', layoutSkipped: true });
    expect(states(steps)).toEqual({ site: 'done', layout: 'skipped', rules: 'skipped', report: 'done' });
  });

  it('해당 없음 표시보다 진행 중·실패가 우선', () => {
    expect(states(reportSteps({ site: 'success', review: 'success', layout: 'loading', layoutSkipped: true })).layout).toBe('running');
    expect(states(reportSteps({ site: 'success', review: 'error', reviewSkipped: true, layout: 'idle' })).rules).toBe('failed');
  });
});
