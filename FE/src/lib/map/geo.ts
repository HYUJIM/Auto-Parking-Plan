import type { LatLng } from './types';

const TILE = 256;
const EARTH_CIRCUMFERENCE = 40075016.686;
const EARTH_RADIUS = 6371000;

/** 위경도 → 웹 메르카토르 픽셀 좌표 (zoom 기준) */
export function project(p: LatLng, zoom: number): { x: number; y: number } {
  const scale = TILE * 2 ** zoom;
  const sin = Math.min(Math.max(Math.sin((p.lat * Math.PI) / 180), -0.9999), 0.9999);
  return {
    x: ((p.lng + 180) / 360) * scale,
    y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale,
  };
}

/** 웹 메르카토르 픽셀 좌표 → 위경도 */
export function unproject(x: number, y: number, zoom: number): LatLng {
  const scale = TILE * 2 ** zoom;
  const lng = (x / scale) * 360 - 180;
  const n = Math.PI - (2 * Math.PI * y) / scale;
  const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
  return { lat, lng };
}

/** 해당 위도·줌에서 1px 당 m */
export function metersPerPixel(lat: number, zoom: number): number {
  return (EARTH_CIRCUMFERENCE * Math.cos((lat * Math.PI) / 180)) / (TILE * 2 ** zoom);
}

/** 두 지점 사이 거리 (m, haversine) */
export function distanceMeters(a: LatLng, b: LatLng): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS * Math.asin(Math.sqrt(h));
}

/** 점들의 무게중심 (라벨 위치용) */
export function centroid(points: LatLng[]): LatLng {
  const n = points.length || 1;
  return {
    lat: points.reduce((s, p) => s + p.lat, 0) / n,
    lng: points.reduce((s, p) => s + p.lng, 0) / n,
  };
}

export function bounds(points: LatLng[]): { min: LatLng; max: LatLng } {
  return {
    min: { lat: Math.min(...points.map((p) => p.lat)), lng: Math.min(...points.map((p) => p.lng)) },
    max: { lat: Math.max(...points.map((p) => p.lat)), lng: Math.max(...points.map((p) => p.lng)) },
  };
}

/**
 * 점들이 (width × height) 안에 padding 을 두고 들어가는 가장 큰 정수 줌과 중심.
 */
export function fitZoom(
  points: LatLng[],
  width: number,
  height: number,
  padding: { top: number; right: number; bottom: number; left: number },
  minZoom = 6,
  maxZoom = 19,
): { center: LatLng; zoom: number } {
  const b = bounds(points);
  const availW = Math.max(40, width - padding.left - padding.right);
  const availH = Math.max(40, height - padding.top - padding.bottom);

  let zoom = minZoom;
  for (let z = maxZoom; z >= minZoom; z--) {
    const a = project(b.min, z);
    const c = project(b.max, z);
    if (Math.abs(c.x - a.x) <= availW && Math.abs(c.y - a.y) <= availH) {
      zoom = z;
      break;
    }
  }

  // 패딩이 비대칭이면(모바일 하단 시트) 가운데를 그만큼 밀어 줍니다.
  const a = project(b.min, zoom);
  const c = project(b.max, zoom);
  const midX = (a.x + c.x) / 2 - (padding.left - padding.right) / 2;
  const midY = (a.y + c.y) / 2 - (padding.top - padding.bottom) / 2;
  return { center: unproject(midX, midY, zoom), zoom };
}

// ── GeoJSON 링 ───────────────────────────────────────────────────────

/** GeoJSON 좌표 배열([lng, lat][]) → LatLng 링. 닫는 점(첫 점과 같은 마지막 점)은 뗍니다. 숫자가 아닌 점은 건너뜁니다. */
export function toRing(raw: unknown): LatLng[] {
  if (!Array.isArray(raw)) return [];
  const ring: LatLng[] = [];
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

/** GeoJSON Polygon/MultiPolygon → 가장 큰 폴리곤의 외곽 링 (열린 링). 다른 타입이면 빈 배열 */
export function outerRing(type: string | undefined, coordinates: unknown): LatLng[] {
  const polygons: unknown[] = type === 'MultiPolygon' ? (coordinates as unknown[]) : type === 'Polygon' ? [coordinates] : [];
  let best: LatLng[] = [];
  let bestArea = -1;
  for (const poly of Array.isArray(polygons) ? polygons : []) {
    const ring = toRing((poly as unknown[])?.[0]);
    if (ring.length < 3) continue;
    const area = ringAreaM2(ring);
    if (area > bestArea) {
      bestArea = area;
      best = ring;
    }
  }
  return best;
}

/** 링을 첫 점 기준의 평면(m) 좌표로 — 필지 크기(수백 m 이내)에서는 등거리 근사로 충분합니다 */
function toLocalMeters(ring: LatLng[]): { x: number; y: number }[] {
  const o = ring[0];
  const cos = Math.cos((o.lat * Math.PI) / 180);
  const k = (Math.PI / 180) * EARTH_RADIUS;
  return ring.map((p) => ({ x: (p.lng - o.lng) * k * cos, y: (p.lat - o.lat) * k }));
}

/** 링의 평면 면적 (㎡, shoelace). 점이 3개 미만이면 0 */
export function ringAreaM2(ring: LatLng[]): number {
  if (ring.length < 3) return 0;
  const pts = toLocalMeters(ring);
  let sum = 0;
  for (let i = 0; i < pts.length; i++) {
    const a = pts[i];
    const b = pts[(i + 1) % pts.length];
    sum += a.x * b.y - b.x * a.y;
  }
  return Math.abs(sum) / 2;
}

/** 면적 가중 중심 (라벨·핀 위치). 면적이 0 이면 꼭짓점 평균 */
export function ringCentroid(ring: LatLng[]): LatLng {
  if (ring.length < 3) return centroid(ring);
  const pts = toLocalMeters(ring);
  let a2 = 0;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const q = pts[(i + 1) % pts.length];
    const cross = p.x * q.y - q.x * p.y;
    a2 += cross;
    cx += (p.x + q.x) * cross;
    cy += (p.y + q.y) * cross;
  }
  if (Math.abs(a2) < 1e-9) return centroid(ring);
  const o = ring[0];
  const cos = Math.cos((o.lat * Math.PI) / 180);
  const k = (Math.PI / 180) * EARTH_RADIUS;
  const x = cx / (3 * a2);
  const y = cy / (3 * a2);
  return { lat: o.lat + y / k, lng: o.lng + x / (k * cos) };
}

/** 대구 수성구 기준 좌표/범위 (초기화면 지도) */
export const SUSEONG_CENTER: LatLng = { lat: 35.842, lng: 128.648 };
export const SUSEONG_BOUNDS: LatLng[] = [
  { lat: 35.805, lng: 128.59 },
  { lat: 35.88, lng: 128.72 },
];
