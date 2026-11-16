/**
 * BE(Spring, ../BE) 공개 API 의 응답 모양 — 와이어 타입(snake_case).
 * 출처: BE/server/src/main/java/com/autoparkingplan/server/api/PublicController.java,
 *       BE/ai/src/main/resources/schemas/review_result.schema.json (review_result@1.0), BE/README.md 의 API 표.
 *
 * 이 타입은 어댑터(beAdapters.ts)와 API 함수만 봅니다. 화면은 src/lib/api/types.ts 의 FE 모델을 씁니다.
 * BE 응답은 신뢰하되 필드가 빠지거나 null 일 수 있다고 보고, 어댑터가 모두 방어적으로 읽습니다.
 */

export interface BeErrorBody {
  error?: { code?: string; message?: string; details?: unknown };
}

/** GET /v1/address/search?q= */
export interface BeAddressItem {
  pnu: string;
  road_address: string | null;
  jibun_address: string;
  sido?: string;
  sigungu?: string;
  region_code?: string;
  location: { lng: number; lat: number } | null;
  supported: boolean;
  building_name?: string | null;
}

export interface BeAddressSearch {
  query: string;
  count: number;
  items: BeAddressItem[];
}

export type BeDesignationKind = 'zoning' | 'district' | 'zone' | 'other';
export type BeConflict = 'included' | 'partially_included' | 'adjacent' | 'unknown';

export interface BeDesignation {
  kind: BeDesignationKind;
  name: string;
  conflict: BeConflict;
  code?: string | null;
}

export interface BeGeometry {
  type: string;
  coordinates: unknown;
}

export interface BeParcel {
  pnu: string;
  road_address: string | null;
  jibun_address: string;
  jurisdiction: { code?: string; sido: string; sigungu: string | null; supported: boolean };
  land_area_m2: number | null;
  land_area_pyeong?: number | null;
  land_category: string | null;
  official_land_price?: number | null;
  road_side?: string | null;
  designations: BeDesignation[] | null;
  location: { centroid: number[] | null; geometry: BeGeometry | null } | null;
  buildings?: { main_use?: string | null; gross_floor_area?: number | null; approval_date?: string | null }[] | null;
  sources?: { field: string; source: string; fetched_at: string }[] | null;
  data_notices?: { code: string; message: string }[] | null;
  is_example?: boolean;
  /** 토지특성의 토지형상 (정방형·가로장방·부정형 …). BE 가 아직 주지 않으면 없음 */
  land_shape?: string | null;
  /** 토지특성의 지형높이 (평지·완경사 …) */
  terrain_height?: string | null;
  /** 토지특성의 토지이용상황 (단독·주차장 …) */
  land_use_status?: string | null;
}

/** POST /v1/reviews → 202 */
export interface BeReviewCreated {
  review_id: string;
}

/** GET /v1/reviews/{id} — 진행 중 */
export interface BeReviewProgress {
  review_id: string;
  status: string;
  steps: { step: string; label: string; state: string }[];
  error: { code?: string; message?: string } | null;
}

export interface BeBasisNode {
  layer: string;
  document_title: string;
  article_label: string | null;
  effective_from: string | null;
  quote: string | null;
  url: string | null;
  rule_id?: string | null;
}

export interface BeReviewItem {
  category: string;
  title: string;
  status: string;
  reason_code: string | null;
  applied: {
    criteria_text?: string | null;
    value_type?: string | null;
    value?: number | null;
    national_range?: { min: number | null; max: number | null } | null;
    unit?: string | null;
    reference_value?: { label: string; value: number; unit: string; formula: string } | null;
    uses?: { use_name: string; use_group: string; status: string; condition_note: string | null }[] | null;
    table?: { label: string; value: number | string; unit: string | null }[] | null;
  } | null;
  basis: BeBasisNode[] | null;
  explanation: {
    display_source: 'ai_verified' | 'template_fallback' | 'basis_only' | string;
    content: {
      source?: string;
      audience?: string;
      sentences?: { sid?: string; role?: string; text: string; fact_ids?: string[] }[];
      glossary?: { term: string; plain: string }[] | null;
    } | null;
    verification?: {
      verdict: string;
      report_id?: string | null;
      checked_at?: string | null;
      must_mention_total?: number | null;
      must_mention_covered?: number | null;
    } | null;
  } | null;
}

export interface BeManualCheck {
  category: string;
  title: string;
  why: string;
  what_to_check: string[];
  related_basis?: BeBasisNode[] | null;
  triggered_by?: string | null;
}

/** GET /v1/reviews/{id} — 완료 (review_result@1.0) */
export interface BeReviewResult {
  schema_version: string;
  review_id: string;
  as_of_date: string;
  engine_version?: string;
  ruleset_hash?: string;
  /** 검토 당시의 필지 스냅숏 — 판정(건설 불가 등)의 근거로는 이 값을 씁니다 (필지 API 는 그 뒤 다시 조회될 수 있음) */
  parcel: {
    pnu: string;
    road_address?: string | null;
    jibun_address?: string | null;
    jurisdiction?: { sido?: string | null; sigungu?: string | null; supported?: boolean };
    land_area_m2?: number | null;
    land_category?: string | null;
    designations?: BeDesignation[] | null;
    location?: { centroid: number[] | null; geometry: BeGeometry | null } | null;
    sources?: { field: string; source: string; fetched_at: string }[] | null;
  };
  items: BeReviewItem[];
  manual_checks: BeManualCheck[];
  data_notices: { code: string; message: string }[];
  disclaimer: string;
}

