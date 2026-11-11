import { describe, expect, it } from 'vitest';
import type { BeAddressSearch, BeParcel, BeParkingSimulation, BeReviewProgress, BeReviewResult } from './beTypes';
import { isBeReviewResult } from './beTypes';
import {
  geometryToRing,
  pickZoning,
  sourceProvider,
  splitJibun,
  toAddressSearchResult,
  toLayoutLayers,
  toParcel,
  toParkingSimulation,
  toReviewProgress,
  toRuleReview,
} from './beAdapters';
import addressSearch from './__fixtures__/address_search.json';
import parcelS01 from './__fixtures__/parcel_S01.json';
import parcelS04 from './__fixtures__/parcel_S04.json';
import parcelU02 from './__fixtures__/parcel_U02_seoul.json';
import reviewS01 from './__fixtures__/review_S01.json';
import reviewS02 from './__fixtures__/review_S02.json';
import reviewU02 from './__fixtures__/review_U02_unsupported.json';
import reviewProgress from './__fixtures__/review_progress.json';
import simulationS01 from './__fixtures__/simulation_S01_not_simulated.json';
import simulationS02 from './__fixtures__/simulation_S02.json';

/**
 * BE 응답 샘플(__fixtures__/*.json — 실행 중인 BE 에서 받은 원문)이 FE 모델로 예외 없이 변환되고,
 * 화면이 의존하는 필드가 기대한 값으로 매핑되는지 확인합니다.
 */

describe('주소 검색 → AddressSearchResult', () => {
  it('items 를 hits 로, { lng, lat } 를 { lat, lng } 로, supported 를 inSuseong 으로', () => {
    const r = toAddressSearchResult(addressSearch as BeAddressSearch);
    expect(r.total).toBe(2);
    expect(r.hits).toHaveLength(2);
    const [a] = r.hits;
    expect(a.pnu).toBe('2726010100190010000');
    expect(a.roadAddress).toBe('대구광역시 수성구 범어예시로 12 (범어동)');
    expect(a.jibunAddress).toBe('대구광역시 수성구 범어동 9001');
    expect(a.center).toEqual({ lat: 35.8605, lng: 128.6262 });
    expect(a.inSuseong).toBe(true);
    expect(a.buildingName).toContain('S01');
    expect(r.notice).toBeUndefined();
  });

  it('도로명이 없으면 지번으로 채우고 좌표가 없으면 null', () => {
    const r = toAddressSearchResult({
      query: 'x',
      count: 1,
      items: [
        {
          pnu: '1111010100192010000',
          road_address: null,
          jibun_address: '서울특별시 종로구 청운동 9201',
          location: null,
          supported: false,
        },
      ],
    });
    expect(r.hits[0].roadAddress).toBe('서울특별시 종로구 청운동 9201');
    expect(r.hits[0].center).toBeNull();
    expect(r.hits[0].inSuseong).toBe(false);
  });
});

