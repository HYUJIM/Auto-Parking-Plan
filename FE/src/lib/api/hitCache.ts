import type { AddressHit } from './types';
import { isAddressHit } from './guards';

/**
 * 최근 검색 결과(AddressHit)를 PNU 별로 세션에 보관합니다.
 * 필지 데이터가 아직 없는 PNU(브이월드 연동 전)로 /site 에 들어왔을 때 주소·좌표를 보여 주기 위한 용도입니다.
 * 저장소 값은 모양을 검사해 통과한 항목만 씁니다 (guards.ts).
 */
const KEY = 'app:address-hits';
const MAX = 50;

function read(): Map<string, AddressHit> {
  const map = new Map<string, AddressHit>();
  try {
    const raw = sessionStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return map;
    for (const [pnu, hit] of Object.entries(parsed as Record<string, unknown>)) {
      if (isAddressHit(hit) && hit.pnu === pnu) map.set(pnu, hit);
    }
  } catch {
    /* 저장소 접근 불가 또는 손상된 JSON — 빈 캐시로 */
  }
  return map;
}

export function rememberHit(hit: AddressHit): void {
  if (!isAddressHit(hit)) return;
  try {
    const map = read();
    map.delete(hit.pnu);
    map.set(hit.pnu, hit);
    while (map.size > MAX) {
      const oldest = map.keys().next().value;
      if (oldest === undefined) break;
      map.delete(oldest);
    }
    sessionStorage.setItem(KEY, JSON.stringify(Object.fromEntries(map)));
  } catch {
    /* 저장소 접근 불가 — 무시 */
  }
}

export function getCachedHit(pnu: string): AddressHit | null {
  return read().get(pnu) ?? null;
}
