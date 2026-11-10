import { SUSEONG_SGG_CODE, type AddressHit, type AddressSearchResult } from '../shared/types.ts';
import { utmkToWgs84 } from './proj.ts';

export { SUSEONG_SGG_CODE };

/**
 * 행정안전부 도로명주소 API (juso.go.kr) 호출 + 정규화.
 *
 * - 검색:   https://business.juso.go.kr/addrlink/addrLinkApi.do   (검색 API 승인키)
 * - 좌표:   https://business.juso.go.kr/addrlink/addrCoordApi.do  (좌표제공 API 승인키, 별도 신청)
 *
 * 응답의 법정동코드(admCd 10자리)·산 여부(mtYn)·지번 본번/부번(lnbrMnnm/lnbrSlno)으로 PNU 19자리를 만듭니다.
 *   PNU = 법정동코드(10) + 산구분(1: 대지=1, 산=2) + 본번(4) + 부번(4)
 */

const SEARCH_URL = 'https://business.juso.go.kr/addrlink/addrLinkApi.do';
const COORD_URL = 'https://business.juso.go.kr/addrlink/addrCoordApi.do';

/** 승인키 문제(미승인·기간 만료) — 검색어를 바꿔도 해결되지 않으므로 502 로 올립니다. E0013(SQL 예약어) 등 검색어 문제는 안내로 돌려줍니다. */
const CONFIG_ERROR_CODES = new Set(['E0001', 'E0014']);

/** 검색어 최대 길이 — 실제 주소는 60자 안팎 */
const MAX_KEYWORD_LENGTH = 100;
/** 좌표제공 API 는 결과마다 한 번씩 부르므로 상한을 둡니다 */
const MAX_COORD_LOOKUPS = 10;

export interface JusoKeys {
  searchKey: string;
  coordKey?: string;
}

export interface JusoRow {
  admCd: string;
  rnMgtSn: string;
  bdMgtSn: string;
  udrtYn: string;
  buldMnnm: string;
  buldSlno: string;
  lnbrMnnm: string;
  lnbrSlno: string;
  mtYn: string;
  roadAddr: string;
  roadAddrPart1: string;
  roadAddrPart2?: string;
  jibunAddr: string;
  bdNm?: string;
  siNm: string;
  sggNm: string;
  emdNm: string;
  zipNo: string;
}

interface JusoCommon {
  errorCode: string;
  errorMessage: string;
  totalCount: string;
  currentPage: string;
  countPerPage: string;
}

interface JusoResponse {
  results?: {
    common?: JusoCommon;
    juso?: JusoRow[] | null;
  };
}

export class JusoError extends Error {
  code: string;
  kind: 'config' | 'network';

  constructor(message: string, code: string, kind: 'config' | 'network') {
    super(message);
    this.name = 'JusoError';
    this.code = code;
    this.kind = kind;
  }
}

