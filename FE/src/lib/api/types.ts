import type { AddressHit, LandUse, Parcel } from '../../../shared/types';

/**
 * API 응답 모양(와이어 타입)은 shared/types.ts 에 있고 여기서 재export 합니다 — 서버(server/)와 한 정의를 씁니다.
 * 화면 전용 타입(SiteData · 법규 검토 결과 · ApiError)만 이 파일에 둡니다.
 */
export type { AddressHit, AddressSearchResult, LandUse, LatLng, Parcel, ParcelAtResult, ParcelRoad } from '../../../shared/types';
export { PNU_RE, SUSEONG_SGG_CODE, isPnu } from '../../../shared/types';

/**
 * 이용화면이 받는 데이터.
 *  - parcel: 경계·면적·용도지역까지 있는 필지
 *  - pending: 주소·PNU 는 확정됐지만 필지 데이터를 받지 못함 (브이월드 키 없음·미등록 필지) — 검색 결과(hit)로 위치만 표시
 */
export type SiteData = { kind: 'parcel'; parcel: Parcel } | { kind: 'pending'; pnu: string; hit: AddressHit | null };

export type RuleStatus = 'ok' | 'check';

export interface RuleEvidence {
  law: string;
  article: string;
  effective: string;
  url: string;
}

/** 법규 검토 항목 — 제목·기준·적용값은 엔진이 ko.json 템플릿을 채운 문장 */
export interface RuleItem {
  id: string;
  title: string;
  standard: string;
  applied: string;
  status: RuleStatus;
  evidence: RuleEvidence;
  /** 추정값에 기반했을 때의 짧은 단서 */
  note?: string;
}

export interface ManualCheck {
  id: string;
  title: string;
  desc: string;
  /** 토지이용계획 항목명에서 찾을 단어 */
  keywords?: string[];
}

/** 별도 확인 항목 + 토지이용계획에서 찾은 해당 항목 */
export interface ManualCheckResult extends ManualCheck {
  hits: LandUse[];
}

export interface RuleReview {
  /** 시범 지역 밖이면 false — 항목 없이 안내만 표시 */
  supported: boolean;
  items: RuleItem[];
  manual: ManualCheckResult[];
  source: {
    /** 법규·조례 출처 이름 (rules.json) — 화면 하단 출처 줄에 표시 */
    providers: string[];
    /** 조회 일자 (YYYY-MM-DD) */
    date: string;
  };
}

/**
 * API 오류 종류 — HTTP 상태를 base.ts 가 분류합니다.
 *  BAD_REQUEST 400/422 · NOT_FOUND 404 · NOT_READY 501 · RATE_LIMITED 429 · BUSY 503 · UPSTREAM 502/504 · SERVER 기타 5xx · NETWORK 연결 실패
 */
export type ApiErrorCode =
  'BAD_REQUEST' | 'NOT_FOUND' | 'NOT_READY' | 'RATE_LIMITED' | 'BUSY' | 'UPSTREAM' | 'SERVER' | 'NETWORK' | 'UNKNOWN';

export class ApiError extends Error {
  readonly code: ApiErrorCode;

  constructor(message: string, code: ApiErrorCode = 'UNKNOWN') {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}
