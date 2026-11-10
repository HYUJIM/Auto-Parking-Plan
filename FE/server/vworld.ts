import { PNU_RE, SUSEONG_SGG_CODE, type LandUse, type LatLng, type Parcel } from '../shared/types.ts';
import { utmkToWgs84, wgs84ToUtmk } from './proj.ts';

/**
 * 브이월드(VWorld) 오픈API 호출 + 정규화 — 4단계(필지 경계·면적·용도지역).
 *
 *  - 연속지적도:   https://api.vworld.kr/req/data?service=data&request=GetFeature&data=LP_PA_CBND_BUBUN
 *                  attrFilter=pnu:=:<PNU>  또는  geomFilter=POINT(lng lat)   → 필지 경계(MultiPolygon, EPSG:4326)
 *  - 토지특성:     https://api.vworld.kr/ned/data/getLandCharacteristics      → 면적·지목·용도지역·도로접면·공시지가·형상
 *  - 토지이용계획: https://api.vworld.kr/ned/data/getLandUseAttr              → 용도지역·지구·구역 목록(포함/저촉/접함)
 *
 * 세 호출 모두 같은 인증키를 쓰며, 키 발급 때 등록한 서비스 도메인을 `domain` 으로 함께 보냅니다.
 * 실제 응답 모양(2026-11-10 확인)에 맞춰 파싱하고, 모르는 필드는 무시합니다.
 */

const DATA_URL = 'https://api.vworld.kr/req/data';
const NED_URL = 'https://api.vworld.kr/ned/data';
const CADASTRAL_LAYER = 'LP_PA_CBND_BUBUN';

export interface VworldKeys {
  key: string;
  /** 키 발급 때 등록한 서비스 도메인 (예: localhost:5173) */
  domain: string;
}

export class VworldError extends Error {
  code: string;
  kind: 'config' | 'network';

  constructor(message: string, code: string, kind: 'config' | 'network') {
    super(message);
    this.name = 'VworldError';
    this.code = code;
    this.kind = kind;
  }
}

/** 연속지적도 한 필지 */
export interface ParcelFeature {
  pnu: string;
  /** "177-3대" 처럼 지번 + 지목 약어 */
  jibun: string;
  /** "대구광역시 수성구 범어동 177-3" */
  addr: string;
  /** 바깥 경계(가장 큰 폴리곤의 외곽 링), 위경도 */
  polygon: LatLng[];
  /** 공시지가(원/㎡) — 연속지적도 속성, 없을 수 있음 */
  jiga?: number;
  gosiYear?: string;
}

export interface LandCharacteristics {
  jimok?: string;
  /** 면적 ㎡ */
  area?: number;
  zoning1?: string;
  zoning2?: string;
  /** 도로접면 (광대소각·소로한면·맹지 …) */
  roadSide?: string;
  landPrice?: number;
  shape?: string;
  terrain?: string;
  landUseStatus?: string;
  /** 법정동 이름 "대구광역시 수성구 범어동" */
  ldName?: string;
  /** 지번 "177-3" */
  jibunNo?: string;
  baseYear?: string;
  updatedAt?: string;
}

// ── Data API (연속지적도) ───────────────────────────────────────────────

interface DataApiResponse {
  response?: {
    status?: 'OK' | 'NOT_FOUND' | 'ERROR' | string;
    error?: { level?: string; code?: string; text?: string };
    result?: {
      featureCollection?: {
        features?: Array<{
          properties?: Record<string, string | undefined>;
          geometry?: { type?: string; coordinates?: unknown };
        }>;
      };
    };
  };
}

