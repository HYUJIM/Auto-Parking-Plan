import { describe, expect, it } from 'vitest';
import { utmkToWgs84, wgs84ToUtmk } from './proj.ts';

const POINTS = [
  { name: '서울시청', lat: 37.5665, lng: 126.978 },
  { name: '대구 범어동', lat: 35.8585, lng: 128.6268 },
  { name: '부산역', lat: 35.1151, lng: 129.0423 },
  { name: '제주', lat: 33.4996, lng: 126.5312 },
  { name: '독도', lat: 37.2431, lng: 131.8643 },
];

describe('EPSG:5179 ↔ WGS84 변환', () => {
  it('왕복 변환 오차: 서비스 지역(중앙자오선 ±2°)은 1e-8도(약 1mm), 독도처럼 먼 곳도 1e-6도(약 10cm) 이하', () => {
    for (const p of POINTS) {
      const tolerance = Math.abs(p.lng - 127.5) <= 2 ? 1e-8 : 1e-6;
      const xy = wgs84ToUtmk(p);
      const back = utmkToWgs84(xy.x, xy.y);
      expect(Math.abs(back.lat - p.lat), p.name).toBeLessThan(tolerance);
      expect(Math.abs(back.lng - p.lng), p.name).toBeLessThan(tolerance);
    }
  });

  it('서울시청은 알려진 EPSG:5179 좌표 범위(953~955km, 1951~1953km)에 놓인다', () => {
    const xy = wgs84ToUtmk(POINTS[0]);
    expect(xy.x).toBeGreaterThan(953_000);
    expect(xy.x).toBeLessThan(955_000);
    expect(xy.y).toBeGreaterThan(1_951_000);
    expect(xy.y).toBeLessThan(1_953_000);
  });

  it('투영 원점(38°N 127.5°E)은 가산 좌표(1,000,000 / 2,000,000)로 간다', () => {
    const xy = wgs84ToUtmk({ lat: 38, lng: 127.5 });
    expect(xy.x).toBeCloseTo(1_000_000, 3);
    expect(xy.y).toBeCloseTo(2_000_000, 3);
  });

  it('동쪽으로 1km 이동하면 x 가 약 1,000m 커진다 (위도 35.8°)', () => {
    const a = wgs84ToUtmk({ lat: 35.8585, lng: 128.6268 });
    const b = wgs84ToUtmk({ lat: 35.8585, lng: 128.6268 + 1 / ((111_320 * Math.cos((35.8585 * Math.PI) / 180)) / 1000) });
    expect(Math.abs(b.x - a.x - 1000)).toBeLessThan(3);
  });
});
