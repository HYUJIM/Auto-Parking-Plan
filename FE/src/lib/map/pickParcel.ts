import type { LatLng } from './types';
import { getParcelAt } from '@/lib/api/parcel';

export type PickResult = { kind: 'found'; pnu: string } | { kind: 'none' } | { kind: 'failed'; error: unknown };

/**
 * 지도 클릭 지점 → 필지. 지도 공급자와 무관한 규칙만 담습니다 (MapContext 는 결과를 라우팅·토스트로 옮기기만).
 * BE(`GET /v1/parcels/at` → 브이월드 연속지적도)에 묻습니다.
 */
export async function pickParcelAt(point: LatLng): Promise<PickResult> {
  try {
    const found = await getParcelAt(point);
    return found ? { kind: 'found', pnu: found.pnu } : { kind: 'none' };
  } catch (error) {
    return { kind: 'failed', error };
  }
}
