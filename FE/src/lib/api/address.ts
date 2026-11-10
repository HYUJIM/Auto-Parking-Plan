import type { AddressSearchResult, Parcel } from './types';
import { apiUrl, toApiError } from './base';
import { suseongParcels } from './mock';

/**
 * 주소 검색 (도로명·지번 자동완성).
 *
 * 브라우저는 `/api/address?q=` 만 호출합니다. 서버(server/api.ts)가 행정안전부 juso.go.kr 를 대신 호출해
 * 법정동코드·본번·부번으로 PNU 를 만들고, 좌표제공 API 가 있으면 위경도까지 붙여 줍니다.
 * 승인키가 없으면 서버가 목업 필지 목록으로 응답합니다(source: 'mock').
 */
export async function searchAddress(query: string, signal?: AbortSignal): Promise<AddressSearchResult> {
  const q = query.trim();
  if (q.length < 2) return { hits: [], total: 0, source: 'juso' };

  const res = await fetch(apiUrl('/api/address', { q, limit: 10 }), { signal, headers: { Accept: 'application/json' } });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as AddressSearchResult;
}

/** 예시 주소 = 수성구 검증 필지 중 1개 */
export function getExampleParcel(): Parcel {
  return suseongParcels[0];
}