export type BeReviewResponse = BeReviewResult | BeReviewProgress;

export function isBeReviewResult(body: BeReviewResponse): body is BeReviewResult {
  return Array.isArray((body as BeReviewResult).items);
}

export interface BeGeoFeature {
  type: 'Feature';
  geometry: BeGeometry | null;
  properties?: Record<string, unknown> | null;
}

export interface BeGeoFeatureCollection {
  type: 'FeatureCollection';
  features: BeGeoFeature[];
}

/**
 * 배치안 하나의 지도 레이어 — stalls·aisles 는 FeatureCollection, gate·gate_opening·usable_area 는 Feature 하나.
 * 건물로 대지가 나뉘면(SITE_SPLIT_BY_BUILDINGS) gate 는 MultiPoint, gate_opening 은 MultiLineString, usable_area 는 MultiPolygon (영역마다 하나씩)
 */
export interface BeLayoutLayers {
  stalls?: BeGeoFeatureCollection | null;
  aisles?: BeGeoFeatureCollection | null;
  gate?: BeGeoFeature | null;
  gate_opening?: BeGeoFeature | null;
  usable_area?: BeGeoFeature | null;
}

export interface BeAlternative {
  alt_code: string;
  parking_angle: string;
  stall_type?: string;
  orientation_deg?: number | null;
  stall_count: number;
  metrics?: Record<string, number | null> | null;
  constraint_reasons?: string[] | null;
  is_recommended?: boolean;
  layers?: BeLayoutLayers | null;
}

/** POST /v1/reviews/{id}/parking-simulations */
export interface BeParkingSimulation {
  review_id: string;
  pnu: string;
  as_of_date: string;
  ruleset_hash?: string;
  eligibility: { status: string; use_name: string | null; reason: string | null };
  legal_basis?: { category: string; status: string; rule_ids?: string[] | null; criteria_text?: string | null }[] | null;
  params?: Record<string, unknown>;
  disclaimer: string;
  simulated: boolean;
  reference_only?: boolean;
  result: {
    site_area_m2?: number | null;
    crs_internal?: string;
    recommended_alt_code?: string | null;
    alternatives?: BeAlternative[] | null;
  } | null;
  constraint_reasons?: string[] | null;
  message?: string | null;
  /** 기존 건물 반영 결과 — simulated=true 일 때만 (그 밖에는 없거나 null) */
  existing_buildings?: BeExistingBuildings | null;
}

/** 배치 계산이 본 기존 건물 (브이월드 '도로명주소 건물' 레이어) */
export interface BeExistingBuildings {
  /** 요청의 메아리: keep | ignore */
  mode: string;
  /** applied (keep · 1동 이상) | ignored (ignore · 1동 이상) | none (대지 위 건물 없음) | unavailable (조회 실패, count 0 · features null) */
  status: string;
  /** 대지와 겹치는 건물 수 */
  count: number;
  /** 대지 안 건물 면적 (㎡, 0.1 단위) */
  area_m2?: number | null;
  /** 건물에서 띄우는 거리 (m) */
  clearance_m?: number | null;
  source?: string | null;
  /** EPSG:4326 [경도, 위도] — Polygon | MultiPolygon, properties { id, name, floors } */
  features?: BeGeoFeatureCollection | null;
}

export interface BeParkingSimulationRequest {
  include_unverified?: boolean;
  angles?: string[];
  stall_type?: 'general' | 'extended';
  setback_m?: number;
  gate_point?: [number, number];
  gate_count?: 1 | 2;
  max_alternatives?: number;
  /** 기존 건물: keep(피해서 배치, 생략 시 기본) | ignore(철거 가정). 그 밖의 값은 422 */
  existing_buildings?: 'keep' | 'ignore';
}

/** GET /v1/rules/{ruleId}/basis?as_of_date= — 승인된 법규 규칙 하나의 원문 근거 */
export interface BeRuleBasis {
  rule_id: string;
  rule_key?: string;
  category: string;
  title: string;
  layer: string;
  review_status?: string;
  effective_from?: string | null;
  effective_to?: string | null;
  document?: {
    title?: string;
    short_title?: string | null;
    source_type?: string;
    promulgation_number?: string | null;
    promulgation_date?: string | null;
    effective_from?: string | null;
    source_url?: string | null;
    fetched_at?: string | null;
    verification?: { current_version?: string | null; current_checked_on?: string | null } | null;
    is_example?: boolean;
  } | null;
  conditions?: { field: string; operator: string; value: unknown; unit?: string | null }[] | null;
  values?:
    | {
        value_key: string;
        value_type: string;
        num_min?: number | null;
        num_max?: number | null;
        num_exact?: number | null;
        enum_value?: string | null;
        formula?: string | null;
        unit?: string | null;
        condition_note?: string | null;
      }[]
    | null;
  evidence?:
    | {
        role: string;
        article_label?: string | null;
        quoted_text?: string | null;
        char_start?: number | null;
        char_end?: number | null;
        provision_text?: string | null;
        article_full_text?: string | null;
        article_heading?: string | null;
      }[]
    | null;
  basis_tree?: BeBasisNode[] | null;
}
