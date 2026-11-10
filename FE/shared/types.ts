/**
 * FE(브라우저 번들)와 개발용 API 서버(server/)가 함께 쓰는 '와이어 타입'.
 *
 * 규칙
 *  - 이 파일은 외부 import 가 없고, 지울 수 있는 TS 구문(타입·인터페이스·상수)만 씁니다.
 *    server/ 는 Node 의 타입 스트리핑으로 바로 실행되므로 `@/` 별칭·enum·매개변수 프로퍼티를 쓰면 안 됩니다.
 *  - server/ 는 src/ 를 import 하지 않고 이 파일만 import 합니다 (eslint no-restricted-imports 로 강제).
 *  - src/lib/api/types.ts · src/lib/map/types.ts 가 그대로 재export 하므로 화면 코드는 `@/lib/api/types` 를 계속 씁니다.
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

/** 토지이용계획 한 줄 — 용도지역·지구·구역 (브이월드 getLandUseAttr) */
export interface LandUse {
  /** 예: 제3종일반주거지역, 시가지경관지구(중심), 제1종지구단위계획구역 */
  name: string;
  /** 예: UQA230 */
  code: string;
  /** 포함 · 저촉 · 접함 */
  relation: string;
  registeredAt?: string;
}

/** 접도 정보. 목업은 폭·접도 길이·도로선까지, 브이월드는 토지특성의 '도로접면' 문구만 있습니다. */
export interface ParcelRoad {
  /** 접한 방향 (남측 등) — 목업 */
  side?: string;
  /** 도로 폭 (m) — 목업 */
  width?: number;
  /** 접도 길이 (m) — 목업 */
  frontage?: number;
  /** 지도에 강조할 도로 중심선 — 목업 */
  path?: LatLng[];
  /** 도로접면 (광대소각·중로한면·소로한면·세로(가)·맹지 …) — 토지특성 */
  access?: string;
}

/** 필지 한 건. 목업(mock/parcels.json) 또는 브이월드(연속지적도 + 토지특성 + 토지이용계획)에서 옵니다. */
export interface Parcel {
  /** 필지 고유번호 19자리 */
  pnu: string;
  address: {
    gu?: string;
    dong?: string;
    jibun: string;
    /** 도로명주소 — 브이월드에는 없어 검색 결과에서 보충 */
    road?: string;
  };
  /** 대구 수성구 시범 지역 여부 — false 면 법규 검토 미지원 */
  inSuseong: boolean;
  center: LatLng;
  polygon: LatLng[];
  road: ParcelRoad;
  /** 대지면적 (㎡) */
  area: number;
  /** 면적 출처: 토지대장(토지특성) / 경계로 계산 / 목업 */
  areaSource?: 'ledger' | 'geometry' | 'mock';
  /** 용도지역 */
  zoning: string;
  /** 용도지역 2 (있을 때) */
  zoning2?: string;
  /** 지목 */
  jimok: string;
  /** 공시지가 (원/㎡) — 선택 */
  landPrice?: number;
  /** 교차로·횡단보도 인접 여부 (진출입 제한 판정용) — 모르면 undefined */
  nearIntersection?: boolean;
  /** 토지이용계획: 용도지역·지구·구역 전체 목록 */
  landUses?: LandUse[];
  /** 토지특성 부가 정보 */
  land?: {
    shape?: string;
    terrain?: string;
    use?: string;
    /** 공시지가 기준연도 */
    baseYear?: string;
    updatedAt?: string;
  };
  source?: {
    provider: 'mock' | 'vworld';
    /** 조회 일자 (YYYY-MM-DD) */
    queriedAt: string;
    /** 연속지적도 속성의 공시지가 고시연도 */
    gosiYear?: string;
  };
}

/** 주소 검색 결과 한 건 (juso.go.kr 응답을 서버에서 이 모양으로 정규화) */
export interface AddressHit {
  pnu: string;
  roadAddress: string;
  jibunAddress: string;
  /** 좌표제공 API 키가 없거나 조회 실패면 null */
  center: LatLng | null;
  inSuseong: boolean;
}

/** GET /api/address 응답 */
export interface AddressSearchResult {
  hits: AddressHit[];
  /** 전체 건수 (hits 는 상위 일부일 수 있음) */
  total: number;
  /** 'juso' = 행정안전부 API, 'mock' = 승인키 미설정으로 목업 응답 */
  source: 'juso' | 'mock';
  /** 검색어 문제(결과 과다·특수문자 등)에 대한 API 안내 문구 */
  notice?: string;
}

/** GET /api/parcel/at 응답 — 지도 클릭 지점의 필지. 화면은 PNU 로 /site 에 이동하고 나머지는 /api/parcel 에서 받습니다. */
export interface ParcelAtResult {
  pnu: string;
}
