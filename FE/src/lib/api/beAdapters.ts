import type {
  BeAddressItem,
  BeAddressSearch,
  BeAlternative,
  BeBasisNode,
  BeDesignation,
  BeGeoFeature,
  BeGeometry,
  BeLayoutLayers,
  BeManualCheck,
  BeParcel,
  BeParkingSimulation,
  BeReviewItem,
  BeReviewProgress,
  BeReviewResult,
} from './beTypes';
import type {
  AddressHit,
  AddressSearchResult,
  BasisNode,
  GeoJsonGeometry,
  LandUse,
  LandUseKind,
  LatLng,
  LayoutAlternative,
  LayoutFeature,
  LayoutLayerKind,
  LayoutLayers,
  ManualCheck,
  Parcel,
  ParkingSimulation,
  ReviewProgress,
  RuleItem,
  RuleReview,
  RuleStatus,
} from './types';
import { SUSEONG_SGG_CODE, isRuleStatus } from './types';
import { outerRing, ringAreaM2, ringCentroid } from '@/lib/map/geo';

/**
 * BE 공개 API 응답(beTypes.ts) → FE 모델(types.ts) 변환. 전부 순수 함수이며 네트워크·DOM 을 모릅니다.
 *
 * 규칙 (docs/BE-API-MAPPING.md 의 대응표)
 *  - 좌표: BE 는 GeoJSON 관례 [lng, lat], 주소 검색은 { lng, lat } 객체 → 항상 { lat, lng } 로 명시 변환
 *  - 경계: MultiPolygon 중 가장 큰 폴리곤의 외곽 링 → 닫는 점을 뗀 열린 링
 *  - 용도지역: designations 중 kind=zoning 이고 '접함'이 아닌 것의 1·2번째 (BE 검토 엔진과 같은 규칙)
 *  - 날짜: ISO datetime 은 앞 10자리(YYYY-MM-DD)만
 *  - 빠지거나 null 인 필드는 화면이 '—' 를 그릴 수 있게 undefined/null 로 통일
 */

const CONFLICT_RELATION: Record<string, string> = {
  included: '포함',
  partially_included: '저촉',
  adjacent: '접함',
  unknown: '',
};

const LAND_USE_KINDS: readonly LandUseKind[] = ['zoning', 'district', 'zone', 'other'];

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function dateOnly(value: unknown): string | undefined {
  const s = str(value);
  return s ? s.slice(0, 10) : undefined;
}

function toStatus(value: unknown, fallback: RuleStatus = 'MANUAL_CHECK'): RuleStatus {
  return isRuleStatus(value) ? value : fallback;
}

// ── 주소 검색 ───────────────────────────────────────────────────────

export function toAddressHit(item: BeAddressItem): AddressHit {
  const loc = item.location;
  const center: LatLng | null =
    loc && Number.isFinite(loc.lat) && Number.isFinite(loc.lng) ? { lat: Number(loc.lat), lng: Number(loc.lng) } : null;
  const jibun = str(item.jibun_address) ?? '';
  const supported = typeof item.supported === 'boolean' ? item.supported : item.pnu.startsWith(SUSEONG_SGG_CODE);
  const hit: AddressHit = {
    pnu: item.pnu,
    roadAddress: str(item.road_address) ?? jibun,
    jibunAddress: jibun,
    center,
    inSuseong: supported,
  };
  const building = str(item.building_name);
  if (building) hit.buildingName = building;
  return hit;
}

export function toAddressSearchResult(body: BeAddressSearch): AddressSearchResult {
  const items = Array.isArray(body.items) ? body.items : [];
  const hits = items.filter((it) => typeof it?.pnu === 'string').map(toAddressHit);
  return { hits, total: num(body.count) ?? hits.length };
}

// ── 필지 ─────────────────────────────────────────────────────────────

/** '대구광역시 수성구 범어동 9001' → { gu: '수성구', dong: '범어동' } */
export function splitJibun(jibun: string): { gu?: string; dong?: string } {
  const parts = jibun.trim().split(/\s+/);
  const gu = parts.find((p) => /(구|군)$/.test(p) && !/(광역시|특별시)$/.test(p));
  const dong = parts.find((p) => /(동|가|읍|면|리)$/.test(p) && !/(광역시|특별시)$/.test(p));
  return { gu, dong };
}

