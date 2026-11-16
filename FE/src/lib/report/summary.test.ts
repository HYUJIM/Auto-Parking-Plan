import { describe, expect, it } from 'vitest';
import { mergeNotices, parcelSourceLine, reviewProgressText, reviewSourceLine, summaryLabel, verdictChip } from './summary';
import type { ParkingVerdict } from './verdict';

const possible = (status: 'REVIEWABLE' | 'CONDITIONAL' | 'MANUAL_CHECK'): ParkingVerdict => ({
  kind: 'possible',
  status,
  useName: null,
  reason: null,
  referenceOnly: status !== 'REVIEWABLE',
});

describe('주차장 설치 판단 칩', () => {
  it('BE 판정을 그대로 옮긴다', () => {
    expect(verdictChip(possible('REVIEWABLE'), false)).toMatchObject({ label: '가능', variant: 'ok' });
    expect(verdictChip(possible('CONDITIONAL'), false)).toMatchObject({ label: '조건부', variant: 'check' });
    expect(verdictChip(possible('MANUAL_CHECK'), false)).toMatchObject({ label: '별도 확인', variant: 'manual' });
    const np: ParkingVerdict = {
      kind: 'not_permitted',
      cause: 'zoning',
      reason: null,
      landCategory: null,
      zoning: [],
      landUses: null,
      zoningBasis: [],
      zoningItem: null,
    };
    expect(verdictChip(np, false)).toMatchObject({ label: '건설 불가', variant: 'danger' });
  });

  it('판정 전에는 확인 중, 계산하지 못했으면 판단 보류', () => {
    expect(verdictChip({ kind: 'unknown' }, true).label).toBe('확인 중');
    expect(verdictChip({ kind: 'unknown' }, false).label).toBe('판단 보류');
  });
});

describe('요약 출처 이름', () => {
  it('원문 인용 요약은 AI 라고 부르지 않는다', () => {
    expect(summaryLabel('template_fallback')).toBe('원문 인용 요약');
    expect(summaryLabel('template_fallback')).not.toContain('AI');
    expect(summaryLabel('ai_verified')).toBe('AI 요약(검증 통과)');
  });
});

describe('데이터 안내·출처', () => {
  it('같은 문구는 한 번만', () => {
    const merged = mergeNotices(
      [
        { code: 'RULE_NOT_APPROVED', message: '주차대수: 승인된 데이터 없음' },
        { code: 'PENDING_AMENDMENT', message: '개정분 미반영' },
      ],
      undefined,
      [
        { code: 'DESIGNATION_UNAVAILABLE', message: '개정분 미반영 ' },
        { code: 'PARCEL_PARTIAL', message: '일부 조회 실패' },
        { code: 'X', message: '  ' },
      ],
    );
    expect(merged.map((n) => n.code)).toEqual(['RULE_NOT_APPROVED', 'PENDING_AMENDMENT', 'PARCEL_PARTIAL']);
  });

  it('검토 진행 문구: 진행 중 단계 → 대기 단계 → 기본 문구', () => {
    const steps = [
      { step: 'parcel', label: '대지정보', state: 'done' },
      { step: 'zoning', label: '용도지역', state: 'running' },
      { step: 'rules', label: '건축 법규', state: 'pending' },
    ];
    expect(reviewProgressText({ status: 'running', steps })).toBe('법규 검토 중 — 용도지역');
    expect(
      reviewProgressText({ status: 'queued', steps: steps.map((s) => ({ ...s, state: s.state === 'running' ? 'done' : s.state })) }),
    ).toBe('법규 검토 중 — 건축 법규');
    expect(reviewProgressText(null)).toBe('법규 검토 중…');
  });

  it('출처 줄', () => {
    expect(parcelSourceLine({ isExample: false, source: { providers: ['vworld', 'data.go.kr'], queriedAt: '2026-11-10' } })).toBe(
      '출처 vworld · data.go.kr · 조회 2026-11-10',
    );
    expect(parcelSourceLine({ isExample: true, source: { providers: [] } })).toContain('예시 데이터');
    expect(reviewSourceLine({ asOfDate: '2026-11-10', engineVersion: '0.1.0' })).toBe('검토 기준일 2026-11-10 · 검토 엔진 0.1.0');
  });
});
