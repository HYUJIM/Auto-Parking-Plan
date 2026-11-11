import type { BeParcel } from './beTypes';
import { toParcel } from './beAdapters';
import { getJson } from './base';
import { getCachedHit } from './hitCache';
import { ApiError, type LatLng, type Parcel, type SiteData } from './types';

/**
 * 이용화면 데이터 — `GET /v1/parcels/{pnu}`.
 *  - 200: 필지(브이월드 연속지적도·토지특성·토지이용계획 + 건축물대장). 도로명주소가 없으면 검색 결과(세션 캐시)에서 보충합니다.
 *  - 404 PARCEL_NOT_FOUND(연속지적도에 없음·신규 분할 등): 주소·좌표만으로 '대기' 화면을 구성합니다.
 *  - 503(외부 API 장애)·429 등은 그대로 던져 카드가 '다시 시도' 를 보여 줍니다.
 */
export async function resolveSite(pnu: string, signal?: AbortSignal): Promise<SiteData> {
  try {
    const body = await getJson<BeParcel>(`/v1/parcels/${encodeURIComponent(pnu)}`, undefined, { signal });
    const parcel = toParcel(body);
    if (!parcel.address.road) {
      const hit = getCachedHit(pnu);
      if (hit?.roadAddress && hit.roadAddress !== hit.jibunAddress) parcel.address.road = hit.roadAddress;
    }
    return { kind: 'parcel', parcel };
  } catch (err) {
    if (err instanceof ApiError && err.code === 'NOT_FOUND') return { kind: 'pending', pnu, hit: getCachedHit(pnu) };
    throw err;
  }
}

/**
 * 지도 클릭 지점의 필지 — `GET /v1/parcels/at?lng=&lat=`. 응답은 필지 전체이므로 바로 Parcel 로 돌려줍니다.
 * 그 지점에 필지가 없으면(404) null, 좌표 범위 밖(422)·외부 API 장애(503)는 ApiError.
 */
export async function getParcelAt(point: LatLng, signal?: AbortSignal): Promise<Parcel | null> {
  try {
    const body = await getJson<BeParcel>('/v1/parcels/at', { lng: point.lng.toFixed(7), lat: point.lat.toFixed(7) }, { signal });
    return toParcel(body);
  } catch (err) {
    if (err instanceof ApiError && err.code === 'NOT_FOUND') return null;
    throw err;
  }
}