/** juso.go.kr 이 거부하는 특수문자 제거 */
export function sanitizeKeyword(raw: string): string {
  return raw
    .replace(/[%=<>[\]{}"'\\;|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function buildPnu(row: Pick<JusoRow, 'admCd' | 'mtYn' | 'lnbrMnnm' | 'lnbrSlno'>): string | null {
  if (!/^\d{10}$/.test(row.admCd ?? '')) return null;
  const mainText = (row.lnbrMnnm ?? '').trim();
  const subText = (row.lnbrSlno ?? '').trim();
  // 본번은 1~4자리 숫자(0 은 지번 없음), 부번은 0~4자리 숫자만 허용 — '1e3'·'12.5'·'' 같은 값은 거릅니다
  if (!/^\d{1,4}$/.test(mainText) || !/^\d{0,4}$/.test(subText)) return null;
  const main = Number(mainText);
  const sub = subText ? Number(subText) : 0;
  if (main === 0) return null;
  const mountain = row.mtYn === '1' ? '2' : '1';
  return `${row.admCd}${mountain}${String(main).padStart(4, '0')}${String(sub).padStart(4, '0')}`;
}

export function toAddressHit(row: JusoRow): AddressHit | null {
  const pnu = buildPnu(row);
  if (!pnu) return null;
  // 참고항목 없는 도로명주소(Part1)를 기본으로, 비어 있으면 전체 도로명주소로 대체
  const base = row.roadAddrPart1 || row.roadAddr || '';
  const road = row.bdNm && base ? `${base} (${row.bdNm})` : base;
  return {
    pnu,
    roadAddress: road,
    jibunAddress: row.jibunAddr,
    center: null,
    inSuseong: row.admCd.startsWith(SUSEONG_SGG_CODE),
  };
}

export interface SearchOptions {
  page?: number;
  count?: number;
  /** 요청 취소·시간 제한 */
  signal?: AbortSignal;
}

export async function searchJuso(
  keyword: string,
  keys: JusoKeys,
  options: SearchOptions = {},
  fetchImpl: typeof fetch = fetch,
): Promise<AddressSearchResult> {
  const kw = sanitizeKeyword(keyword).slice(0, MAX_KEYWORD_LENGTH);
  if (kw.length < 2) return { hits: [], total: 0, source: 'juso' };
  const count = Math.min(100, Math.max(1, options.count ?? 10));

  const url = new URL(SEARCH_URL);
  url.searchParams.set('confmKey', keys.searchKey);
  url.searchParams.set('currentPage', String(options.page ?? 1));
  url.searchParams.set('countPerPage', String(count));
  url.searchParams.set('keyword', kw);
  url.searchParams.set('resultType', 'json');
  url.searchParams.set('hstryYn', 'N');
  url.searchParams.set('firstSort', 'road');

  const res = await fetchImpl(url, { headers: { Accept: 'application/json' }, signal: options.signal });
  if (!res.ok) throw new JusoError(`도로명주소 API 응답 오류 (HTTP ${res.status})`, `HTTP_${res.status}`, 'network');

  let data: JusoResponse;
  try {
    data = (await res.json()) as JusoResponse;
  } catch {
    throw new JusoError('도로명주소 API 응답을 해석할 수 없습니다.', 'BAD_RESPONSE', 'network');
  }
  const common = data.results?.common;
  const code = common?.errorCode ?? 'UNKNOWN';

  if (code !== '0') {
    const message = common?.errorMessage || `도로명주소 API 오류 (${code})`;
    if (CONFIG_ERROR_CODES.has(code)) throw new JusoError(message, code, 'config');
    // 검색어 문제(너무 짧음·특수문자·결과 과다 등)는 안내 문구로 돌려줍니다.
    return { hits: [], total: 0, source: 'juso', notice: message };
  }

  const rows = data.results?.juso ?? [];
  const seen = new Set<string>();
  const hits: AddressHit[] = [];
  const hitRows: JusoRow[] = [];
  for (const row of rows) {
    const hit = toAddressHit(row);
    if (!hit || seen.has(hit.pnu)) continue; // 같은 필지의 여러 건물은 한 번만
    seen.add(hit.pnu);
    hits.push(hit);
    hitRows.push(row);
  }

  if (keys.coordKey && hits.length) {
    await attachCoordinates(hits, hitRows, keys.coordKey, fetchImpl, options.signal);
  }

  // 전체 건수: 같은 필지의 여러 건물을 합쳤으므로 원본 건수에서 합쳐진 만큼 뺍니다.
  // 마지막 페이지(받은 행 < 페이지 크기)면 앞 페이지들 + 지금 목록이 전부입니다.
  const page = Math.max(1, options.page ?? 1);
  const rawTotal = Number(common?.totalCount) || rows.length;
  const merged = rows.length - hits.length;
  const total = rows.length < count ? (page - 1) * count + hits.length : Math.max(hits.length, rawTotal - merged);

  return { hits, total, source: 'juso' };
}

/** 좌표제공 API 로 각 결과의 출입구 좌표(EPSG:5179)를 받아 위경도로 바꿔 붙입니다. 실패한 건은 center=null 로 둡니다. */
async function attachCoordinates(
  hits: AddressHit[],
  rows: JusoRow[],
  coordKey: string,
  fetchImpl: typeof fetch,
  signal?: AbortSignal,
): Promise<void> {
  await Promise.allSettled(
    rows.slice(0, MAX_COORD_LOOKUPS).map(async (row, i) => {
      const url = new URL(COORD_URL);
      url.searchParams.set('confmKey', coordKey);
      url.searchParams.set('admCd', row.admCd);
      url.searchParams.set('rnMgtSn', row.rnMgtSn);
      url.searchParams.set('udrtYn', row.udrtYn);
      url.searchParams.set('buldMnnm', row.buldMnnm);
      url.searchParams.set('buldSlno', row.buldSlno);
      url.searchParams.set('resultType', 'json');

      const res = await fetchImpl(url, { headers: { Accept: 'application/json' }, signal });
      if (!res.ok) return;
      const data = (await res.json()) as { results?: { common?: JusoCommon; juso?: Array<{ entX: string; entY: string }> | null } };
      if (data.results?.common?.errorCode !== '0') return;
      const first = data.results.juso?.[0];
      const x = Number(first?.entX);
      const y = Number(first?.entY);
      if (!Number.isFinite(x) || !Number.isFinite(y)) return;
      hits[i].center = utmkToWgs84(x, y);
    }),
  );
}