async function getFeatures(
  filter: { attrFilter?: string; geomFilter?: string },
  keys: VworldKeys,
  withGeometry: boolean,
  fetchImpl: typeof fetch,
  signal?: AbortSignal,
): Promise<ParcelFeature[]> {
  const url = new URL(DATA_URL);
  url.searchParams.set('service', 'data');
  url.searchParams.set('request', 'GetFeature');
  url.searchParams.set('data', CADASTRAL_LAYER);
  url.searchParams.set('key', keys.key);
  url.searchParams.set('domain', keys.domain);
  url.searchParams.set('format', 'json');
  url.searchParams.set('errorformat', 'json');
  url.searchParams.set('crs', 'EPSG:4326');
  url.searchParams.set('geometry', withGeometry ? 'true' : 'false');
  url.searchParams.set('size', '5');
  if (filter.attrFilter) url.searchParams.set('attrFilter', filter.attrFilter);
  if (filter.geomFilter) url.searchParams.set('geomFilter', filter.geomFilter);

  const res = await fetchImpl(url, { headers: { Accept: 'application/json' }, signal });
  if (!res.ok) throw new VworldError(`브이월드 응답 오류 (HTTP ${res.status})`, `HTTP_${res.status}`, 'network');
  let data: DataApiResponse;
  try {
    data = (await res.json()) as DataApiResponse;
  } catch {
    throw new VworldError('브이월드 응답을 해석할 수 없습니다.', 'BAD_RESPONSE', 'network');
  }
  const status = data.response?.status;

  if (status === 'NOT_FOUND') return [];
  if (status !== 'OK') {
    const err = data.response?.error;
    const code = err?.code ?? status ?? 'UNKNOWN';
    const text = err?.text ?? '브이월드 응답 오류';
    const configLike = /key|auth|domain|인증|도메인|권한/i.test(`${code} ${text}`);
    throw new VworldError(text, code, configLike ? 'config' : 'network');
  }

  const features = data.response?.result?.featureCollection?.features ?? [];
  const out: ParcelFeature[] = [];
  for (const f of features) {
    const p = f.properties ?? {};
    const pnu = p.pnu ?? '';
    if (!PNU_RE.test(pnu)) continue;
    const jiga = Number(p.jiga);
    const feature: ParcelFeature = {
      pnu,
      jibun: p.jibun ?? '',
      addr: p.addr ?? '',
      polygon: withGeometry ? outerRing(f.geometry?.type, f.geometry?.coordinates) : [],
    };
    if (Number.isFinite(jiga) && jiga > 0) feature.jiga = jiga;
    if (p.gosi_year) feature.gosiYear = p.gosi_year;
    out.push(feature);
  }
  return out;
}

