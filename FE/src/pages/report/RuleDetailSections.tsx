import { useId, type ReactNode } from 'react';
import type { BasisNode, ManualCheck, RuleItem, RuleSummary } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { sentenceRoleLabel, summaryKind } from '@/lib/ruleDetail/format';
import { ruleCardId } from '@/lib/ruleDetail/params';
import { Badge } from '@/components/Badge';
import { Icon } from '@/components/Icon';
import { AppliedValue, LayerTag } from '@/components/RuleItem';
import styles from './RuleDetailPage.module.css';

/** 법규 상세 페이지의 구역 하나 (카드): 제목 · 오른쪽 표시 · 설명 · 본문 */
export function Section({
  title,
  aside,
  desc,
  className,
  children,
}: {
  title: string;
  aside?: ReactNode;
  desc?: string;
  className?: string;
  children: ReactNode;
}) {
  const headingId = useId();
  return (
    <section className={cx(styles.section, className)} aria-labelledby={headingId}>
      <div className={styles.sectionHead}>
        <h2 id={headingId} className={styles.sectionTitle}>
          {title}
        </h2>
        {aside}
      </div>
      {desc && <p className={styles.sectionDesc}>{desc}</p>}
      {children}
    </section>
  );
}

/** 한눈에 보기: 기준(criteria_text) · 적용값(참고값과 산식 / 용도 목록 / 기준표 — 접지 않고 모두) */
export function OverviewSection({ item }: { item: RuleItem }) {
  const t = content.ruleDetail.overview;
  return (
    <Section title={t.title}>
      <dl className={styles.overview}>
        <div className={styles.overviewRow}>
          <dt>{t.criteria}</dt>
          <dd className={styles.criteria}>{item.criteria ?? content.site.rules.none}</dd>
        </div>
        <div className={styles.overviewRow}>
          <dt>{t.applied}</dt>
          <dd>
            <AppliedValue item={item} expanded />
          </dd>
        </div>
      </dl>
    </Section>
  );
}

/**
 * 요약 — 출처에 따라 제목과 설명이 바뀝니다.
 *  - ai_verified: 'AI 요약' + 검증 통과 배지 · 필수 사실 n/m · 검증일
 *  - template_fallback: '원문 인용 요약' — AI 가 쓴 문장이 아니라고 분명히 밝힙니다
 *  - 없음: emptyText — 아래에 실제로 보여 줄 것(원문 카드 · 근거 인용 · 없음)에 맞춘 문구를 부르는 쪽이 고릅니다
 */
