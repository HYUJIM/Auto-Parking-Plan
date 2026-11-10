import type { LatLng } from '@/lib/map/types';
import type { Parcel, ParcelAtResult, RuleReview, SiteData } from './types';
import { apiUrl, toApiError } from './base';
import { getCachedHit } from './hitCache';
import { delay, maybeFail } from './mock';
import { buildRuleReview } from '@/lib/rules/engine';

/**
 * 이용화면 데이터 해석 — `/api/parcel?pnu=` 호출.
 *  - 200: 필지(목업 또는 브이월드). 도로명주소가 없으면 검색 결과(세션 캐시)에서 보충합니다.
 *  - 501(키 없음)·404(연속지적도에 없음): 주소·좌표만으로 '대기' 화면을 구성합니다.
 */
export async function resolveSite(pnu: string): Promise<SiteData> {
  const res = await fetch(apiUrl('/api/parcel', { pnu }), { headers: { Accept: 'application/json' } });
  if (res.ok) {
    const parcel = (await res.json()) as Parcel;
    if (!parcel.address.road) {
      const hit = getCachedHit(pnu);
      if (hit?.roadAddress) parcel.address.road = hit.roadAddress;
    }
    return { kind: 'parcel', parcel };
  }

  const err = await toApiError(res);
  if (err.code === 'NOT_READY' || err.code === 'NOT_FOUND') {
    return { kind: 'pending', pnu, hit: getCachedHit(pnu) };
  }
  throw err;
}

/**
 * 지도 클릭 지점의 필지 (브이월드). 그 지점에 필지가 없으면 null,
 * 브이월드 키가 없으면 ApiError('NOT_READY') 를 던집니다(호출 쪽에서 안내).
 */
export async function getParcelAt(point: LatLng): Promise<ParcelAtResult | null> {
  const res = await fetch(apiUrl('/api/parcel/at', { lng: point.lng.toFixed(7), lat: point.lat.toFixed(7) }), {
    headers: { Accept: 'application/json' },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as ParcelAtResult;
}

/**
 * 법규 검토 (핵심 6항목 + 별도 확인 4항목).
 * 지금은 브라우저 안에서 목업 기준표로 만듭니다(src/lib/rules/engine.ts). 네트워크 지연·실패 흉내는 '다시 시도' UI 확인용.
 * BE 연동 시 이 함수 본문만 `POST /v1/reviews` 호출로 바꿉니다.
 */
export async function getRuleReview(parcel: Parcel): Promise<RuleReview> {
  await delay(320);
  maybeFail();
  return buildRuleReview(parcel);
}
