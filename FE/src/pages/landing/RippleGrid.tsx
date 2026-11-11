import { useEffect, useRef, useState, type CSSProperties } from 'react';
import styles from './LandingPage.module.css';

const CELL = 34;
const clamp = (n: number, min: number, max: number) => Math.min(max, Math.max(min, n));

/**
 * 마지막 구역의 점 격자 — 포인터가 지나간 칸에서 물결이 퍼집니다 (useLandingMotion 이 data-cols/rows 를 읽음).
 * 칸 수는 구역 크기에 맞춰 정하되 점이 너무 많아지지 않게 상한을 둡니다.
 */
export function RippleGrid() {
  const ref = useRef<HTMLDivElement>(null);
  const [dims, setDims] = useState({ cols: 0, rows: 0 });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      const cols = clamp(Math.floor(el.clientWidth / CELL), 8, 40);
      const rows = clamp(Math.floor(el.clientHeight / CELL), 4, 12);
      setDims((d) => (d.cols === cols && d.rows === rows ? d : { cols, rows }));
    };
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const style = { '--cols': dims.cols, '--rows': dims.rows } as CSSProperties;
  return (
    <div
      ref={ref}
      className={styles.ripple}
      data-anim="ripple"
      data-cols={dims.cols}
      data-rows={dims.rows}
      style={style}
      aria-hidden="true"
    >
      {Array.from({ length: dims.cols * dims.rows }, (_, i) => (
        <span key={i} data-anim="ripple-dot" className={styles.rippleDot} />
      ))}
    </div>
  );
}
