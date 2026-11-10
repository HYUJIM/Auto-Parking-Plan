/**
 * EPSG:5179 (Korea 2000 / Unified CS, GRS80 횡축 메르카토르) ↔ WGS84 (EPSG:4326).
 *
 * 도로명주소 좌표제공 API(addrCoordApi) 는 entX/entY 를 EPSG:5179 로 돌려주므로
 * 지도에 올리려면 위경도로 바꿔야 합니다. 외부 의존성 없이 Snyder(1987) 공식을 그대로 씁니다.
 *
 *   투영 원점 38°N 127.5°E · 축척 0.9996 · 가산 동거 1,000,000 m · 가산 북거 2,000,000 m
 */
const A = 6378137;
const F = 1 / 298.257222101;
const E2 = 2 * F - F * F;
const EP2 = E2 / (1 - E2);
const K0 = 0.9996;
const LAT0 = (38 * Math.PI) / 180;
const LON0 = (127.5 * Math.PI) / 180;
const FALSE_EASTING = 1_000_000;
const FALSE_NORTHING = 2_000_000;

const E4 = E2 * E2;
const E6 = E4 * E2;

function meridianArc(phi: number): number {
  return (
    A *
    ((1 - E2 / 4 - (3 * E4) / 64 - (5 * E6) / 256) * phi -
      ((3 * E2) / 8 + (3 * E4) / 32 + (45 * E6) / 1024) * Math.sin(2 * phi) +
      ((15 * E4) / 256 + (45 * E6) / 1024) * Math.sin(4 * phi) -
      ((35 * E6) / 3072) * Math.sin(6 * phi))
  );
}

const M0 = meridianArc(LAT0);

export interface LngLat {
  lat: number;
  lng: number;
}

/** EPSG:5179 (x=동거, y=북거, m) → 위경도(도) */
export function utmkToWgs84(x: number, y: number): LngLat {
  const M = M0 + (y - FALSE_NORTHING) / K0;
  const mu = M / (A * (1 - E2 / 4 - (3 * E4) / 64 - (5 * E6) / 256));
  const e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
  const phi1 =
    mu +
    ((3 * e1) / 2 - (27 * e1 ** 3) / 32) * Math.sin(2 * mu) +
    ((21 * e1 ** 2) / 16 - (55 * e1 ** 4) / 32) * Math.sin(4 * mu) +
    ((151 * e1 ** 3) / 96) * Math.sin(6 * mu) +
    ((1097 * e1 ** 4) / 512) * Math.sin(8 * mu);

  const sin1 = Math.sin(phi1);
  const cos1 = Math.cos(phi1);
  const tan1 = Math.tan(phi1);
  const C1 = EP2 * cos1 * cos1;
  const T1 = tan1 * tan1;
  const N1 = A / Math.sqrt(1 - E2 * sin1 * sin1);
  const R1 = (A * (1 - E2)) / Math.pow(1 - E2 * sin1 * sin1, 1.5);
  const D = (x - FALSE_EASTING) / (N1 * K0);

  const lat =
    phi1 -
    ((N1 * tan1) / R1) *
      (D ** 2 / 2 -
        ((5 + 3 * T1 + 10 * C1 - 4 * C1 * C1 - 9 * EP2) * D ** 4) / 24 +
        ((61 + 90 * T1 + 298 * C1 + 45 * T1 * T1 - 252 * EP2 - 3 * C1 * C1) * D ** 6) / 720);
  const lng =
    LON0 + (D - ((1 + 2 * T1 + C1) * D ** 3) / 6 + ((5 - 2 * C1 + 28 * T1 - 3 * C1 * C1 + 8 * EP2 + 24 * T1 * T1) * D ** 5) / 120) / cos1;

  return { lat: (lat * 180) / Math.PI, lng: (lng * 180) / Math.PI };
}

/** 위경도(도) → EPSG:5179. 면적·중심 계산(vworld.ts)과 왕복 검증에 사용 */
export function wgs84ToUtmk(p: LngLat): { x: number; y: number } {
  const phi = (p.lat * Math.PI) / 180;
  const lam = (p.lng * Math.PI) / 180;
  const sin = Math.sin(phi);
  const cos = Math.cos(phi);
  const tan = Math.tan(phi);
  const N = A / Math.sqrt(1 - E2 * sin * sin);
  const T = tan * tan;
  const C = EP2 * cos * cos;
  const Aa = (lam - LON0) * cos;
  const M = meridianArc(phi);

  const x = FALSE_EASTING + K0 * N * (Aa + ((1 - T + C) * Aa ** 3) / 6 + ((5 - 18 * T + T * T + 72 * C - 58 * EP2) * Aa ** 5) / 120);
  const y =
    FALSE_NORTHING +
    K0 *
      (M -
        M0 +
        N *
          tan *
          (Aa ** 2 / 2 + ((5 - T + 9 * C + 4 * C * C) * Aa ** 4) / 24 + ((61 - 58 * T + T * T + 600 * C - 330 * EP2) * Aa ** 6) / 720));

  return { x, y };
}