export function toLandUse(d: BeDesignation): LandUse {
  const kind: LandUseKind = LAND_USE_KINDS.includes(d.kind) ? d.kind : 'other';
  const use: LandUse = { name: d.name, relation: CONFLICT_RELATION[d.conflict] ?? '', kind };
  const code = str(d.code);
  if (code) use.code = code;
  return use;
}

/** 용도지역 1·2 — kind=zoning 이고 접함이 아닌 것 (BE ReviewEngine 과 같은 규칙) */
export function pickZoning(designations: BeDesignation[] | null | undefined): [string | undefined, string | undefined] {
  const names: string[] = [];
  for (const d of designations ?? []) {
    if (d.kind === 'zoning' && d.conflict !== 'adjacent' && str(d.name) && !names.includes(d.name)) names.push(d.name);
  }
  return [names[0], names[1]];
}

/** 'vworld:LP_PA_CBND_BUBUN' → 'vworld', 'data.go.kr:BldRgstHubService/getBrTitleInfo' → 'data.go.kr' */
export function sourceProvider(source: string): string {
  const i = source.indexOf(':');
  return i > 0 ? source.slice(0, i) : source;
}

export function geometryToRing(geometry: BeGeometry | null | undefined): LatLng[] {
  if (!geometry) return [];
  return outerRing(geometry.type, geometry.coordinates);
}

export function toParcel(body: BeParcel): Parcel {
  const jibun = str(body.jibun_address) ?? '';
  const polygon = geometryToRing(body.location?.geometry);
  const c = body.location?.centroid;
  const centroid: LatLng | null =
    Array.isArray(c) && c.length >= 2 && Number.isFinite(c[0]) && Number.isFinite(c[1]) ? { lat: Number(c[1]), lng: Number(c[0]) } : null;
  const center = centroid ?? (polygon.length >= 3 ? ringCentroid(polygon) : null);

  const ledgerArea = num(body.land_area_m2);
  const geometryArea = ledgerArea === null && polygon.length >= 3 ? Math.round(ringAreaM2(polygon) * 10) / 10 : null;
  const area = ledgerArea ?? geometryArea;

  const [zoning, zoning2] = pickZoning(body.designations);
  const landUses = Array.isArray(body.designations) ? body.designations.filter((d) => str(d?.name)).map(toLandUse) : undefined;

  const providers: string[] = [];
  let queriedAt: string | undefined;
  for (const s of body.sources ?? []) {
    const p = sourceProvider(s.source);
    if (p && !providers.includes(p)) providers.push(p);
    const d = dateOnly(s.fetched_at);
    if (d && (!queriedAt || d > queriedAt)) queriedAt = d;
  }

  const parcel: Parcel = {
    pnu: body.pnu,
    address: { ...splitJibun(jibun), jibun },
    inSuseong: Boolean(body.jurisdiction?.supported),
    center,
    polygon,
    road: {},
    area,
    zoning: zoning ?? '',
    jimok: str(body.land_category) ?? '',
    buildings: (body.buildings ?? []).map((b) => ({
      mainUse: str(b.main_use),
      grossFloorArea: num(b.gross_floor_area),
      approvalDate: dateOnly(b.approval_date) ?? null,
    })),
    dataNotices: (body.data_notices ?? []).filter((n) => str(n?.message)).map((n) => ({ code: String(n.code ?? ''), message: n.message })),
    isExample: Boolean(body.is_example),
    source: { providers, queriedAt },
  };

  const road = str(body.road_address);
  if (road) parcel.address.road = road;
  const code = str(body.jurisdiction?.code);
  if (code) parcel.jurisdictionCode = code;
  if (body.jurisdiction?.sigungu && !parcel.address.gu) parcel.address.gu = body.jurisdiction.sigungu;
  if (ledgerArea !== null) parcel.areaSource = 'ledger';
  else if (geometryArea !== null) parcel.areaSource = 'geometry';
  const pyeong = num(body.land_area_pyeong);
  if (pyeong !== null) parcel.areaPyeong = pyeong;
  if (zoning2) parcel.zoning2 = zoning2;
  const price = num(body.official_land_price);
  if (price !== null) parcel.landPrice = price;
  const access = str(body.road_side);
  if (access) parcel.road.access = access;
  if (landUses) parcel.landUses = landUses;
  return parcel;
}

// ── 법규 검토 ───────────────────────────────────────────────────────

