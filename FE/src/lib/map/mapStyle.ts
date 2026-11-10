/**
 * 지도 위 대지·도로·마커 색은 테마 토큰(--map-*)에서 읽습니다.
 * 네이버 지도처럼 실제 색 문자열이 필요한 공급자를 위해 계산된 값을 돌려줍니다.
 */
export interface MapStyle {
  siteStroke: string;
  siteStrokeWidth: number;
  siteFill: string;
  siteFillOpacity: number;
  roadStroke: string;
  roadStrokeWidth: number;
  marker: string;
  markerRing: string;
  markerPreview: string;
  labelBg: string;
  labelText: string;
}

export function readToken(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function num(name: string, fallback: number): number {
  const v = parseFloat(readToken(name));
  return Number.isFinite(v) ? v : fallback;
}

export function getMapStyle(): MapStyle {
  return {
    siteStroke: readToken('--map-site-stroke'),
    siteStrokeWidth: num('--map-site-stroke-width', 3),
    siteFill: readToken('--map-site-fill'),
    siteFillOpacity: num('--map-site-fill-opacity', 0.18),
    roadStroke: readToken('--map-road-stroke'),
    roadStrokeWidth: num('--map-road-stroke-width', 5),
    marker: readToken('--map-marker'),
    markerRing: readToken('--map-marker-ring'),
    markerPreview: readToken('--map-marker-preview'),
    labelBg: readToken('--map-label-bg'),
    labelText: readToken('--map-label-text'),
  };
}
