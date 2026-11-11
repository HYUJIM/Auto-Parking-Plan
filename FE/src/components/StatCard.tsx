import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import styles from './StatCard.module.css';

interface StatCardProps {
  label: string;
  value: ReactNode;
  /** 단위는 작게 */
  unit?: string;
  hint?: string;
  /** 숫자 카드면 true — 숫자 글꼴·tabular 적용 */
  numeric?: boolean;
  /** 두 칸 차지 */
  wide?: boolean;
  /** 값·단위 뒤에 작게 붙는 보조 표기 (예: 평 환산) */
  sub?: ReactNode;
}

/** 대지 요약 카드: 숫자는 크게, 단위는 작게 */
export function StatCard({ label, value, unit, hint, numeric, wide, sub }: StatCardProps) {
  return (
    <div className={cx(styles.card, wide && styles.wide)}>
      <span className={styles.label}>
        {label}
        {hint && <span className={styles.hint}>{hint}</span>}
      </span>
      <span className={cx(styles.value, numeric && 'numeric', numeric && styles.numeric)}>
        {value}
        {unit && <span className={styles.unit}>{unit}</span>}
        {sub && <span className={styles.sub}>{sub}</span>}
      </span>
    </div>
  );
}
