import type { ReactNode } from 'react';
import type { IconName } from '@/design/icons';
import { describeApiError } from '@/lib/api/errors';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import styles from './ReportNotice.module.css';

interface ReportNoticeProps {
  icon?: IconName;
  /** info: 안내 · warning: 확인 필요 · danger: 건설 불가 · loading: 진행 중 */
  tone?: 'info' | 'warning' | 'danger' | 'loading';
  title?: ReactNode;
  children?: ReactNode;
  /** 오른쪽 아래 버튼 자리 */
  action?: ReactNode;
  className?: string;
  role?: 'alert' | 'status' | 'note';
}

/** 결과 리포트 카드 안의 안내 상자 (미지원 지역 · 경계 없음 · 계산하지 않음 · 진행 중 · 오류) */
export function ReportNotice({ icon, tone = 'info', title, children, action, className, role }: ReportNoticeProps) {
  const name = icon ?? (tone === 'loading' ? 'loader' : tone === 'info' ? 'info' : 'alert-circle');
  return (
    <div className={cx(styles.notice, styles[tone], className)} role={role} aria-live={tone === 'loading' ? 'polite' : undefined}>
      <Icon name={name} size={20} className={cx(styles.icon, tone === 'loading' && styles.spin)} />
      <div className={styles.body}>
        {title && <strong className={styles.title}>{title}</strong>}
        {children}
        {action && <div className={styles.action}>{action}</div>}
      </div>
    </div>
  );
}

/** 요청 오류 — 화면 문구는 describeApiError 가 정하고, 다시 시도가 의미 있는 오류에만 버튼을 둡니다 */
export function ErrorNotice({
  error,
  onRetry,
  lead,
  className,
}: {
  error: unknown;
  onRetry?: () => void;
  lead?: string;
  className?: string;
}) {
  const { title, desc, retryable } = describeApiError(error);
  return (
    <ReportNotice
      tone="warning"
      role="alert"
      title={title}
      className={className}
      action={
        retryable && onRetry ? (
          <Button variant="secondary" size="sm" iconLeft="refresh" onClick={onRetry}>
            {content.status.retry}
          </Button>
        ) : undefined
      }
    >
      {lead && <p>{lead}</p>}
      {desc && <p>{desc}</p>}
    </ReportNotice>
  );
}
