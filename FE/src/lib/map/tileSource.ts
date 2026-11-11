/**
 * 배경 지도 타일 소스 — 네이버 지도 Client ID 가 없을 때 미리보기 지도가 아래에 까는 실제 지도 타일입니다.
 * URL 템플릿의 {z} {x} {y} 를 바꿔 끼우는 웹 메르카토르 표준 타일(256px)만 다룹니다.
 *
 * 어느 소스를 쓸지는 tileConfig.ts 가 정합니다: .env 에 템플릿이 있으면 그것, 없으면 BE 가 알려 주는 브이월드 타일(/v1/map/tiles),
 * BE 에 브이월드 키가 없거나 BE 를 못 부르면 아래 기본값(OpenStreetMap · Esri).
 */

export interface TileSource {
  /** '일반' 레이어 템플릿 */
  normal: string;
  /** '위성' 레이어 템플릿 */
  satellite: string;
  /** 타일 서버가 주는 가장 작은 줌 — 그보다 멀리서 보면 타일을 요청하지 않습니다 (없으면 0) */
  minZoom?: number;
  /** 타일 서버가 주는 가장 큰 줌 — 그 이상은 이 줌의 타일을 키워서 씁니다 (없으면 TILE_MAX_ZOOM) */
  maxZoom?: number;
}

/**
 * 지금 깔린 배경 타일의 공급자 — 배지·출처 문구가 실제로 보이는 지도와 맞도록 화면이 이 값을 봅니다.
 *  - vworld: BE 가 대신 받아 주는 브이월드 타일 (필지 경계와 같은 기준이라 정확히 겹침)
 *  - default: OpenStreetMap(일반) · Esri(위성) — 국내 지적과 수 m 어긋날 수 있음
 *  - custom: .env 의 VITE_MAP_TILE_URL 로 지정한 타일
 *  - none: 타일 없음 (도식 격자)
 */
export type TileProvider = 'vworld' | 'default' | 'custom' | 'none';

export interface TileChoice {
  provider: TileProvider;
  /** provider 가 none 이면 null */
  source: TileSource | null;
}

export const TILE_SIZE = 256;
/** 표준 타일 서버가 주는 가장 큰 줌 — 그 이상은 이 줌의 타일을 키워서 씁니다 */
export const TILE_MAX_ZOOM = 19;

/** 기본값: OpenStreetMap 표준 타일(일반) · Esri World Imagery(위성). 출처 문구는 ko.json 의 map.sourceTiles */
export const DEFAULT_TILE_SOURCE: TileSource = {
  normal: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  satellite: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
};

export interface TileEnv {
  VITE_MAP_TILE_URL?: string;
  VITE_MAP_TILE_URL_SATELLITE?: string;
}

/** 비우면 기본값, `off` 면 null(= 사용 안 함) */
function fromEnv(value: string | undefined, fallback: string): string | null {
  const v = value?.trim() ?? '';
  if (v === '') return fallback;
  if (v.toLowerCase() === 'off') return null;
  return v;
}

/** .env 에서 읽은 타일 소스. 일반 타일이 `off` 면 null — 미리보기 지도가 타일 없이 도식 격자만 그립니다 */
export function tileSourceFromEnv(env: TileEnv): TileSource | null {
  const normal = fromEnv(env.VITE_MAP_TILE_URL, DEFAULT_TILE_SOURCE.normal);
  if (!normal) return null;
  const satellite = fromEnv(env.VITE_MAP_TILE_URL_SATELLITE, DEFAULT_TILE_SOURCE.satellite) ?? normal;
  return { normal, satellite };
}

/**
 * .env 에 타일 템플릿을 직접 적었을 때의 선택 (둘 중 하나라도 적혀 있으면). 아무것도 안 적었으면 null — BE 에 물어봅니다.
 * `off` 로 껐으면 provider 가 none 입니다.
 */
export function tileChoiceFromEnv(env: TileEnv): TileChoice | null {
  const explicit = (env.VITE_MAP_TILE_URL?.trim() ?? '') !== '' || (env.VITE_MAP_TILE_URL_SATELLITE?.trim() ?? '') !== '';
  if (!explicit) return null;
  const source = tileSourceFromEnv(env);
  return source ? { provider: 'custom', source } : { provider: 'none', source: null };
}

export function tileUrl(template: string, z: number, x: number, y: number): string {
  return template.replace('{z}', String(z)).replace('{x}', String(x)).replace('{y}', String(y));
}

export interface TileRef {
  z: number;
  x: number;
  y: number;
  /** 화면 좌표(px, 정수) */
  left: number;
  top: number;
  /** 화면에 그릴 크기(px) — 줌이 타일 서버의 최대 줌보다 크면 256 의 배수 */
  size: number;
  /** 같은 타일이 날짜변경선 양쪽에 두 번 보일 수 있어 감싸기 전 x 까지 넣은 키 */
  key: string;
}

export interface TileViewport {
  /** 화면 좌상단의 월드 픽셀 좌표 */
  left: number;
  top: number;
  width: number;
  height: number;
}

/** 타일 서버가 주는 줌 범위 (TileSource 의 minZoom·maxZoom) */
export interface TileZoomRange {
  minZoom?: number;
  maxZoom?: number;
}

/**
 * 뷰포트에 걸치는 타일 목록. x 는 날짜변경선에서 감싸고(wrap), y 는 세계 범위 밖이면 뺍니다.
 * 화면 좌표는 타일마다 따로 반올림하지 않고 뷰포트 오프셋 한 번만 반올림해 타일 사이 틈이 생기지 않게 합니다.
 * 줌이 range.maxZoom(기본 TILE_MAX_ZOOM)보다 크면 그 줌의 타일을 키워 쓰고, range.minZoom 보다 작으면 타일이 없습니다.
 */
export function visibleTiles(vp: TileViewport, zoom: number, range: TileZoomRange = {}): TileRef[] {
  if (vp.width <= 0 || vp.height <= 0) return [];
  if (zoom < (range.minZoom ?? 0)) return [];
  const z = Math.min(zoom, range.maxZoom ?? TILE_MAX_ZOOM);
  const size = TILE_SIZE * 2 ** (zoom - z);
  const n = 2 ** z;
  const x0 = Math.floor(vp.left / size);
  const x1 = Math.floor((vp.left + vp.width - 1) / size);
  const y0 = Math.max(0, Math.floor(vp.top / size));
  const y1 = Math.min(n - 1, Math.floor((vp.top + vp.height - 1) / size));
  const offX = Math.round(-vp.left);
  const offY = Math.round(-vp.top);
  const out: TileRef[] = [];
  for (let x = x0; x <= x1; x++) {
    const wrapped = ((x % n) + n) % n;
    for (let y = y0; y <= y1; y++) {
      out.push({ z, x: wrapped, y, left: x * size + offX, top: y * size + offY, size, key: `${z}/${wrapped}/${y}@${x}` });
    }
  }
  return out;
}
