import { useId, useState } from 'react';
import type { ManualCheckResult, RuleItem as RuleItemData } from '@/lib/api/types';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { relationVariant } from '@/lib/landUse';
import { Badge } from './Badge';
import { Icon } from './Icon';
import styles from './RuleItem.module.css';

/** 핵심 법규 한 항목: 기준값 / 적용값 / 상태 배지, 클릭하면 근거(법령·조항·시행일·원문) 펼침 */
export function RuleItem({ item }: { item: RuleItemData }) {
  const [open, setOpen] = useState(false);
  const bodyId = useId();
  const t = content.site.rules;

  return (
    <li className={cx(styles.item, open && styles.open)}>
      <button type="button" className={styles.head} aria-expanded={open} aria-controls={bodyId} onClick={() => setOpen((o) => !o)}>
        <span className={styles.title}>{item.title}</span>
        <Badge variant={item.status} icon={item.status === 'ok' ? 'check-circle' : 'alert-circle'}>
          {content.status[item.status]}
        </Badge>
        <Icon name="chevron-down" size={18} className={styles.chevron} label={open ? t.evidenceHide : t.evidence} />
      </button>

      <dl className={styles.values}>
        <div>
          <dt>{t.standard}</dt>
          <dd>{item.standard}</dd>
        </div>
        <div>
          <dt>{t.applied}</dt>
          <dd>{item.applied}</dd>
        </div>
      </dl>

      {item.note && (
        <p className={styles.note}>
          <Icon name="info" size={13} />
          {item.note}
        </p>
      )}

      {open && (
        <dl id={bodyId} className={styles.evidence}>
          <div>
            <dt>{t.law}</dt>
            <dd>{item.evidence.law}</dd>
          </div>
          <div>
            <dt>{t.article}</dt>
            <dd>{item.evidence.article}</dd>
          </div>
          <div>
            <dt>{t.effective}</dt>
            <dd className="numeric">{item.evidence.effective}</dd>
          </div>
          <a className={styles.link} href={item.evidence.url} target="_blank" rel="noreferrer">
            {t.original}
            <Icon name="external-link" size={14} />
          </a>
        </dl>
      )}
    </li>
  );
}

/** 별도 확인 항목: 자동 판정하지 않음 — 회색 '별도 확인'. 토지이용계획에서 찾은 해당 항목이 있으면 함께 표시 */
export function ManualCheckItem({ item }: { item: ManualCheckResult }) {
  const t = content.site.rules;
  return (
    <li className={cx(styles.item, styles.manual)}>
      <div className={styles.head}>
        <span className={styles.title}>{item.title}</span>
        <Badge variant="manual" icon="info">
          {content.status.manual}
        </Badge>
      </div>
      <p className={styles.manualDesc}>{item.desc}</p>
      {item.hits.length > 0 && (
        <ul className={styles.hits} aria-label={t.landUseHits}>
          {item.hits.map((h) => (
            <li key={`${h.code}:${h.name}`} className={styles.hit}>
              <Badge variant={relationVariant(h.relation)}>{h.relation}</Badge>
              <span>{h.name}</span>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}
