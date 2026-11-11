import type { BeAddressSearch } from './beTypes';
import { toAddressSearchResult } from './beAdapters';
import { getJson } from './base';
import { ApiError, type AddressSearchResult } from './types';

const EMPTY: AddressSearchResult = { hits: [], total: 0 };

/**
 * 주소 검색 (도로명·지번 자동완성) — `GET /v1/address/search?q=`.
 * BE 가 행정안전부 도로명주소 API 를 대신 호출해 PNU·필지 중심 좌표·지원 관할 여부까지 붙여 줍니다 (최대 10건).
 * 결과 0건은 BE 가 404 ADDRESS_NOT_FOUND 로 답하므로 오류가 아니라 빈 결과(+안내)로 돌려줍니다.
 */
export async function searchAddress(query: string, signal?: AbortSignal): Promise<AddressSearchResult> {
  const q = query.trim();
  if (q.length < 2) return EMPTY;
  try {
    const body = await getJson<BeAddressSearch>('/v1/address/search', { q }, { signal });
    return toAddressSearchResult(body);
  } catch (err) {
    if (err instanceof ApiError && err.code === 'NOT_FOUND' && err.beCode === 'ADDRESS_NOT_FOUND') {
      const reason = (err.details as { reason?: unknown } | undefined)?.reason;
      return typeof reason === 'string' && reason ? { ...EMPTY, notice: reason } : EMPTY;
    }
    throw err;
  }
}
