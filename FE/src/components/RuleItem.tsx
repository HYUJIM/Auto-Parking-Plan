import { useId, useMemo, useState } from 'react';
import type { BasisNode, ManualCheck, RuleItem as RuleItemData, RuleStatus, RuleUse } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatNumber } from '@/lib/format';
import type { IconName } from '@/design/icons';
import { Badge, type BadgeVariant } from './Badge';
import { Icon } from './Icon';
import styles from './RuleItem.module.css';

/** 검토 상태 3값 → 배지 모양. 검토 가능=초록, 조건부=노랑, 별도 확인=회색 */
const STATUS_BADGE: Record<RuleStatus, { variant: BadgeVariant; icon: IconName }> = {
  REVIEWABLE: { variant: 'ok', icon: 'check-circle' },
  CONDITIONAL: { variant: 'check', icon: 'alert-circle' },
  MANUAL_CHECK: { variant: 'manual', icon: 'info' },
};

export function StatusBadge({ status, className }: { status: RuleStatus; className?: string }) {
  const b = STATUS_BADGE[status];
  return (
    <Badge variant={b.variant} icon={b.icon} className={className}>
      {content.site.rules.status[status]}
    </Badge>
  );
}

function reasonText(code: string | null): string | null {
  if (!code) return null;
  return (content.site.rules.reasons as Record<string, string>)[code] ?? null;
}

/**
 * 핵심 법규 한 항목 (BE review_result 의 item 1개 = 카드 1장).
 * 제목 + 상태 배지(검토 가능 / 조건부 검토 / 별도 확인 필요), 기준값(criteria_text) / 적용값(참고값·용도 목록·기준표),
 * 상태 사유, BE 가 검증한 쉬운 설명. 카드를 누르면 근거 트리(법률 → 시행령 → 조례, 원문 인용·링크)가 펼쳐집니다.
 */
export function RuleItem({ item }: { item: RuleItemData }) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const t = content.site.rules;
  const reason = reasonText(item.reasonCode);

  return (
    <li className={cx(styles.item, open && styles.open)}>
      <button type="button" className={styles.head} aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen((o) => !o)}>
        <span className={styles.title}>{item.title}</span>
        <StatusBadge status={item.status} />
        <Icon name="chevron-down" size={18} className={styles.chevron} label={open ? t.evidenceHide : t.evidence} />
      </button>

      <dl className={styles.values}>
        <div>
          <dt>{t.standard}</dt>
          <dd>{item.criteria ?? t.none}</dd>
        </div>
        <div>
          <dt>{t.applied}</dt>
          <dd>
            <AppliedValue item={item} />
          </dd>
        </div>
      </dl>

      {reason && (
        <p className={styles.note}>
          <Icon name="info" size={13} />
          {reason}
        </p>
      )}

      {item.explanation.length > 0 && (
        <div className={styles.explain}>
          <span className={styles.explainLabel}>
            {t.explanation}
            <span className={styles.explainHint}>{t.explanationNote}</span>
          </span>
          <ul className={styles.explainList}>
            {item.explanation.map((text, i) => (
              <li key={i}>{text}</li>
            ))}
          </ul>
        </div>
      )}

      {open && (
        <div id={bodyId} className={styles.evidence}>
          <span className={styles.evidenceTitle}>{t.evidenceTitle}</span>
          <BasisList nodes={item.basis} emptyText={t.evidenceEmpty} />
        </div>
      )}
    </li>
  );
}