describe('필지 → Parcel', () => {
  const s01 = toParcel(parcelS01 as BeParcel);

  it('S01: 주소·관할·중심([lng,lat] → {lat,lng})·경계(닫는 점 제거)', () => {
    expect(s01.pnu).toBe('2726010100190010000');
    expect(s01.address.jibun).toBe('대구광역시 수성구 범어동 9001');
    expect(s01.address.gu).toBe('수성구');
    expect(s01.address.dong).toBe('범어동');
    expect(s01.address.road).toBeUndefined();
    expect(s01.inSuseong).toBe(true);
    expect(s01.jurisdictionCode).toBe('27260');
    expect(s01.center).toEqual({ lat: 35.8605, lng: 128.6262 });
    expect(s01.polygon).toHaveLength(4);
    expect(s01.polygon[0]).toEqual({ lat: 35.8603699, lng: 128.6260866 });
  });

  it('S01: 면적(토지대장·평)·지목·용도지역·도로접면·공시지가', () => {
    expect(s01.area).toBe(624.1);
    expect(s01.areaSource).toBe('ledger');
    expect(s01.areaPyeong).toBe(188.79);
    expect(s01.jimok).toBe('대');
    expect(s01.zoning).toBe('제2종일반주거지역');
    expect(s01.zoning2).toBeUndefined();
    expect(s01.road.access).toBe('세로한면(가)');
    expect(s01.landPrice).toBe(3850000);
  });

  it('S01: 토지이용계획(포함/저촉/접함 관계·종류)·건축물·안내·예시 표시·출처', () => {
    expect(s01.landUses).toHaveLength(5);
    expect(s01.landUses![1]).toEqual({ name: '제2종일반주거지역', code: 'UQA122', relation: '포함', kind: 'zoning' });
    expect(s01.landUses![4].relation).toBe('저촉');
    expect(s01.landUses![2].kind).toBe('zone');
    expect(s01.buildings).toEqual([{ mainUse: '단독주택', grossFloorArea: 182.4, approvalDate: '1994-05-12' }]);
    expect(s01.dataNotices).toEqual([]);
    expect(s01.isExample).toBe(true);
    expect(s01.source.providers).toEqual(['vworld', 'data.go.kr']);
    expect(s01.source.queriedAt).toBe('2026-11-10');
  });

  it('S04: 근린상업지역 · 중로한면', () => {
    const p = toParcel(parcelS04 as BeParcel);
    expect(p.zoning).toBe('근린상업지역');
    expect(p.road.access).toBe('중로한면');
    expect(p.buildings[0].mainUse).toBe('제2종근린생활시설');
  });

  it('U02(서울): 지원 관할 밖 → inSuseong=false, 나머지 필드는 그대로', () => {
    const p = toParcel(parcelU02 as BeParcel);
    expect(p.inSuseong).toBe(false);
    expect(p.address.gu).toBe('종로구');
    expect(p.zoning).toBe('제1종일반주거지역');
    expect(p.landUses!.filter((u) => u.kind === 'district')).toHaveLength(2);
  });

  it('토지대장 면적이 없으면 경계로 계산(geometry), 중심이 없으면 경계 중심, designations null 이면 landUses 없음', () => {
    const body = {
      ...(parcelS01 as BeParcel),
      land_area_m2: null,
      land_area_pyeong: null,
      designations: null,
      location: { centroid: null, geometry: (parcelS01 as BeParcel).location!.geometry },
    };
    const p = toParcel(body);
    expect(p.areaSource).toBe('geometry');
    expect(p.area).not.toBeNull();
    expect(Math.abs(p.area! - 624.1) / 624.1).toBeLessThan(0.03);
    expect(p.center!.lat).toBeCloseTo(35.8605, 3);
    expect(p.center!.lng).toBeCloseTo(128.6262, 3);
    expect(p.landUses).toBeUndefined();
    expect(p.zoning).toBe('');
    expect(p.areaPyeong).toBeUndefined();
  });

  it('경계·중심이 모두 없으면 polygon 빈 배열, center·area null', () => {
    const p = toParcel({ ...(parcelS01 as BeParcel), land_area_m2: null, location: null });
    expect(p.polygon).toEqual([]);
    expect(p.center).toBeNull();
    expect(p.area).toBeNull();
    expect(p.areaSource).toBeUndefined();
  });

  it('pickZoning 은 kind=zoning 이고 접함이 아닌 것만, 중복 없이 2개까지', () => {
    expect(
      pickZoning([
        { kind: 'other', name: '도시지역', conflict: 'included' },
        { kind: 'zoning', name: '제2종일반주거지역', conflict: 'included' },
        { kind: 'zoning', name: '제2종일반주거지역', conflict: 'included' },
        { kind: 'zoning', name: '근린상업지역', conflict: 'adjacent' },
        { kind: 'zoning', name: '제3종일반주거지역', conflict: 'partially_included' },
      ]),
    ).toEqual(['제2종일반주거지역', '제3종일반주거지역']);
    expect(pickZoning(null)).toEqual([undefined, undefined]);
  });

  it('splitJibun · sourceProvider · geometryToRing', () => {
    expect(splitJibun('대구광역시 수성구 수성동1가 9004')).toEqual({ gu: '수성구', dong: '수성동1가' });
    expect(splitJibun('서울특별시 종로구 청운동 9201')).toEqual({ gu: '종로구', dong: '청운동' });
    expect(sourceProvider('vworld:LP_PA_CBND_BUBUN')).toBe('vworld');
    expect(sourceProvider('data.go.kr:BldRgstHubService/getBrTitleInfo')).toBe('data.go.kr');
    expect(geometryToRing(null)).toEqual([]);
    expect(geometryToRing({ type: 'Point', coordinates: [1, 2] })).toEqual([]);
  });
});

