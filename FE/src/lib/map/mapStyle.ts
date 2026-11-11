/**
 * 지도 위 대지·도로·마커·배치안 색은 테마 토큰(--map-*)에서 읽습니다.
 * 네이버 지도처럼 실제 색 문자열이 필요한 공급자를 위해 계산된 값을 돌려줍니다. (미리보기 지도는 CSS 에서 같은 토큰을 직접 씁니다 — map.css)
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
  /** 주차장 배치안 — 주차구획 */
  layoutStallStroke: string;
  layoutStallFill: string;
  layoutStallFillOpacity: number;
  /** 차로 */
  layoutAisleFill: string;
  layoutAisleFillOpacity: number;
  /** 출입구 (점·개구부 선) */
  layoutGateStroke: string;
  layoutGateStrokeWidth: number;
  /** 가용 영역(이격 후) 외곽선 */
  layoutUsableStroke: string;
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
    layoutStallStroke: readToken('--map-layout-stall-stroke'),
    layoutStallFill: readToken('--map-layout-stall-fill'),
    layoutStallFillOpacity: num('--map-layout-stall-fill-opacity', 0.55),
    layoutAisleFill: readToken('--map-layout-aisle-fill'),
    layoutAisleFillOpacity: num('--map-layout-aisle-fill-opacity', 0.3),
    layoutGateStroke: readToken('--map-layout-gate-stroke'),
    layoutGateStrokeWidth: num('--map-layout-gate-stroke-width', 4),
    layoutUsableStroke: readToken('--map-layout-usable-stroke'),
  };
}
