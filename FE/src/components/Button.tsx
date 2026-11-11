import { forwardRef, type ButtonHTMLAttributes } from 'react';
import type { IconName } from '@/design/icons';
import { cx } from '@/lib/cx';
import { Icon } from './Icon';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'icon';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  iconLeft?: IconName;
  iconRight?: IconName;
  /** variant="icon" 일 때의 아이콘 */
  icon?: IconName;
  /** 가로 꽉 채움 */
  block?: boolean;
  /** 토글이 켜진 상태 (레이어 버튼 등) */
  active?: boolean;
}

const ICON_PX: Record<ButtonSize, number> = { sm: 18, md: 20, lg: 22 };

/**
 * 사이트의 모든 버튼. 종류 4 × 크기 3 × 상태(기본·hover·비활성·로딩).
 * 모양은 토큰(--button-*)에서만 바꿉니다.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    iconLeft,
    iconRight,
    icon,
    block,
    active,
    className,
    children,
    disabled,
    type = 'button',
    ...rest
  },
  ref,
) {
  const px = ICON_PX[size];
  const isIconOnly = variant === 'icon';

  return (
    <button
      ref={ref}
      type={type}
      className={cx(
        styles.button,
        styles[variant],
        styles[size],
        block && styles.block,
        active && styles.active,
        loading && styles.loading,
        className,
      )}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      aria-pressed={active}
      {...rest}
    >
      {loading && <Icon name="loader" size={px} className={styles.spinner} />}
      <span className={styles.content}>
        {isIconOnly ? (
          <Icon name={icon ?? iconLeft ?? 'info'} size={px} />
        ) : (
          <>
            {iconLeft && <Icon name={iconLeft} size={px} />}
            {children != null && <span className={styles.label}>{children}</span>}
            {iconRight && <Icon name={iconRight} size={px} />}
          </>
        )}
      </span>
    </button>
  );
});