/** 적용값: 참고값(대지면적 × 비율) → 용도 목록(접이식) → 기준표(접이식) → '—' */
function AppliedValue({ item }: { item: RuleItemData }) {
  const t = content.site.rules;
  const [listOpen, setListOpen] = useState(false);
  const listId = useId();

  if (item.referenceValue) {
    const r = item.referenceValue;
    return (
      <span className={styles.reference}>
        <span>
          <span className="numeric">{formatNumber(r.value)}</span>
          {r.unit}
        </span>
        <span className={styles.referenceMeta}>
          {r.label}
          {r.formula && <span className="numeric"> · {r.formula}</span>}
        </span>
      </span>
    );
  }

  if (item.uses && item.uses.length > 0) {
    const uses = item.uses;
    const count = (s: RuleStatus) => uses.filter((u) => u.status === s).length;
    return (
      <span className={styles.reference}>
        <span className={styles.referenceMeta}>
          {fmt(t.usesSummary, { n: uses.length, ok: count('REVIEWABLE'), cond: count('CONDITIONAL'), manual: count('MANUAL_CHECK') })}
        </span>
        <button
          type="button"
          className={styles.toggle}
          aria-expanded={listOpen}
          aria-controls={listId}
          onClick={() => setListOpen((o) => !o)}
        >
          {listOpen ? t.usesHide : t.usesShow}
          <Icon name={listOpen ? 'chevron-up' : 'chevron-down'} size={14} />
        </button>
        {listOpen && <UseGroups id={listId} uses={uses} />}
      </span>
    );
  }

  if (item.table && item.table.length > 0) {
    const rows = item.table;
    return (
      <span className={styles.reference}>
        <button
          type="button"
          className={styles.toggle}
          aria-expanded={listOpen}
          aria-controls={listId}
          onClick={() => setListOpen((o) => !o)}
        >
          {listOpen ? t.tableHide : fmt(t.tableShow, { n: rows.length })}
          <Icon name={listOpen ? 'chevron-up' : 'chevron-down'} size={14} />
        </button>
        {listOpen && (
          <table id={listId} className={styles.table}>
            <tbody>
              {rows.map((row, i) => (
                <tr key={`${row.label}:${i}`}>
                  <th scope="row">{row.label}</th>
                  <td className="numeric">
                    {typeof row.value === 'number' ? formatNumber(row.value) : row.value}
                    {row.unit ? ` ${row.unit}` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </span>
    );
  }

  return <>{t.none}</>;
}

/** 용도 목록을 BE 의 use_group(주거·근린생활·업무·기타) 순서대로 묶어 표시 */
function UseGroups({ id, uses }: { id: string; uses: RuleUse[] }) {
  const groups = useMemo(() => {
    const map = new Map<string, RuleUse[]>();
    for (const u of uses) {
      const list = map.get(u.group) ?? [];
      list.push(u);
      map.set(u.group, list);
    }
    return [...map.entries()];
  }, [uses]);

  return (
    <div id={id} className={styles.useGroups}>
      {groups.map(([group, list]) => (
        <div key={group} className={styles.useGroup}>
          {group && <span className={styles.useGroupName}>{group}</span>}
          <ul className={styles.useList}>
            {list.map((u, i) => (
              <li key={`${u.name}:${i}`} className={styles.use}>
                <StatusBadge status={u.status} className={styles.useBadge} />
                <span className={styles.useBody}>
                  <span>{u.name}</span>
                  {u.conditionNote && <span className={styles.useNote}>{u.conditionNote}</span>}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/** 근거 트리 — 상위(법률) → 하위(조례) 순서 그대로. 층 라벨·문서·조항·시행일·인용·원문 링크 */
export function BasisList({ nodes, emptyText }: { nodes: BasisNode[]; emptyText?: string }) {
  const t = content.site.rules;
  if (nodes.length === 0) return emptyText ? <p className={styles.evidenceEmpty}>{emptyText}</p> : null;
  return (
    <ol className={styles.basisList}>
      {nodes.map((b, i) => (
        <li key={`${b.layer}:${b.documentTitle}:${b.articleLabel ?? ''}:${i}`} className={styles.basisNode}>
          <span className={styles.basisHead}>
            <span className={cx(styles.layerTag, styles[`layer_${b.layer}`])}>
              {(t.layers as Record<string, string>)[b.layer] ?? b.layer}
            </span>
            <strong className={styles.basisDoc}>{b.documentTitle}</strong>
            {b.articleLabel && <span className={styles.basisArticle}>{b.articleLabel}</span>}
            {b.effectiveFrom && <span className={cx(styles.basisDate, 'numeric')}>{fmt(t.effective, { date: b.effectiveFrom })}</span>}
          </span>
          {b.quote && <blockquote className={styles.quote}>{b.quote}</blockquote>}
          {b.url && (
            <a className={styles.link} href={b.url} target="_blank" rel="noreferrer">
              {t.original}
              <Icon name="external-link" size={14} />
            </a>
          )}
        </li>
      ))}
    </ol>
  );
}

/** 별도 확인 항목 (BE manual_checks): 자동 판정하지 않음 — 이유 · 확인할 것 · 해당 사유 · 관련 근거 링크 */
export function ManualCheckItem({ item }: { item: ManualCheck }) {
  const t = content.site.rules;
  return (
    <li className={cx(styles.item, styles.manual)}>
      <div className={styles.head}>
        <span className={styles.title}>{item.title}</span>
        <Badge variant="manual" icon="info">
          {content.status.manual}
        </Badge>
      </div>
      {item.why && <p className={styles.manualDesc}>{item.why}</p>}
      {item.triggeredBy && (
        <p className={styles.note}>
          <Icon name="alert-circle" size={13} />
          {t.triggeredBy}: {item.triggeredBy}
        </p>
      )}
      {item.whatToCheck.length > 0 && (
        <div className={styles.checks}>
          <span className={styles.checksLabel}>{t.whatToCheck}</span>
          <ul className={styles.checkList}>
            {item.whatToCheck.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </div>
      )}
      {item.relatedBasis.length > 0 && (
        <div className={styles.checks}>
          <span className={styles.checksLabel}>{t.relatedBasis}</span>
          <BasisList nodes={item.relatedBasis} />
        </div>
      )}
    </li>
  );
}
