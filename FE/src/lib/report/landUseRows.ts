import type { LandUse, LandUseKind, LayoutAlternative, Parcel, ReviewParcel } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { formatArea, formatInt } from '@/lib/format';
import { zoningNames, type ParkingVerdict } from './verdict';
import { gatePlanText } from './layoutMetrics';

/**
 * 결과 리포트 오른쪽 '토지이용계획' 표의 줄들.
 * 값이 없는 줄은 빼고(대지 형상만 '정보 없음'으로 남김), 건설 불가 판정의 근거가 된 줄(지목 또는 용도지역)은 강조합니다.
 */
export type LandUseRowKey = 'area' | 'jimok' | 'zoning' | 'districts' | 'shape' | 'terrain' | 'useStatus' | 'road' | 'landPrice' | 'gate';

export interface LandUseTag {
  name: string;
  relation: string;
  kind: LandUseKind;
  /** 접함 — 대지에 걸치지 않고 붙어만 있는 지정 */
  adjacent: boolean;
}

export interface LandUseRow {
  key: LandUseRowKey;
  label: string;
  value: string;
  /** 값 옆에 작게 (평 환산 등) */
  sub?: string;
  /** 용도지구·구역 목록 (화면은 배지 목록, 인쇄는 value 의 한 줄 글) */
  tags?: LandUseTag[];
  /** 건설 불가 판정의 근거가 된 줄 */
  highlight?: boolean;
  /** 값이 없음·해당 없음 (흐리게) */
  muted?: boolean;
}

export type LandUseParcel = Pick<
  Parcel,
  | 'area'
  | 'areaPyeong'
  | 'jimok'
  | 'zoning'
  | 'zoning2'
  | 'landUses'
  | 'landShape'
  | 'terrainHeight'
  | 'landUseStatus'
  | 'road'
  | 'landPrice'
>;

export interface LandUseRowsInput {
  parcel: LandUseParcel;
  verdict: ParkingVerdict;
  /** 지금 고른 배치안 (없으면 출입구 계획 '—') */
  alternative: Pick<LayoutAlternative, 'layers' | 'metrics'> | null;
}

/**
 * 필지 조회가 실패했지만 검토(id)는 받은 경우 — 검토 당시의 필지 스냅숏(면적·지목·토지이용계획)으로 표를 채웁니다.
 * 토지특성(형상·지형·도로·공시지가)은 스냅숏에 없어 빠집니다.
 */
export function landUseParcelFromReview(p: ReviewParcel): LandUseParcel {
  const [zoning, zoning2] = zoningNames(p.landUses);
  return {
    area: p.areaM2,
    jimok: p.landCategory ?? '',
    zoning: zoning ?? '',
    zoning2,
    landUses: p.landUses ?? undefined,
    road: {},
  };
}

/** ㎡ → 평 (1평 = 400/121 ㎡) */
export function toPyeong(m2: number): number {
  return (m2 * 121) / 400;
}

/** 지정 한 개를 글줄로: '상대보호구역(저촉)' (관계가 없으면 이름만) — 인쇄 1쪽의 한 줄 목록과 표의 값 */
export function districtText(tag: Pick<LandUseTag, 'name' | 'relation'>): string {
  const relation = tag.relation.trim();
  return relation ? fmt(content.report.landUse.tagInline, { name: tag.name, relation }) : tag.name;
}

/** 용도지역을 뺀 지정(용도지구·구역·기타) — 대지에 걸친 것 먼저, 접함은 뒤로 */
export function districtTags(landUses: LandUse[] | undefined | null): LandUseTag[] {
  const tags = (landUses ?? [])
    .filter((u) => u.kind !== 'zoning')
    .map((u) => ({ name: u.name, relation: u.relation, kind: u.kind, adjacent: u.relation === '접함' }));
  return [...tags.filter((t) => !t.adjacent), ...tags.filter((t) => t.adjacent)];
}

function text(value: string | undefined | null): string {
  return value?.trim() ?? '';
}

export function landUseRows({ parcel, verdict, alternative }: LandUseRowsInput): LandUseRow[] {
  const t = content.report.landUse;
  const r = t.rows;
  const rows: LandUseRow[] = [];
  const cause = verdict.kind === 'not_permitted' ? verdict.cause : null;

  if (parcel.area !== null && Number.isFinite(parcel.area)) {
    const pyeong = parcel.areaPyeong ?? toPyeong(parcel.area);
    rows.push({
      key: 'area',
      label: r.area,
      value: fmt(t.areaValue, { area: formatArea(parcel.area) }),
      sub: fmt(t.areaPyeong, { pyeong: formatArea(pyeong) }),
    });
  }

  // 건설 불가 판정의 원인 행은 판정에 쓴 값(검토 당시 스냅숏)을 보여 줍니다 — 그 뒤 필지를 다시 조회해 값이 달라졌어도 근거와 어긋나지 않게
  const judgedJimok = verdict.kind === 'not_permitted' && cause === 'land_category' ? text(verdict.landCategory ?? '') : '';
  const jimok = judgedJimok || text(parcel.jimok);
  if (jimok) rows.push({ key: 'jimok', label: r.jimok, value: jimok, highlight: cause === 'land_category' });

  const judgedZoning = verdict.kind === 'not_permitted' && cause === 'zoning' ? verdict.zoning.map(text).filter(Boolean) : [];
  const zoning = (judgedZoning.length ? judgedZoning : [text(parcel.zoning), text(parcel.zoning2)]).filter(
    (z, i, all) => z && all.indexOf(z) === i,
  );
  if (zoning.length) rows.push({ key: 'zoning', label: r.zoning, value: zoning.join(' · '), highlight: cause === 'zoning' });

  const tags = districtTags(parcel.landUses);
  if (tags.length) rows.push({ key: 'districts', label: r.districts, value: tags.map(districtText).join(' · '), tags });

  const shape = text(parcel.landShape);
  rows.push({ key: 'shape', label: r.shape, value: shape || t.unknown, muted: !shape });

  const terrain = text(parcel.terrainHeight);
  if (terrain) rows.push({ key: 'terrain', label: r.terrain, value: terrain });

  const useStatus = text(parcel.landUseStatus);
  if (useStatus) rows.push({ key: 'useStatus', label: r.useStatus, value: useStatus });

  const road = text(parcel.road.access);
  if (road) rows.push({ key: 'road', label: r.road, value: road });

  if (parcel.landPrice !== undefined && Number.isFinite(parcel.landPrice)) {
    rows.push({ key: 'landPrice', label: r.landPrice, value: fmt(t.landPriceValue, { price: formatInt(parcel.landPrice) }) });
  }

  // 출입구 계획: 건설 불가면 해당 없음, 배치안이 있으면 그 출입구, 아니면 '—'
  const gate = cause ? null : alternative ? gatePlanText(alternative) : null;
  rows.push({
    key: 'gate',
    label: r.gate,
    value: cause ? t.notApplicable : (gate ?? content.site.rules.none),
    muted: !gate,
  });

  return rows;
}
