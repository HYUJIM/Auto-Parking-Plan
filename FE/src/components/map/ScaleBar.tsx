import { cx } from '@/lib/cx';
import { formatDistance } from '@/lib/format';
import styles from './ScaleBar.module.css';

const STEPS = [5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000];
const MAX_PX = 140;

/** 축척 바: 현재 줌에서 깔끔한 거리(10 m·50 m·1 km…)를 골라 그 길이만큼 선을 그립니다 */
export function ScaleBar({ metersPerPixel }: { metersPerPixel: number }) {
  if (!Number.isFinite(metersPerPixel) || metersPerPixel <= 0) return null;
  let meters = STEPS[0];
  for (const step of STEPS) {
    if (step / metersPerPixel <= MAX_PX) meters = step;
    else break;
  }
  const px = Math.max(24, Math.round(meters / metersPerPixel));

  return (
    <div className={styles.scale} aria-hidden="true">
      <span className={styles.bar} style={{ width: px }} />
      <span className={cx(styles.label, 'numeric')}>{formatDistance(meters)}</span>
    </div>
  );
}