export function SummarySection({ summary, emptyText }: { summary: RuleSummary | null; emptyText?: string }) {
  const t = content.ruleDetail.summary;
  const kind = summaryKind(summary);
  const title = kind === 'ai' ? t.titleAi : kind === 'quote' ? t.titleQuote : t.titleNone;

  if (kind === 'none' || !summary) {
    return (
      <Section title={title}>
        <p className={styles.empty}>{emptyText ?? t.none}</p>
      </Section>
    );
  }

  const mm = summary.mustMention;
  return (
    <Section
      title={title}
      aside={
        kind === 'ai' ? (
          <Badge variant="info" icon="shield-check">
            {t.badgeAi}
          </Badge>
        ) : (
          <Badge variant="neutral" icon="book-open">
            {t.badgeQuote}
          </Badge>
        )
      }
    >
      {kind === 'ai' && (mm || summary.checkedAt) && (
        <p className={styles.summaryMeta}>
          {mm && mm.total > 0 && <span className="numeric">{fmt(t.mustMention, { covered: mm.covered, total: mm.total })}</span>}
          {summary.checkedAt && <span className="numeric">{fmt(t.checkedAt, { date: summary.checkedAt })}</span>}
        </p>
      )}
      <p className={styles.sectionNote}>{kind === 'ai' ? t.noteAi : t.noteQuote}</p>

      <ol className={styles.sentences}>
        {summary.sentences.map((s, i) => {
          const label = sentenceRoleLabel(s.role);
          return (
            <li key={`${s.role}:${i}`} className={cx(styles.sentence, s.role === 'headline' && styles.headline)}>
              <span className={cx(styles.role, label ? styles[`role_${s.role}`] : styles.roleEmpty)}>{label ?? ''}</span>
              <span className={styles.sentenceText}>{s.text}</span>
            </li>
          );
        })}
      </ol>

      {summary.glossary.length > 0 && (
        <div className={styles.glossary}>
          <h3 className={styles.subTitle}>{t.glossary}</h3>
          <dl className={styles.glossaryList}>
            {summary.glossary.map((g) => (
              <div key={g.term} className={styles.glossaryRow}>
                <dt>{g.term}</dt>
                <dd>{g.plain}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </Section>
  );
}

/** 별도 확인 항목: 자동 판정하지 않는다는 안내 + 확인이 필요한 이유 · 해당 사유 · 확인할 것 */
export function ManualSection({ item }: { item: ManualCheck }) {
  const t = content.ruleDetail.manual;
  return (
    <Section title={t.title}>
      <p className={styles.manualNote}>
        <Icon name="info" size={16} />
        {t.note}
      </p>
      <dl className={styles.overview}>
        {item.why && (
          <div className={styles.overviewRow}>
            <dt>{t.why}</dt>
            <dd>{item.why}</dd>
          </div>
        )}
        {item.triggeredBy && (
          <div className={styles.overviewRow}>
            <dt>{t.triggeredBy}</dt>
            <dd>{item.triggeredBy}</dd>
          </div>
        )}
        {item.whatToCheck.length > 0 && (
          <div className={styles.overviewRow}>
            <dt>{t.whatToCheck}</dt>
            <dd>
              <ul className={styles.checkList}>
                {item.whatToCheck.map((w) => (
                  <li key={w}>
                    <Icon name="check" size={14} className={styles.checkIcon} />
                    {w}
                  </li>
                ))}
              </ul>
            </dd>
          </div>
        )}
      </dl>
    </Section>
  );
}

/** 별도 확인 항목의 관련 근거 (토지이음 고시 링크 등) */
export function RelatedBasisSection({ nodes, linkRules }: { nodes: BasisNode[]; linkRules: boolean }) {
  const t = content.ruleDetail.manual;
  return (
    <Section title={t.relatedBasis}>
      {nodes.length > 0 ? <DelegationStepper nodes={nodes} linkRules={linkRules} /> : <p className={styles.empty}>{t.relatedEmpty}</p>}
    </Section>
  );
}

/** 위임 관계: 근거 트리(상위 법령 → 하위 조례)를 세로 단계로 */
export function DelegationSection({ nodes, linkRules }: { nodes: BasisNode[]; linkRules: boolean }) {
  const t = content.ruleDetail.tree;
  return (
    <Section title={t.title} desc={nodes.length > 0 ? t.desc : undefined}>
      {nodes.length > 0 ? <DelegationStepper nodes={nodes} linkRules={linkRules} /> : <p className={styles.empty}>{t.empty}</p>}
    </Section>
  );
}

/**
 * 근거 트리 단계 — 층 라벨 · 시행일 · 문서 제목 · 조항 · 인용 · 원문 링크(https 만).
 * linkRules 면 승인된 규칙이 걸린 단계에 같은 페이지의 원문 카드로 가는 링크를 답니다.
 */
export function DelegationStepper({ nodes, linkRules = false }: { nodes: BasisNode[]; linkRules?: boolean }) {
  const t = content.ruleDetail.source;
  return (
    <ol className={styles.stepper}>
      {nodes.map((b, i) => (
        <li key={`${b.layer}:${b.documentTitle}:${b.articleLabel ?? ''}:${i}`} className={styles.step}>
          <span className={styles.stepDot} aria-hidden="true" />
          <div className={styles.stepBody}>
            <div className={styles.stepHead}>
              <LayerTag layer={b.layer} />
              {b.effectiveFrom && <span className={cx(styles.muted, 'numeric')}>{fmt(t.effective, { date: b.effectiveFrom })}</span>}
            </div>
            <p className={styles.stepDoc}>
              <strong>{b.documentTitle}</strong>
              {b.articleLabel && <span className={styles.stepArticle}>{b.articleLabel}</span>}
            </p>
            {b.quote && <blockquote className={styles.stepQuote}>{b.quote}</blockquote>}
            {(b.url || (linkRules && b.ruleId)) && (
              <div className={styles.links}>
                {b.url && (
                  <a className={styles.link} href={b.url} target="_blank" rel="noopener noreferrer">
                    {t.original}
                    <Icon name="external-link" size={14} />
                  </a>
                )}
                {linkRules && b.ruleId && (
                  <a className={styles.link} href={`#${ruleCardId(b.ruleId)}`} data-print="hide">
                    {t.toCard}
                    <Icon name="file-text" size={14} />
                  </a>
                )}
              </div>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}
