import type { CSSProperties } from 'react';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import {
  CAR_PATH,
  GATE,
  PARCEL_POINTS,
  SCENE,
  STALL,
  dotGrid,
  ringArcs,
  ringTicks,
  stallLayout,
  visibleCount,
  type ArcTone,
  type LayoutKind,
} from './sceneGeometry';
import styles from './LandingPage.module.css';

const TICKS = ringTicks();
const ARCS = ringArcs();
const DOTS = dotGrid();
const STALLS = stallLayout(0);

const ARC_CLASS: Record<ArcTone, string> = {
  glow: styles.arcGlow,
  amber: styles.arcAmber,
  accent: styles.arcAccent,
  mint: styles.arcMint,
};

/**
 * 첫 자리(정지 화면). anime.js 가 이어서 움직일 수 있도록 변환 함수를 하나씩 풀어 씁니다 —
 * translate(x, y) 단축형으로 쓰면 anime.js 가 translateX/Y 를 따로 덧붙여 자리가 두 번 더해집니다.
 */
function pose(x: number, y: number, rotate: number): CSSProperties {
  return { transform: `translateX(${x}px) translateY(${y}px) rotate(${rotate}deg)` };
}

interface Callout {
  key: keyof typeof content.landing.hero.callouts;
  /** 지시선: 글자 옆 → 꺾이는 점 → 가리키는 곳 */
  line: string;
  dot: [number, number];
  text: [number, number];
  anchor: 'start' | 'end';
}

const CALLOUTS: Callout[] = [
  { key: 'parcel', line: '98,38 150,38 196,214', dot: [196, 214], text: [16, 34], anchor: 'start' },
  { key: 'stall', line: '482,38 458,38 417,242', dot: [417, 242], text: [624, 34], anchor: 'end' },
  { key: 'aisle', line: '98,600 150,600 228,322', dot: [228, 322], text: [16, 596], anchor: 'start' },
  { key: 'gate', line: '566,600 500,600 324,434', dot: [GATE.x, GATE.y], text: [624, 596], anchor: 'end' },
];

/**
 * 히어로 그림 — 눈금 고리 안에 대지·도로·주차구획을 그린 도면.
 * 마크업은 "배치안 A 가 그려진 정지 화면"이고, 움직임은 useLandingMotion 이 data-anim 표식을 찾아 입힙니다.
 * (모션을 줄이도록 설정한 사용자는 이 정지 화면을 그대로 봅니다.)
 */
export function HeroScene({ layout }: { layout: LayoutKind }) {
  const t = content.landing.hero;
  const current = t.layouts[layout];

  return (
    <div className={styles.scene} data-anim="scene">
      <svg viewBox={`0 0 ${SCENE.size} ${SCENE.size}`} className={styles.sceneSvg} role="img" aria-label={t.sceneLabel}>
        <defs>
          <clipPath id="landing-scene-clip">
            <circle cx={SCENE.center} cy={SCENE.center} r={258} />
          </clipPath>
        </defs>

        <g data-anim="arcs" className={styles.spin}>
          {ARCS.map((a) => (
            <path key={a.tone} data-anim="arc" d={a.d} className={cx(styles.arc, ARC_CLASS[a.tone])} />
          ))}
        </g>
        <g data-anim="ring" className={styles.spin}>
          {TICKS.map((k, i) => (
            <line
              key={i}
              data-anim="tick"
              x1={k.x1}
              y1={k.y1}
              x2={k.x2}
              y2={k.y2}
              className={cx(styles.tick, k.major && styles.tickMajor)}
            />
          ))}
        </g>
        <circle className={styles.ringLine} cx={SCENE.center} cy={SCENE.center} r={262} />

        <g clipPath="url(#landing-scene-clip)">
          {DOTS.map((d, i) => (
            <circle key={i} data-anim="dot" className={styles.dot} cx={d.x} cy={d.y} r={1.8} />
          ))}
          <rect data-anim="road" className={styles.road} x={40} y={458} width={560} height={46} />
          <line data-anim="road" className={styles.roadLine} x1={40} y1={481} x2={600} y2={481} />
        </g>

        <polygon data-anim="parcel-fill" className={styles.parcelFill} points={PARCEL_POINTS} />
        <rect data-anim="aisle" className={styles.aisle} x={206} y={296} width={240} height={48} rx={4} />
        <line data-anim="aisle" className={styles.aisleLine} x1={214} y1={320} x2={438} y2={320} />
        <polygon data-anim="parcel" className={styles.parcel} points={PARCEL_POINTS} />

        {STALLS.map((s, i) => (
          <rect
            key={i}
            data-anim="stall"
            className={styles.stall}
            x={-STALL.w / 2}
            y={-STALL.h / 2}
            width={STALL.w}
            height={STALL.h}
            rx={2.5}
            style={pose(s.x, s.y, s.rotate)}
          />
        ))}

        <path data-anim="car-path" d={CAR_PATH} className={styles.carPath} />
        <g data-anim="car" style={pose(414, 320, 0)}>
          <rect className={styles.carBody} x={-13} y={-7} width={26} height={14} rx={4} />
          <rect className={styles.carGlass} x={1} y={-5} width={7} height={10} rx={2} />
        </g>

        <circle data-anim="gate-halo" className={styles.gateHalo} cx={GATE.x} cy={GATE.y} r={7} />
        <circle data-anim="gate" className={styles.gate} cx={GATE.x} cy={GATE.y} r={5} />

        <g className={styles.callouts}>
          {CALLOUTS.map((c) => (
            <g key={c.key}>
              <polyline data-anim="callout-line" className={styles.calloutLine} points={c.line} />
              <circle data-anim="callout-text" className={styles.calloutDot} cx={c.dot[0]} cy={c.dot[1]} r={2.5} />
              <text data-anim="callout-text" className={styles.calloutTag} x={c.text[0]} y={c.text[1]} textAnchor={c.anchor}>
                {t.callouts[c.key].tag}
              </text>
              <text data-anim="callout-text" className={styles.calloutText} x={c.text[0]} y={c.text[1] + 17} textAnchor={c.anchor}>
                {t.callouts[c.key].text}
              </text>
            </g>
          ))}
        </g>
      </svg>

      <div className={styles.chip} data-anim="chip">
        <span className={styles.chipLabel}>{t.layoutLabel}</span>
        <span className={styles.chipTabs}>
          {t.layouts.map((l, i) => (
            <span key={l.code} className={cx(styles.chipTab, i === layout && styles.chipTabOn)}>
              {l.code}
            </span>
          ))}
        </span>
        <span className={styles.chipText}>
          {current.name} · <b className="numeric">{fmt(t.stalls, { n: visibleCount(layout) })}</b>
        </span>
        <span className={styles.chipSample}>{t.sample}</span>
      </div>
    </div>
  );
}