/** PNU 로 필지 경계 */
export async function getParcelFeature(
  pnu: string,
  keys: VworldKeys,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<ParcelFeature | null> {
  if (!PNU_RE.test(pnu)) return null;
  const list = await getFeatures({ attrFilter: `pnu:=:${pnu}` }, keys, true, fetchImpl, signal);
  // 요청한 PNU 와 다른 필지가 오면(필터 무시 등) 없는 것으로 봅니다 — 다른 필지를 그려 주면 더 위험
  return list.find((f) => f.pnu === pnu) ?? null;
}

/** 지도 클릭 지점의 필지 (경계 포함) */
export async function getParcelAtPoint(
  point: LatLng,
  keys: VworldKeys,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<ParcelFeature | null> {
  const lng = point.lng.toFixed(7);
  const lat = point.lat.toFixed(7);
  const list = await getFeatures({ geomFilter: `POINT(${lng} ${lat})` }, keys, true, fetchImpl, signal);
  return list[0] ?? null;
}

// ── NED (토지특성·토지이용계획) ───────────────────────────────────────

function nedUrl(path: string, keys: VworldKeys, params: Record<string, string>): URL {
  const url = new URL(`${NED_URL}/${path}`);
  url.searchParams.set('key', keys.key);
  url.searchParams.set('domain', keys.domain);
  url.searchParams.set('format', 'json');
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  return url;
}

async function nedJson(url: URL, fetchImpl: typeof fetch, signal?: AbortSignal): Promise<Record<string, unknown>> {
  const res = await fetchImpl(url, { headers: { Accept: 'application/json' }, signal });
  if (!res.ok) throw new VworldError(`브이월드 NED 응답 오류 (HTTP ${res.status})`, `HTTP_${res.status}`, 'network');
  const text = await res.text();
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    // 인증 실패 등은 JSON 이 아닌 본문으로 올 수 있음 — 본문은 서버 로그에만 남기고 사용자에게는 고정 문구
    console.warn('[vworld] NED 응답이 JSON 이 아님:', text.slice(0, 200));
    throw new VworldError(
      '브이월드 토지 정보 응답을 해석할 수 없습니다.',
      'BAD_RESPONSE',
      /key|auth|인증/i.test(text) ? 'config' : 'network',
    );
  }
}

/** 응답 루트의 어떤 키 아래든 { field: [...] } 를 찾습니다 (landCharacteristicss / landUses …) */
function nedFields(data: Record<string, unknown>): Record<string, string>[] {
  for (const value of Object.values(data)) {
    if (value && typeof value === 'object' && Array.isArray((value as { field?: unknown }).field)) {
      return (value as { field: Record<string, string>[] }).field;
    }
  }
  return [];
}

/** 토지특성 — 기준연도 데이터가 아직 없으면 전년도로 한 번 더 */
export async function getLandCharacteristics(
  pnu: string,
  keys: VworldKeys,
  year: number = new Date().getFullYear(),
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<LandCharacteristics | null> {
  for (const y of [year, year - 1]) {
    const url = nedUrl('getLandCharacteristics', keys, { pnu, stdrYear: String(y), numOfRows: '5', pageNo: '1' });
    const data = await nedJson(url, fetchImpl, signal);
    const row = nedFields(data).find((r) => r.pnu === pnu) ?? nedFields(data)[0];
    if (!row) continue;
    return {
      jimok: row.lndcgrCodeNm || undefined,
      area: toNumber(row.lndpclAr),
      zoning1: cleanZoning(row.prposArea1Nm),
      zoning2: cleanZoning(row.prposArea2Nm),
      roadSide: row.roadSideCodeNm || undefined,
      landPrice: toNumber(row.pblntfPclnd),
      shape: row.tpgrphFrmCodeNm || undefined,
      terrain: row.tpgrphHgCodeNm || undefined,
      landUseStatus: row.ladUseSittnNm || undefined,
      ldName: row.ldCodeNm || undefined,
      jibunNo: row.mnnmSlno || undefined,
      baseYear: row.stdrYear || undefined,
      updatedAt: row.lastUpdtDt || undefined,
    };
  }
  return null;
}

/** 토지이용계획 — 용도지역·지구·구역 목록 */
export async function getLandUses(
  pnu: string,
  keys: VworldKeys,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<LandUse[]> {
  const url = nedUrl('getLandUseAttr', keys, { pnu, numOfRows: '100', pageNo: '1' });
  const data = await nedJson(url, fetchImpl, signal);
  const seen = new Set<string>();
  return nedFields(data)
    .filter((r) => r.pnu === pnu || !r.pnu)
    .map((r) => ({
      name: (r.prposAreaDstrcCodeNm ?? '').trim(),
      code: (r.prposAreaDstrcCode ?? '').trim(),
      relation: (r.cnflcAtNm ?? '').trim(),
      registeredAt: r.registDt || undefined,
    }))
    .filter((u) => u.name && !seen.has(`${u.code}:${u.name}`) && seen.add(`${u.code}:${u.name}`));
}

// ── 조합: Parcel 한 건 ────────────────────────────────────────────────

/**
 * PNU 하나를 FE 의 Parcel 로 만듭니다. 경계(연속지적도)가 없으면 null.
 * 토지특성·토지이용계획은 실패해도 경계만으로 돌려줍니다(해당 값은 비움).
 */
export async function fetchVworldParcel(
  pnu: string,
  keys: VworldKeys,
  fetchImpl: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<Parcel | null> {
  // 토지특성·토지이용계획은 '그 데이터만' 없거나 일시 장애일 때 비워서 돌려줍니다.
  // 인증 오류·취소·시간 초과·호출 예산 초과는 경계 조회에도 같이 걸리는 문제이므로 그대로 올립니다.
  const degrade =
    <T>(fallback: T) =>
    (err: unknown): T => {
      if (err instanceof VworldError && err.kind === 'config') throw err;
      if (signal?.aborted) throw err;
      if (err instanceof Error && (err.name === 'BusyError' || err.name === 'TimeoutError' || err.name === 'AbortError')) throw err;
      return fallback;
    };
  const [feature, land, uses] = await Promise.all([
    getParcelFeature(pnu, keys, fetchImpl, signal),
    getLandCharacteristics(pnu, keys, undefined, fetchImpl, signal).catch(degrade<LandCharacteristics | null>(null)),
    getLandUses(pnu, keys, fetchImpl, signal).catch(degrade<LandUse[]>([])),
  ]);
  if (!feature || feature.polygon.length < 3) return null;
  return assembleParcel(feature, land, uses);
}

export function assembleParcel(feature: ParcelFeature, land: LandCharacteristics | null, uses: LandUse[]): Parcel {
  const { pnu, polygon } = feature;
  const { gu, dong } = splitLdName(land?.ldName ?? feature.addr);
  const jibunAddress = feature.addr || [land?.ldName, land?.jibunNo].filter(Boolean).join(' ');
  const zoningFromUses = pickZoning(uses);
  // 토지대장 면적은 0 보다 클 때만 믿고, 아니면 경계로 계산
  const ledgerArea = land?.area !== undefined && land.area > 0 ? land.area : undefined;

  return {
    pnu,
    address: { gu, dong, jibun: jibunAddress },
    inSuseong: pnu.startsWith(SUSEONG_SGG_CODE),
    center: centroid(polygon),
    polygon,
    road: { access: land?.roadSide },
    area: ledgerArea ?? round1(polygonAreaM2(polygon)),
    areaSource: ledgerArea !== undefined ? 'ledger' : 'geometry',
    zoning: land?.zoning1 ?? zoningFromUses ?? '',
    zoning2: land?.zoning2,
    jimok: land?.jimok ?? jimokFromJibun(feature.jibun) ?? '',
    landPrice: land?.landPrice ?? feature.jiga,
    landUses: uses,
    land: land
      ? { shape: land.shape, terrain: land.terrain, use: land.landUseStatus, baseYear: land.baseYear, updatedAt: land.updatedAt }
      : undefined,
    source: { provider: 'vworld', queriedAt: new Date().toISOString().slice(0, 10), gosiYear: feature.gosiYear },
  };
}

/** 토지이용계획에서 구체적인 용도지역 한 줄 — 상위 분류(도시지역·주거지역 …)는 건너뜁니다 */
const ZONING_CATEGORY_NAMES = new Set([
  '도시지역',
  '주거지역',
  '상업지역',
  '공업지역',
  '녹지지역',
  '관리지역',
  '농림지역',
  '자연환경보전지역',
]);

export function pickZoning(uses: LandUse[]): string | undefined {
  const candidates = uses.filter(
    (u) => u.code.startsWith('UQA') && u.relation === '포함' && u.name && !ZONING_CATEGORY_NAMES.has(u.name) && !/00$/.test(u.code),
  );
  return (candidates[0] ?? uses.find((u) => u.code.startsWith('UQA') && u.relation === '포함' && !ZONING_CATEGORY_NAMES.has(u.name)))?.name;
}

// ── 기하 도우미 ───────────────────────────────────────────────────────

type Ring = LatLng[];

/** MultiPolygon / Polygon 좌표에서 가장 큰 폴리곤의 외곽 링 (닫는 점 제거) */
function outerRing(type: string | undefined, coordinates: unknown): Ring {
  const polygons: unknown[] = type === 'MultiPolygon' ? (coordinates as unknown[]) : type === 'Polygon' ? [coordinates] : [];
  let best: Ring = [];
  let bestArea = -1;
  for (const poly of polygons) {
    const ring = toRing((poly as unknown[])?.[0]);
    if (ring.length < 3) continue;
    const area = polygonAreaM2(ring);
    if (area > bestArea) {
      bestArea = area;
      best = ring;
    }
  }
  return best;
}

function toRing(raw: unknown): Ring {
  if (!Array.isArray(raw)) return [];
  const ring: Ring = [];
  for (const pt of raw) {
    if (!Array.isArray(pt) || pt.length < 2) continue;
    const lng = Number(pt[0]);
    const lat = Number(pt[1]);
    if (Number.isFinite(lng) && Number.isFinite(lat)) ring.push({ lat, lng });
  }
  if (ring.length > 1) {
    const a = ring[0];
    const z = ring[ring.length - 1];
    if (a.lat === z.lat && a.lng === z.lng) ring.pop();
  }
  return ring;
}

/** 평면(EPSG:5179) 투영 후 신발끈 공식 — 필지 크기에서는 토지대장 면적과 거의 같음 */
export function polygonAreaM2(ring: Ring): number {
  if (ring.length < 3) return 0;
  const pts = ring.map((p) => wgs84ToUtmk(p));
  let sum = 0;
  for (let i = 0; i < pts.length; i++) {
    const a = pts[i];
    const b = pts[(i + 1) % pts.length];
    sum += a.x * b.y - b.x * a.y;
  }
  return Math.abs(sum) / 2;
}

/**
 * 면적 가중 중심 (라벨·핀 위치).
 * 절대 위경도(128.6, 35.8)로 계산하면 큰 수끼리의 뺄셈에서 수십 m 오차가 나므로,
 * 첫 꼭짓점을 원점으로 한 평면(m) 좌표에서 계산한 뒤 되돌립니다.
 */
export function centroid(ring: Ring): LatLng {
  const mean = (): LatLng => {
    const n = ring.length || 1;
    return { lat: ring.reduce((s, p) => s + p.lat, 0) / n, lng: ring.reduce((s, p) => s + p.lng, 0) / n };
  };
  if (ring.length < 3) return mean();

  const pts = ring.map((p) => wgs84ToUtmk(p));
  const ox = pts[0].x;
  const oy = pts[0].y;
  let a = 0;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const q = pts[(i + 1) % pts.length];
    const px = p.x - ox;
    const py = p.y - oy;
    const qx = q.x - ox;
    const qy = q.y - oy;
    const cross = px * qy - qx * py;
    a += cross;
    cx += (px + qx) * cross;
    cy += (py + qy) * cross;
  }
  a *= 0.5;
  if (Math.abs(a) < 0.01) return mean(); // 0.01㎡ 미만의 퇴화한 다각형
  return utmkToWgs84(ox + cx / (6 * a), oy + cy / (6 * a));
}

// ── 문자열 도우미 ─────────────────────────────────────────────────────

function toNumber(s: string | undefined): number | undefined {
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : undefined;
}

function round1(n: number): number {
  return Math.round(n * 10) / 10;
}

function cleanZoning(s: string | undefined): string | undefined {
  const v = (s ?? '').trim();
  if (!v || v === '지정되지않음' || v === '지정되지 않음') return undefined;
  return v;
}

/** "177-3대" → "대" */
function jimokFromJibun(jibun: string): string | undefined {
  const m = /[\d-]+\s*([가-힣]+)$/.exec(jibun.trim());
  return m?.[1];
}

/** "대구광역시 수성구 범어동 177-3" → gu/dong */
function splitLdName(name: string | undefined): { gu?: string; dong?: string } {
  const parts = (name ?? '').trim().split(/\s+/);
  const gu = parts.find((p) => /(구|군)$/.test(p));
  const dong = parts.find((p) => /(동|가|읍|면|리)$/.test(p) && !/(광역시|특별시)$/.test(p));
  return { gu, dong };
}
