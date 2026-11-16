import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { isUuid } from '@/lib/api/beAdapters';
import { resolveSite } from '@/lib/api/parcel';
import { reviewById, reviewForParcel } from '@/lib/api/review';
import { ApiError, isPnu, type ManualCheck, type ReviewProgress, type RuleItem, type RuleReview } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { uniqueRuleIds } from '@/lib/ruleDetail/format';
import { isManualCategory, isRuleCardHash, parseRuleDetailParams, type DetailCategory, type ReviewTarget } from '@/lib/ruleDetail/params';
import { reportHref } from '@/lib/report/links';
import { useAsync } from '@/lib/useAsync';
import type { IconName } from '@/design/icons';
import { AsyncBlock, Skeleton } from '@/components/AsyncBlock';
import { Badge } from '@/components/Badge';
import { Icon } from '@/components/Icon';
import { StatusBadge } from '@/components/RuleItem';
import { DelegationSection, ManualSection, OverviewSection, RelatedBasisSection, SummarySection } from './RuleDetailSections';
import { SourceSection } from './RuleDetailSource';
import styles from './RuleDetailPage.module.css';

/** 검토를 불러온 결과 — 시범 지역 밖 필지는 검토를 만들지 않고 안내만 */
type DetailData = { kind: 'review'; review: RuleReview } | { kind: 'unsupported' };

/**
 * 검토 결과 불러오기.
 *  - review(검토 id)가 있으면 그 검토 (이 탭에서 이미 받았으면 다시 받지 않음, 새 검토를 만들지 않음)
 *  - 없으면 pnu 로 필지를 확인한 뒤 그 필지의 검토 (진행 중이거나 끝난 검토가 있으면 이어받음)
 */
async function loadDetail(target: ReviewTarget, signal: AbortSignal, onProgress: (p: ReviewProgress) => void): Promise<DetailData> {
  if (target.reviewId) return { kind: 'review', review: await reviewById(target.reviewId, { signal, onProgress }) };
  if (!target.pnu) throw new ApiError(content.ruleDetail.invalid.target, 'BAD_REQUEST');
  const site = await resolveSite(target.pnu, signal);
  if (site.kind !== 'parcel') throw new ApiError(content.ruleDetail.parcelPending, 'NOT_FOUND');
  // 시범 지역 밖 필지는 BE 도 빈 결과만 주므로 검토를 요청하지 않습니다 (검토 생성은 요청 수 제한이 있습니다)
  if (!site.parcel.inSuseong) return { kind: 'unsupported' };
  return { kind: 'review', review: await reviewForParcel(site.parcel, { signal, onProgress }) };
}

/**
 * 법규 상세 (/report/rules/:category?pnu=…&review=…).
 * 결과 리포트의 법규 검토 항목 하나를 펼쳐 봅니다: 한눈에 보기(기준·적용값) → 요약 → 법규 원문(인용 강조) → 위임 관계.
 * 별도 확인 항목(접도·건축선·높이·특화구역)은 자동 판정하지 않으므로 요약 대신 확인할 내용과 관련 근거를 보여 줍니다.
 *
 * 주소의 값은 모양(항목 이름·PNU 19자리·UUID)이 맞을 때만 쓰고, 틀린 값은 화면·링크·요청 어디에도 싣지 않습니다.
 */
