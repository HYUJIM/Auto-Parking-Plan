import type { LayoutAlternative } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { formatArea, formatNumber } from '@/lib/format';

/**
 * 배치안 한 개의 표시 값 — 결과 리포트의 '주차 배치 결과' 카드와 토지이용계획 표의 '출입구 계획' 줄이 함께 씁니다.
 * BE metrics 키: usable_area_m2 · parking_area_m2 · stall_area_m2 · efficiency_percent(= 구획 면적 ÷ 가용 면적 × 100)
 * · area_per_stall_m2(없을 수 있음) · aisle_width_m · gate_width_m · stall_width_m · stall_length_m
 */

/** 'ALT_A' → 'A' (모르는 모양이면 그대로) */
export function altLetter(code: string): string {
  return code.replace(/^ALT_/, '') || code;
}

/** 'PERPENDICULAR_90' → '직각 주차' */
export function angleLabel(angle: string): string {
  return (content.site.layout.angles as Record<string, string>)[angle] ?? angle;
}

/** 출입구 — 배치안마다 출입구는 하나이지만, 개수는 레이어에서 셉니다 (없으면 폭 값이 있을 때 1개소) */
export function gateInfo(alt: Pick<LayoutAlternative, 'layers' | 'metrics'>): { count: number; widthM: number | null } {
  const width = alt.metrics.gate_width_m;
  const widthM = typeof width === 'number' && Number.isFinite(width) ? width : null;
  const points = alt.layers.features.filter((f) => f.properties.layer === 'gate').length;
  const openings = alt.layers.features.filter((f) => f.properties.layer === 'gate_opening').length;
  const count = points || openings || (widthM !== null ? 1 : 0);
  return { count, widthM };
}

/** 토지이용계획 표의 '출입구 계획': '1개소 · 폭 6 m' */
export function gatePlanText(alt: Pick<LayoutAlternative, 'layers' | 'metrics'>): string | null {
  const g = gateInfo(alt);
  if (g.count === 0) return null;
  const t = content.report.landUse;
  return g.widthM !== null ? fmt(t.gatePlan, { n: g.count, width: formatNumber(g.widthM) }) : fmt(t.gatePlanNoWidth, { n: g.count });
}

/** 주차 효율 '41.5' (%) — 없으면 null */
export function efficiencyText(alt: Pick<LayoutAlternative, 'metrics'>): string | null {
  const e = alt.metrics.efficiency_percent;
  return typeof e === 'number' && Number.isFinite(e) ? formatNumber(e, 1) : null;
}

export interface MetricRow {
  key: 'usable' | 'parking' | 'perStall' | 'aisle' | 'gate' | 'stallSize';
  label: string;
  value: string;
}

function m2(n: number): string {
  return fmt(content.report.layout.metrics.areaValue, { n: formatArea(n) });
}

function meters(n: number): string {
  return fmt(content.report.layout.metrics.lengthValue, { n: formatNumber(n) });
}

/** 지표 줄: 가용 면적 · 주차 면적 · 1대당 면적(있을 때) · 차로 폭 · 출입구 · 주차구획 크기 — 값이 없는 줄은 뺍니다 */
export function layoutMetricRows(alt: Pick<LayoutAlternative, 'layers' | 'metrics'>): MetricRow[] {
  const t = content.report.layout.metrics;
  const m = alt.metrics;
  const rows: MetricRow[] = [];
  const has = (k: string) => typeof m[k] === 'number' && Number.isFinite(m[k]);
  if (has('usable_area_m2')) rows.push({ key: 'usable', label: t.usable, value: m2(m.usable_area_m2) });
  if (has('parking_area_m2')) rows.push({ key: 'parking', label: t.parking, value: m2(m.parking_area_m2) });
  if (has('area_per_stall_m2')) rows.push({ key: 'perStall', label: t.perStall, value: m2(m.area_per_stall_m2) });
  if (has('aisle_width_m')) rows.push({ key: 'aisle', label: t.aisle, value: meters(m.aisle_width_m) });
  const g = gateInfo(alt);
  if (g.count > 0) {
    const value =
      g.widthM !== null ? fmt(t.gateValue, { n: g.count, width: formatNumber(g.widthM) }) : fmt(t.gateValueNoWidth, { n: g.count });
    rows.push({ key: 'gate', label: t.gate, value });
  }
  if (has('stall_width_m') && has('stall_length_m')) {
    const value = fmt(t.stallSizeValue, { w: formatNumber(m.stall_width_m), l: formatNumber(m.stall_length_m) });
    rows.push({ key: 'stallSize', label: t.stallSize, value });
  }
  return rows;
}
