import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { getRuleBasis } from '@/lib/api/rules';
import type { BasisNode, DataNotice, RuleBasisDetail, RuleEvidence, RuleValue } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatRuleValue, highlightParts, ruleNotApprovedNotices, type RuleValueTone } from '@/lib/ruleDetail/format';
import { ruleCardId } from '@/lib/ruleDetail/params';
import { useAsync } from '@/lib/useAsync';
import { AsyncBlock, Skeleton } from '@/components/AsyncBlock';
import { Badge } from '@/components/Badge';
import { Icon } from '@/components/Icon';
import { LayerTag } from '@/components/RuleItem';
import { DelegationStepper, Section } from './RuleDetailSections';
import styles from './RuleDetailPage.module.css';

/** 화면에서 처음 보여 줄 개수 — 용도 목록 규칙은 값·근거가 30건 가까이 됩니다. 인쇄에는 전부 나옵니다 */
const VALUES_LIMIT = 6;
const EVIDENCE_LIMIT = 4;

interface SourceSectionProps {
  /** 근거 트리에 걸린 승인된 규칙 id (겹침 없이, 상위 → 하위) */
  ruleIds: string[];
  /** 검토 기준일 — 검토 때와 같은 판의 조문을 받습니다 */
  asOfDate: string;
  basis: BasisNode[];
  itemTitle: string;
  /** 항목의 상태 사유 (DATA_MISSING 이면 '검수 승인된 데이터 없음' 이 사유) */
  reasonCode: string | null;
  notices: DataNotice[];
}

/**
 * 법규 원문 — 승인된 규칙마다 원문 카드 1장 (GET /v1/rules/{id}/basis). 카드마다 따로 불러오므로 하나가 실패해도 나머지는 보입니다.
 * 걸린 규칙이 없으면(예: 주차대수 — 승인된 데이터 없음) 그 사실과 이 항목의 BE 안내(RULE_NOT_APPROVED), 연결된 근거 인용만 보여 줍니다.
 */
export function SourceSection({ ruleIds, asOfDate, basis, itemTitle, reasonCode, notices }: SourceSectionProps) {
  const t = content.ruleDetail.source;

  if (ruleIds.length === 0) {
    const own = ruleNotApprovedNotices(
      notices,
      itemTitle,
      basis.map((b) => b.documentTitle),
    );
    // '승인된 데이터가 없다' 는 BE 가 그렇게 알렸거나 사유가 DATA_MISSING 일 때만 — 아니면 연결된 규칙이 없다는 사실만
    const lead = own.length > 0 || reasonCode === 'DATA_MISSING' ? t.noRules : t.noLinkedRules;
    return (
      <Section title={t.title}>
        <p className={styles.emptyLead}>
          <Icon name="info" size={16} />
          {lead}
        </p>
        {own.length > 0 && (
          <div className={styles.subBlock}>
            <h3 className={styles.subTitle}>{t.notices}</h3>
            <ul className={styles.notices}>
              {own.map((n, i) => (
                <li key={`${n.code}:${i}`}>{n.message}</li>
              ))}
            </ul>
          </div>
        )}
        {basis.length > 0 && (
          <div className={styles.subBlock}>
            <h3 className={styles.subTitle}>{t.basisQuotes}</h3>
            <DelegationStepper nodes={basis} />
          </div>
        )}
      </Section>
    );
  }

  return (
    <Section title={t.title} desc={t.desc}>
      <div className={styles.ruleCards}>
        {ruleIds.map((id) => (
          <RuleBasisCard key={id} ruleId={id} asOfDate={asOfDate} />
        ))}
      </div>
    </Section>
  );
}

/** 규칙 하나의 원문 카드 — 따로 불러오고 따로 실패합니다. id 는 위임 관계의 '원문으로' 링크가 가리킵니다 */
function RuleBasisCard({ ruleId, asOfDate }: { ruleId: string; asOfDate: string }) {
  const state = useAsync((signal) => getRuleBasis(ruleId, asOfDate || null, signal), [ruleId, asOfDate]);
  const cardRef = useRef<HTMLElement>(null);
  const { hash } = useLocation();
  const loaded = state.status === 'success';
  const cardId = ruleCardId(ruleId);
  // 새로고침·공유한 …#rule-<id> 주소: 브라우저의 첫 이동 때는 카드가 아직 없으므로, 이 카드를 받은 뒤 그 자리로 갑니다
  useEffect(() => {
    if (loaded && hash === `#${cardId}`) cardRef.current?.scrollIntoView({ block: 'start' });
  }, [loaded, hash, cardId]);
  return (
    <article id={cardId} ref={cardRef} className={styles.ruleCard}>
      <AsyncBlock state={state} skeleton={<Skeleton lines={4} />}>
        {(detail) => <RuleBasisBody detail={detail} />}
      </AsyncBlock>
    </article>
  );
}

