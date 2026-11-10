import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Parcel, RuleReview } from '@/lib/api/types';
import { getRuleReview } from '@/lib/api/parcel';
import { parcels } from '@/lib/api/mock';
import { buildRuleItems, buildRuleReview, roadFacts, rules } from './engine';

const SQUARE = [
  { lat: 35.86, lng: 128.63 },
  { lat: 35.86, lng: 128.631108 },
  { lat: 35.8609, lng: 128.631108 },
  { lat: 35.8609, lng: 128.63 },
];

/** 브이월드에서 온 필지처럼: 도로 폭·접도 길이 없음, 도로접면만 있음 */
function vworldParcel(overrides: Partial<Parcel> = {}): Parcel {
  return {
    pnu: '2726010100101770003',
    address: { jibun: '대구광역시 수성구 범어동 177-3' },
    inSuseong: true,
    center: { lat: 35.86045, lng: 128.630554 },
    polygon: SQUARE,
    road: { access: '광대소각' },
    area: 2771,
    areaSource: 'ledger',
    zoning: '중심상업지역',
    jimok: '대',
    landPrice: 7633000,
    landUses: [
      { name: '제1종지구단위계획구역', code: 'UQQ310', relation: '접함' },
      { name: '시가지경관지구(중심)', code: 'UQF131', relation: '저촉' },
      { name: '군사기지 및 군사시설 보호구역', code: 'UMA100', relation: '포함' },
    ],
    source: { provider: 'vworld', queriedAt: '2026-11-10' },
    ...overrides,
  };
}

const byId = (review: RuleReview, id: string) => {
  const item = review.items.find((i) => i.id === id);
  if (!item) throw new Error(`항목 없음: ${id}`);
  return item;
};

describe('buildRuleReview — 목업 필지 (폭·접도 길이 있음)', () => {
  it('수성구 목업 필지는 6항목 + 별도 확인 4항목을 돌려준다', () => {
    const review = buildRuleReview(parcels[0]);
    expect(review.supported).toBe(true);
    expect(review.items).toHaveLength(6);
    expect(review.manual).toHaveLength(4);
    expect(review.source.providers).toEqual(rules.providers);
    expect(byId(review, 'bcr').status).toBe('ok');
    expect(byId(review, 'road').status).toBe('ok');
    expect(byId(review, 'lane-width').status).toBe('ok');
    expect(byId(review, 'access').status).toBe('ok');
  });

  it('기준 폭(4m)과 같은 도로는 접도 적합이지만 막다른 도로 단서가 붙고, 차로 폭은 확인 필요', () => {
    const narrow = parcels.find((p) => p.road.width === rules.road.minWidth)!;
    const review = buildRuleReview(narrow);
    expect(byId(review, 'road').status).toBe('ok');
    expect(byId(review, 'road').note).toContain('막다른');
    expect(byId(review, 'lane-width').status).toBe('check');
  });

  it('접도 요약 문장은 요약 카드와 같은 템플릿(폭 · 접도 길이)을 쓴다', () => {
    const facts = roadFacts(parcels[0]);
    expect(facts.inferred).toBe(false);
    expect(facts.label).toContain(`${parcels[0].road.width}m 도로`);
    expect(facts.label).toContain(`${parcels[0].road.frontage}m 접함`);
  });
});

describe('buildRuleReview — 문구는 rules.json 숫자를 그대로 따른다', () => {
  it('접도·주차·진출입 기준값 문구에 기준표의 숫자가 들어간다', () => {
    const items = buildRuleItems(parcels[0]);
    const road = items.find((i) => i.id === 'road')!;
    const parking = items.find((i) => i.id === 'parking')!;
    const access = items.find((i) => i.id === 'access')!;
    expect(road.standard).toContain(`폭 ${rules.road.minWidth}m`);
    expect(road.standard).toContain(`${rules.road.minFrontage}m 이상 접함`);
    expect(parking.standard).toContain(`${rules.parking.slotAreaIncludingLane}㎡/면`);
    expect(parking.applied).toContain(`${Math.round(rules.parking.usableRatio * 100)}%`);
    expect(access.standard).toContain(`${rules.road.intersectionClearance}m 이내`);
    expect(access.standard).toContain(`${rules.parking.arterialWidth}m 이상`);
  });

  it('기준표를 바꾸면 판정과 문구가 함께 바뀐다', () => {
    const stricter = { ...rules, road: { ...rules.road, minWidth: 6 }, parking: { ...rules.parking, usableRatio: 0.8 } };
    const items = buildRuleItems(
      parcels.find((p) => p.road.width === 4)!,
      stricter,
    );
    const road = items.find((i) => i.id === 'road')!;
    expect(road.standard).toContain('폭 6m');
    expect(road.status).toBe('check');
    expect(items.find((i) => i.id === 'parking')!.applied).toContain('80%');
  });
});

