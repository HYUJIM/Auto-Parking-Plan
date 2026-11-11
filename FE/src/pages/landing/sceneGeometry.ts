/**
 * 소개 페이지 히어로 장면의 좌표 — 전부 640×640 viewBox 기준의 순수 계산입니다.
 * 그림(HeroScene.tsx)과 모션(useLandingMotion.ts)이 같은 값을 쓰도록 한곳에 둡니다.
 */

export const SCENE = { size: 640, center: 320 } as const;

const r1 = (n: number) => Math.round(n * 10) / 10;

/** 0° = 12시 방향, 시계 방향 */
function polar(radius: number, deg: number): { x: number; y: number } {
  const a = ((deg - 90) * Math.PI) / 180;
  return { x: r1(SCENE.center + Math.cos(a) * radius), y: r1(SCENE.center + Math.sin(a) * radius) };
}

// ── 바깥 눈금 고리 ────────────────────────────────────────────────

export interface Tick {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** 8칸마다 긴 눈금 */
  major: boolean;
}

export function ringTicks(count = 96, inner = 270, outer = 284, majorEvery = 8): Tick[] {
  const out: Tick[] = [];
  for (let i = 0; i < count; i++) {
    const deg = (i / count) * 360;
    const major = i % majorEvery === 0;
    const a = polar(major ? inner - 8 : inner, deg);
    const b = polar(outer, deg);
    out.push({ x1: a.x, y1: a.y, x2: b.x, y2: b.y, major });
  }
  return out;
}

export type ArcTone = 'glow' | 'amber' | 'accent' | 'mint';

export interface Arc {
  d: string;
  tone: ArcTone;
}

/** 고리 바깥의 색 호 4개 (각 74°, 사이 16°) */
export function ringArcs(radius = 300): Arc[] {
  const tones: ArcTone[] = ['glow', 'amber', 'accent', 'mint'];
  return tones.map((tone, i) => {
    const start = polar(radius, i * 90 + 8);
    const end = polar(radius, i * 90 + 82);
    return { tone, d: `M ${start.x} ${start.y} A ${radius} ${radius} 0 0 1 ${end.x} ${end.y}` };
  });
}

// ── 측량 점 격자 ──────────────────────────────────────────────────

export const DOT_GRID = { cols: 13, rows: 13, gap: 30 } as const;

/** 행 우선(왼쪽 위 → 오른쪽 아래) — anime.js 의 stagger({ grid }) 가 이 순서를 가정합니다 */
export function dotGrid(): { x: number; y: number }[] {
  const { cols, rows, gap } = DOT_GRID;
  const x0 = SCENE.center - ((cols - 1) * gap) / 2;
  const y0 = SCENE.center - ((rows - 1) * gap) / 2;
  const out: { x: number; y: number }[] = [];
  for (let row = 0; row < rows; row++) {
    for (let col = 0; col < cols; col++) out.push({ x: x0 + col * gap, y: y0 + row * gap });
  }
  return out;
}

// ── 대지·도로·출입구·차 ───────────────────────────────────────────

/** 대지 경계 (약 28m × 23m 를 9.6px/m 로 그린 사각형) */
export const PARCEL: ReadonlyArray<readonly [number, number]> = [
  [196, 214],
  [452, 200],
  [468, 424],
  [182, 438],
];

export const PARCEL_POINTS = PARCEL.map(([x, y]) => `${x},${y}`).join(' ');

/** 아래쪽 경계 위의 출입구 */
export const GATE = { x: 320, y: 431 } as const;

/** 도로 → 출입구 → 차로. 차가 이 경로를 따라 들어옵니다 */
export const CAR_PATH = 'M 118 481 L 286 481 Q 320 481 320 448 L 320 344 Q 320 320 344 320 L 414 320';

// ── 주차구획 배치 3안 ─────────────────────────────────────────────

/** 0 = A 직각, 1 = B 60° 대향, 2 = C 평행 */
export type LayoutKind = 0 | 1 | 2;
export const LAYOUT_KINDS: readonly LayoutKind[] = [0, 1, 2];

/** 일반형 2.5m × 5m (9.6px/m) */
export const STALL = { w: 24, h: 48, count: 16 } as const;

