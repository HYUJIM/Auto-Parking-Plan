import type { LatLng } from './types';

const TILE = 256;
const EARTH_CIRCUMFERENCE = 40075016.686;

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
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
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

/** 대구 수성구 기준 좌표/범위 (초기화면 지도) */
export const SUSEONG_CENTER: LatLng = { lat: 35.842, lng: 128.648 };
export const SUSEONG_BOUNDS: LatLng[] = [
  { lat: 35.805, lng: 128.59 },
  { lat: 35.88, lng: 128.72 },
];
