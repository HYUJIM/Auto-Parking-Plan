import { describe, expect, it } from 'vitest';
import { layoutShapes, staticView } from './staticView';
import type { LayoutLayers } from '@/lib/api/types';

const SQUARE = [
  { lat: 35.8582, lng: 128.6305 },
  { lat: 35.8582, lng: 128.6315 },
  { lat: 35.8575, lng: 128.6315 },
  { lat: 35.8575, lng: 128.6305 },
];

describe('staticView', () => {
  it('대지가 여백 안에 들어오는 가장 큰 줌을 고르고, 타일이 화면을 빈틈없이 덮는다', () => {
    const v = staticView(SQUARE, { width: 800, height: 600, padding: 48, range: { maxZoom: 19 } });
    expect(v).not.toBeNull();
    if (!v) return;
    const pts = SQUARE.map(v.toPx);
    for (const p of pts) {
      expect(p.x).toBeGreaterThanOrEqual(48 - 1);
      expect(p.x).toBeLessThanOrEqual(800 - 48 + 1);
      expect(p.y).toBeGreaterThanOrEqual(48 - 1);
      expect(p.y).toBeLessThanOrEqual(600 - 48 + 1);
    }
    // 한 단계 더 당기면 들어가지 않아야 최대 줌
    const v2 = staticView(SQUARE, { width: 400, height: 300, padding: 48, range: { maxZoom: 19 } });
    expect(v2!.zoom).toBeLessThanOrEqual(v.zoom);
    // 타일이 (0,0)~(100%,100%) 를 덮는다
    const minLeft = Math.min(...v.tiles.map((t) => t.left));
    const minTop = Math.min(...v.tiles.map((t) => t.top));
    const maxRight = Math.max(...v.tiles.map((t) => t.left + t.size));
    const maxBottom = Math.max(...v.tiles.map((t) => t.top + (t.size * 800) / 600));
    expect(minLeft).toBeLessThanOrEqual(0);
    expect(minTop).toBeLessThanOrEqual(0);
    expect(maxRight).toBeGreaterThanOrEqual(100);
    expect(maxBottom).toBeGreaterThanOrEqual(100);
    expect(v.metersPerPx).toBeGreaterThan(0);
  });

  it('타일 서버 최대 줌을 넘으면 그 줌의 타일을 키워 쓴다', () => {
    const tiny = [
      { lat: 35.8582, lng: 128.6305 },
      { lat: 35.85821, lng: 128.63051 },
    ];
    const v = staticView(tiny, { width: 800, height: 600, range: { maxZoom: 18 } });
    expect(v!.zoom).toBe(19);
    expect(v!.tiles.every((t) => t.z === 18)).toBe(true);
  });

  it('점이 없으면 null', () => {
    expect(staticView([], { width: 10, height: 10 })).toBeNull();
  });
});

describe('layoutShapes', () => {
  it('GeoJSON 레이어를 그리는 순서대로 SVG 도형으로 바꾸고, 잘못된 좌표는 건너뛴다', () => {
    const v = staticView(SQUARE, { width: 800, height: 600 })!;
    const layers: LayoutLayers = {
      type: 'FeatureCollection',
      features: [
        {
          type: 'Feature',
          properties: { layer: 'stall' },
          geometry: {
            type: 'Polygon',
            coordinates: [
              [
                [128.631, 35.858],
                [128.6311, 35.858],
                [128.6311, 35.8579],
                [128.631, 35.858],
              ],
            ],
          },
        },
        { type: 'Feature', properties: { layer: 'gate' }, geometry: { type: 'Point', coordinates: [128.6305, 35.8578] } },
        {
          type: 'Feature',
          properties: { layer: 'usable_area' },
          geometry: {
            type: 'Polygon',
            coordinates: [
              [
                [128.6306, 35.8581],
                [Number.NaN, 1],
                [128.6314, 35.8576],
              ],
            ],
          },
        },
        {
          type: 'Feature',
          properties: { layer: 'gate_opening' },
          geometry: {
            type: 'LineString',
            coordinates: [
              [128.6305, 35.8578],
              [128.6305, 35.8579],
            ],
          },
        },
      ],
    };
    const shapes = layoutShapes(v, layers);
    expect(shapes.map((s) => s.layer)).toEqual(['usable_area', 'stall', 'gate_opening', 'gate']);
    expect(shapes[0].d.match(/[ML]/g)).toHaveLength(2);
    expect(shapes[3].point).toBeDefined();
    expect(layoutShapes(v, null)).toEqual([]);
  });
});
