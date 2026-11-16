import type { GeoJsonGeometry, LatLng, LayoutLayers } from '@/lib/api/types';
import { fitZoom, project } from './geo';
import { TILE_MAX_ZOOM, visibleTiles, type TileZoomRange } from './tileSource';

/**
 * 결과 페이지·인쇄용 정지 지도의 계산.
 * 화면 크기와 상관없이 고정된 논리 크기(width × height px)로 한 번 계산하고, 그림은 비율(%)로 배치합니다.
 * 그래서 화면 폭이 바뀌거나 인쇄(PDF 저장) 때 크기가 달라져도 타일을 다시 받지 않고 그대로 늘어납니다.
 */
export interface StaticTile {
  key: string;
  z: number;
  x: number;
  y: number;
  /** 논리 크기에 대한 비율 (%) */
  left: number;
  top: number;
  size: number;
}

export interface StaticView {
  width: number;
  height: number;
  zoom: number;
  center: LatLng;
  /** 위경도 → 논리 좌표 (px) */
  toPx: (p: LatLng) => { x: number; y: number };
  tiles: StaticTile[];
  /** 화면 1px 당 m (축척 막대용) */
  metersPerPx: number;
}

export interface StaticViewOptions {
  width: number;
  height: number;
  /** 대지 둘레 여백 (px) */
  padding?: number;
  range?: TileZoomRange;
  /** 대지가 아주 작아도 이 줌보다 더 당기지 않습니다 (타일 서버 최대 줌 + 1 = 한 단계 확대까지) */
  maxZoom?: number;
}

export function staticView(points: LatLng[], opts: StaticViewOptions): StaticView | null {
  if (points.length === 0) return null;
  const { width, height } = opts;
  const pad = opts.padding ?? 48;
  const maxZoom = opts.maxZoom ?? (opts.range?.maxZoom ?? TILE_MAX_ZOOM) + 1;
  const { center, zoom } = fitZoom(points, width, height, { top: pad, right: pad, bottom: pad, left: pad }, 6, maxZoom);
  const c = project(center, zoom);
  const originX = c.x - width / 2;
  const originY = c.y - height / 2;
  const refs = visibleTiles({ left: originX, top: originY, width, height }, zoom, opts.range);
  const tiles = refs.map((t) => ({
    key: t.key,
    z: t.z,
    x: t.x,
    y: t.y,
    left: (t.left / width) * 100,
    top: (t.top / height) * 100,
    size: (t.size / width) * 100,
  }));
  const lat = center.lat;
  const metersPerPx = (40075016.686 * Math.cos((lat * Math.PI) / 180)) / (256 * 2 ** zoom);
  return {
    width,
    height,
    zoom,
    center,
    tiles,
    metersPerPx,
    toPx: (p) => {
      const q = project(p, zoom);
      return { x: q.x - originX, y: q.y - originY };
    },
  };
}

/** 링 → SVG path 문자열 (논리 좌표) */
export function ringPath(view: StaticView, ring: LatLng[], close = true): string {
  if (ring.length === 0) return '';
  const pts = ring.map((p) => view.toPx(p));
  return pts.map((q, i) => `${i === 0 ? 'M' : 'L'}${q.x.toFixed(1)} ${q.y.toFixed(1)}`).join('') + (close ? 'Z' : '');
}

export interface StaticShape {
  layer: string;
  /** SVG path (논리 좌표). 점이면 빈 문자열 */
  d: string;
  /** 점(출입구 표시)의 위치 */
  point?: { x: number; y: number };
}

function lngLat(raw: unknown): LatLng | null {
  if (!Array.isArray(raw) || raw.length < 2) return null;
  const [lng, lat] = raw;
  return typeof lng === 'number' && typeof lat === 'number' && Number.isFinite(lng) && Number.isFinite(lat) ? { lat, lng } : null;
}

function ringOf(raw: unknown): LatLng[] {
  return Array.isArray(raw) ? raw.map(lngLat).filter((p): p is LatLng => p !== null) : [];
}

/** 도형 하나 → SVG 도형 (모양이 틀려 그릴 점이 없으면 null) */
function geometryShape(view: StaticView, layer: string, g: GeoJsonGeometry): StaticShape | null {
  if (g.type === 'Polygon') {
    const d = g.coordinates.map((r) => ringPath(view, ringOf(r))).join('');
    return d ? { layer, d } : null;
  }
  if (g.type === 'MultiPolygon') {
    const d = g.coordinates.map((poly) => poly.map((r) => ringPath(view, ringOf(r))).join('')).join('');
    return d ? { layer, d } : null;
  }
  if (g.type === 'LineString') {
    const d = ringPath(view, ringOf(g.coordinates), false);
    return d ? { layer, d } : null;
  }
  if (g.type === 'Point') {
    const p = lngLat(g.coordinates);
    return p ? { layer, d: '', point: view.toPx(p) } : null;
  }
  return null;
}

/** 그리는 순서(아래 → 위): 가용 영역 → 차로 → 주차구획 → 기존 건물 → 출입구 개구부 → 출입구 */
const SHAPE_ORDER = ['usable_area', 'aisle', 'stall', 'building', 'gate_opening', 'gate'];

/**
 * 배치안 GeoJSON(과 기존 건물 외곽선) → SVG 도형.
 * 기존 건물(layer 'building')은 주차구획 위·출입구 아래에 그립니다 — 철거 가정이면 그 자리에 놓인 구획 위로 점선 외곽선이 보이고, 출입구 표시는 가리지 않게.
 * 출입구는 점마다 하나 (건물로 나뉜 대지는 어댑터가 MultiPoint 를 점마다 feature 로 나눠 둠)
 */
export function layoutShapes(
  view: StaticView,
  layers: LayoutLayers | null | undefined,
  buildings: readonly { geometry: GeoJsonGeometry }[] = [],
): StaticShape[] {
  const out: StaticShape[] = [];
  for (const f of layers?.features ?? []) {
    const shape = geometryShape(view, String(f.properties?.layer ?? ''), f.geometry);
    if (shape) out.push(shape);
  }
  for (const b of buildings) {
    const shape = geometryShape(view, 'building', b.geometry);
    if (shape) out.push(shape);
  }
  return out.sort((a, b) => SHAPE_ORDER.indexOf(a.layer) - SHAPE_ORDER.indexOf(b.layer));
}
