import { Link } from 'react-router-dom';
import type { IconName } from '@/design/icons';
import type { ManualCheck, ReviewProgress, RuleItem, RuleReview, RuleStatus } from '@/lib/api/types';
import type { ChecklistPhase } from '@/lib/report/pageState';
import { ruleDetailHref } from '@/lib/report/links';
import { reviewProgressText, type VerdictChip } from '@/lib/report/summary';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Badge } from '@/components/Badge';
import { Icon } from '@/components/Icon';
import { Skeleton } from '@/components/AsyncBlock';
import { StatusBadge } from '@/components/RuleItem';
import { ReportCard } from '@/components/report/ReportCard';
import { ErrorNotice, ReportNotice } from '@/components/report/ReportNotice';
import styles from './RuleChecklistCard.module.css';

const STATUS_ICON: Record<RuleStatus, IconName> = {
  REVIEWABLE: 'check-circle',
  CONDITIONAL: 'alert-circle',
  MANUAL_CHECK: 'info',
};

const STEP_ICON: Record<string, IconName> = { done: 'check', running: 'loader', failed: 'alert-circle' };

interface RuleChecklistCardProps {
  /** 주차장 설치 판단 칩 */
  chip: VerdictChip;
  review: RuleReview | null;
  /** 무엇을 보여 줄지 (lib/report/pageState.ts checklistPhase) */
  phase: ChecklistPhase;
  error: unknown;
  onRetry: () => void;
  progress: ReviewProgress | null;
  pnu: string;
}

/**
 * 오른쪽 '법규 검토' 체크리스트 — 첫 줄은 주차장 설치 판단(BE 판정), 이어서 핵심 법규 항목(상태 아이콘 · 제목 · 기준 · 상태 배지 · 상세 보기)과
 * 별도 확인 항목. 상세 보기는 /report/rules/{항목 코드} 로, 같은 검토 id 를 넘겨 새 검토를 만들지 않습니다.
 * 인쇄 1쪽에서는 기준 줄을 빼고 별도 확인 항목을 '2쪽 참고' 한 줄로 접습니다 (항목별 상세는 2쪽 '법규 검토 상세').
 * 검토 요청이 없을 때(필지 조회 실패 · 대기 필지)는 '검토 중'이라고 하지 않고 그 이유를 보여 줍니다.
 */
export function RuleChecklistCard({ chip, review, phase, error, onRetry, progress, pnu }: RuleChecklistCardProps) {
  const t = content.report.rules;
  return (
    <ReportCard title={t.title} desc={review?.supported ? fmt(t.desc, { n: review.items.length, m: review.manual.length }) : undefined}>
      <div className={styles.verdict}>
        <span className={styles.verdictLabel}>
          <Icon name="car" size={18} />
          {t.verdictLabel}
        </span>
        <Badge variant={chip.variant} icon={chip.icon}>
          {chip.label}
        </Badge>
      </div>

      {phase === 'unsupported' ? (
        <ReportNotice tone="warning" title={content.site.unsupported.title}>
          <p>{content.site.unsupported.desc}</p>
        </ReportNotice>
      ) : phase === 'ready' && review ? (
        <>
          <ul className={styles.list}>
            {review.items.map((item) => (
              <RuleRow key={item.id} item={item} href={ruleDetailHref(item.id, pnu, review.reviewId)} />
            ))}
          </ul>
          {review.manual.length > 0 && (
            <div className={styles.manual}>
              <h3 className={styles.h3}>{t.manualTitle}</h3>
              <ul className={styles.list}>
                {review.manual.map((m) => (
                  <ManualRow key={m.id} item={m} href={ruleDetailHref(m.id, pnu, review.reviewId)} />
                ))}
              </ul>
            </div>
          )}
          {/* 인쇄 전용 한 줄 — 2쪽 '법규 검토 상세'(PrintRuleDetails)가 있을 때만 */}
          {review.items.length + review.manual.length > 0 && (
            <p className={styles.printNote}>{review.manual.length > 0 ? fmt(t.printManual, { n: review.manual.length }) : t.printMore}</p>
          )}
        </>
      ) : phase === 'error' ? (
        <ErrorNotice error={error} onRetry={onRetry} />
      ) : phase === 'pendingParcel' ? (
        <ReportNotice title={t.pendingParcel} />
      ) : phase === 'notStarted' ? (
        <ReportNotice tone="warning" title={t.notStarted} />
      ) : phase === 'waiting' ? (
        <Skeleton lines={6} />
      ) : (
        <Progress progress={progress} />
      )}
    </ReportCard>
  );
}

function DetailLink({ href, title }: { href: string | null; title: string }) {
  if (!href) return null;
  return (
    <Link to={href} className={styles.detail} aria-label={fmt(content.report.rules.detailAria, { title })}>
      {content.report.rules.detail}
      <Icon name="chevron-right" size={14} />
    </Link>
  );
}

function RuleRow({ item, href }: { item: RuleItem; href: string | null }) {
  return (
    <li className={styles.item}>
      <Icon name={STATUS_ICON[item.status]} size={18} className={cx(styles.statusIcon, styles[`s_${item.status}`])} />
      <div className={styles.body}>
        <div className={styles.head}>
          <span className={styles.title}>{item.title}</span>
          <StatusBadge status={item.status} />
        </div>
        {item.criteria && <p className={styles.criteria}>{item.criteria}</p>}
      </div>
      <DetailLink href={href} title={item.title} />
    </li>
  );
}

function ManualRow({ item, href }: { item: ManualCheck; href: string | null }) {
  return (
    <li className={cx(styles.item, styles.compact)}>
      <Icon name="info" size={16} className={cx(styles.statusIcon, styles.s_MANUAL_CHECK)} />
      <div className={styles.body}>
        <div className={styles.head}>
          <span className={styles.title}>{item.title}</span>
          <Badge variant="manual">{content.status.manual}</Badge>
        </div>
      </div>
      <DetailLink href={href} title={item.title} />
    </li>
  );
}

/** 검토 폴링 중 — BE 가 알려 주는 단계 목록 (아직 없으면 뼈대) */
function Progress({ progress }: { progress: ReviewProgress | null }) {
  const steps = progress?.steps ?? [];
  return (
    <div className={styles.progress}>
      <ReportNotice tone="loading" title={reviewProgressText(progress)} />
      {steps.length > 0 ? (
        <ol className={styles.steps} aria-label={content.report.rules.progressTitle}>
          {steps.map((s, i) => {
            const icon = STEP_ICON[s.state];
            return (
              <li key={`${s.step}:${i}`} className={cx(styles.step, styles[`p_${s.state}`])}>
                <span className={styles.stepMark} aria-hidden="true">
                  {icon ? <Icon name={icon} size={12} className={s.state === 'running' ? styles.spin : undefined} /> : i + 1}
                </span>
                {s.label}
              </li>
            );
          })}
        </ol>
      ) : (
        <Skeleton lines={6} />
      )}
    </div>
  );
}
