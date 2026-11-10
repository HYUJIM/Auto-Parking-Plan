import type { LatLng } from './types';
import { distanceMeters } from './geo';
import { parcels } from '@/lib/api/mock';
import { getParcelAt } from '@/lib/api/parcel';
import { ApiError } from '@/lib/api/types';

export type PickResult = { kind: 'found'; pnu: string } | { kind: 'none' } | { kind: 'not-ready' } | { kind: 'failed'; error: unknown };

/** 클릭 지점에서 '필지 점'으로 인정하는 반경(m) — 최소 60m, 줌이 낮으면 14px 상당 */
export function pickRadiusMeters(metersPerPixel: number): number {
  return Math.max(60, metersPerPixel * 14);
}

/** 목업 검증 필지 점 중 반경 안에서 가장 가까운 것 */
export function nearestMockParcel(point: LatLng, metersPerPixel: number): string | null {
  const radius = pickRadiusMeters(metersPerPixel);
  let best: { pnu: string; d: number } | null = null;
  for (const p of parcels) {
    const d = distanceMeters(point, p.center);
    if (d <= radius && (!best || d < best.d)) best = { pnu: p.pnu, d };
  }
  return best?.pnu ?? null;
}

/**
 * 지도 클릭 지점 → 필지. 지도 공급자와 무관한 규칙만 담습니다 (MapContext 는 결과를 라우팅·토스트로 옮기기만).
 * 1) 목업 검증 필지 점 근처면 그 필지, 2) 아니면 서버(/api/parcel/at → 브이월드 연속지적도)에 묻습니다.
 * BE 연동 시 2) 만 `/v1/parcels/at` 호출로 바꾸면 됩니다.
 */
export async function pickParcelAt(point: LatLng, metersPerPixel: number): Promise<PickResult> {
  const nearby = nearestMockParcel(point, metersPerPixel);
  if (nearby) return { kind: 'found', pnu: nearby };
  try {
    const found = await getParcelAt(point);
    return found ? { kind: 'found', pnu: found.pnu } : { kind: 'none' };
  } catch (error) {
    if (error instanceof ApiError && error.code === 'NOT_READY') return { kind: 'not-ready' };
    return { kind: 'failed', error };
  }
}
