import { describe, expect, it } from 'vitest';
import { DEFAULT_TILE_SOURCE, TILE_MAX_ZOOM, tileSourceFromEnv, tileUrl, visibleTiles } from './tileSource';

describe('tileSourceFromEnv', () => {
  it('비우면 기본값(OSM · Esri)', () => {
    expect(tileSourceFromEnv({})).toEqual(DEFAULT_TILE_SOURCE);
    expect(tileSourceFromEnv({ VITE_MAP_TILE_URL: '  ', VITE_MAP_TILE_URL_SATELLITE: '' })).toEqual(DEFAULT_TILE_SOURCE);
  });

  it('일반 타일이 off 면 타일을 쓰지 않는다', () => {
    expect(tileSourceFromEnv({ VITE_MAP_TILE_URL: 'off' })).toBeNull();
    expect(tileSourceFromEnv({ VITE_MAP_TILE_URL: 'OFF', VITE_MAP_TILE_URL_SATELLITE: 'https://x/{z}/{x}/{y}' })).toBeNull();
  });

  it('위성 타일만 off 면 일반 타일을 그대로 쓴다', () => {
    const custom = 'https://tiles.example/{z}/{x}/{y}.png';
    expect(tileSourceFromEnv({ VITE_MAP_TILE_URL: custom, VITE_MAP_TILE_URL_SATELLITE: 'off' })).toEqual({
      normal: custom,
      satellite: custom,
    });
  });
});

describe('tileUrl', () => {
  it('{z} {x} {y} 자리표시자를 순서와 무관하게 바꾼다', () => {
    expect(tileUrl('https://a/{z}/{x}/{y}.png', 15, 27966, 12876)).toBe('https://a/15/27966/12876.png');
    expect(tileUrl('https://b/tile/{z}/{y}/{x}', 3, 1, 2)).toBe('https://b/tile/3/2/1');
  });
});

describe('visibleTiles', () => {
  it('뷰포트에 걸치는 타일만, 뷰포트 오프셋 기준 화면 좌표로 돌려준다', () => {
    // 줌 3: 세계가 8×8 타일. 뷰포트가 (300,300) 에서 300×300px 이면 x,y 타일 1..2
    const tiles = visibleTiles({ left: 300, top: 300, width: 300, height: 300 }, 3);
    expect(tiles.map((t) => `${t.x},${t.y}`).sort()).toEqual(['1,1', '1,2', '2,1', '2,2']);
    const first = tiles.find((t) => t.x === 1 && t.y === 1);
    expect(first).toMatchObject({ z: 3, left: 256 - 300, top: 256 - 300, size: 256 });
  });

  it('x 는 날짜변경선에서 감싸고 y 는 세계 밖이면 뺀다', () => {
    // 줌 1: 세계가 2×2 타일(512px). 뷰포트가 왼쪽·위로 세계 밖까지 나가 있음
    const tiles = visibleTiles({ left: -300, top: -100, width: 900, height: 700 }, 1);
    expect(tiles.every((t) => t.y >= 0 && t.y <= 1)).toBe(true);
    expect(tiles.every((t) => t.x >= 0 && t.x <= 1)).toBe(true);
    // 타일은 256px: x = -2..2 다섯 열(감싸면 0,1,0,1,0) × y 0..1 두 줄
    expect(tiles).toHaveLength(10);
    const left = tiles.find((t) => t.key.endsWith('@-1'));
    expect(left).toMatchObject({ x: 1, left: -256 + 300 });
  });

  it('타일 서버의 최대 줌을 넘으면 그 줌의 타일을 키워서 쓴다', () => {
    const tiles = visibleTiles({ left: 0, top: 0, width: 600, height: 600 }, TILE_MAX_ZOOM + 1);
    expect(tiles.length).toBeGreaterThan(0);
    expect(tiles.every((t) => t.z === TILE_MAX_ZOOM && t.size === 512)).toBe(true);
  });

  it('뷰포트가 비어 있으면 아무것도 없다', () => {
    expect(visibleTiles({ left: 0, top: 0, width: 0, height: 0 }, 5)).toEqual([]);
  });
});