export function toBasisNode(b: BeBasisNode): BasisNode {
  return {
    layer: str(b.layer) ?? 'L1_NATIONAL',
    documentTitle: str(b.document_title) ?? '',
    articleLabel: str(b.article_label),
    effectiveFrom: dateOnly(b.effective_from) ?? null,
    quote: str(b.quote),
    // 원문 링크는 https 주소만 화면에 겁니다 (javascript: 같은 스킴과 평문 http 를 막습니다 — BE 도 걸러 주지만 여기서 한 번 더)
    url: str(b.url) && /^https:\/\/[^\s/?#]+/.test(b.url as string) ? (b.url as string) : null,
    ruleId: str(b.rule_id),
  };
}

export function toRuleItem(it: BeReviewItem): RuleItem {
  const applied = it.applied ?? {};
  const ref = applied.reference_value;
  const sentences = it.explanation && it.explanation.display_source !== 'basis_only' ? (it.explanation.content?.sentences ?? []) : [];
  return {
    id: it.category,
    title: str(it.title) ?? it.category,
    status: toStatus(it.status),
    reasonCode: str(it.reason_code),
    criteria: str(applied.criteria_text),
    referenceValue:
      ref && num(ref.value) !== null
        ? { label: str(ref.label) ?? '', value: ref.value, unit: str(ref.unit) ?? '', formula: str(ref.formula) ?? '' }
        : null,
    uses: Array.isArray(applied.uses)
      ? applied.uses.map((u) => ({
          name: str(u.use_name) ?? '',
          group: str(u.use_group) ?? '',
          status: toStatus(u.status),
          conditionNote: str(u.condition_note),
        }))
      : null,
    table: Array.isArray(applied.table)
      ? applied.table.map((row) => ({
          label: str(row.label) ?? '',
          value: typeof row.value === 'number' || typeof row.value === 'string' ? row.value : '',
          unit: str(row.unit),
        }))
      : null,
    explanation: sentences.map((s) => str(s?.text)).filter((t): t is string => t !== null),
    basis: (it.basis ?? []).map(toBasisNode),
  };
}

export function toManualCheck(m: BeManualCheck): ManualCheck {
  return {
    id: m.category,
    title: str(m.title) ?? m.category,
    why: str(m.why) ?? '',
    whatToCheck: Array.isArray(m.what_to_check) ? m.what_to_check.filter((s): s is string => typeof s === 'string') : [],
    relatedBasis: (m.related_basis ?? []).map(toBasisNode),
    triggeredBy: str(m.triggered_by),
  };
}

export function toRuleReview(body: BeReviewResult): RuleReview {
  const notices = (body.data_notices ?? []).filter((n) => str(n?.message)).map((n) => ({ code: String(n.code ?? ''), message: n.message }));
  const unsupported = body.parcel?.jurisdiction?.supported === false || notices.some((n) => n.code === 'REGION_NOT_SUPPORTED');
  const review: RuleReview = {
    reviewId: body.review_id,
    supported: !unsupported,
    asOfDate: dateOnly(body.as_of_date) ?? '',
    items: (body.items ?? []).map(toRuleItem),
    manual: (body.manual_checks ?? []).map(toManualCheck),
    dataNotices: notices,
    disclaimer: str(body.disclaimer) ?? '',
  };
  const engine = str(body.engine_version);
  if (engine) review.engineVersion = engine;
  const hash = str(body.ruleset_hash);
  if (hash) review.rulesetHash = hash;
  return review;
}

export function toReviewProgress(body: BeReviewProgress): ReviewProgress {
  return {
    status: str(body.status) ?? 'running',
    steps: Array.isArray(body.steps)
      ? body.steps.map((s) => ({
          step: String(s.step ?? ''),
          label: str(s.label) ?? String(s.step ?? ''),
          state: str(s.state) ?? 'pending',
        }))
      : [],
  };
}

// ── 주차장 배치 시뮬레이션 ──────────────────────────────────────────

const GEOMETRY_TYPES = new Set(['Point', 'LineString', 'Polygon', 'MultiPolygon']);

function isPosition(v: unknown): boolean {
  return Array.isArray(v) && v.length >= 2 && Number.isFinite(v[0]) && Number.isFinite(v[1]);
}

/** 좌표가 [경도, 위도] 를 depth 단계만큼 감싼 배열인지 (Point=0, LineString=1, Polygon=2, MultiPolygon=3) */
function isCoordinates(v: unknown, depth: number): boolean {
  if (depth === 0) return isPosition(v);
  return Array.isArray(v) && v.every((item) => isCoordinates(item, depth - 1));
}

const GEOMETRY_DEPTH: Record<string, number> = { Point: 0, LineString: 1, Polygon: 2, MultiPolygon: 3 };

/**
 * 종류와 좌표 모양이 맞는 도형만 받습니다. 모양이 틀린 좌표가 지도 그리기(매 프레임 도는 렌더)까지 가면
 * 거기서 예외가 나 지도 위 표시가 통째로 멈추므로, 여기서 걸러 그 도형만 빼고 그립니다.
 */
function toGeometry(g: BeGeometry | null | undefined): GeoJsonGeometry | null {
  if (!g || !GEOMETRY_TYPES.has(g.type) || !Array.isArray(g.coordinates)) return null;
  if (!isCoordinates(g.coordinates, GEOMETRY_DEPTH[g.type])) return null;
  return g as GeoJsonGeometry;
}

function toLayoutFeature(f: BeGeoFeature | null | undefined, layer: LayoutLayerKind): LayoutFeature | null {
  const geometry = toGeometry(f?.geometry);
  if (!geometry) return null;
  return { type: 'Feature', geometry, properties: { ...(f?.properties ?? {}), layer } };
}

/**
 * BE 의 layers 는 { stalls, aisles: FeatureCollection, gate, gate_opening, usable_area: Feature } 묶음입니다.
 * 지도 어댑터가 한 번에 그릴 수 있게 FeatureCollection 하나로 펴고, 종류는 properties.layer 에 적습니다.
 * 그리는 순서(아래 → 위): usable_area → aisle → stall → gate_opening → gate
 */
export function toLayoutLayers(layers: BeLayoutLayers | null | undefined): LayoutLayers {
  const features: LayoutFeature[] = [];
  const push = (f: LayoutFeature | null) => {
    if (f) features.push(f);
  };
  push(toLayoutFeature(layers?.usable_area, 'usable_area'));
  for (const f of layers?.aisles?.features ?? []) push(toLayoutFeature(f, 'aisle'));
  for (const f of layers?.stalls?.features ?? []) push(toLayoutFeature(f, 'stall'));
  push(toLayoutFeature(layers?.gate_opening, 'gate_opening'));
  push(toLayoutFeature(layers?.gate, 'gate'));
  return { type: 'FeatureCollection', features };
}

export function toLayoutAlternative(a: BeAlternative): LayoutAlternative {
  const metrics: Record<string, number> = {};
  for (const [k, v] of Object.entries(a.metrics ?? {})) {
    const n = num(v);
    if (n !== null) metrics[k] = n;
  }
  return {
    code: str(a.alt_code) ?? '',
    angle: str(a.parking_angle) ?? '',
    stallType: str(a.stall_type) ?? 'general',
    orientationDeg: num(a.orientation_deg),
    stallCount: num(a.stall_count) ?? 0,
    isRecommended: Boolean(a.is_recommended),
    metrics,
    constraintReasons: (a.constraint_reasons ?? []).filter((s): s is string => typeof s === 'string'),
    layers: toLayoutLayers(a.layers),
  };
}

export function toParkingSimulation(body: BeParkingSimulation): ParkingSimulation {
  const result = body.result;
  const alternatives = (result?.alternatives ?? []).map(toLayoutAlternative);
  return {
    reviewId: body.review_id,
    pnu: body.pnu,
    asOfDate: dateOnly(body.as_of_date) ?? '',
    eligibility: {
      status: str(body.eligibility?.status) ?? 'MANUAL_CHECK',
      useName: str(body.eligibility?.use_name),
      reason: str(body.eligibility?.reason),
    },
    legalBasis: (body.legal_basis ?? []).map((b) => ({ category: b.category, status: b.status, criteria: str(b.criteria_text) })),
    disclaimer: str(body.disclaimer) ?? '',
    simulated: Boolean(body.simulated),
    referenceOnly: Boolean(body.reference_only),
    constraintReasons: (body.constraint_reasons ?? []).filter((s): s is string => typeof s === 'string'),
    message: str(body.message),
    result: result
      ? {
          siteAreaM2: num(result.site_area_m2),
          recommendedCode:
            str(result.recommended_alt_code) ?? alternatives.find((a) => a.isRecommended)?.code ?? alternatives[0]?.code ?? null,
          alternatives,
        }
      : null,
  };
}
