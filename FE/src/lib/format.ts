const nf = new Intl.NumberFormat('ko-KR');

/** 1234567 → "1,234,567" */
export function formatInt(n: number): string {
  return nf.format(Math.round(n));
}

/** 495 → "495.0", 1203.456 → "1,203.5" (대지면적·연면적 표기) */
export function formatArea(n: number, digits = 1): string {
  return n.toLocaleString('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** 374.46 → "374.46", 6 → "6" (법규 참고값·배치안 지표 — 있는 소수만, 최대 2자리) */
export function formatNumber(n: number, maxDigits = 2): string {
  return n.toLocaleString('ko-KR', { maximumFractionDigits: maxDigits });
}

/** 좌표 표기 (소수 6자리) */
export function formatCoord(n: number): string {
  return n.toFixed(6);
}

/** 1000 → "1 km", 250 → "250 m" */
export function formatDistance(meters: number): string {
  return meters >= 1000 ? `${meters / 1000} km` : `${meters} m`;
}
