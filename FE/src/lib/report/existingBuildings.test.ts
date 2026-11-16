import { describe, expect, it } from 'vitest';
import type { BeParkingSimulation } from '@/lib/api/beTypes';
import { toParkingSimulation } from '@/lib/api/beAdapters';
import type { ExistingBuildings, ParkingSimulation } from '@/lib/api/types';
import simulationS02 from '@/lib/api/__fixtures__/simulation_S02.json';
import simulationSplit from '@/lib/api/__fixtures__/simulation_buildings_split.json';
import {
  buildingNotes,
  buildingOverlay,
  buildingSourceLine,
  canChooseBuildings,
  dxfBuildingsStatus,
  pickAlternative,
  reasonsWithoutNotes,
  shownBuildingsMode,
  simulationForMode,
} from './existingBuildings';

const keep = toParkingSimulation(simulationSplit as unknown as BeParkingSimulation);
const plain = toParkingSimulation(simulationS02 as BeParkingSimulation);

function withBuildings(sim: ParkingSimulation, eb: Partial<ExistingBuildings> | null): ParkingSimulation {
  return { ...sim, existingBuildings: eb === null ? null : { ...keep.existingBuildings!, ...eb } };
}

const ignored = withBuildings(keep, { mode: 'ignore', status: 'ignored' });

describe('유지/철거 가정 전환', () => {
  it('기본 계산이 건물 1동 이상을 피했을 때만 고를 수 있다', () => {
    expect(canChooseBuildings(keep)).toBe(true);
    expect(canChooseBuildings(plain)).toBe(false);
    expect(canChooseBuildings(withBuildings(keep, { status: 'none', count: 0 }))).toBe(false);
    expect(canChooseBuildings(withBuildings(keep, { status: 'unavailable', count: 0 }))).toBe(false);
    expect(canChooseBuildings({ ...keep, simulated: false })).toBe(false);
    expect(canChooseBuildings(null)).toBe(false);
  });

  it('철거 가정을 골랐고 그 계산을 받았을 때만 그것을 그리고, 기다리는 동안·실패면 기본 결과', () => {
    expect(simulationForMode(keep, ignored, 'ignore')).toBe(ignored);
    expect(simulationForMode(keep, null, 'ignore')).toBe(keep);
    expect(simulationForMode(keep, ignored, 'keep')).toBe(keep);
    // 고를 수 없는 결과(건물 없음)에는 철거 가정 결과를 쓰지 않음
    expect(simulationForMode(plain, ignored, 'ignore')).toBe(plain);
    expect(shownBuildingsMode(ignored)).toBe('ignore');
    expect(shownBuildingsMode(plain)).toBe('keep');
  });
});

describe('지도에 그릴 기존 건물', () => {
  it('유지는 진한 회색, 철거 가정은 점선 — 외곽선 배열은 결과의 것을 그대로(지도 계산을 다시 하지 않게)', () => {
    const o = buildingOverlay(keep)!;
    expect(o.drawAs).toBe('keep');
    expect(o.count).toBe(2);
    expect(o.footprints).toBe(keep.existingBuildings!.footprints);
    expect(buildingOverlay(ignored)!.drawAs).toBe('ignore');
  });

  it('건물이 없거나 정보가 없으면 그리지 않는다', () => {
    expect(buildingOverlay(plain)).toBeNull();
    expect(buildingOverlay(withBuildings(keep, { status: 'none', count: 0, footprints: [] }))).toBeNull();
    expect(buildingOverlay(withBuildings(keep, { status: 'unavailable', count: 0, footprints: [] }))).toBeNull();
  });
});