export interface StallPose {
  /** 구획 중심 */
  x: number;
  y: number;
  /** 도 단위. 0 = 세로로 선 구획 */
  rotate: number;
  /** 이 배치안에서 쓰지 않는 구획은 가까운 구획 자리로 모여 사라집니다 */
  visible: boolean;
}

interface RowSpec {
  y: number;
  rotate: number;
  /** 보이는 구획의 중심 x (왼쪽 → 오른쪽) */
  xs: number[];
}

/** 윗줄 8칸(0–7) · 아랫줄 8칸(8–15). 아랫줄은 출입구(x=320) 자리를 비웁니다 */
const ROWS: Record<LayoutKind, [RowSpec, RowSpec]> = {
  0: [
    { y: 264, rotate: 0, xs: [235, 261, 287, 313, 339, 365, 391, 417] },
    { y: 376, rotate: 0, xs: [211, 237, 263, 289, 351, 377, 403, 429] },
  ],
  1: [
    { y: 262, rotate: 30, xs: [241, 275, 309, 343, 377, 411] },
    { y: 378, rotate: -30, xs: [216, 250, 284, 356, 390, 424] },
  ],
  2: [
    { y: 246, rotate: 90, xs: [245, 299, 353, 407] },
    { y: 394, rotate: 90, xs: [224, 278, 362, 416] },
  ],
};

/** 한 줄(perRow 칸)의 자리. 보이는 구획을 가운데에 두고, 남는 칸은 양 끝의 보이는 구획 자리에 겹쳐 둡니다 */
function rowPoses(row: RowSpec, perRow: number): StallPose[] {
  const lead = Math.floor((perRow - row.xs.length) / 2);
  const out: StallPose[] = [];
  for (let i = 0; i < perRow; i++) {
    const k = i - lead;
    const visible = k >= 0 && k < row.xs.length;
    const x = row.xs[Math.min(row.xs.length - 1, Math.max(0, k))];
    out.push({ x, y: row.y, rotate: row.rotate, visible });
  }
  return out;
}

export function stallLayout(kind: LayoutKind): StallPose[] {
  const [top, bottom] = ROWS[kind];
  const perRow = STALL.count / 2;
  return [...rowPoses(top, perRow), ...rowPoses(bottom, perRow)];
}

export function visibleCount(kind: LayoutKind): number {
  return stallLayout(kind).filter((s) => s.visible).length;
}

/** 구획이 차지하는 축 정렬 범위의 절반 (회전 반영) */
export function stallHalfExtent(rotate: number): { hx: number; hy: number } {
  const a = (Math.abs(rotate) * Math.PI) / 180;
  return {
    hx: (STALL.w * Math.cos(a) + STALL.h * Math.sin(a)) / 2,
    hy: (STALL.w * Math.sin(a) + STALL.h * Math.cos(a)) / 2,
  };
}

/** 점이 대지 경계 안쪽인지 (볼록 사각형, 시계 방향) */
export function insideParcel(x: number, y: number): boolean {
  for (let i = 0; i < PARCEL.length; i++) {
    const [ax, ay] = PARCEL[i];
    const [bx, by] = PARCEL[(i + 1) % PARCEL.length];
    if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0) return false;
  }
  return true;
}

// ── '결과 확인' 단계의 작은 배치 그림 (200×120 viewBox) ───────────

export const MINI_STALL = { w: 14, h: 28, count: 8 } as const;

export function miniLayout(kind: LayoutKind): StallPose[] {
  const rows: Record<LayoutKind, [RowSpec, RowSpec]> = {
    0: [
      { y: 30, rotate: 0, xs: [64, 88, 112, 136] },
      { y: 90, rotate: 0, xs: [64, 88, 112, 136] },
    ],
    1: [
      { y: 30, rotate: 30, xs: [70, 100, 130] },
      { y: 90, rotate: -30, xs: [70, 100, 130] },
    ],
    2: [
      { y: 22, rotate: 90, xs: [82, 118] },
      { y: 98, rotate: 90, xs: [82, 118] },
    ],
  };
  const [top, bottom] = rows[kind];
  const perRow = MINI_STALL.count / 2;
  return [...rowPoses(top, perRow), ...rowPoses(bottom, perRow)];
}
