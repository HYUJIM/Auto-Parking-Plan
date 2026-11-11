/**
 * 배경 지도 타일 소스 — 네이버 지도 Client ID 가 없을 때 미리보기 지도가 아래에 까는 실제 지도 타일입니다.
 * URL 템플릿의 {z} {x} {y} 를 바꿔 끼우는 웹 메르카토르 표준 타일(256px)만 다룹니다.
 */

export interface TileSource {
  /** '일반' 레이어 템플릿 */
  normal: string;
  /** '위성' 레이어 템플릿 */
  satellite: string;
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

/** 빌드 시점의 설정 — 화면(배지·출처 문구)이 타일 사용 여부를 알 때 씁니다 */
export const MAP_TILES_ENABLED = tileSourceFromEnv(import.meta.env) !== null;

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
  /** 화면에 그릴 크기(px) — 줌이 TILE_MAX_ZOOM 보다 크면 256 의 배수 */
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

/**
 * 뷰포트에 걸치는 타일 목록. x 는 날짜변경선에서 감싸고(wrap), y 는 세계 범위 밖이면 뺍니다.
 * 화면 좌표는 타일마다 따로 반올림하지 않고 뷰포트 오프셋 한 번만 반올림해 타일 사이 틈이 생기지 않게 합니다.
 */
export function visibleTiles(vp: TileViewport, zoom: number): TileRef[] {
  if (vp.width <= 0 || vp.height <= 0) return [];
  const z = Math.min(zoom, TILE_MAX_ZOOM);
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