describe('지도 아래 안내 문장', () => {
  const [altA, altB] = keep.result!.alternatives;

  it('applied: 동수·면적·이격, 건물로 나뉘면 출입구 수(도형에서 셈)만큼 영역', () => {
    const notes = buildingNotes(keep, altA);
    expect(notes.map((n) => n.text)).toEqual([
      '기존 건물 2동(약 98.6㎡)을 피해 배치했습니다. 건물에서 1 m 띄웠습니다.',
      '건물로 나뉜 영역 2곳에 각각 출입구를 두었습니다.',
    ]);
    expect(notes.every((n) => n.tone === 'info')).toBe(true);
    // 결과 전체가 나뉨이어도 출입구가 하나인 배치안에는 영역 문장을 붙이지 않음
    expect(buildingNotes(keep, altB)).toHaveLength(1);
  });

  it('면적·이격을 모르면 그 부분만 뺀다', () => {
    const sim = withBuildings(keep, { areaM2: null, clearanceM: null });
    expect(buildingNotes(sim, null).map((n) => n.text)).toEqual(['기존 건물 2동을 피해 배치했습니다.']);
  });

  it('ignored → 철거 가정, unavailable → 경고, none·예전 응답 → 없음', () => {
    expect(buildingNotes(ignored, null)).toEqual([{ tone: 'info', text: '기존 건물 2동을 철거한다고 가정해 배치했습니다.', code: null }]);
    const unavailable = buildingNotes(withBuildings(keep, { status: 'unavailable', count: 0, footprints: [] }), null);
    expect(unavailable).toEqual([
      { tone: 'warning', text: '건물 정보를 불러오지 못해 기존 건물을 반영하지 못했습니다.', code: 'EXISTING_BUILDINGS_UNAVAILABLE' },
    ]);
    expect(buildingNotes(withBuildings(keep, { status: 'none', count: 0 }), null)).toEqual([]);
    expect(buildingNotes(plain, plain.result!.alternatives[0])).toEqual([]);
  });

  it('안내 문장이 말한 이유 코드는 이유 목록에서 뺀다', () => {
    const notes = buildingNotes(keep, altA);
    expect(reasonsWithoutNotes(['EXISTING_BUILDINGS_EXCLUDED', 'SITE_SPLIT_BY_BUILDINGS', 'GATE_SNAPPED'], notes)).toEqual([
      'GATE_SNAPPED',
    ]);
    expect(reasonsWithoutNotes(['GATE_SNAPPED'], [])).toEqual(['GATE_SNAPPED']);
  });
});

describe('출처 줄', () => {
  it('건물을 그렸을 때만 출처를 적는다', () => {
    expect(buildingSourceLine(keep)).toBe('기존 건물 출처 브이월드 도로명주소 건물');
    expect(buildingSourceLine(withBuildings(keep, { source: null }))).toBeNull();
    expect(buildingSourceLine(withBuildings(keep, { status: 'unavailable', count: 0, footprints: [] }))).toBeNull();
    expect(buildingSourceLine(plain)).toBeNull();
  });
});

describe('DXF 안내 줄의 처리 방식', () => {
  it('applied · ignored · unavailable 만, 건물 없음·예전 응답은 null', () => {
    expect(dxfBuildingsStatus(keep)).toBe('applied');
    expect(dxfBuildingsStatus(ignored)).toBe('ignored');
    expect(dxfBuildingsStatus(withBuildings(keep, { status: 'unavailable', count: 0 }))).toBe('unavailable');
    expect(dxfBuildingsStatus(withBuildings(keep, { status: 'none', count: 0 }))).toBeNull();
    expect(dxfBuildingsStatus(plain)).toBeNull();
  });
});

describe('전환 뒤 배치안 고르기', () => {
  const alts = [
    { code: 'ALT_A', angle: 'PERPENDICULAR_90' },
    { code: 'ALT_B', angle: 'ANGLE_60' },
    { code: 'ALT_C', angle: 'PARALLEL_0' },
  ];

  it('고른 적이 없으면 추천안, 추천안이 없으면 첫 번째', () => {
    expect(pickAlternative(alts, null, 'ALT_B')?.code).toBe('ALT_B');
    expect(pickAlternative(alts, null, null)?.code).toBe('ALT_A');
    expect(pickAlternative([], null, 'ALT_A')).toBeNull();
  });

  it('같은 코드·각도 → 같은 각도 → 추천안 순서', () => {
    expect(pickAlternative(alts, { code: 'ALT_C', angle: 'PARALLEL_0' }, 'ALT_A')?.code).toBe('ALT_C');
    // 철거 가정에서 순서가 바뀌어 평행 주차가 B 가 되었으면 B
    const swapped = [alts[0], { code: 'ALT_B', angle: 'PARALLEL_0' }, { code: 'ALT_C', angle: 'ANGLE_60' }];
    expect(pickAlternative(swapped, { code: 'ALT_C', angle: 'PARALLEL_0' }, 'ALT_A')?.code).toBe('ALT_B');
    // 그 각도가 없으면 추천안
    expect(pickAlternative(alts, { code: 'ALT_C', angle: 'ANGLE_45' }, 'ALT_A')?.code).toBe('ALT_A');
  });
});
