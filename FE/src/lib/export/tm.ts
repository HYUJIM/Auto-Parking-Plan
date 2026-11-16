import type { LatLng } from '@/lib/map/types';

/**
 * 위경도(WGS84 ≈ GRS80) → 한국 측지계 2000 중부원점 TM 좌표 (EPSG:5186, 단위 m).
 * 지적도·설계 도면이 쓰는 좌표계라서 CAD 파일을 이 좌표로 내보내면 다른 도면 위에 그대로 겹칩니다.
 *
 * 투영식: 횡메르카토르 Krüger 급수(n 의 6차, Karney 2011). 원점에서 3° 안에서는 오차가 0.1mm 보다 작습니다.
 * 타원체 GRS80 (a = 6378137, f = 1/298.257222101), 원점 북위 38° 동경 127°, 축척계수 1, 가산값 동 200000 · 북 600000.
 * WGS84 와 GRS80 의 차이(편평률 1e-11 수준)는 무시합니다.
 */
export interface TmPoint {
  /** 동쪽 방향 (m) */
  x: number;
  /** 북쪽 방향 (m) */
  y: number;
}

const A = 6378137;
const F = 1 / 298.257222101;
const LAT0 = 38;
const LON0 = 127;
const K0 = 1;
const FALSE_EASTING = 200000;
const FALSE_NORTHING = 600000;

const N = F / (2 - F);
const N2 = N * N;
const N3 = N2 * N;
const N4 = N3 * N;
const N5 = N4 * N;
const N6 = N5 * N;
/** 직사각 반경 (rectifying radius) */
const A_RECT = (A / (1 + N)) * (1 + N2 / 4 + N4 / 64 + N6 / 256);
const E = Math.sqrt(F * (2 - F));

/** 정방향 계수 α1..α6 */
const ALPHA = [
  N / 2 - (2 * N2) / 3 + (5 * N3) / 16 + (41 * N4) / 180 - (127 * N5) / 288 + (7891 * N6) / 37800,
  (13 * N2) / 48 - (3 * N3) / 5 + (557 * N4) / 1440 + (281 * N5) / 630 - (1983433 * N6) / 1935360,
  (61 * N3) / 240 - (103 * N4) / 140 + (15061 * N5) / 26880 + (167603 * N6) / 181440,
  (49561 * N4) / 161280 - (179 * N5) / 168 + (6601661 * N6) / 7257600,
  (34729 * N5) / 80640 - (3418889 * N6) / 1995840,
  (212378941 * N6) / 319334400,
];

/** 역방향 계수 β1..β6 */
const BETA = [
  N / 2 - (2 * N2) / 3 + (37 * N3) / 96 - N4 / 360 - (81 * N5) / 512 + (96199 * N6) / 604800,
  N2 / 48 + N3 / 15 - (437 * N4) / 1440 + (46 * N5) / 105 - (1118711 * N6) / 3870720,
  (17 * N3) / 480 - (37 * N4) / 840 - (209 * N5) / 4480 + (5569 * N6) / 90720,
  (4397 * N4) / 161280 - (11 * N5) / 504 - (830251 * N6) / 7257600,
  (4583 * N5) / 161280 - (108847 * N6) / 3991680,
  (20648693 * N6) / 638668800,
];

const DEG = Math.PI / 180;

/** 등각위도의 tan (τ' = tan χ) */
function conformalTan(phi: number): number {
  const tau = Math.tan(phi);
  const sigma = Math.sinh(E * Math.atanh((E * tau) / Math.sqrt(1 + tau * tau)));
  return tau * Math.sqrt(1 + sigma * sigma) - sigma * Math.sqrt(1 + tau * tau);
}

/** 경도 차 0, 위도 phi 의 자오선 호 길이 → 원점 북거 계산용 */
function northingOf(phiDeg: number): number {
  const tp = conformalTan(phiDeg * DEG);
  const xi = Math.atan(tp);
  let s = xi;
  for (let j = 1; j <= 6; j++) s += ALPHA[j - 1] * Math.sin(2 * j * xi);
  return K0 * A_RECT * s;
}

const ORIGIN_NORTHING = northingOf(LAT0);

export function toTm(p: LatLng): TmPoint {
  const phi = p.lat * DEG;
  const lam = (p.lng - LON0) * DEG;
  const tp = conformalTan(phi);
  const xiP = Math.atan2(tp, Math.cos(lam));
  const etaP = Math.asinh(Math.sin(lam) / Math.sqrt(tp * tp + Math.cos(lam) ** 2));
  let xi = xiP;
  let eta = etaP;
  for (let j = 1; j <= 6; j++) {
    xi += ALPHA[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
    eta += ALPHA[j - 1] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
  }
  return {
    x: FALSE_EASTING + K0 * A_RECT * eta,
    y: FALSE_NORTHING + K0 * A_RECT * xi - ORIGIN_NORTHING,
  };
}

export function fromTm(t: TmPoint): LatLng {
  const xi = (t.y - FALSE_NORTHING + ORIGIN_NORTHING) / (K0 * A_RECT);
  const eta = (t.x - FALSE_EASTING) / (K0 * A_RECT);
  let xiP = xi;
  let etaP = eta;
  for (let j = 1; j <= 6; j++) {
    xiP -= BETA[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
    etaP -= BETA[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
  }
  const tauP = Math.sin(xiP) / Math.sqrt(Math.sinh(etaP) ** 2 + Math.cos(xiP) ** 2);
  const lam = Math.atan2(Math.sinh(etaP), Math.cos(xiP));
  // τ' → τ (뉴턴 반복)
  let tau = tauP;
  for (let i = 0; i < 6; i++) {
    const sigma = Math.sinh(E * Math.atanh((E * tau) / Math.sqrt(1 + tau * tau)));
    const tpi = tau * Math.sqrt(1 + sigma * sigma) - sigma * Math.sqrt(1 + tau * tau);
    const dTau = ((tauP - tpi) / Math.sqrt(1 + tpi * tpi)) * ((1 + (1 - E * E) * tau * tau) / ((1 - E * E) * Math.sqrt(1 + tau * tau)));
    tau += dTau;
    if (Math.abs(dTau) < 1e-14) break;
  }
  return { lat: Math.atan(tau) / DEG, lng: LON0 + lam / DEG };
}
