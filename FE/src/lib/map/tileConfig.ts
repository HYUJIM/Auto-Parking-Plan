import { API_BASE, apiUrl } from '@/lib/api/base';
import { DEFAULT_TILE_SOURCE, TILE_MAX_ZOOM, tileChoiceFromEnv, type TileChoice, type TileEnv, type TileSource } from './tileSource';

/**
 * 미리보기 지도가 어떤 배경 타일을 쓸지 정합니다.
 *  1) .env 에 VITE_MAP_TILE_URL(또는 _SATELLITE)을 적었으면 그것 (`off` 면 타일 없음) — BE 에 묻지 않습니다.
 *  2) 아니면 BE 에 한 번 묻습니다 (GET /v1/map/config). 브이월드 키가 있는 BE 는 `/v1/map/tiles/...` 템플릿을 알려 줍니다.
 *     필지 경계가 브이월드 연속지적도라서 브이월드 타일 위에서만 건물·도로와 정확히 맞습니다. 인증키는 BE 에만 있습니다.
 *  3) BE 가 `tiles: null` 을 주거나 요청이 실패하면 기본값(OpenStreetMap · Esri).
 */

/** GET /v1/map/config 의 응답 모양 (BE MapController). 필드는 빠질 수 있다고 보고 방어적으로 읽습니다 */
export interface BeMapConfig {
  tiles?: {
    provider?: string;
    normal?: string;
    satellite?: string;
    min_zoom?: number;
    max_zoom?: number;
  } | null;
}

/** BE 가 응답하지 않아도 지도가 오래 비어 있지 않도록 짧게 기다립니다 */
export const MAP_CONFIG_TIMEOUT_MS = 2500;

const PLACEHOLDERS = ['{z}', '{x}', '{y}'];

/** BE 타일 경로의 모양: `/v1/map/tiles/<레이어>/…` 에 영문·숫자·`/ _ - . { }` 만 */
const BE_TILE_TEMPLATE = /^\/v1\/map\/tiles\/[a-z0-9_-]+\/[A-Za-z0-9/_{}.-]+$/;

/**
 * BE 가 준 템플릿은 같은 BE 의 타일 경로(`/v1/map/tiles/...`)여야 합니다.
 * 응답이 변조돼도 타일 요청(보고 있는 위치가 드러납니다)이 다른 호스트로 나가지 않게 허용 문자를 좁힙니다:
 * 역슬래시·탭·줄바꿈(브라우저가 `/\host`·`/<탭>/host` 를 다른 호스트로 풉니다), `..`, `//`, 질의 문자열을 받지 않고
 * 자리표시자 {z} {x} {y} 는 정확히 한 번씩만 있어야 합니다.
 */
export function bePathTemplate(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 200) return null;
  if (!BE_TILE_TEMPLATE.test(value) || value.includes('..') || value.includes('//')) return null;
  if (!PLACEHOLDERS.every((p) => value.split(p).length === 2)) return null;
  if (value.replace(/\{[zxy]\}/g, '').includes('{') || value.replace(/\{[zxy]\}/g, '').includes('}')) return null;
  return value;
}

function zoomOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 30 ? value : undefined;
}

/**
 * BE 응답 → 타일 소스. 템플릿 앞에 API_BASE 를 붙입니다 (비어 있으면 같은 origin 의 상대 경로).
 * 브이월드가 아니거나 모양이 맞지 않으면 null — 호출 쪽이 기본값으로 대체합니다.
 */
export function tileSourceFromBe(body: unknown, apiBase: string): TileSource | null {
  const tiles = (body as BeMapConfig | null)?.tiles;
  if (!tiles || typeof tiles !== 'object' || tiles.provider !== 'vworld') return null;
  const normal = bePathTemplate(tiles.normal);
  if (!normal) return null;
  const satellite = bePathTemplate(tiles.satellite) ?? normal;
  const minZoom = zoomOrUndefined(tiles.min_zoom);
  const maxZoom = zoomOrUndefined(tiles.max_zoom) ?? TILE_MAX_ZOOM;
  if (minZoom !== undefined && minZoom > maxZoom) return null;
  return { normal: `${apiBase}${normal}`, satellite: `${apiBase}${satellite}`, minZoom, maxZoom };
}

export interface TileChoiceDeps {
  /** GET /v1/map/config 의 본문을 돌려줍니다. 실패하면 던지거나 null */
  fetchConfig: () => Promise<unknown>;
  apiBase: string;
  /** BE 에 묻지 못했을 때(시간 초과·연결 실패) 불립니다 — 이 결과는 임시이므로 호출 쪽이 기억해 두지 않게 */
  onFetchFailed?: () => void;
}

export async function resolveTileChoice(env: TileEnv, deps: TileChoiceDeps): Promise<TileChoice> {
  const fromEnv = tileChoiceFromEnv(env);
  if (fromEnv) return fromEnv;
  let body: unknown = null;
  try {
    body = await deps.fetchConfig();
  } catch {
    /* BE 를 못 부름 — 이번에는 기본 타일로 대체 */
    deps.onFetchFailed?.();
  }
  const source = tileSourceFromBe(body, deps.apiBase);
  if (source) return { provider: 'vworld', source };
  return { provider: 'default', source: DEFAULT_TILE_SOURCE };
}

/** GET {API_BASE}/v1/map/config — 제한 시간 안에 2xx JSON 이 오지 않으면 던집니다 */
export async function fetchMapConfig(timeoutMs: number = MAP_CONFIG_TIMEOUT_MS): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(apiUrl('/v1/map/config'), { signal: controller.signal, headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`map config HTTP ${res.status}`);
    return (await res.json()) as unknown;
  } finally {
    clearTimeout(timer);
  }
}

let cached: Promise<TileChoice> | null = null;

/**
 * 이 페이지에서 쓸 타일 선택 — BE 에는 한 번만 묻고 결과를 계속 씁니다 (지도를 다시 붙여도, 네이버 실패로 대체 지도를 띄워도 같은 값).
 * BE 가 답한 결과(브이월드, 또는 `tiles: null` 이라 기본 타일)는 그대로 둡니다: 화면 도중에 배경 지도가 바뀌지 않도록.
 * 다만 BE 에 묻지 못해 기본 타일로 정해진 경우는 기억하지 않습니다 — 한 번의 느린 응답 때문에 새로고침 전까지
 * 계속 외부 타일(필지 경계와 6~10m 어긋남)을 쓰지 않도록, 다음에 지도를 붙일 때 다시 묻습니다.
 */
export function loadTileChoice(): Promise<TileChoice> {
  if (cached) return cached;
  let failed = false;
  // 빌드 변수는 필요한 두 값만 넘깁니다 (import.meta.env 통째로 넘기면 다른 VITE_ 값이 번들에 딸려 올 수 있습니다)
  const env: TileEnv = {
    VITE_MAP_TILE_URL: import.meta.env.VITE_MAP_TILE_URL,
    VITE_MAP_TILE_URL_SATELLITE: import.meta.env.VITE_MAP_TILE_URL_SATELLITE,
  };
  const pending = resolveTileChoice(env, {
    fetchConfig: () => fetchMapConfig(),
    apiBase: API_BASE,
    onFetchFailed: () => {
      failed = true;
    },
  }).then((choice) => {
    if (failed && cached === pending) cached = null;
    return choice;
  });
  cached = pending;
  return pending;
}

/** 테스트용: 기억해 둔 타일 선택을 지웁니다 */
export function resetTileChoiceCache(): void {
  cached = null;
}
