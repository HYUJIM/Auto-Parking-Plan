import { Fragment } from 'react';
import type { IconName } from '@/design/icons';
import type { ReportStep, StepState } from '@/lib/report/steps';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Icon } from '@/components/Icon';
import styles from './StepIndicator.module.css';

const ICON: Record<StepState, IconName | null> = {
  done: 'check',
  running: 'loader',
  pending: null,
  failed: 'alert-circle',
  skipped: 'minus',
};

/** 진행 단계 (대지 입력 › 자동 배치 › 법규 검토 › 결과 리포트) — 각 단계의 상태는 실제 요청 상태에서 옵니다 (lib/report/steps.ts) */
export function StepIndicator({ steps }: { steps: ReportStep[] }) {
  const t = content.report.steps;
  return (
    <ol className={styles.steps} aria-label={t.label}>
      {steps.map((s, i) => {
        const icon = ICON[s.state];
        return (
          <Fragment key={s.id}>
            {i > 0 && (
              <li className={styles.sep} aria-hidden="true">
                <Icon name="chevron-right" size={14} />
              </li>
            )}
            <li className={cx(styles.step, styles[s.state])} aria-current={s.state === 'running' ? 'step' : undefined}>
              <span className={cx(styles.mark, 'numeric')} aria-hidden="true">
                {icon ? <Icon name={icon} size={13} className={s.state === 'running' ? styles.spin : undefined} /> : i + 1}
              </span>
              <span className={styles.label}>{s.label}</span>
              <span className="visually-hidden">{t.states[s.state]}</span>
            </li>
          </Fragment>
        );
      })}
    </ol>
  );
}
