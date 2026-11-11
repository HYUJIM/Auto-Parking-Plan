import type { MapAdapter } from './types';
import { MockMapAdapter } from './mockMap';
import { NaverMapAdapter } from './naverMap';

/**
 * 지도 공급자 선택 — 화면 코드가 지도 회사를 모르게 하는 유일한 진입점.
 * 카카오로 바꾸려면 kakaoMap.ts 를 만들고 이 함수에서 돌려주면 됩니다.
 */
export function createMapAdapter(): MapAdapter {
  const keyId = import.meta.env.VITE_NAVER_MAP_KEY_ID?.trim();
  return keyId ? new NaverMapAdapter(keyId) : new MockMapAdapter();
}
