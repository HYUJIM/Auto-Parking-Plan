import { useId, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import styles from './ReportCard.module.css';

interface ReportCardProps {
  /** 번호 배지 ('01') — 없으면 제목만 */
  no?: string;
  title: string;
  /** 제목 옆 한 줄 설명 */
  desc?: string;
  /** 머리 띠 색: 어두운 띠(현황) · 메인색 띠(배치 결과) · 밝은 띠(오른쪽 표·목록) */
  tone?: 'dark' | 'primary' | 'plain';
  /** 머리 띠 오른쪽 (칩·버튼) */
  aside?: ReactNode;
  className?: string;
  bodyClassName?: string;
  children: ReactNode;
}

/** 결과 리포트의 카드 한 장 — 머리 띠(번호 배지 + 제목 + 설명) + 본문. 인쇄 때 쪽 중간에서 잘리지 않습니다. */
export function ReportCard({ no, title, desc, tone = 'plain', aside, className, bodyClassName, children }: ReportCardProps) {
  const titleId = useId();
  return (
    <section className={cx(styles.card, className)} aria-labelledby={titleId}>
      <header className={cx(styles.head, styles[tone])}>
        {no && (
          <span className={cx(styles.no, 'numeric')} aria-hidden="true">
            {no}
          </span>
        )}
        <span className={styles.titles}>
          <h2 id={titleId} className={styles.title}>
            {title}
          </h2>
          {desc && <span className={styles.desc}>{desc}</span>}
        </span>
        {aside && <span className={styles.aside}>{aside}</span>}
      </header>
      <div className={cx(styles.body, bodyClassName)}>{children}</div>
    </section>
  );
}
