import { describe, expect, it } from 'vitest';
import type { BeParcel, BeParkingSimulation } from '@/lib/api/beTypes';
import { toParcel, toParkingSimulation } from '@/lib/api/beAdapters';
import parcelS01 from '@/lib/api/__fixtures__/parcel_S01.json';
import simulationS02 from '@/lib/api/__fixtures__/simulation_S02.json';
import simulationSplit from '@/lib/api/__fixtures__/simulation_buildings_split.json';
import { districtTags, districtText, landUseParcelFromReview, landUseRows, toPyeong } from './landUseRows';
import type { ParkingVerdict } from './verdict';

const parcel = toParcel(parcelS01 as BeParcel);
const altA = toParkingSimulation(simulationS02 as BeParkingSimulation).result!.alternatives[0];
const unknown: ParkingVerdict = { kind: 'unknown' };

function notPermitted(cause: 'land_category' | 'zoning'): ParkingVerdict {
  return {
    kind: 'not_permitted',
    cause,
    reason: '사유',
    landCategory: '도로',
    zoning: [],
    landUses: null,
    zoningBasis: [],
    zoningItem: null,
  };
}

const byKey = (rows: ReturnType<typeof landUseRows>) => Object.fromEntries(rows.map((r) => [r.key, r]));

describe('토지이용계획 표', () => {
  it('필지 값으로 줄을 만든다 (토지특성의 형상·지형·이용상황 포함)', () => {
    const rows = landUseRows({ parcel, verdict: unknown, alternative: null });
    expect(rows.map((r) => r.key)).toEqual([
      'area',
      'jimok',
      'zoning',
      'districts',
      'shape',
      'terrain',
      'useStatus',
      'road',
      'landPrice',
      'gate',
    ]);
    const k = byKey(rows);
    expect(k.area.value).toBe('624.1㎡');
    expect(k.area.sub).toBe('약 188.8평');
    expect(k.jimok.value).toBe('대');
    expect(k.zoning.value).toBe('제2종일반주거지역');
    expect(k.shape).toMatchObject({ value: '정방형', muted: false });
    expect(k.terrain.value).toBe('평지');
    expect(k.useStatus.value).toBe('단독');
    expect(k.road.value).toBe('세로한면(가)');
    expect(k.landPrice.value).toBe('3,850,000원/㎡');
    expect(k.gate).toMatchObject({ value: '—', muted: true });
  });

  it('없는 값의 줄은 빼고, 대지 형상만 정보 없음으로 남긴다', () => {
    const rows = landUseRows({
      parcel: {
        ...parcel,
        landShape: undefined,
        terrainHeight: undefined,
        landUseStatus: ' ',
        landPrice: undefined,
        road: {},
        landUses: undefined,
        zoning2: '제3종일반주거지역',
      },
      verdict: unknown,
      alternative: null,
    });
    expect(rows.map((r) => r.key)).toEqual(['area', 'jimok', 'zoning', 'shape', 'gate']);
    const k = byKey(rows);
    expect(k.shape).toMatchObject({ value: '정보 없음', muted: true });
    expect(k.zoning.value).toBe('제2종일반주거지역 · 제3종일반주거지역');
  });

  it('평 값이 없으면 ㎡ 에서 환산', () => {
    expect(toPyeong(330.5785)).toBeCloseTo(100, 3);
    const k = byKey(landUseRows({ parcel: { ...parcel, areaPyeong: undefined, area: 100 }, verdict: unknown, alternative: null }));
    expect(k.area.sub).toBe('약 30.3평');
  });

  it('출입구 계획: 배치안이 있으면 개소·폭, 건설 불가면 해당 없음', () => {
    expect(byKey(landUseRows({ parcel, verdict: unknown, alternative: altA })).gate).toMatchObject({
      value: '1개소 · 폭 6 m',
      muted: false,
    });
    expect(byKey(landUseRows({ parcel, verdict: notPermitted('zoning'), alternative: altA })).gate.value).toBe('해당 없음');
    // 건물로 나뉜 대지 — 영역마다 출입구 (MultiPoint 의 점 수)
    const split = toParkingSimulation(simulationSplit as unknown as BeParkingSimulation).result!.alternatives[0];
    expect(byKey(landUseRows({ parcel, verdict: unknown, alternative: split })).gate.value).toBe('2개소 · 폭 3 m');
  });

  it('건설 불가 판정의 근거 줄을 강조한다', () => {
    const land = byKey(landUseRows({ parcel, verdict: notPermitted('land_category'), alternative: null }));
    expect(land.jimok.highlight).toBe(true);
    // 판정에 쓴 값(검토 당시 지목)을 보여 준다 — 필지 API 의 현재 값(대)이 아니라
    expect(land.jimok.value).toBe('도로');
    expect(land.zoning.highlight).toBe(false);
    const zoning = byKey(landUseRows({ parcel, verdict: notPermitted('zoning'), alternative: null }));
    expect(zoning.zoning.highlight).toBe(true);
    expect(zoning.jimok.highlight).toBe(false);
  });

  it('필지 조회 실패 시 검토 스냅숏으로 채운다 (토지특성 줄은 빠짐)', () => {
    const p = landUseParcelFromReview({
      pnu: '2726010100190010000',
      roadAddress: null,
      jibunAddress: '대구광역시 수성구 범어동 9001',
      areaM2: 624.1,
      landCategory: '대',
      landUses: [
        { name: '제2종일반주거지역', relation: '포함', kind: 'zoning' },
        { name: '지구단위계획구역', relation: '포함', kind: 'zone' },
      ],
      polygon: [],
    });
    const rows = landUseRows({ parcel: p, verdict: unknown, alternative: null });
    expect(rows.map((r) => r.key)).toEqual(['area', 'jimok', 'zoning', 'districts', 'shape', 'gate']);
    expect(byKey(rows).zoning.value).toBe('제2종일반주거지역');
  });

  it('용도지구·구역: 용도지역은 빼고, 접함은 뒤로', () => {
    const tags = districtTags([
      { name: '도로', relation: '접함', kind: 'other' },
      { name: '제2종일반주거지역', relation: '포함', kind: 'zoning' },
      { name: '지구단위계획구역', relation: '포함', kind: 'zone' },
      { name: '시가지경관지구', relation: '저촉', kind: 'district' },
    ]);
    expect(tags.map((t) => [t.name, t.adjacent])).toEqual([
      ['지구단위계획구역', false],
      ['시가지경관지구', false],
      ['도로', true],
    ]);
    expect(districtTags(undefined)).toEqual([]);
  });

  it('용도지구·구역 줄의 글(인쇄 1쪽): 이름(관계)을 가운뎃점으로 — 관계가 없으면 이름만', () => {
    expect(districtText({ name: '상대보호구역', relation: '저촉' })).toBe('상대보호구역(저촉)');
    expect(districtText({ name: '도시지역', relation: ' ' })).toBe('도시지역');
    const rows = byKey(landUseRows({ parcel, verdict: unknown, alternative: null }));
    expect(rows.districts.tags?.length).toBeGreaterThan(0);
    expect(rows.districts.value).toBe(rows.districts.tags!.map(districtText).join(' · '));
  });
});

describe('판정 원인 행의 값', () => {
  it('용도지역 때문이면 판정에 쓴 용도지역을, 원인이 아닌 행은 필지 값을 쓴다', () => {
    const base = notPermitted('zoning');
    if (base.kind !== 'not_permitted') throw new Error('unreachable');
    const v: ParkingVerdict = { ...base, zoning: ['자연녹지지역'] };
    const rows = byKey(landUseRows({ parcel, verdict: v, alternative: null }));
    expect(rows.zoning.value).toBe('자연녹지지역');
    expect(rows.jimok.value).toBe(parcel.jimok);
  });
});
