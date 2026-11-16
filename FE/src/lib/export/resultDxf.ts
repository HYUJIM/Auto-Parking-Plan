import type { LatLng, LayoutAlternative, Parcel } from '@/lib/api/types';
import { toTm, type TmPoint } from './tm';
import { writeDxf, type DxfEntity, type DxfLayer } from './dxf';

/**
 * 결과 → CAD 도면(DXF). 좌표는 EPSG:5186(중부원점 TM, m) 이라 지적도·현황 측량 도면 위에 그대로 겹칩니다.
 * 계층: 대지 경계 · 가용 영역 · 차로 · 주차구획 · 출입구 · 글자(제목·좌표계 안내).
 */
export const DXF_LAYERS: DxfLayer[] = [
  { name: 'SITE_BOUNDARY', color: 5 },
  { name: 'USABLE_AREA', color: 8 },
  { name: 'AISLE', color: 9 },
  { name: 'STALL', color: 4 },
  { name: 'GATE', color: 2 },
  { name: 'ANNOTATION', color: 7 },
];

const LAYER_OF: Record<string, string> = {
  usable_area: 'USABLE_AREA',
  aisle: 'AISLE',
  stall: 'STALL',
  gate_opening: 'GATE',
  gate: 'GATE',
};

function lngLatToTm(raw: unknown): TmPoint | null {
  if (!Array.isArray(raw) || raw.length < 2) return null;
  const [lng, lat] = raw;
  if (typeof lng !== 'number' || typeof lat !== 'number' || !Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  return toTm({ lat, lng });
}

/** GeoJSON 링(닫는 점 포함 가능) → 열린 TM 점 목록 */
function ring(raw: unknown): TmPoint[] {
  if (!Array.isArray(raw)) return [];
  const pts = raw.map(lngLatToTm).filter((p): p is TmPoint => p !== null);
  if (pts.length > 1) {
    const a = pts[0];
    const b = pts[pts.length - 1];
    if (Math.abs(a.x - b.x) < 1e-6 && Math.abs(a.y - b.y) < 1e-6) pts.pop();
  }
  return pts;
}

export interface ResultDxfInput {
  parcel: Pick<Parcel, 'pnu' | 'polygon' | 'address' | 'area'>;
  alternative?: Pick<LayoutAlternative, 'code' | 'stallCount' | 'layers'> | null;
  /** 도면 제목 줄 (예: '대구광역시 수성구 … · 배치안 A · 주차 24면') */
  title: string;
  /** 좌표계·출처 안내 줄들 */
  notes: string[];
}

export function buildResultDxf(input: ResultDxfInput): string {
  const entities: DxfEntity[] = [];
  const boundary = input.parcel.polygon.map((p: LatLng) => toTm(p));
  if (boundary.length >= 3) entities.push({ kind: 'polyline', layer: 'SITE_BOUNDARY', points: boundary, closed: true });

  for (const f of input.alternative?.layers.features ?? []) {
    const layer = LAYER_OF[String(f.properties?.layer ?? '')];
    if (!layer) continue;
    const g = f.geometry;
    if (g.type === 'Polygon') {
      for (const r of g.coordinates) {
        const pts = ring(r);
        if (pts.length >= 3) entities.push({ kind: 'polyline', layer, points: pts, closed: true });
      }
    } else if (g.type === 'MultiPolygon') {
      for (const poly of g.coordinates)
        for (const r of poly) {
          const pts = ring(r);
          if (pts.length >= 3) entities.push({ kind: 'polyline', layer, points: pts, closed: true });
        }
    } else if (g.type === 'LineString') {
      const pts = g.coordinates.map(lngLatToTm).filter((p): p is TmPoint => p !== null);
      if (pts.length >= 2) entities.push({ kind: 'polyline', layer, points: pts, closed: false });
    } else if (g.type === 'Point') {
      const p = lngLatToTm(g.coordinates);
      if (p) entities.push({ kind: 'circle', layer, center: p, radius: 0.6 });
    }
  }

  // 제목·안내: 대지 경계 아래쪽에 (도면 크기에 맞춘 글자 높이)
  if (boundary.length > 0) {
    const minX = Math.min(...boundary.map((p) => p.x));
    const maxX = Math.max(...boundary.map((p) => p.x));
    const minY = Math.min(...boundary.map((p) => p.y));
    const h = Math.min(3, Math.max(0.8, (maxX - minX) / 40));
    const lines = [input.title, ...input.notes];
    lines.forEach((text, i) =>
      entities.push({
        kind: 'text',
        layer: 'ANNOTATION',
        at: { x: minX, y: minY - h * 2.2 * (i + 1) },
        height: i === 0 ? h * 1.2 : h,
        text,
      }),
    );
  }
  return writeDxf(DXF_LAYERS, entities);
}
