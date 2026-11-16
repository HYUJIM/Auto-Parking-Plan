import { describe, expect, it } from 'vitest';
import { DXF_LAYERS, buildResultDxf } from './resultDxf';
import { toTm } from './tm';
import type { LayoutLayers } from '@/lib/api/types';
import type { BeParkingSimulation } from '@/lib/api/beTypes';
import { toParkingSimulation } from '@/lib/api/beAdapters';
import simulationSplit from '@/lib/api/__fixtures__/simulation_buildings_split.json';

const polygon = [
  { lat: 35.8582, lng: 128.6305 },
  { lat: 35.8582, lng: 128.6309 },
  { lat: 35.8579, lng: 128.6309 },
  { lat: 35.8579, lng: 128.6305 },
];

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
            [128.6306, 35.8581],
            [128.63063, 35.8581],
            [128.63063, 35.85805],
            [128.6306, 35.85805],
            [128.6306, 35.8581],
          ],
        ],
      },
    },
    {
      type: 'Feature',
      properties: { layer: 'aisle' },
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [128.6306, 35.858],
            [128.6308, 35.858],
            [128.6308, 35.85795],
            [128.6306, 35.858],
          ],
        ],
      },
    },
    { type: 'Feature', properties: { layer: 'gate' }, geometry: { type: 'Point', coordinates: [128.6305, 35.858] } },
    {
      type: 'Feature',
      properties: { layer: 'gate_opening' },
      geometry: {
        type: 'LineString',
        coordinates: [
          [128.6305, 35.858],
          [128.6305, 35.85805],
        ],
      },
    },
    { type: 'Feature', properties: { layer: 'unknown' as never }, geometry: { type: 'Point', coordinates: [128.6305, 35.858] } },
  ],
};

function entityLayers(dxf: string, kind: string): string[] {
  const lines = dxf.split('\n');
  const out: string[] = [];
  for (let i = 0; i < lines.length - 3; i += 2) {
    if (lines[i] === '0' && lines[i + 1] === kind) {
      const at = lines.indexOf('8', i + 2);
      out.push(lines[at + 1]);
    }
  }
  return out;
}

describe('buildResultDxf', () => {
  it('대지 경계·배치 요소를 계층별로, TM 좌표(m)로 쓴다', () => {
    const dxf = buildResultDxf({
      parcel: { pnu: '2726010100100010000', polygon, address: { jibun: '범어동 1' }, area: 1000 },
      alternative: { code: 'ALT_A', stallCount: 1, layers },
      title: '범어동 1 · 배치안 A',
      notes: ['EPSG:5186'],
    });
    expect(entityLayers(dxf, 'POLYLINE')).toEqual(['SITE_BOUNDARY', 'STALL', 'AISLE', 'GATE']);
    expect(entityLayers(dxf, 'CIRCLE')).toEqual(['GATE']);
    expect(entityLayers(dxf, 'TEXT')).toEqual(['ANNOTATION', 'ANNOTATION']);
    // 첫 꼭짓점 = 경계 첫 점의 TM 좌표
    const first = toTm(polygon[0]);
    expect(dxf).toContain(`10\n${first.x.toFixed(3)}\n20\n${first.y.toFixed(3)}`);
    // 닫는 점은 빼고 닫힘 표시로 (주차구획 링 5점 → 꼭짓점 4개)
    const stallStart = dxf.indexOf('8\nSTALL');
    const stallEnd = dxf.indexOf('SEQEND', stallStart);
    expect(dxf.slice(stallStart, stallEnd).split('VERTEX').length - 1).toBe(4);
  });

  it('기존 건물은 EXISTING_BUILDING 계층에 링마다 닫힌 폴리선, 건물로 나뉜 대지의 출입구는 점마다 원 하나', () => {
    const sim = toParkingSimulation(simulationSplit as unknown as BeParkingSimulation);
    const [split] = sim.result!.alternatives;
    const dxf = buildResultDxf({
      parcel: { pnu: '2726010200190020003', polygon, address: { jibun: '범어동 1' }, area: 1000 },
      alternative: split,
      buildings: sim.existingBuildings!.footprints,
      title: '범어동 1 · 배치안 A',
      notes: ['EPSG:5186', '기존 건물: 피해서 배치'],
    });
    const polylines = entityLayers(dxf, 'POLYLINE');
    // 건물 2동 (Polygon 1 + MultiPolygon 1) → 링 2개
    expect(polylines.filter((l) => l === 'EXISTING_BUILDING')).toHaveLength(2);
    expect(polylines.slice(0, 3)).toEqual(['SITE_BOUNDARY', 'EXISTING_BUILDING', 'EXISTING_BUILDING']);
    // 가용 영역 MultiPolygon 2조각, 개구부 MultiLineString 2줄
    expect(polylines.filter((l) => l === 'USABLE_AREA')).toHaveLength(2);
    expect(polylines.filter((l) => l === 'GATE')).toHaveLength(2);
    expect(entityLayers(dxf, 'CIRCLE')).toEqual(['GATE', 'GATE']);
    expect(entityLayers(dxf, 'TEXT')).toHaveLength(3);
    // 계층 표: 쓰지 않는 색 번호(251)
    const building = DXF_LAYERS.find((l) => l.name === 'EXISTING_BUILDING')!;
    expect(DXF_LAYERS.filter((l) => l.color === building.color)).toHaveLength(1);
    expect(dxf).toContain('2\nEXISTING_BUILDING\n70\n0\n62\n251');
  });

  it('배치안 없이도 경계와 제목만으로 도면을 만든다', () => {
    const dxf = buildResultDxf({
      parcel: { pnu: '2726010100100010000', polygon, address: { jibun: '범어동 1' }, area: 1000 },
      title: '범어동 1',
      notes: [],
    });
    expect(entityLayers(dxf, 'POLYLINE')).toEqual(['SITE_BOUNDARY']);
    expect(dxf.trimEnd().endsWith('0\nEOF')).toBe(true);
  });
});
