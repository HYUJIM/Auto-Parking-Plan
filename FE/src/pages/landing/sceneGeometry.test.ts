import { describe, expect, it } from 'vitest';
import {
  DOT_GRID,
  GATE,
  LAYOUT_KINDS,
  MINI_STALL,
  SCENE,
  STALL,
  dotGrid,
  insideParcel,
  miniLayout,
  ringArcs,
  ringTicks,
  stallHalfExtent,
  stallLayout,
  visibleCount,
} from './sceneGeometry';

describe('ringTicks', () => {
  it('고리 위에 고르게 놓이고 8칸마다 긴 눈금이다', () => {
    const ticks = ringTicks();
    expect(ticks).toHaveLength(96);
    expect(ticks.filter((t) => t.major)).toHaveLength(12);
    // 첫 눈금은 12시 방향 (중심 바로 위)
    expect(ticks[0].x1).toBeCloseTo(SCENE.center, 0);
    expect(ticks[0].y2).toBeLessThan(ticks[0].y1);
    for (const t of ticks) {
      const outer = Math.hypot(t.x2 - SCENE.center, t.y2 - SCENE.center);
      expect(outer).toBeCloseTo(284, 0);
    }
  });
});

describe('ringArcs', () => {
  it('색 호 4개가 viewBox 안에 있다', () => {
    const arcs = ringArcs();
    expect(arcs.map((a) => a.tone)).toEqual(['glow', 'amber', 'accent', 'mint']);
    for (const a of arcs) {
      const nums = a.d.match(/-?\d+(\.\d+)?/g)!.map(Number);
      // M x y A r r 0 0 1 x y → 좌표는 0..640
      for (const n of [nums[0], nums[1], nums[7], nums[8]]) {
        expect(n).toBeGreaterThanOrEqual(0);
        expect(n).toBeLessThanOrEqual(SCENE.size);
      }
    }
  });
});

describe('dotGrid', () => {
  it('행 우선 순서의 정사각 격자이고 눈금 고리 안쪽에 들어간다', () => {
    const dots = dotGrid();
    expect(dots).toHaveLength(DOT_GRID.cols * DOT_GRID.rows);
    // 첫 행은 y 가 같고 x 만 늘어난다 (stagger grid 가 가정하는 순서)
    expect(dots[1].y).toBe(dots[0].y);
    expect(dots[1].x - dots[0].x).toBe(DOT_GRID.gap);
    expect(dots[DOT_GRID.cols].y - dots[0].y).toBe(DOT_GRID.gap);
    const farthest = Math.max(...dots.map((d) => Math.hypot(d.x - SCENE.center, d.y - SCENE.center)));
    expect(farthest).toBeLessThan(262);
  });
});

describe('stallLayout', () => {
  it('배치안마다 구획 16개의 자리를 주고, 보이는 수는 A 16 · B 12 · C 8', () => {
    for (const kind of LAYOUT_KINDS) expect(stallLayout(kind)).toHaveLength(STALL.count);
    expect(LAYOUT_KINDS.map(visibleCount)).toEqual([16, 12, 8]);
  });

  it('보이는 구획은 전부 대지 경계 안에 있다', () => {
    for (const kind of LAYOUT_KINDS) {
      for (const s of stallLayout(kind).filter((p) => p.visible)) {
        const { hx, hy } = stallHalfExtent(s.rotate);
        for (const [dx, dy] of [
          [-hx, -hy],
          [hx, -hy],
          [hx, hy],
          [-hx, hy],
        ]) {
          expect(insideParcel(s.x + dx, s.y + dy), `kind ${kind} stall at ${s.x},${s.y}`).toBe(true);
        }
      }
    }
  });

  it('같은 줄의 보이는 구획은 서로 겹치지 않고, 아랫줄은 출입구 자리를 비운다', () => {
    for (const kind of LAYOUT_KINDS) {
      const poses = stallLayout(kind);
      for (const row of [poses.slice(0, 8), poses.slice(8)]) {
        const visible = row.filter((p) => p.visible).sort((a, b) => a.x - b.x);
        for (let i = 1; i < visible.length; i++) {
          // 기울어진 구획도 줄 방향 간격이 폭(24) 이상이면 겹치지 않는다
          expect(visible[i].x - visible[i - 1].x).toBeGreaterThanOrEqual(STALL.w);
        }
      }
      // 차 폭(14)의 절반보다 넉넉하게 비어 있어야 한다
      for (const s of poses.slice(8).filter((p) => p.visible)) {
        const { hx } = stallHalfExtent(s.rotate);
        expect(Math.abs(s.x - GATE.x) - hx).toBeGreaterThan(9);
      }
    }
  });

  it('쓰지 않는 구획은 같은 줄의 보이는 구획 자리로 모인다', () => {
    const b = stallLayout(1);
    expect(b[0].visible).toBe(false);
    expect(b[0].x).toBe(b[1].x);
    expect(b[7].visible).toBe(false);
    expect(b[7].x).toBe(b[6].x);
  });
});

describe('miniLayout', () => {
  it('작은 배치 그림도 8개 자리와 8 · 6 · 4 개가 보인다', () => {
    for (const kind of LAYOUT_KINDS) expect(miniLayout(kind)).toHaveLength(MINI_STALL.count);
    expect(LAYOUT_KINDS.map((k) => miniLayout(k).filter((p) => p.visible).length)).toEqual([8, 6, 4]);
  });
});