export function RuleDetailPage() {
  const { category: rawCategory } = useParams();
  const [search] = useSearchParams();
  const { category, pnu, reviewId } = parseRuleDetailParams(rawCategory, search);
  const hasTarget = Boolean(pnu || reviewId);
  const enabled = Boolean(category && hasTarget);

  // 진행 상태는 이 검토 대상의 것만 (다른 필지로 넘어가면 이전 진행 표시를 버립니다)
  const targetKey = `${pnu ?? ''}|${reviewId ?? ''}`;
  const [progress, setProgress] = useState<{ key: string; value: ReviewProgress } | null>(null);
  const state = useAsync(
    (signal) => loadDetail({ pnu, reviewId }, signal, (value) => setProgress({ key: targetKey, value })),
    [pnu, reviewId],
    enabled,
  );
  const currentProgress = progress?.key === targetKey ? progress.value : null;

  // 결과 리포트와 같은 스크롤 상자를 쓰므로, 항목을 열면 맨 위부터 보이게 합니다 (그리기 전에 — 깜빡임 없이).
  // 주소에 #rule-<id> 가 있으면(새로고침·공유한 '원문으로' 주소) 맨 위로 올리지 않고, 그 원문 카드가 불러와진 뒤 직접 찾아갑니다
  const topRef = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    if (isRuleCardHash(window.location.hash)) return;
    topRef.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
  }, [rawCategory, pnu, reviewId]);

  // 돌아갈 결과 리포트 — 검토를 받았으면 그 검토의 필지·id 로 (주소에 pnu 만 있었어도 review 를 붙여 다시 검토하지 않게)
  const loaded = state.status === 'success' && state.data?.kind === 'review' ? state.data.review : null;
  const backTarget: ReviewTarget = {
    pnu: loaded && isPnu(loaded.parcel.pnu) ? loaded.parcel.pnu : pnu,
    reviewId: loaded && isUuid(loaded.reviewId) ? loaded.reviewId : reviewId,
  };
  const backHref = reportHref(backTarget.pnu ?? '', backTarget.reviewId);
  const t = content.ruleDetail;

  return (
    <article className={styles.page} ref={topRef}>
      {hasTarget && (
        <Link to={backHref} className={styles.back} data-print="hide" aria-label={t.backAria}>
          <Icon name="arrow-left" size={16} />
          {t.back}
        </Link>
      )}

      {!category || !hasTarget ? (
        <Notice icon="alert-circle" title={t.invalid.title} desc={!category ? t.invalid.category : t.invalid.target} />
      ) : (
        <AsyncBlock state={state} skeleton={<LoadingView progress={currentProgress} />}>
          {(data) =>
            data.kind === 'unsupported' ? (
              <Notice icon="info" title={content.site.unsupported.title} desc={content.site.unsupported.desc} />
            ) : (
              <Detail review={data.review} category={category} backHref={backHref} />
            )
          }
        </AsyncBlock>
      )}
    </article>
  );
}

function Detail({ review, category, backHref }: { review: RuleReview; category: DetailCategory; backHref: string }) {
  const t = content.ruleDetail;
  if (!review.supported) return <Notice icon="info" title={content.site.unsupported.title} desc={content.site.unsupported.desc} />;
  if (isManualCategory(category)) {
    const manual = review.manual.find((m) => m.id === category);
    if (!manual) return <Notice icon="info" title={t.notFound.title} desc={t.notFound.desc} />;
    return <ManualDetail review={review} item={manual} backHref={backHref} />;
  }
  const item = review.items.find((i) => i.id === category);
  if (!item) return <Notice icon="info" title={t.notFound.title} desc={t.notFound.desc} />;
  return <ItemDetail review={review} item={item} backHref={backHref} />;
}

/** 핵심 법규 항목: 한눈에 보기 → 요약 → 법규 원문 → 위임 관계 */
function ItemDetail({ review, item, backHref }: { review: RuleReview; item: RuleItem; backHref: string }) {
  const reason = item.reasonCode ? ((content.site.rules.reasons as Record<string, string>)[item.reasonCode] ?? null) : null;
  const ruleIds = uniqueRuleIds(item.basis);
  // 요약이 없을 때 가리킬 곳 — 원문 카드가 있으면 원문, 없으면 근거 인용, 그것도 없으면 연결된 규칙이 없다는 사실만
  const st = content.ruleDetail.summary;
  const summaryEmpty = ruleIds.length > 0 ? st.none : item.basis.length > 0 ? st.noneBasis : st.noneEmpty;
  return (
    <>
      <DetailHeader review={review} title={item.title} badge={<StatusBadge status={item.status} />}>
        {reason && (
          <p className={styles.reason}>
            <Icon name="info" size={14} />
            {reason}
          </p>
        )}
      </DetailHeader>
      <OverviewSection item={item} />
      <SummarySection summary={item.summary} emptyText={summaryEmpty} />
      <SourceSection
        ruleIds={ruleIds}
        asOfDate={review.asOfDate}
        basis={item.basis}
        itemTitle={item.title}
        reasonCode={item.reasonCode}
        notices={review.dataNotices}
      />
      {/* 원문 규칙이 없으면 근거 인용은 위 '법규 원문' 안에 이미 보여 주므로 따로 두지 않습니다 */}
      {ruleIds.length > 0 && <DelegationSection nodes={item.basis} linkRules />}
      <DetailFooter review={review} backHref={backHref} />
    </>
  );
}

