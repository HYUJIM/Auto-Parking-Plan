/**
 * 화면이 쓰는 데이터 모양(FE 모델).
 *
 * BE 공개 API 의 응답(snake_case, src/lib/api/beTypes.ts)은 beAdapters.ts 가 이 모양으로 바꿉니다.
 * 화면 코드는 이 파일의 타입만 보고, BE 응답 구조를 직접 알지 않습니다.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

/** 필지 고유번호(PNU) 19자리: 법정동코드 10 + 산 구분 1 + 본번 4 + 부번 4 */
export const PNU_RE = /^\d{19}$/;

export function isPnu(value: unknown): value is string {
  return typeof value === 'string' && PNU_RE.test(value);
}

/** 대구광역시 수성구 시군구 코드 (법정동코드·PNU 앞 5자리) */
export const SUSEONG_SGG_CODE = '27260';

/** 토지이용계획 지정 종류 (BE designations[].kind) */
export type LandUseKind = 'zoning' | 'district' | 'zone' | 'other';

/** 토지이용계획 한 줄 — 용도지역·지구·구역 */
export interface LandUse {
  /** 예: 제3종일반주거지역, 시가지경관지구(중심), 제1종지구단위계획구역 */
  name: string;
  /** 예: UQA230 — BE 가 모르면 undefined */
  code?: string;
  /** 포함 · 저촉 · 접함 · ''(미확인) */
  relation: string;
  kind: LandUseKind;
}

/** 접도 정보 — BE 는 토지특성의 '도로접면' 문구만 줍니다 (광대소각·중로한면·세로한면(가)·맹지 …) */
export interface ParcelRoad {
  access?: string;
}

/** 건축물대장 표제부 요약 (BE buildings[]) */
export interface ParcelBuilding {
  mainUse: string | null;
  /** 연면적 (㎡) */
  grossFloorArea: number | null;
  /** 사용승인일 (YYYY-MM-DD) */
  approvalDate: string | null;
}

/** BE 가 덧붙이는 데이터 안내 (PARCEL_PARTIAL · DESIGNATION_UNAVAILABLE · REGION_NOT_SUPPORTED · PENDING_AMENDMENT · RULE_NOT_APPROVED) */
export interface DataNotice {
  code: string;
  message: string;
}

/** 필지 한 건 — GET /v1/parcels/{pnu} · GET /v1/parcels/at 의 응답을 변환한 것 */
export interface Parcel {
  /** 필지 고유번호 19자리 */
  pnu: string;
  address: {
    gu?: string;
    dong?: string;
    jibun: string;
    /** 도로명주소 — BE 필지 응답에 없으면 검색 결과(세션 캐시)에서 보충 */
    road?: string;
  };
  /** 법규 데이터 지원 관할(대구 수성구) 여부 — false 면 법규 검토·배치안 미지원 */
  inSuseong: boolean;
  /** 관할 시군구 코드 (예: 27260) */
  jurisdictionCode?: string;
  /** 필지 중심 — BE centroid, 없으면 경계로 계산, 경계도 없으면 null */
  center: LatLng | null;
  /** 경계 외곽 링 (열린 링, 가장 큰 폴리곤). 경계가 없으면 빈 배열 */
  polygon: LatLng[];
  road: ParcelRoad;
  /** 대지면적 (㎡) — 토지대장 값, 없으면 경계로 계산, 둘 다 없으면 null */
  area: number | null;
  /** 대지면적 (평) — BE 가 준 값만 */
  areaPyeong?: number;
  /** 면적 출처: 토지대장(토지특성) / 경계로 계산 */
  areaSource?: 'ledger' | 'geometry';
  /** 용도지역 (designations 중 kind=zoning·접함 제외 1번째) */
  zoning: string;
  /** 용도지역 2 (있을 때) */
  zoning2?: string;
  /** 지목 */
  jimok: string;
  /** 공시지가 (원/㎡) */
  landPrice?: number;
  /** 토지이용계획: 용도지역·지구·구역 전체 목록. BE 가 조회하지 못했으면 undefined (data_notices 에 안내) */
  landUses?: LandUse[];
  buildings: ParcelBuilding[];
  dataNotices: DataNotice[];
  /** BE 모의 데이터셋의 예시 필지(실재하지 않음) */
  isExample: boolean;
  source: {
    /** 출처 이름들 (BE sources[].source — 'vworld:…' 같은 식별자에서 공급자만) */
    providers: string[];
    /** 가장 최근 조회 일자 (YYYY-MM-DD) — 없으면 undefined */
    queriedAt?: string;
  };
}

