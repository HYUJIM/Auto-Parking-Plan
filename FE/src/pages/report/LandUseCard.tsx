import type { ReactNode } from 'react';
import type { LandUseRow } from '@/lib/report/landUseRows';
import { relationVariant } from '@/lib/landUse';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Badge } from '@/components/Badge';
import { ReportCard } from '@/components/report/ReportCard';
import styles from './LandUseCard.module.css';

/**
 * 오른쪽 '토지이용계획' 표 — 대지면적 · 지목 · 용도지역 · 용도지구·구역 · 대지 형상 · 지형 · 토지이용상황 · 인접 도로 · 공시지가 · 출입구 계획.
 * 줄 구성은 lib/report/landUseRows.ts 가 정하고, 여기서는 그리기만 합니다. 건설 불가 판정의 근거가 된 줄은 붉게 강조합니다.
 * 용도지구·구역은 화면에서 관계 배지 목록, 인쇄(1쪽을 A4 한 장에 맞추려고)에서는 '이름(관계) · …' 한 줄 글로 보여 줍니다.
 */
export function LandUseCard({ rows, fallback }: { rows: LandUseRow[] | null; fallback: ReactNode }) {
  const t = content.report.landUse;
  const none = content.site.rules.none;
  return (
    <ReportCard title={t.title} desc={t.desc}>
      {rows ? (
        <table className={styles.table}>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className={cx(r.highlight && styles.highlight)}>
                <th scope="row">
                  <span>{r.label}</span>
                  {r.highlight && <Badge variant="danger">{t.basisMark}</Badge>}
                </th>
                <td>
                  {r.tags ? (
                    <>
                      <ul className={styles.tags}>
                        {r.tags.map((tag, i) => (
                          <li key={`${tag.name}:${i}`} className={cx(styles.tag, tag.adjacent && styles.adjacent)}>
                            <Badge variant={tag.adjacent ? 'neutral' : relationVariant(tag.relation)}>{tag.relation || none}</Badge>
                            <span className={styles.tagName}>{tag.name}</span>
                          </li>
                        ))}
                      </ul>
                      <span className={styles.tagsPrint}>{r.value}</span>
                    </>
                  ) : (
                    <span className={cx(styles.value, r.muted && styles.muted, (r.key === 'area' || r.key === 'landPrice') && 'numeric')}>
                      {r.value}
                    </span>
                  )}
                  {r.sub && <span className={cx(styles.sub, 'numeric')}>{r.sub}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        fallback
      )}
    </ReportCard>
  );
}
