import { isPnu, type AddressHit, type LatLng } from './types';
import type { RecentSearch } from './recent';

/**
 * Web Storage 에서 읽은 값의 모양 검사.
 * sessionStorage/localStorage 는 같은 origin 의 다른 스크립트·개발자 도구·예전 스키마가 바꿔 둘 수 있으므로
 * JSON.parse 결과를 그대로 믿지 않고, 통과한 항목만 화면과 지도에 넘깁니다.
 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isLatLng(value: unknown): value is LatLng {
  return isRecord(value) && Number.isFinite(value.lat) && Number.isFinite(value.lng);
}

export function isAddressHit(value: unknown): value is AddressHit {
  return (
    isRecord(value) &&
    isPnu(value.pnu) &&
    typeof value.roadAddress === 'string' &&
    typeof value.jibunAddress === 'string' &&
    (value.center === null || isLatLng(value.center)) &&
    typeof value.inSuseong === 'boolean'
  );
}

export function isRecentSearch(value: unknown): value is RecentSearch {
  return isRecord(value) && isPnu(value.pnu) && typeof value.label === 'string' && Number.isFinite(value.at);
}