describe('buildRuleReview — 브이월드 필지 (도로접면으로 추정)', () => {
  it('조례 값 미확인(basis: decree) 용도지역은 시행령 상한을 쓰고 확인 필요로 표시한다', () => {
    const review = buildRuleReview(vworldParcel());
    const bcr = byId(review, 'bcr');
    expect(bcr.status).toBe('check');
    expect(bcr.standard).toContain('시행령 상한');
    expect(bcr.evidence.law).toContain('시행령');
    expect(bcr.note).toBeTruthy();
  });

  it('조례 표에 없는 용도지역은 값 없이 확인 필요만 표시한다', () => {
    const review = buildRuleReview(vworldParcel({ zoning: '계획관리지역' }));
    const bcr = byId(review, 'bcr');
    expect(bcr.status).toBe('check');
    expect(bcr.applied).toContain('계획관리지역');
    expect(bcr.standard).not.toMatch(/\d+%/);
  });

  it('광대로 접면은 접도·차로 폭 적합이지만 추정 단서가 붙고, 교차로 여부를 모르면 진출입은 확인 필요', () => {
    const review = buildRuleReview(vworldParcel());
    const road = byId(review, 'road');
    expect(road.status).toBe('ok');
    expect(road.note).toContain('추정');
    expect(byId(review, 'lane-width').status).toBe('ok');
    expect(byId(review, 'access').status).toBe('check');
  });

  it('세로(가)·맹지·접도 정보 없음은 확인 필요', () => {
    for (const access of ['세로한면(가)', '맹지', undefined]) {
      const review = buildRuleReview(vworldParcel({ road: { access } }));
      expect(byId(review, 'road').status, String(access)).toBe('check');
      expect(byId(review, 'lane-width').status, String(access)).toBe('check');
    }
  });

  it('별도 확인 항목은 토지이용계획에서 관련 항목을 찾아 붙이되 상태는 바뀌지 않는다', () => {
    const review = buildRuleReview(vworldParcel());
    const manual = Object.fromEntries(review.manual.map((m) => [m.id, m.hits.map((h) => h.name)]));
    expect(manual['district-plan']).toEqual(['제1종지구단위계획구역']);
    expect(manual['landscape']).toEqual(['시가지경관지구(중심)']);
    expect(manual['flight-safety']).toEqual(['군사기지 및 군사시설 보호구역']);
    expect(manual['heritage']).toEqual([]);
  });

  it('용도지역을 모르면 건폐율·용적률은 기준값 확인 필요', () => {
    const review = buildRuleReview(vworldParcel({ zoning: '' }));
    expect(byId(review, 'bcr').status).toBe('check');
    expect(byId(review, 'bcr').applied).toContain('미확인');
  });

  it('수성구 밖이면 항목 없이 미지원으로 답한다', () => {
    const review = buildRuleReview(vworldParcel({ pnu: '2711011000100630012', inSuseong: false }));
    expect(review.supported).toBe(false);
    expect(review.items).toEqual([]);
  });
});

describe('getRuleReview (목업 지연 포함)', () => {
  afterEach(() => vi.useRealTimers());

  it('지연 뒤 엔진 결과를 그대로 돌려준다', async () => {
    vi.useFakeTimers();
    const promise = getRuleReview(vworldParcel());
    await vi.runAllTimersAsync();
    const review = await promise;
    expect(review).toEqual(buildRuleReview(vworldParcel()));
  });
});