describe('법규 검토 → RuleReview', () => {
  const s01 = toRuleReview(reviewS01 as unknown as BeReviewResult);
  const s02 = toRuleReview(reviewS02 as unknown as BeReviewResult);

  it('S01: review_id·기준일·지원 여부·6항목 순서·상태 3값', () => {
    expect(s01.reviewId).toBe('01a1c5cf-d680-719d-a82c-612c939d4dbd');
    expect(s01.asOfDate).toBe('2026-11-10');
    expect(s01.engineVersion).toBe('0.1.0');
    expect(s01.supported).toBe(true);
    expect(s01.items.map((i) => i.id)).toEqual([
      'ZONING_USE',
      'BUILDING_COVERAGE',
      'FLOOR_AREA_RATIO',
      'PARKING_REQUIREMENT',
      'PARKING_STALL_SIZE',
      'DRIVE_AISLE_WIDTH',
    ]);
    expect(s01.items.map((i) => i.status)).toEqual([
      'MANUAL_CHECK',
      'MANUAL_CHECK',
      'MANUAL_CHECK',
      'MANUAL_CHECK',
      'REVIEWABLE',
      'REVIEWABLE',
    ]);
    expect(s01.items[0].reasonCode).toBe('ZONE_OVERLAY');
    expect(s01.items[4].reasonCode).toBeNull();
  });

  it('S01: 적용값 — 참고값(건폐율) · 용도 목록(ZONING_USE) · 기준표(주차구획·차로) · 없음(주차대수)', () => {
    const [uses, bcr, far, parking, stall, aisle] = s01.items;
    expect(bcr.criteria).toBe('60% 이하');
    expect(bcr.referenceValue).toEqual({ label: '최대 건축면적 참고값', value: 374.46, unit: '㎡', formula: '624.1 × 0.60' });
    expect(far.referenceValue!.value).toBe(1373.02);
    expect(uses.uses!.length).toBeGreaterThan(20);
    expect(uses.uses![0]).toEqual({ name: '단독주택', group: '주거', status: 'REVIEWABLE', conditionNote: null });
    expect(uses.uses!.find((u) => u.name === '제2종 근린생활시설')!.status).toBe('CONDITIONAL');
    expect(uses.referenceValue).toBeNull();
    expect(stall.table).toHaveLength(18);
    expect(stall.table![7]).toEqual({ label: '평행주차 외 · 일반형 너비', value: 2.5, unit: 'm 이상' });
    expect(aisle.table).toHaveLength(12);
    expect(parking.referenceValue).toBeNull();
    expect(parking.uses).toBeNull();
    expect(parking.table).toBeNull();
    expect(parking.criteria).toBe('주차 기준 확인 필요');
  });

  it('S01: 쉬운 설명은 basis_only 가 아닐 때만, 근거 트리는 법률 → 시행령 → 조례 순서 그대로', () => {
    const [, bcr, , parking] = s01.items;
    expect(bcr.explanation).toHaveLength(3);
    expect(bcr.explanation[0]).toContain('60퍼센트');
    expect(parking.explanation).toEqual([]);
    expect(parking.basis).toEqual([]);
    expect(bcr.basis).toHaveLength(4);
    expect(bcr.basis.map((b) => b.layer)).toEqual(['L1_NATIONAL', 'L1_NATIONAL', 'L1_NATIONAL', 'L3_LOCAL']);
    expect(bcr.basis[3]).toMatchObject({
      documentTitle: '대구광역시 도시계획 조례',
      articleLabel: '제75조제4호',
      effectiveFrom: '2026-05-11',
      quote: '제2종일반주거지역 : 60퍼센트 이하',
    });
    expect(bcr.basis[3].url).toMatch(/^https:\/\/www\.law\.go\.kr\//);
    expect(bcr.basis[3].ruleId).toBe('01a1c5ce-9d5c-7123-bf6d-9b43fac20c8a');
  });

  it('S01: 별도 확인 4항목(확인할 것·관련 근거·해당 사유)·데이터 안내·고지', () => {
    expect(s01.manual.map((m) => m.id)).toEqual(['ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE']);
    expect(s01.manual[0].whatToCheck).toEqual(['도로 폭', '접도 조건', '차량 진출입 가능 위치']);
    expect(s01.manual[0].why).toContain('세로한면(가)');
    expect(s01.manual[3].triggeredBy).toBe('지구단위계획구역 중첩 확인');
    expect(s01.manual[3].relatedBasis).toHaveLength(1);
    expect(s01.manual[3].relatedBasis[0].layer).toBe('L4_SPECIAL_ZONE');
    expect(s01.manual[3].relatedBasis[0].url).toBe('https://www.eum.go.kr/web/am/amMain.jsp');
    expect(s01.dataNotices.map((n) => n.code)).toEqual(['RULE_NOT_APPROVED', 'PENDING_AMENDMENT']);
    expect(s01.disclaimer).toContain('인허가 가능 여부를 확정하지 않습니다');
  });

  it('S02: 특화구역 중첩이 없는 필지는 검토 가능, 주차대수만 별도 확인(DATA_MISSING)', () => {
    expect(s02.items.map((i) => i.status)).toEqual(['REVIEWABLE', 'REVIEWABLE', 'REVIEWABLE', 'MANUAL_CHECK', 'REVIEWABLE', 'REVIEWABLE']);
    expect(s02.items[3].reasonCode).toBe('DATA_MISSING');
    expect(s02.items[0].reasonCode).toBeNull();
  });

  it('U02(서울): REGION_NOT_SUPPORTED → supported=false, 항목 없음', () => {
    const u = toRuleReview(reviewU02 as unknown as BeReviewResult);
    expect(u.supported).toBe(false);
    expect(u.items).toEqual([]);
    expect(u.manual).toEqual([]);
    expect(u.dataNotices[0].code).toBe('REGION_NOT_SUPPORTED');
  });

  it('진행 중 응답은 items 가 없고 단계 목록으로 변환된다', () => {
    expect(isBeReviewResult(reviewProgress as BeReviewProgress)).toBe(false);
    expect(isBeReviewResult(reviewS01 as unknown as BeReviewResult)).toBe(true);
    const p = toReviewProgress(reviewProgress as BeReviewProgress);
    expect(p.status).toBe('running');
    expect(p.steps).toHaveLength(5);
    expect(p.steps[2]).toEqual({ step: 'building', label: '건축 법규 검토', state: 'running' });
  });
});

describe('주차장 배치 시뮬레이션 → ParkingSimulation', () => {
  it('S01: simulated=false — 이유 코드와 안내만, result 없음', () => {
    const sim = toParkingSimulation(simulationS01 as BeParkingSimulation);
    expect(sim.simulated).toBe(false);
    expect(sim.result).toBeNull();
    expect(sim.constraintReasons).toEqual(['PARKING_USE_NOT_CONFIRMED']);
    // 화면에 그대로 나가는 문장 — 요청 파라미터 이름 같은 개발자용 표현이 없어야 합니다
    expect(sim.message).toContain('배치를 계산하지 않았습니다');
    expect(sim.message).not.toContain('include_unverified');
    expect(sim.eligibility).toEqual({
      status: 'MANUAL_CHECK',
      useName: null,
      reason: '특화구역(지구단위계획 등)과 겹쳐 별도 확인이 필요합니다.',
    });
    expect(sim.legalBasis).toHaveLength(2);
    expect(sim.legalBasis[0].criteria).toBe('일반형 2.5m × 5m 이상');
  });

  it('S02: 배치안 3개, 추천 ALT_A, 지표·각도·주차면수', () => {
    const sim = toParkingSimulation(simulationS02 as BeParkingSimulation);
    expect(sim.simulated).toBe(true);
    expect(sim.referenceOnly).toBe(false);
    expect(sim.eligibility.status).toBe('REVIEWABLE');
    expect(sim.result!.siteAreaM2).toBe(526.78);
    expect(sim.result!.recommendedCode).toBe('ALT_A');
    const alts = sim.result!.alternatives;
    expect(alts.map((a) => a.code)).toEqual(['ALT_A', 'ALT_B', 'ALT_C']);
    expect(alts[0]).toMatchObject({ angle: 'PERPENDICULAR_90', stallCount: 16, isRecommended: true, stallType: 'general' });
    expect(alts[0].metrics.aisle_width_m).toBe(6);
    expect(alts[0].metrics.efficiency_percent).toBe(41.52);
    expect(alts[1].isRecommended).toBe(false);
  });

  it('S02: layers 묶음 → FeatureCollection 하나 (가용 영역 → 차로 → 주차구획 → 개구부 → 출입구), properties.layer 로 종류 구분', () => {
    const sim = toParkingSimulation(simulationS02 as BeParkingSimulation);
    const layers = sim.result!.alternatives[0].layers;
    expect(layers.type).toBe('FeatureCollection');
    expect(layers.features).toHaveLength(16 + 2 + 1 + 1 + 1);
    const kinds = layers.features.map((f) => f.properties.layer);
    expect(kinds[0]).toBe('usable_area');
    expect(kinds.filter((k) => k === 'aisle')).toHaveLength(2);
    expect(kinds.filter((k) => k === 'stall')).toHaveLength(16);
    expect(kinds.slice(-2)).toEqual(['gate_opening', 'gate']);
    expect(layers.features[0].geometry.type).toBe('Polygon');
    expect(layers.features.at(-1)!.geometry.type).toBe('Point');
    expect(layers.features.at(-2)!.geometry.type).toBe('LineString');
    const stall = layers.features.find((f) => f.properties.layer === 'stall')!;
    expect(stall.properties.id).toBe('P-001');
    expect(stall.properties.width_m).toBe(2.5);
  });

  it('layers 가 없거나 geometry 가 비어 있으면 빈 FeatureCollection', () => {
    expect(toLayoutLayers(null)).toEqual({ type: 'FeatureCollection', features: [] });
    expect(
      toLayoutLayers({ gate: { type: 'Feature', geometry: null }, stalls: { type: 'FeatureCollection', features: [] } }).features,
    ).toEqual([]);
  });
});

describe('모든 BE 샘플이 예외 없이 변환된다', () => {
  it('필지 샘플', () => {
    for (const body of [parcelS01, parcelS04, parcelU02]) expect(() => toParcel(body as BeParcel)).not.toThrow();
  });
  it('검토 샘플', () => {
    for (const body of [reviewS01, reviewS02, reviewU02]) expect(() => toRuleReview(body as unknown as BeReviewResult)).not.toThrow();
  });
  it('시뮬레이션 샘플', () => {
    for (const body of [simulationS01, simulationS02]) expect(() => toParkingSimulation(body as BeParkingSimulation)).not.toThrow();
  });
});
