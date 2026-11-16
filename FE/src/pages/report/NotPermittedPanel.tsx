import type { BasisNode } from '@/lib/api/types';
import { LAND_CATEGORY_LAW, PUBLIC_FACILITY_LAND, type ParkingVerdict } from '@/lib/report/verdict';
import { relationVariant } from '@/lib/landUse';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Badge } from '@/components/Badge';
import { Icon } from '@/components/Icon';
import { ReportNotice } from '@/components/report/ReportNotice';
import styles from './NotPermittedPanel.module.css';

type NotPermitted = Extract<ParkingVerdict, { kind: 'not_permitted' }>;

/**
 * '주차장 건설 불가 지역' — 판정(BE)과 그 근거.
 * 지목 줄: 지목이 공공시설 용지라서 불가일 때 강조하고, 지목의 법적 구분(공간정보관리법 제67조) 링크를 붙입니다.
 * 용도지역 줄: 용도지역의 허용 용도 때문일 때 강조하고, '검토 가능한 주요 용도' 항목의 기준과 근거 법령을 붙입니다.
 * 아래에 검토 당시의 토지이용계획(용도지역·지구·구역)을 그대로 보여 줍니다. 최종 확인은 인허가 기관이라는 안내를 함께 둡니다.
 * 판정 사유 문장은 맨 위 안내에 한 번만 둡니다 — BE 사유(eligibility.reason)가 있으면 그것을, 없으면 원인별 문장을.
 * 근거 줄에는 같은 문장을 되풀이하지 않고 '판정 근거' 표시와 값·기준·법령만 둡니다.
 */
export function NotPermittedPanel({ verdict }: { verdict: NotPermitted }) {
  const t = content.report.notPermitted;
  const byLand = verdict.cause === 'land_category';
  const byZoning = verdict.cause === 'zoning';
  const criteria = verdict.zoningItem?.criteria ?? null;
  const none = content.site.rules.none;
  const causeText = byLand ? fmt(t.landCategoryCause, { list: PUBLIC_FACILITY_LAND.join('·') }) : t.zoningCause;

  return (
    <div className={styles.panel}>
      <ReportNotice tone="danger" role="alert" title={t.title}>
        <p>{verdict.reason?.trim() || causeText}</p>
      </ReportNotice>

      <section className={styles.basis} aria-label={t.basisTitle}>
        <h3 className={styles.h3}>{t.basisTitle}</h3>
        <dl className={styles.rows}>
          <div className={cx(styles.row, byLand && styles.cause)}>
            <dt>
              {t.landCategory}
              {byLand && <CauseMark />}
            </dt>
            <dd>
              <strong className={styles.value}>{verdict.landCategory ?? none}</strong>
              <a className={styles.law} href={LAND_CATEGORY_LAW.url} target="_blank" rel="noreferrer">
                <span className={styles.lawLabel}>{t.landCategoryLaw}</span>
                {LAND_CATEGORY_LAW.title} {LAND_CATEGORY_LAW.article}
                <Icon name="external-link" size={13} />
              </a>
            </dd>
          </div>

          <div className={cx(styles.row, byZoning && styles.cause)}>
            <dt>
              {t.zoning}
              {byZoning && <CauseMark />}
            </dt>
            <dd>
              <strong className={styles.value}>{verdict.zoning.length ? verdict.zoning.join(' · ') : t.zoningUnknown}</strong>
              {criteria && (
                <p>
                  <span className={styles.inlineLabel}>{t.criteria}</span>
                  {criteria}
                </p>
              )}
              {verdict.zoningBasis.length > 0 && <BasisLinks nodes={verdict.zoningBasis} label={t.basis} />}
            </dd>
          </div>
        </dl>

        <div className={styles.landUses}>
          <h4 className={styles.h4}>{t.landUses}</h4>
          {verdict.landUses && verdict.landUses.length > 0 ? (
            <ul className={styles.useList}>
              {verdict.landUses.map((u, i) => (
                <li key={`${u.name}:${i}`} className={cx(styles.use, adjacent(u.relation) && styles.adjacent)}>
                  {/* 접함은 대지에 걸치지 않은 지정 — 토지이용계획 표와 같이 중립 색 */}
                  <Badge variant={adjacent(u.relation) ? 'neutral' : relationVariant(u.relation)}>{u.relation || none}</Badge>
                  <span className={styles.useName}>{u.name}</span>
                  <span className={styles.useKind}>{content.site.summary.landUseKinds[u.kind]}</span>
                </li>
              ))}
            </ul>
          ) : (
            // null = 조회 실패, [] = 지정 없음 (BE ParcelSnapshot 의 약속)
            <p className={styles.muted}>{verdict.landUses === null ? t.landUsesEmpty : t.landUsesNone}</p>
          )}
        </div>
      </section>

      <p className={styles.note}>
        <Icon name="shield-check" size={14} />
        {content.site.layout.notPermittedNote}
      </p>
    </div>
  );
}

function adjacent(relation: string): boolean {
  return relation === '접함';
}

function CauseMark() {
  return <Badge variant="danger">{content.report.landUse.basisMark}</Badge>;
}

/** 근거 법령 — 문서 이름 + 조항 + 원문 링크(https 만) */
function BasisLinks({ nodes, label }: { nodes: BasisNode[]; label: string }) {
  return (
    <div className={styles.basisLinks}>
      <span className={styles.inlineLabel}>{label}</span>
      <ul>
        {nodes.map((b, i) => (
          <li key={`${b.documentTitle}:${b.articleLabel ?? ''}:${i}`}>
            {b.url ? (
              <a className={styles.law} href={b.url} target="_blank" rel="noreferrer">
                {b.documentTitle}
                {b.articleLabel && ` ${b.articleLabel}`}
                <Icon name="external-link" size={13} />
              </a>
            ) : (
              <span>
                {b.documentTitle}
                {b.articleLabel && ` ${b.articleLabel}`}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
