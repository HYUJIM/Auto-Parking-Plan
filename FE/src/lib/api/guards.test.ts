import { describe, expect, it } from 'vitest';
import { isAddressHit, isLatLng, isRecentSearch } from './guards';

const hit = {
  pnu: '2726010100101770003',
  roadAddress: '대구광역시 수성구 동대구로 336',
  jibunAddress: '대구광역시 수성구 범어동 177-3',
  center: null,
  inSuseong: true,
};

describe('저장소 값 모양 검사', () => {
  it('isLatLng 는 유한한 lat·lng 만 통과', () => {
    expect(isLatLng({ lat: 35.86, lng: 128.63 })).toBe(true);
    expect(isLatLng({ lat: 'a', lng: 1 })).toBe(false);
    expect(isLatLng({ lat: Infinity, lng: 1 })).toBe(false);
    expect(isLatLng(null)).toBe(false);
    expect(isLatLng([35.86, 128.63])).toBe(false);
  });

  it('isAddressHit 는 PNU 19자리·문자열 주소·좌표(null 허용)·boolean 을 요구', () => {
    expect(isAddressHit(hit)).toBe(true);
    expect(isAddressHit({ ...hit, center: { lat: 35.86, lng: 128.63 } })).toBe(true);
    expect(isAddressHit({ ...hit, roadAddress: {} })).toBe(false);
    expect(isAddressHit({ ...hit, pnu: '123' })).toBe(false);
    expect(isAddressHit({ ...hit, center: { lat: 'x' } })).toBe(false);
    expect(isAddressHit({ ...hit, inSuseong: 'yes' })).toBe(false);
    expect(isAddressHit(null)).toBe(false);
    expect(isAddressHit([hit])).toBe(false);
  });

  it('isRecentSearch 는 pnu·label·at 을 검사', () => {
    expect(isRecentSearch({ pnu: hit.pnu, label: '범어동 177-3', at: 1 })).toBe(true);
    expect(isRecentSearch(null)).toBe(false);
    expect(isRecentSearch('문자열')).toBe(false);
    expect(isRecentSearch({ pnu: hit.pnu, label: 1, at: 1 })).toBe(false);
    expect(isRecentSearch({ pnu: hit.pnu, label: 'x', at: 'now' })).toBe(false);
  });
});
