import { describe, expect, it } from 'vitest';
import { PUBLIC_FACILITY_LAND, parkingVerdict, zoningNames } from './verdict';
import type { ParkingSimulation, RuleReview } from '@/lib/api/types';

function review(landCategory: string | null): RuleReview {
  return {
    reviewId: '00000000-0000-4000-8000-000000000001',
    parcel: {
      pnu: '2726010100100010000',
      roadAddress: null,
      jibunAddress: '범어동 1',
      areaM2: 500,
      landCategory,
      landUses: [
        { name: '제2종일반주거지역', relation: '포함', kind: 'zoning' },
        { name: '제3종일반주거지역', relation: '접함', kind: 'zoning' },
        { name: '지구단위계획구역', relation: '포함', kind: 'zone' },
      ],
      polygon: [],
    },
    supported: true,
    asOfDate: '2026-11-16',
    items: [
      {
        id: 'ZONING_USE',
        title: '검토 가능한 주요 용도',
        status: 'REVIEWABLE',
        reasonCode: null,
        criteria: '제2종일반주거지역 안에서 건축할 수 있는 건축물',
        referenceValue: null,
        uses: [],
        table: null,
        explanation: [],
        summary: null,
        basis: [
          {
            layer: 'L1_NATIONAL',
            documentTitle: '시행령',
            articleLabel: '별표4',
            effectiveFrom: null,
            quote: null,
            url: null,
            ruleId: null,
          },
        ],
      },
    ],
    manual: [],
    dataNotices: [],
    disclaimer: '',
  };
}

function sim(status: string, reasons: string[] = [], extra: Partial<ParkingSimulation> = {}): ParkingSimulation {
  return {
    reviewId: 'r',
    pnu: 'p',
    asOfDate: '2026-11-16',
    eligibility: { status, useName: status === 'REVIEWABLE' ? '주차장' : null, reason: '사유' },
    legalBasis: [],
    disclaimer: '',
    simulated: status !== 'NOT_PERMITTED',
    referenceOnly: false,
    constraintReasons: reasons,
    message: null,
    result: null,
    ...extra,
  };
}

describe('parkingVerdict', () => {
  it('지목이 공공시설 용지면 지목 때문에 건설 불가', () => {
    const v = parkingVerdict(review('도로'), sim('NOT_PERMITTED', ['PARKING_NOT_PERMITTED']));
    expect(v.kind).toBe('not_permitted');
    if (v.kind !== 'not_permitted') return;
    expect(v.cause).toBe('land_category');
    expect(v.landCategory).toBe('도로');
    expect(v.zoning).toEqual(['제2종일반주거지역']);
    expect(v.zoningBasis).toHaveLength(1);
  });

  it('지목이 대지인데 건설 불가면 용도지역 때문', () => {
    const v = parkingVerdict(review('대'), sim('NOT_PERMITTED'));
    expect(v.kind === 'not_permitted' && v.cause).toBe('zoning');
  });

  it('constraint 코드만 와도 건설 불가로 본다', () => {
    expect(parkingVerdict(review('대'), sim('MANUAL_CHECK', ['PARKING_NOT_PERMITTED'])).kind).toBe('not_permitted');
  });

  it('가능·확인 필요는 상태와 참고용 여부를 그대로', () => {
    const ok = parkingVerdict(review('대'), sim('REVIEWABLE'));
    expect(ok).toMatchObject({ kind: 'possible', status: 'REVIEWABLE', referenceOnly: false, useName: '주차장' });
    const manual = parkingVerdict(review('대'), sim('MANUAL_CHECK'));
    expect(manual).toMatchObject({ kind: 'possible', status: 'MANUAL_CHECK', referenceOnly: true });
    expect(parkingVerdict(review('대'), sim('WEIRD'))).toMatchObject({ status: 'MANUAL_CHECK' });
  });

  it('배치 결과가 없으면 unknown', () => {
    expect(parkingVerdict(review('대'), null)).toEqual({ kind: 'unknown' });
  });

  it('BE 와 같은 공공시설 지목 목록, 접함은 용도지역에서 뺀다', () => {
    expect(PUBLIC_FACILITY_LAND).toEqual(['도로', '철도용지', '하천', '제방', '구거', '유지', '수도용지']);
    expect(zoningNames(null)).toEqual([]);
  });
});
