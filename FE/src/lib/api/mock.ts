import parcelsJson from '@/mock/parcels.json';
import type { Parcel } from './types';
import { ApiError } from './types';

/**
 * 목업 필지 데이터 — 초기화면의 검증 필지 점, 예시 주소, 지도 클릭 근접 판정에 사용합니다.
 * 주소·필지 조회 자체는 server/ 가 담당하고(브라우저는 /api 만 호출), 법규 검토의 지연·실패 흉내도 여기 둡니다.
 */
export const parcels = parcelsJson as Parcel[];

/** 시범 지역(수성구) 검증 필지 10개 */
export const suseongParcels = parcels.filter((p) => p.inSuseong);

/** 네트워크 지연 흉내 */
export function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const failRate = Math.min(1, Math.max(0, Number(import.meta.env.VITE_MOCK_FAIL_RATE ?? 0) || 0));

/** VITE_MOCK_FAIL_RATE 비율로 일부러 실패 — 카드 단위 '다시 시도' UI 확인용 */
export function maybeFail(): void {
  if (failRate > 0 && Math.random() < failRate) {
    throw new ApiError('목업 API 실패 (VITE_MOCK_FAIL_RATE)', 'NETWORK');
  }
}
