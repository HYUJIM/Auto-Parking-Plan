import type { ReactNode } from 'react';
import type { IconName } from '@/design/icons';
import { cx } from '@/lib/cx';
import { Icon } from './Icon';
import styles from './Badge.module.css';

export type BadgeVariant = 'ok' | 'check' | 'manual' | 'info' | 'primary' | 'neutral' | 'danger';

interface BadgeProps {
  variant?: BadgeVariant;
  icon?: IconName;
  className?: string;
  children: ReactNode;
}

/** 상태 배지: 적합(ok) · 확인 필요(check) · 별도 확인(manual) 등 */
export function Badge({ variant = 'neutral', icon, className, children }: BadgeProps) {
  return (
    <span className={cx(styles.badge, styles[variant], className)}>
      {icon && <Icon name={icon} size={14} />}
      {children}
    </span>
  );
}
