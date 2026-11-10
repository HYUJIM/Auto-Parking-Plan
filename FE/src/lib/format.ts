import type { ParcelRoad } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';

const nf = new Intl.NumberFormat('ko-KR');

/** 폭·접도 길이가 있는(목업) 접도 조건 한 문장. 폭을 모르면 null — 요약 카드와 법규 항목이 같은 문장을 씁니다 */
export function formatRoad(road: ParcelRoad): string | null {
  if (typeof road.width !== 'number') return null;
  return fmt(content.site.summary.roadValue, { side: road.side ?? '', width: road.width, frontage: road.frontage ?? '?' }).trim();
}

/** 1234567 → "1,234,567" */
export function formatInt(n: number): string {
  return nf.format(Math.round(n));
}

/** 495 → "495.0", 1203.456 → "1,203.5" (대지면적 표기) */
export function formatArea(n: number, digits = 1): string {
  return n.toLocaleString('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** 좌표 표기 (소수 6자리) */
export function formatCoord(n: number): string {
  return n.toFixed(6);
}

/** 1000 → "1 km", 250 → "250 m" */
export function formatDistance(meters: number): string {
  return meters >= 1000 ? `${meters / 1000} km` : `${meters} m`;
}
