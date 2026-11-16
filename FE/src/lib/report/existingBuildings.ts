import type {
  ExistingBuilding,
  ExistingBuildings,
  ExistingBuildingsMode,
  ExistingBuildingsStatus,
  LayoutAlternative,
  ParkingSimulation,
} from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { formatArea, formatInt, formatNumber } from '@/lib/format';
import { gateInfo } from './layoutMetrics';

/**
 * 결과 리포트의 기존 건물 처리 — 유지/철거 가정 전환을 보여 줄지, 지금 그릴 결과, 지도 아래 안내 문장, 지도·DXF 에 그릴 외곽선,
 * 전환 뒤에 고를 배치안. 모두 순수 함수라 화면 없이 시험합니다.
 *
 * BE 는 기본(keep)으로 대지 위 건물(+ 이격)을 배치 영역에서 빼고, ignore 면 건물을 철거한다고 보고 무시합니다.
 * 결과의 existing_buildings 는 계산한 결과(simulated=true)에만 붙습니다.
 */

type SimLike = Pick<ParkingSimulation, 'simulated' | 'existingBuildings'>;

/** 유지/철거 가정을 고를 수 있는지 — 기본(유지) 계산이 대지 위 건물 1동 이상을 실제로 피했을 때만 */
export function canChooseBuildings(base: SimLike | null | undefined): boolean {
  const eb = base?.simulated ? base.existingBuildings : null;
  return Boolean(eb && eb.count > 0 && (eb.status === 'applied' || eb.status === 'ignored'));
}

/**
 * 지도·지표·DXF 에 쓸 결과 — 철거 가정을 골랐고 그 계산을 받았으면 그것, 아니면 기본(유지) 결과.
 * 철거 가정을 계산하는 동안·실패했을 때는 기본 결과를 그대로 보여 줍니다 (지도가 비지 않게).
 */
export function simulationForMode<T extends SimLike>(base: T | null, ignored: T | null, mode: ExistingBuildingsMode): T | null {
  if (mode === 'ignore' && canChooseBuildings(base) && ignored?.simulated) return ignored;
  return base;
}

/** 지금 그린 결과가 어느 쪽으로 계산된 것인지 (건물 정보가 없으면 keep) */
export function shownBuildingsMode(sim: SimLike | null | undefined): ExistingBuildingsMode {
  return sim?.existingBuildings?.mode ?? 'keep';
}

/** 지도에 그릴 기존 건물 — 대지 위 건물이 있고 반영(또는 철거 가정)한 결과일 때만. drawAs: 그림 모양 (유지 = 진한 회색, 철거 가정 = 점선) */
export interface BuildingOverlay {
  footprints: readonly ExistingBuilding[];
  drawAs: ExistingBuildingsMode;
  count: number;
}

export function buildingOverlay(sim: SimLike | null | undefined): BuildingOverlay | null {
  const eb = sim?.simulated ? sim.existingBuildings : null;
  if (!eb || eb.footprints.length === 0 || (eb.status !== 'applied' && eb.status !== 'ignored')) return null;
  return { footprints: eb.footprints, drawAs: eb.status === 'ignored' ? 'ignore' : 'keep', count: eb.count };
}

/** 지도 아래 안내 한 줄 — code 는 이 문장이 설명하는 constraint_reasons 코드 (이유 목록에서 같은 말을 되풀이하지 않게) */
export interface BuildingNote {
  tone: 'info' | 'warning';
  text: string;
  code: string | null;
}

/** 기존 건물 반영 문장: applied → 피해 배치(면적·이격), ignored → 철거 가정, unavailable → 반영 못함(경고), none → 없음 */
export function buildingStatusNote(eb: ExistingBuildings | null | undefined): BuildingNote | null {
  if (!eb) return null;
  const t = content.report.layout.buildings;
  if (eb.status === 'unavailable') return { tone: 'warning', text: t.unavailable, code: 'EXISTING_BUILDINGS_UNAVAILABLE' };
  if (eb.count <= 0) return null;
  const n = formatInt(eb.count);
  if (eb.status === 'ignored') return { tone: 'info', text: fmt(t.ignored, { n }), code: null };
  if (eb.status !== 'applied') return null;
  const lead = eb.areaM2 !== null && eb.areaM2 > 0 ? fmt(t.applied, { n, area: formatArea(eb.areaM2) }) : fmt(t.appliedNoArea, { n });
  const clearance = eb.clearanceM !== null && eb.clearanceM > 0 ? fmt(t.clearance, { clearance: formatNumber(eb.clearanceM) }) : '';
  return { tone: 'info', text: [lead, clearance].filter(Boolean).join(' '), code: 'EXISTING_BUILDINGS_EXCLUDED' };
}

