import type { DataNotice } from '@/lib/api/types';
import { content } from '@/lib/content';
import { Icon } from '@/components/Icon';
import styles from './ReportFootnotes.module.css';

interface ReportFootnotesProps {
  /** 검토·필지 데이터 안내 (중복 제거한 것) */
  notices: DataNotice[];
  /** 고지 문장들 (검토 · 배치 계산 — 같은 문장은 한 번) */
  disclaimers: string[];
  /** 출처 줄들 (필지 출처·조회일 · 기준일·엔진 · 배경 지도) */
  sources: string[];
}

/** 리포트 아래 — 데이터 안내 · 고지 · 출처. 인쇄 때는 2쪽(법규 검토 상세) 뒤에 옵니다 */
export function ReportFootnotes({ notices, disclaimers, sources }: ReportFootnotesProps) {
  const t = content.report.footer;
  const shownDisclaimers = disclaimers.length ? disclaimers : [content.map.disclaimer];
  return (
    <footer className={styles.footnotes}>
      {notices.length > 0 && (
        <section className={styles.block} aria-label={t.notices}>
          <h2 className={styles.h2}>
            <Icon name="info" size={14} />
            {t.notices}
          </h2>
          <ul className={styles.list}>
            {notices.map((n, i) => (
              <li key={`${n.code}:${i}`}>{n.message}</li>
            ))}
          </ul>
        </section>
      )}
      <section className={styles.block} aria-label={t.disclaimer}>
        <h2 className={styles.h2}>
          <Icon name="shield-check" size={14} />
          {t.disclaimer}
        </h2>
        <ul className={styles.list}>
          {shownDisclaimers.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      </section>
      {sources.length > 0 && (
        <section className={styles.block} aria-label={t.sources}>
          <h2 className={styles.h2}>
            <Icon name="book-open" size={14} />
            {t.sources}
          </h2>
          <ul className={styles.sources}>
            {sources.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </section>
      )}
    </footer>
  );
}
