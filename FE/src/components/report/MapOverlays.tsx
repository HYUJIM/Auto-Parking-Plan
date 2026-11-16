import type { ReactNode } from 'react';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import type { IconName } from '@/design/icons';
import { Icon } from '@/components/Icon';
import styles from './MapOverlays.module.css';

/**
 * 정지 지도(StaticMap) 위에 얹는 표시들 — 방위표 · 대지면적 말풍선 · 지역 칩 · 수치 상자 · 경고 배지.
 * 지도는 위가 북쪽(웹 메르카토르)이라 방위표는 늘 위를 가리킵니다.
 */

export function NorthArrow() {
  return (
    <span className={cx(styles.overlay, styles.topLeft, styles.north)} aria-hidden="true">
      <svg viewBox="0 0 24 24" width="18" height="18" className={styles.northIcon}>
        <path d="M12 2 L18 20 L12 16 L6 20 Z" />
      </svg>
      <span className={styles.northLabel}>{content.map.north}</span>
    </span>
  );
}

/** 대지면적 말풍선 (오른쪽 위) */
export function MapBubble({ children }: { children: ReactNode }) {
  return <span className={cx(styles.overlay, styles.topRight, styles.bubble, 'numeric')}>{children}</span>;
}

/** 왼쪽 아래 칩 (지역 · 위성 지도) */
export function MapChip({ icon, children }: { icon?: IconName; children: ReactNode }) {
  return (
    <span className={cx(styles.overlay, styles.bottomLeft, styles.chip)}>
      {icon && <Icon name={icon} size={13} />}
      {children}
    </span>
  );
}

export interface MapStat {
  label: string;
  value: string;
  unit?: string;
  hint?: string;
}

/** 오른쪽 위 수치 상자 (주차 가능 대수 · 주차 효율) */
export function MapStatBox({ stats }: { stats: MapStat[] }) {
  return (
    <dl className={cx(styles.overlay, styles.topRight, styles.stats)}>
      {stats.map((s) => (
        <div key={s.label} className={styles.stat} title={s.hint}>
          <dt>{s.label}</dt>
          <dd className="numeric">
            <strong>{s.value}</strong>
            {s.unit && <span className={styles.unit}>{s.unit}</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** 지도 가운데 위 경고 배지 (주차장 건설 불가 지역) */
export function MapAlert({ children }: { children: ReactNode }) {
  return (
    <span className={cx(styles.overlay, styles.topCenter, styles.alert)}>
      <Icon name="alert-circle" size={16} />
      {children}
    </span>
  );
}
