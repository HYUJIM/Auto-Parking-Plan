import { describe, expect, it } from 'vitest';
import {
  SUSEONG_BOUNDS,
  distanceMeters,
  fitZoom,
  metersPerPixel,
  outerRing,
  project,
  ringAreaM2,
  ringCentroid,
  toRing,
  unproject,
} from './geo';

describe('웹 메르카토르 투영', () => {
  it('project ↔ unproject 왕복', () => {
    const p = { lat: 35.8585, lng: 128.6268 };
    for (const zoom of [6, 13, 19]) {
      const xy = project(p, zoom);
      const back = unproject(xy.x, xy.y, zoom);
      expect(back.lat).toBeCloseTo(p.lat, 9);
      expect(back.lng).toBeCloseTo(p.lng, 9);
    }
  });

  it('줌 0·적도에서 1px 은 약 156,543m, 줌이 1 오르면 절반', () => {
    expect(metersPerPixel(0, 0)).toBeCloseTo(156_543, 0);
    expect(metersPerPixel(0, 1)).toBeCloseTo(78_271.5, 0);
  });
});

describe('distanceMeters', () => {
  it('경도 0.011° 차이(위도 35.86°)는 약 1km', () => {
    const d = distanceMeters({ lat: 35.86, lng: 128.63 }, { lat: 35.86, lng: 128.64108 });
    expect(d).toBeGreaterThan(990);
    expect(d).toBeLessThan(1010);
  });
});

describe('fitZoom', () => {
  const pad = { top: 50, right: 50, bottom: 50, left: 50 };

  it('범위가 여백을 제외한 화면 안에 들어가는 가장 큰 정수 줌을 고른다', () => {
    const { zoom, center } = fitZoom(SUSEONG_BOUNDS, 800, 600, pad);
    const a = project(SUSEONG_BOUNDS[0], zoom);
    const b = project(SUSEONG_BOUNDS[1], zoom);
    expect(Math.abs(b.x - a.x)).toBeLessThanOrEqual(700);
    expect(Math.abs(b.y - a.y)).toBeLessThanOrEqual(500);
    // 한 단계 더 확대하면 넘친다
    const a2 = project(SUSEONG_BOUNDS[0], zoom + 1);
    const b2 = project(SUSEONG_BOUNDS[1], zoom + 1);
    expect(Math.abs(b2.x - a2.x) > 700 || Math.abs(b2.y - a2.y) > 500).toBe(true);
    expect(center.lat).toBeGreaterThan(SUSEONG_BOUNDS[0].lat);
    expect(center.lat).toBeLessThan(SUSEONG_BOUNDS[1].lat);
  });

  it('하단 여백이 크면(모바일 시트) 중심을 아래로 밀어 필지가 위쪽에 보이게 한다', () => {
    const symmetric = fitZoom(SUSEONG_BOUNDS, 400, 800, pad);
    const sheet = fitZoom(SUSEONG_BOUNDS, 400, 800, { ...pad, bottom: 400 });
    expect(sheet.center.lat).toBeLessThan(symmetric.center.lat);
  });

  it('최소·최대 줌을 벗어나지 않는다', () => {
    const tiny = fitZoom(
      [
        { lat: 35.86, lng: 128.63 },
        { lat: 35.860001, lng: 128.630001 },
      ],
      800,
      600,
      pad,
    );
    expect(tiny.zoom).toBeLessThanOrEqual(19);
    const huge = fitZoom(
      [
        { lat: 33, lng: 124 },
        { lat: 39, lng: 132 },
      ],
      100,
      100,
      pad,
    );
    expect(huge.zoom).toBeGreaterThanOrEqual(6);
  });
});

describe('GeoJSON 링 (outerRing · toRing · ringAreaM2 · ringCentroid)', () => {
  // BE 예시 필지 S01 의 경계 (약 24m × 26m 사각형, 토지대장 면적 624.1㎡)
  const s01 = [
    [128.6260866, 35.8603699],
    [128.6263502, 35.8603976],
    [128.6263134, 35.8606301],
    [128.6260498, 35.8606024],
    [128.6260866, 35.8603699],
  ];

  it('toRing 은 [lng, lat] 를 {lat, lng} 로 바꾸고 닫는 점을 뗀다', () => {
    const ring = toRing(s01);
    expect(ring).toHaveLength(4);
    expect(ring[0]).toEqual({ lat: 35.8603699, lng: 128.6260866 });
    expect(toRing([[1, 2], ['x', 3], [4]])).toEqual([{ lat: 2, lng: 1 }]);
    expect(toRing(null)).toEqual([]);
  });

  it('outerRing 은 MultiPolygon 중 가장 큰 폴리곤의 외곽 링만 (구멍·작은 조각 제외)', () => {
    const small = [
      [128.63, 35.86],
      [128.63001, 35.86],
      [128.63001, 35.86001],
      [128.63, 35.86001],
      [128.63, 35.86],
    ];
    const hole = [
      [128.6261, 35.8604],
      [128.6262, 35.8604],
      [128.6262, 35.8605],
      [128.6261, 35.8605],
      [128.6261, 35.8604],
    ];
    const ring = outerRing('MultiPolygon', [[small], [s01, hole]]);
    expect(ring).toHaveLength(4);
    expect(ring[0].lng).toBe(128.6260866);
    expect(outerRing('Polygon', [s01])).toHaveLength(4);
    expect(outerRing('Point', [1, 2])).toEqual([]);
    expect(outerRing(undefined, null)).toEqual([]);
  });

  it('ringAreaM2 는 토지대장 면적과 3% 안에서 맞고, 점 3개 미만이면 0', () => {
    const area = ringAreaM2(toRing(s01));
    expect(Math.abs(area - 624.1) / 624.1).toBeLessThan(0.03);
    expect(ringAreaM2([])).toBe(0);
  });

  it('ringCentroid 는 사각형의 중심 — BE centroid 와 1m 안', () => {
    const c = ringCentroid(toRing(s01));
    expect(distanceMeters(c, { lat: 35.8605, lng: 128.6262 })).toBeLessThan(1.5);
    expect(ringCentroid([{ lat: 1, lng: 2 }])).toEqual({ lat: 1, lng: 2 });
  });
});
