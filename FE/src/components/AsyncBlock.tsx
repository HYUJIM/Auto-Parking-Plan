import type { ReactNode } from 'react';
import type { AsyncState } from '@/lib/useAsync';
import { describeApiError } from '@/lib/api/errors';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Button } from './Button';
import { Icon } from './Icon';
import styles from './AsyncBlock.module.css';

interface AsyncBlockProps<T> {
  state: AsyncState<T>;
  skeleton?: ReactNode;
  children: (data: T) => ReactNode;
}

/**
 * 데이터 블록 하나의 로딩·오류·성공을 감쌉니다.
 * 공공 API 가 실패해도 "해당 카드만" 다시 시도 버튼을 보여주고 나머지 화면은 그대로 둡니다.
 */
export function AsyncBlock<T>({ state, skeleton, children }: AsyncBlockProps<T>) {
  if (state.status === 'loading' || state.status === 'idle') {
    return <>{skeleton ?? <Skeleton lines={3} />}</>;
  }
  if (state.status === 'error') {
    const { title, desc, retryable } = describeApiError(state.error);
    return (
      <div className={styles.error} role="alert">
        <Icon name="alert-circle" size={22} className={styles.errorIcon} />
        <div className={styles.errorBody}>
          <strong>{title}</strong>
          {desc && <p>{desc}</p>}
        </div>
        {retryable && (
          <Button variant="secondary" size="sm" iconLeft="refresh" onClick={state.reload}>
            {content.status.retry}
          </Button>
        )}
      </div>
    );
  }
  return <>{children(state.data as T)}</>;
}

export function Skeleton({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={cx(styles.skeleton, className)} aria-busy="true" aria-label={content.status.loading}>
      {Array.from({ length: lines }, (_, i) => (
        <span key={i} className={styles.bone} style={{ width: `${100 - (i % 3) * 18}%` }} />
      ))}
    </div>
  );
}