/** 주소 검색 결과 한 건 (GET /v1/address/search items[]) */
export interface AddressHit {
  pnu: string;
  /** 도로명주소 — BE 에 없으면 지번주소로 채움 */
  roadAddress: string;
  jibunAddress: string;
  /** 필지 중심 (BE location). 없으면 null */
  center: LatLng | null;
  /** 법규 데이터 지원 관할 여부 */
  inSuseong: boolean;
  buildingName?: string;
}

export interface AddressSearchResult {
  hits: AddressHit[];
  /** 건수 (BE count — 최대 10건) */
  total: number;
  /** 결과 0건일 때의 안내 문구 (404 ADDRESS_NOT_FOUND 의 details.reason, 검색어 거절 422 의 안내, 화면의 문자 검사) */
  notice?: string;
}

/**
 * 이용화면이 받는 데이터.
 *  - parcel: 경계·면적·용도지역까지 있는 필지
 *  - pending: 주소·PNU 는 확정됐지만 BE 에 필지가 없음(404 PARCEL_NOT_FOUND) — 검색 결과(hit)로 위치만 표시
 */
export type SiteData = { kind: 'parcel'; parcel: Parcel } | { kind: 'pending'; pnu: string; hit: AddressHit | null };

// ── 법규 검토 (POST /v1/reviews → GET /v1/reviews/{id}, review_result@1.0) ──────────

/** 검토 상태 3값 — 검토 가능 / 조건부 검토 / 별도 확인 필요 */
export type RuleStatus = 'REVIEWABLE' | 'CONDITIONAL' | 'MANUAL_CHECK';

export const RULE_STATUSES: readonly RuleStatus[] = ['REVIEWABLE', 'CONDITIONAL', 'MANUAL_CHECK'];

export function isRuleStatus(value: unknown): value is RuleStatus {
  return typeof value === 'string' && (RULE_STATUSES as readonly string[]).includes(value);
}

/** 상태가 그렇게 된 이유 (BE reason_code) */
export type RuleReasonCode = 'NO_LOCAL_VALUE' | 'ZONE_OVERLAY' | 'OUT_OF_RANGE' | 'DATA_MISSING' | 'EXCEPTION_CLAUSE' | 'NEEDS_USE_INPUT';

export type BasisLayer = 'L1_NATIONAL' | 'L2_LAND_USE' | 'L3_LOCAL' | 'L4_SPECIAL_ZONE';

/** 근거 트리 한 단 (법률 → 시행령 → 조례 → 고시 순서로 옴) */
export interface BasisNode {
  layer: BasisLayer | string;
  documentTitle: string;
  articleLabel: string | null;
  /** 시행일 (YYYY-MM-DD) */
  effectiveFrom: string | null;
  quote: string | null;
  url: string | null;
  ruleId: string | null;
}

/** 용도지역에서 검토 가능한 용도 한 줄 (ZONING_USE 항목) */
export interface RuleUse {
  name: string;
  /** 화면 묶음: 주거 / 근린생활 / 업무 / 기타 */
  group: string;
  status: RuleStatus;
  conditionNote: string | null;
}

export interface RuleTableRow {
  label: string;
  value: number | string;
  unit: string | null;
}

export interface RuleReferenceValue {
  label: string;
  value: number;
  unit: string;
  formula: string;
}

/** 핵심 법규 한 항목 (화면 카드 1장) */
export interface RuleItem {
  /** BE category: ZONING_USE · BUILDING_COVERAGE · FLOOR_AREA_RATIO · PARKING_REQUIREMENT · PARKING_STALL_SIZE · DRIVE_AISLE_WIDTH */
  id: string;
  title: string;
  status: RuleStatus;
  reasonCode: RuleReasonCode | string | null;
  /** 기준값 문장 (applied.criteria_text) */
  criteria: string | null;
  /** 적용값 — 대지면적 × 비율 같은 참고값 */
  referenceValue: RuleReferenceValue | null;
  /** 적용값 — 검토 가능한 용도 목록 (ZONING_USE) */
  uses: RuleUse[] | null;
  /** 적용값 — 표 (주차구획 크기 · 차로 폭) */
  table: RuleTableRow[] | null;
  /** 쉬운 설명 — BE 가 원문과 대조해 검증한 문장만. 없으면 빈 배열 */
  explanation: string[];
  /** 적용 근거 트리 (상위 → 하위) */
  basis: BasisNode[];
}

/** 자동 판정하지 않는 별도 확인 항목 (ROAD_ACCESS · SETBACK · HEIGHT_SUNLIGHT · SPECIAL_ZONE) */
export interface ManualCheck {
  id: string;
  title: string;
  why: string;
  whatToCheck: string[];
  relatedBasis: BasisNode[];
  triggeredBy: string | null;
}