/** 원문 카드 본문: 층 · 문서 · 공포/시행 · 원문 링크 · 규칙 값 · 근거 조문(인용 강조) */
export function RuleBasisBody({ detail }: { detail: RuleBasisDetail }) {
  const t = content.ruleDetail.source;
  const d = detail.document;
  const effective = d.effectiveFrom ?? detail.effectiveFrom;
  const hasMeta = Boolean(d.promulgationNumber || d.promulgationDate || effective || detail.effectiveTo);
  return (
    <>
      <div className={styles.ruleHead}>
        <LayerTag layer={detail.layer} />
        {d.isExample && <Badge variant="neutral">{content.site.exampleBadge}</Badge>}
      </div>
      <h3 className={styles.docTitle}>{d.title || detail.title}</h3>
      {d.title && detail.title && <p className={styles.ruleTitle}>{detail.title}</p>}
      {hasMeta && (
        <p className={styles.docMeta}>
          {d.promulgationNumber && <span>{fmt(t.promulgationNumber, { value: d.promulgationNumber })}</span>}
          {d.promulgationDate && <span className="numeric">{fmt(t.promulgationDate, { date: d.promulgationDate })}</span>}
          {effective && <span className="numeric">{fmt(t.effective, { date: effective })}</span>}
          {detail.effectiveTo && <span className="numeric">{fmt(t.effectiveUntil, { date: detail.effectiveTo })}</span>}
        </p>
      )}
      {d.url && (
        <a className={styles.link} href={d.url} target="_blank" rel="noopener noreferrer">
          {t.original}
          <Icon name="external-link" size={14} />
        </a>
      )}

      {detail.values.length > 0 && (
        <div className={styles.subBlock}>
          <h4 className={styles.subTitle}>{t.values}</h4>
          <Expandable
            items={detail.values}
            limit={VALUES_LIMIT}
            listClassName={styles.valueList}
            itemClassName={styles.valueItem}
            itemKey={(v, i) => `${v.key}:${i}`}
            render={(v) => <ValueRow value={v} />}
          />
        </div>
      )}

      {detail.evidence.length > 0 && (
        <div className={styles.subBlock}>
          <h4 className={styles.subTitle}>{t.evidence}</h4>
          <Expandable
            items={detail.evidence}
            limit={EVIDENCE_LIMIT}
            listClassName={styles.evidenceList}
            itemClassName={styles.evidenceItem}
            itemKey={(e, i) => `${e.articleLabel ?? ''}:${i}`}
            render={(e) => <EvidenceRow evidence={e} />}
          />
        </div>
      )}
    </>
  );
}

/** 목록형 값 구분 칩의 색 — 허용(기본 초록) · 조건부(노랑) · 금지(빨강) */
const TONE_CLASS: Record<RuleValueTone, string | undefined> = {
  allowed: undefined,
  conditional: styles.valueTagConditional,
  prohibited: styles.valueTagProhibited,
};

/** 규칙 값 한 줄: [허용·조건부·금지] 값 + 조건 설명 (값 자리에 이미 쓴 설명은 다시 쓰지 않음) */
function ValueRow({ value }: { value: RuleValue }) {
  const f = formatRuleValue(value);
  const note = value.conditionNote && value.conditionNote !== f.text ? value.conditionNote : null;
  return (
    <>
      <span className={styles.valueMain}>
        {f.tag && <span className={cx(styles.valueTag, f.tone && TONE_CLASS[f.tone])}>{f.tag}</span>}
        <span className={f.tag ? undefined : 'numeric'}>{f.text}</span>
      </span>
      {note && <span className={styles.valueNote}>{note}</span>}
    </>
  );
}

/**
 * 근거 조문 한 건: 조항 · 제목 · (예외 조항) + 조문 원문에서 규칙이 인용한 부분을 <mark> 로.
 * 원문은 텍스트로만 그립니다 (HTML 로 끼워 넣지 않음). 인용 위치를 모르면 원문과 인용문을 따로 보여 줍니다.
 */
function EvidenceRow({ evidence }: { evidence: RuleEvidence }) {
  const t = content.ruleDetail.source;
  const parts = highlightParts(evidence.provisionText, evidence.highlight);
  return (
    <>
      <div className={styles.evidenceHead}>
        {evidence.articleLabel && <strong className={styles.article}>{evidence.articleLabel}</strong>}
        {evidence.articleHeading && <span className={styles.muted}>{evidence.articleHeading}</span>}
        {evidence.role === 'exception' && (
          <Badge variant="check" icon="alert-circle">
            {t.exception}
          </Badge>
        )}
      </div>
      {parts ? (
        <p className={styles.provision}>
          {parts.before}
          <mark className={styles.mark}>{parts.mark}</mark>
          {parts.after}
        </p>
      ) : (
        <>
          {evidence.provisionText && <p className={styles.provision}>{evidence.provisionText}</p>}
          {evidence.quoted && (
            <blockquote className={styles.quoted}>
              <span className={styles.quotedLabel}>{t.quoted}</span>
              {evidence.quoted}
            </blockquote>
          )}
        </>
      )}
    </>
  );
}

/**
 * 긴 목록: 처음 limit 건만 보이고 '전체 n건 보기' 로 펼칩니다.
 * 나머지는 화면에서만 CSS 로 숨기므로 인쇄(PDF 저장)에는 전부 나옵니다.
 */
function Expandable<T>({
  items,
  limit,
  listClassName,
  itemClassName,
  itemKey,
  render,
}: {
  items: T[];
  limit: number;
  listClassName?: string;
  itemClassName?: string;
  itemKey: (item: T, index: number) => string;
  render: (item: T) => ReactNode;
}) {
  const t = content.ruleDetail.source;
  const [open, setOpen] = useState(false);
  const listId = useId();
  return (
    <>
      <ul id={listId} className={cx(listClassName, open && styles.listOpen)}>
        {items.map((item, i) => (
          <li key={itemKey(item, i)} className={cx(itemClassName, i >= limit && styles.extra)}>
            {render(item)}
          </li>
        ))}
      </ul>
      {items.length > limit && (
        <button
          type="button"
          className={styles.more}
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen((o) => !o)}
          data-print="hide"
        >
          {open ? t.showLess : fmt(t.showAll, { n: items.length })}
          <Icon name={open ? 'chevron-up' : 'chevron-down'} size={14} />
        </button>
      )}
    </>
  );
}
