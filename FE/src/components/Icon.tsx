import type { CSSProperties } from 'react';
import type { IconName } from '@/design/icons';
import { cx } from '@/lib/cx';
import styles from './Icon.module.css';

interface IconProps {
  name: IconName;
  /** px. 기본 24 */
  size?: number;
  className?: string;
  /** 있으면 스크린리더에 읽히는 아이콘, 없으면 장식용 */
  label?: string;
}

/**
 * public/icons/<name>.svg 를 CSS mask 로 그립니다.
 * 색은 background-color: currentColor → 버튼 글자색을 그대로 따라가고,
 * SVG 파일을 같은 이름으로 바꾸면 새로고침 후 전체에 반영됩니다.
 */
export function Icon({ name, size = 24, className, label }: IconProps) {
  const style = { '--icon-url': `url(/icons/${name}.svg)`, '--icon-px': `${size}px` } as CSSProperties;
  return (
    <span
      className={cx(styles.icon, className)}
      style={style}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    />
  );
}