export interface RuleReview {
  reviewId: string;
  /** 시범 지역 밖이면 false — 항목 없이 안내만 표시 */
  supported: boolean;
  /** 기준일 (YYYY-MM-DD) */
  asOfDate: string;
  engineVersion?: string;
  rulesetHash?: string;
  items: RuleItem[];
  manual: ManualCheck[];
  dataNotices: DataNotice[];
  disclaimer: string;
}

/** 검토 진행 상태 (완료 전 GET /v1/reviews/{id}) */
export interface ReviewProgress {
  status: 'queued' | 'running' | 'failed' | string;
  steps: { step: string; label: string; state: 'pending' | 'running' | 'done' | 'failed' | string }[];
}

// ── 주차장 배치 시뮬레이션 (POST /v1/reviews/{id}/parking-simulations) ──────────

/** 지도에 그릴 배치 요소 종류 */
export type LayoutLayerKind = 'stall' | 'aisle' | 'gate' | 'gate_opening' | 'usable_area';

export type GeoJsonPosition = number[];

export type GeoJsonGeometry =
  | { type: 'Point'; coordinates: GeoJsonPosition }
  | { type: 'LineString'; coordinates: GeoJsonPosition[] }
  | { type: 'Polygon'; coordinates: GeoJsonPosition[][] }
  | { type: 'MultiPolygon'; coordinates: GeoJsonPosition[][][] };

export interface LayoutFeature {
  type: 'Feature';
  geometry: GeoJsonGeometry;
  properties: { layer: LayoutLayerKind; [key: string]: unknown };
}

/** 배치안 한 개의 지도 레이어 — GeoJSON FeatureCollection (각 feature 의 properties.layer 로 종류 구분) */
export interface LayoutLayers {
  type: 'FeatureCollection';
  features: LayoutFeature[];
}

export interface LayoutAlternative {
  /** ALT_A · ALT_B · ALT_C */
  code: string;
  /** PERPENDICULAR_90 · ANGLE_60 · ANGLE_45 · PARALLEL_0 */
  angle: string;
  stallType: string;
  orientationDeg: number | null;
  stallCount: number;
  isRecommended: boolean;
  /** site_area_m2 · usable_area_m2 · parking_area_m2 · efficiency_percent · area_per_stall_m2 · aisle_width_m … */
  metrics: Record<string, number>;
  constraintReasons: string[];
  layers: LayoutLayers;
}

export interface ParkingSimulation {
  reviewId: string;
  pnu: string;
  asOfDate: string;
  eligibility: { status: RuleStatus | string; useName: string | null; reason: string | null };
  legalBasis: { category: string; status: RuleStatus | string; criteria: string | null }[];
  disclaimer: string;
  /** false 면 계산하지 않음 — message · constraintReasons 에 이유 */
  simulated: boolean;
  /** include_unverified 로 받은 참고 배치 (주차장 설치 가능 여부 미확인) */
  referenceOnly: boolean;
  constraintReasons: string[];
  message: string | null;
  result: {
    siteAreaM2: number | null;
    recommendedCode: string | null;
    alternatives: LayoutAlternative[];
  } | null;
}

// ── 오류 ─────────────────────────────────────────────────────────────

/**
 * API 오류 종류 — HTTP 상태와 BE error.code 를 base.ts 가 분류합니다.
 *  BAD_REQUEST 400/422 · NOT_FOUND 404 · CONFLICT 409 · RATE_LIMITED 429 · BUSY 503 SERVICE_BUSY · UPSTREAM 503 UPSTREAM_UNAVAILABLE ·
 *  SERVER 기타 5xx · NETWORK 연결 실패 · UNKNOWN 그 밖
 */
export type ApiErrorCode =
  'BAD_REQUEST' | 'NOT_FOUND' | 'CONFLICT' | 'RATE_LIMITED' | 'BUSY' | 'UPSTREAM' | 'SERVER' | 'NETWORK' | 'UNKNOWN';

export interface ApiErrorInfo {
  /** BE error.code (ADDRESS_NOT_FOUND · PARCEL_NOT_FOUND · REVIEW_NOT_FOUND · INVALID_REQUEST · SERVICE_BUSY · UPSTREAM_UNAVAILABLE …) */
  beCode?: string;
  details?: unknown;
  status?: number;
  /** Retry-After (초) */
  retryAfter?: number;
}

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly beCode?: string;
  readonly details?: unknown;
  readonly status?: number;
  readonly retryAfter?: number;

  constructor(message: string, code: ApiErrorCode = 'UNKNOWN', info: ApiErrorInfo = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.beCode = info.beCode;
    this.details = info.details;
    this.status = info.status;
    this.retryAfter = info.retryAfter;
  }
}
