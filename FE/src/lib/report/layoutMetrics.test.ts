import { describe, expect, it } from 'vitest';
import type { BeParkingSimulation } from '@/lib/api/beTypes';
import { toParkingSimulation } from '@/lib/api/beAdapters';
import type { LayoutAlternative } from '@/lib/api/types';
import simulationS02 from '@/lib/api/__fixtures__/simulation_S02.json';
import simulationSplit from '@/lib/api/__fixtures__/simulation_buildings_split.json';
import { altLetter, angleLabel, efficiencyText, gateInfo, gatePlanText, layoutMetricRows } from './layoutMetrics';

const sim = toParkingSimulation(simulationS02 as BeParkingSimulation);
const [altA, altB] = sim.result!.alternatives;

function alt(metrics: Record<string, number>, features: LayoutAlternative['layers']['features'] = []): LayoutAlternative {
  return {
    code: 'ALT_X',
    angle: 'PARALLEL_0',
    stallType: 'general',
    orientationDeg: null,
    stallCount: 3,
    isRecommended: false,
    metrics,
    constraintReasons: [],
    layers: { type: 'FeatureCollection', features },
  };
}

describe('배치안 표시 값', () => {
  it('코드·각도 이름', () => {
    expect(altLetter('ALT_A')).toBe('A');
    expect(altLetter('B')).toBe('B');
    expect(angleLabel('PERPENDICULAR_90')).toBe('직각 주차');
    expect(angleLabel('UNKNOWN_ANGLE')).toBe('UNKNOWN_ANGLE');
  });

  it('출입구는 레이어의 출입구 점 개수와 폭(gate_width_m)', () => {
    expect(gateInfo(altA)).toEqual({ count: 1, widthM: 6 });
    expect(gatePlanText(altA)).toBe('1개소 · 폭 6 m');
    expect(gatePlanText(altB)).toBe('1개소 · 폭 5.5 m');
    // 레이어가 비어도 폭이 있으면 1개소, 둘 다 없으면 표시하지 않음
    expect(gatePlanText(alt({ gate_width_m: 5 }))).toBe('1개소 · 폭 5 m');
    expect(gatePlanText(alt({}))).toBeNull();
  });

  it('건물로 나뉜 대지: 출입구 MultiPoint 를 나눈 점의 수가 개소 수 (지표 줄 · 토지이용계획 줄)', () => {
    const [split, single] = toParkingSimulation(simulationSplit as unknown as BeParkingSimulation).result!.alternatives;
    expect(gateInfo(split)).toEqual({ count: 2, widthM: 3 });
    expect(gatePlanText(split)).toBe('2개소 · 폭 3 m');
    expect(layoutMetricRows(split).find((r) => r.key === 'gate')?.value).toBe('2개소 (폭 3 m)');
    expect(gatePlanText(single)).toBe('1개소 · 폭 3.5 m');
  });

  it('주차 효율은 소수 1자리', () => {
    expect(efficiencyText(altA)).toBe('41.5');
    expect(efficiencyText(alt({}))).toBeNull();
  });

  it('지표 줄: 있는 값만, 1대당 면적은 있을 때만', () => {
    const rows = layoutMetricRows(altA);
    expect(rows.map((r) => [r.label, r.value])).toEqual([
      ['가용 면적', '481.7㎡'],
      ['주차 면적', '323.7㎡'],
      ['1대당 면적', '30.1㎡'],
      ['차로 폭', '6 m'],
      ['출입구', '1개소 (폭 6 m)'],
      ['주차구획 크기', '2.5 × 5 m'],
    ]);
    const partial = layoutMetricRows(alt({ usable_area_m2: 100, aisle_width_m: 5, stall_width_m: 2 }));
    expect(partial.map((r) => r.key)).toEqual(['usable', 'aisle']);
  });
});
