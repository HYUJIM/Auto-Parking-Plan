import type { BeAddressSearch } from './beTypes';
import { toAddressSearchResult } from './beAdapters';
import { content } from '@/lib/content';
import { getJson } from './base';
import { ApiError, type AddressSearchResult } from './types';

const EMPTY: AddressSearchResult = { hits: [], total: 0 };

/**
 * 주소 검색어에 쓸 수 있는 문자: 한글, 영문, 숫자, 공백, 그리고 - . , ( ) [ ] ·
 * BE 의 검색어 사전 검사(JusoKeyword)와 같은 기준입니다. 그 밖의 문자가 있으면 BE 가 422 로 거절하고
 * 도로명주소 API 로 보내지 않으므로, 여기서 먼저 걸러 요청 자체를 보내지 않습니다.
 * 기준은 BE 입니다. 이 검사가 BE 보다 엄격해지면 안 됩니다 (느슨한 쪽은 BE 가 422 로 알려 줍니다).
 */
const ALLOWED_QUERY = /^[가-힣ㄱ-ㆎA-Za-z0-9\s\-.,()[\]·]*$/;

export function hasInvalidQueryChars(query: string): boolean {
  return !ALLOWED_QUERY.test(query);
}

/** 422 INVALID_REQUEST 의 details 에서 검색어(q) 안내 문구를 꺼냅니다 */
function queryMessage(details: unknown): string | null {
  if (!Array.isArray(details)) return null;
  for (const d of details as { loc?: unknown; msg?: unknown }[]) {
    if (Array.isArray(d?.loc) && d.loc.includes('q') && typeof d.msg === 'string' && d.msg) return d.msg;
  }
  return null;
}

/**
 * 주소 검색 (도로명·지번 자동완성) — `GET /v1/address/search?q=`.
 * BE 가 행정안전부 도로명주소 API 를 대신 호출해 PNU·필지 중심 좌표·지원 관할 여부까지 붙여 줍니다 (최대 10건).
 * 결과 0건은 BE 가 404 ADDRESS_NOT_FOUND 로 답하므로 오류가 아니라 빈 결과(+안내)로 돌려줍니다.
 * 쓸 수 없는 문자가 든 검색어는 요청하지 않고, BE 가 검색어를 거절(422)하면 그 안내를 빈 결과와 함께 돌려줍니다.
 */
export async function searchAddress(query: string, signal?: AbortSignal): Promise<AddressSearchResult> {
  const q = query.trim();
  if (q.length < 2) return EMPTY;
  if (hasInvalidQueryChars(q)) return { ...EMPTY, notice: content.search.invalidChars };
  try {
    const body = await getJson<BeAddressSearch>('/v1/address/search', { q }, { signal });
    return toAddressSearchResult(body);
  } catch (err) {
    if (err instanceof ApiError && err.code === 'NOT_FOUND' && err.beCode === 'ADDRESS_NOT_FOUND') {
      const reason = (err.details as { reason?: unknown } | undefined)?.reason;
      return typeof reason === 'string' && reason ? { ...EMPTY, notice: reason } : EMPTY;
    }
    if (err instanceof ApiError && err.code === 'BAD_REQUEST') {
      const notice = queryMessage(err.details);
      if (notice) return { ...EMPTY, notice };
    }
    throw err;
  }
}