/**
 * 지도 아래 안내 줄들: 기존 건물 반영 문장 + (건물로 대지가 나뉘었으면) 영역마다 출입구를 둔 문장.
 * 나뉨 여부는 결과 전체·고른 배치안의 SITE_SPLIT_BY_BUILDINGS, 영역 수는 고른 배치안의 출입구 수(도형에서 셈)입니다.
 */
export function buildingNotes(
  sim: Pick<ParkingSimulation, 'simulated' | 'existingBuildings' | 'constraintReasons'> | null | undefined,
  alt: Pick<LayoutAlternative, 'layers' | 'metrics' | 'constraintReasons'> | null | undefined,
): BuildingNote[] {
  if (!sim?.simulated) return [];
  const notes: BuildingNote[] = [];
  const status = buildingStatusNote(sim.existingBuildings);
  if (status) notes.push(status);
  const split =
    (alt?.constraintReasons ?? []).includes('SITE_SPLIT_BY_BUILDINGS') || sim.constraintReasons.includes('SITE_SPLIT_BY_BUILDINGS');
  const gates = alt ? gateInfo(alt).count : 0;
  if (split && gates > 1) {
    notes.push({ tone: 'info', text: fmt(content.report.layout.buildings.split, { k: gates }), code: 'SITE_SPLIT_BY_BUILDINGS' });
  }
  return notes;
}

/** 이유 목록에서 안내 문장이 이미 말한 코드는 뺍니다 */
export function reasonsWithoutNotes(codes: readonly string[], notes: readonly BuildingNote[]): string[] {
  const covered = new Set(notes.map((n) => n.code).filter((c): c is string => c !== null));
  return codes.filter((c) => !covered.has(c));
}

/** 안내·고지·출처의 기존 건물 출처 줄: '기존 건물 출처 브이월드 도로명주소 건물' — 건물을 그렸을 때만 */
export function buildingSourceLine(sim: SimLike | null | undefined): string | null {
  const source = buildingOverlay(sim) ? sim?.existingBuildings?.source : null;
  return source ? fmt(content.report.layout.buildings.sourceLine, { source }) : null;
}

/** DXF 안내 줄의 기존 건물 처리 방식 (건물이 없거나 정보가 없는 예전 결과면 null) */
export function dxfBuildingsStatus(sim: SimLike | null | undefined): ExistingBuildingsStatus | null {
  const eb = sim?.simulated ? sim.existingBuildings : null;
  if (!eb) return null;
  if (eb.status === 'unavailable') return 'unavailable';
  return eb.count > 0 && (eb.status === 'applied' || eb.status === 'ignored') ? eb.status : null;
}

/** 고른 배치안을 기억하는 값 — 유지/철거 가정을 바꿔도 같은 주차 방식(각도)을 이어서 보여 주려고 각도도 함께 */
export interface AlternativePick {
  code: string;
  angle: string;
}

/**
 * 보여 줄 배치안: 고른 것과 코드·각도가 같은 것 → 각도가 같은 것(유지/철거 가정 사이에 순서가 바뀐 경우) → 추천안 → 첫 번째.
 * 고른 적이 없으면 추천안.
 */
export function pickAlternative<A extends Pick<LayoutAlternative, 'code' | 'angle'>>(
  alternatives: readonly A[],
  pick: AlternativePick | null,
  recommendedCode: string | null | undefined,
): A | null {
  if (pick) {
    const same =
      alternatives.find((a) => a.code === pick.code && a.angle === pick.angle) ?? alternatives.find((a) => a.angle === pick.angle);
    if (same) return same;
  }
  return alternatives.find((a) => a.code === recommendedCode) ?? alternatives[0] ?? null;
}