/** 별도 확인 항목: 자동 판정하지 않음 — 확인할 내용 → 관련 근거 (→ 승인된 규칙이 걸려 있으면 원문) */
function ManualDetail({ review, item, backHref }: { review: RuleReview; item: ManualCheck; backHref: string }) {
  const ruleIds = uniqueRuleIds(item.relatedBasis);
  return (
    <>
      <DetailHeader
        review={review}
        title={item.title}
        badge={
          <Badge variant="manual" icon="info">
            {content.status.manual}
          </Badge>
        }
      />
      <ManualSection item={item} />
      <RelatedBasisSection nodes={item.relatedBasis} linkRules={ruleIds.length > 0} />
      {ruleIds.length > 0 && (
        <SourceSection
          ruleIds={ruleIds}
          asOfDate={review.asOfDate}
          basis={item.relatedBasis}
          itemTitle={item.title}
          reasonCode={null}
          notices={[]}
        />
      )}
      <DetailFooter review={review} backHref={backHref} />
    </>
  );
}

function DetailHeader({ review, title, badge, children }: { review: RuleReview; title: string; badge: ReactNode; children?: ReactNode }) {
  const t = content.ruleDetail;
  const p = review.parcel;
  const address = p.roadAddress ?? p.jibunAddress;
  const jibun = p.roadAddress && p.jibunAddress && p.jibunAddress !== p.roadAddress ? p.jibunAddress : null;
  return (
    <header className={styles.header}>
      <span className={styles.eyebrow}>{t.eyebrow}</span>
      <div className={styles.titleRow}>
        <h1 className={styles.title}>{title}</h1>
        {badge}
      </div>
      <p className={styles.meta}>
        {address && (
          <span className={styles.address}>
            <Icon name="map-pin" size={14} />
            {address}
          </span>
        )}
        {jibun && <span>{jibun}</span>}
        {isPnu(p.pnu) && <span className="numeric">{fmt(t.pnu, { pnu: p.pnu })}</span>}
        {review.asOfDate && <span className="numeric">{fmt(t.asOf, { date: review.asOfDate })}</span>}
      </p>
      {children}
    </header>
  );
}

function DetailFooter({ review, backHref }: { review: RuleReview; backHref: string }) {
  return (
    <footer className={styles.footer}>
      {review.disclaimer && <p className={styles.disclaimer}>{review.disclaimer}</p>}
      <Link to={backHref} className={styles.footerLink} data-print="hide">
        <Icon name="arrow-left" size={16} />
        {content.ruleDetail.footer.back}
      </Link>
    </footer>
  );
}

/** 잘못된 주소 · 항목 없음 · 시범 지역 밖 — 안내와 주소 검색으로 가는 길 (결과 리포트로 가는 링크는 페이지 맨 위에) */
function Notice({ icon, title, desc }: { icon: IconName; title: string; desc: string }) {
  return (
    <div className={styles.notice}>
      <Icon name={icon} size={22} className={styles.noticeIcon} />
      <div className={styles.noticeBody}>
        <h1 className={styles.noticeTitle}>{title}</h1>
        <p className={styles.noticeDesc}>{desc}</p>
        <Link to="/app" className={styles.noticeLink}>
          <Icon name="search" size={16} />
          {content.ruleDetail.invalid.toSearch}
        </Link>
      </div>
    </div>
  );
}

function LoadingView({ progress }: { progress: ReviewProgress | null }) {
  const running = progress?.steps.find((s) => s.state === 'running');
  return (
    <div className={styles.loading}>
      <p className={styles.loadingText} aria-live="polite">
        <Icon name="loader" size={14} className={styles.spin} />
        {running ? fmt(content.site.rules.reviewingStep, { label: running.label }) : content.ruleDetail.loading}
      </p>
      <Skeleton lines={2} />
      <div className={styles.section}>
        <Skeleton lines={3} />
      </div>
      <div className={styles.section}>
        <Skeleton lines={5} />
      </div>
    </div>
  );
}
