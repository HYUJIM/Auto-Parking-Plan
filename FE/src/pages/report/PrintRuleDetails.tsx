import type { BasisNode, RuleReview } from '@/lib/api/types';
import { summaryLabel } from '@/lib/report/summary';
import { content } from '@/lib/content';
import { Badge } from '@/components/Badge';
import { StatusBadge } from '@/components/RuleItem';
import styles from './PrintRuleDetails.module.css';

/** 근거 문서 이름 + 조항 — 같은 줄은 한 번만 */
function basisLines(nodes: BasisNode[]): string[] {
  const out: string[] = [];
  for (const b of nodes) {
    const line = [b.documentTitle, b.articleLabel].filter(Boolean).join(' ');
    if (line && !out.includes(line)) out.push(line);
  }
  return out;
}

/**
 * 인쇄(PDF) 2쪽 '법규 검토 상세' — 항목마다 제목 · 상태 · 기준값 · 요약 문장(출처 이름: 원문 인용 요약 / AI 요약(검증 통과)) · 근거 문서,
 * 이어서 별도 확인 항목. 화면에서는 각 항목의 '상세 보기' 페이지가 같은 내용을 더 자세히 보여 주므로 숨기고 인쇄 때만 나옵니다.
 */
export function PrintRuleDetails({ review }: { review: RuleReview | null }) {
  if (!review?.supported || (review.items.length === 0 && review.manual.length === 0)) return null;
  const t = content.report.print;
  const r = content.site.rules;
  return (
    <section className={styles.detail} aria-label={t.detailTitle}>
      <header className={styles.header}>
        <h2 className={styles.h2}>{t.detailTitle}</h2>
        <p className={styles.desc}>{t.detailDesc}</p>
      </header>

      <ol className={styles.items}>
        {review.items.map((item) => {
          const basis = basisLines(item.basis);
          return (
            <li key={item.id} className={styles.item}>
              <div className={styles.head}>
                <strong className={styles.title}>{item.title}</strong>
                <StatusBadge status={item.status} />
              </div>
              <p>
                <span className={styles.label}>{t.criteria}</span>
                {item.criteria ?? r.none}
              </p>
              {item.summary ? (
                <div>
                  <span className={styles.label}>{summaryLabel(item.summary.source)}</span>
                  <ul className={styles.sentences}>
                    {item.summary.sentences.map((s, i) => (
                      <li key={i}>{s.text}</li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className={styles.muted}>{t.noSummary}</p>
              )}
              <div>
                <span className={styles.label}>{t.basis}</span>
                {basis.length ? (
                  <span className={styles.basis}>{basis.join(' · ')}</span>
                ) : (
                  <span className={styles.muted}>{t.basisEmpty}</span>
                )}
              </div>
            </li>
          );
        })}
      </ol>

      {review.manual.length > 0 && (
        <>
          <h3 className={styles.h3}>{content.report.rules.manualTitle}</h3>
          <ol className={styles.items}>
            {review.manual.map((m) => (
              <li key={m.id} className={styles.item}>
                <div className={styles.head}>
                  <strong className={styles.title}>{m.title}</strong>
                  <Badge variant="manual">{content.status.manual}</Badge>
                </div>
                {m.why && <p>{m.why}</p>}
                {m.whatToCheck.length > 0 && (
                  <p>
                    <span className={styles.label}>{t.whatToCheck}</span>
                    {m.whatToCheck.join(' · ')}
                  </p>
                )}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}
