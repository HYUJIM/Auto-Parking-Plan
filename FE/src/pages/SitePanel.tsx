import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  isPnu,
  type AddressHit,
  type DataNotice,
  type LandUse,
  type Parcel,
  type ParcelBuilding,
  type ReviewProgress,
  type RuleReview,
  type RuleStatus,
  type SiteData,
} from '@/lib/api/types';
import { resolveSite } from '@/lib/api/parcel';
import { reviewForParcel } from '@/lib/api/review';
import { describeApiError } from '@/lib/api/errors';
import { getCachedHit } from '@/lib/api/hitCache';
import { addRecentSearch } from '@/lib/api/recent';
import { useAsync, type AsyncState } from '@/lib/useAsync';
import { reportHref, siteHref } from '@/lib/report/links';
import { useMap } from '@/lib/map/MapContext';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatArea, formatInt } from '@/lib/format';
import { relationVariant } from '@/lib/landUse';
import type { IconName } from '@/design/icons';
import { AsyncBlock, Skeleton } from '@/components/AsyncBlock';
import { Badge, type BadgeVariant } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { SearchBox } from '@/components/SearchBox';
import { StatCard } from '@/components/StatCard';
import {
  countReviewStatuses,
  currentStep,
  progressSteps,
  reviewSummaryVars,
  statusCountList,
  type ProgressStep,
  type StepState,
} from './siteReviewSummary';
import styles from './SitePanel.module.css';

/**
 * 2단계 — 실제 이용화면 (/site?pnu=…) 좌측 패널.
 * 상단: 검색창(재검색 가능) + 주소·PNU. 본문: 데이터 안내 · 대지 요약 · 법규 검토 진행 상태(한 줄 요약) · 토지이용계획 · 건축물.
 * 하단 고정: '결과 보기' → 결과 리포트(/report?pnu=…&review=…). 검토 항목·주차 배치·PDF·CAD 저장은 결과 리포트에서 봅니다.
 *
 * 필지(GET /v1/parcels/{pnu})를 받으면 법규 검토(POST /v1/reviews → 폴링)를 바로 시작합니다. 검토는 탭 안에서 기억되므로(reviewForParcel)
 * 검토 중에 '결과 보기'를 눌러도 결과 리포트가 같은 검토를 이어받고 새로 만들지 않습니다.
 * BE 에 없는 PNU(404)는 주소·좌표만으로 '대기' 화면을, 시범 지역 밖 필지는 법규 검토 대신 안내를 보여 줍니다.
 */
export function SitePanel() {
  const [params] = useSearchParams();
  // ?pnu= 는 숫자 19자리만 믿습니다 — 조작된 값은 화면에 되비치지도, 서버로 보내지도 않습니다
  const rawPnu = params.get('pnu') ?? '';
  const pnuValid = isPnu(rawPnu);
  const pnu = pnuValid ? rawPnu : '';
  const navigate = useNavigate();
  const { setScene, setPreviewPins } = useMap();
  /** 진행 상태는 필지별로 — 다른 필지의 늦은 알림이 지금 필지에 보이지 않게 */
  const [progress, setProgress] = useState<{ pnu: string; value: ReviewProgress } | null>(null);
  const headRef = useRef<HTMLElement>(null);
  const hintId = useId();

  const siteState = useAsync((signal) => resolveSite(pnu, signal), [pnu], Boolean(pnu));
  const site = siteState.status === 'success' ? siteState.data : null;
  const parcel = site?.kind === 'parcel' ? site.parcel : null;
  // 시범 지역 밖 필지는 BE 도 빈 결과만 주므로 검토를 요청하지 않습니다
  const reviewEnabled = Boolean(parcel?.inSuseong);
  // 화면을 떠나면 이 화면의 기다림만 끊고(signal), 검토 자체는 이어져 결과 리포트가 이어받습니다
  const reviewPnu = parcel?.pnu ?? '';
  const rulesState = useAsync(
    (signal) => reviewForParcel(parcel as Parcel, { signal, onProgress: (value) => setProgress({ pnu: reviewPnu, value }) }),
    [reviewPnu],
    reviewEnabled,
  );
  // 필지가 바뀌면 진행 표시를 지웁니다 (렌더 중 상태 조정) — 같은 필지로 돌아와 새 검토가 시작돼도 지난 검토의 단계 목록이 보이지 않게
  const [progressFor, setProgressFor] = useState(reviewPnu);
  if (progressFor !== reviewPnu) {
    setProgressFor(reviewPnu);
    setProgress(null);
  }
  const reviewProgress = progress && progress.pnu === reviewPnu ? progress.value : null;
  // 다시 시도는 새 검토입니다 — 실패한 시도의 단계 목록을 지우고 처음부터 보여 줍니다
  const retryReview = () => {
    setProgress(null);
    rulesState.reload();
  };
  /** 필지 조회가 실패해도 주소·PNU 는 검색 결과(세션 캐시)로 보여 줍니다 */
  const cachedHit = getCachedHit(pnu);

  // 필지가 바뀌면 남은 미리보기 핀은 지우고, 키보드·보조기술 초점을 패널 상단으로
  useEffect(() => {
    setPreviewPins([]);
    headRef.current?.focus({ preventScroll: true });
  }, [pnu, setPreviewPins]);

  // 지도 장면 + 최근 검색 기록 (배치안 레이어는 결과 리포트에서만 그립니다)
  useEffect(() => {
    if (!site) return;
    if (site.kind === 'parcel') {
      const p = site.parcel;
      addRecentSearch({ pnu: p.pnu, label: p.address.road ?? p.address.jibun });
      if (p.polygon.length >= 3) setScene({ kind: 'site', parcel: p });
      else if (p.center) setScene({ kind: 'point', pnu: p.pnu, center: p.center, title: p.address.road ?? p.address.jibun });
      else setScene({ kind: 'overview' });
      return;
    }
    if (site.hit) addRecentSearch({ pnu: site.pnu, label: site.hit.roadAddress });
    if (site.hit?.center) {
      setScene({ kind: 'point', pnu: site.pnu, center: site.hit.center, title: site.hit.roadAddress });
    } else {
      setScene({ kind: 'overview' });
    }
  }, [site, setScene]);

  useEffect(() => () => setPreviewPins([]), [setPreviewPins]);

  const onSelect = useCallback((hit: AddressHit) => navigate(siteHref(hit.pnu)), [navigate]);
  const onResults = useCallback((hits: AddressHit[]) => setPreviewPins(hits), [setPreviewPins]);

  const t = content.site;
  const currentAddress =
    parcel?.address.road ??
    parcel?.address.jibun ??
    (site?.kind === 'pending' ? (site.hit?.roadAddress ?? '') : siteState.status === 'error' ? (cachedHit?.roadAddress ?? '') : '');

  // 결과 보기 — 필지만 받으면 열 수 있습니다. 검토가 끝났으면 검토 id 를 함께 넘겨 결과 리포트가 바로 그 검토를 씁니다
  const reviewId = rulesState.status === 'success' ? (rulesState.data?.reviewId ?? null) : null;
  const canOpenResult = Boolean(pnu && parcel);
  const onOpenResult = () => {
    if (canOpenResult) navigate(reportHref(pnu, reviewId));
  };
  const footerHint = site?.kind === 'pending' || siteState.status === 'error' ? t.footer.needsParcel : t.footer.exportHint;

  return (
    <div className={styles.panel}>
      <header className={styles.head} ref={headRef} tabIndex={-1}>
        <div className={styles.headRow}>
          <Button variant="icon" size="sm" icon="arrow-left" aria-label={t.back} onClick={() => navigate('/app')} />
          <div className={styles.searchWrap}>
            <SearchBox key={pnu} compact initialQuery={currentAddress} onSelect={onSelect} onResults={onResults} />
          </div>
        </div>

        {!rawPnu ? (
          <p className={styles.noPnu}>{t.noPnu}</p>
        ) : !pnuValid ? (
          <p className={styles.noPnu}>{t.badRequest}</p>
        ) : siteState.status === 'error' ? (
          // 조회 실패: 주소·PNU 는 유지하고, 다시 시도는 아래 본문 카드 한 곳에서만
          <AddressBlock site={{ kind: 'pending', pnu, hit: cachedHit }} />
        ) : (
          <AsyncBlock state={siteState} skeleton={<Skeleton lines={2} className={styles.addressSkeleton} />}>
            {(data) => <AddressBlock site={data} />}
          </AsyncBlock>
        )}
      </header>

      <div className={styles.body}>
        {pnu && (
          <AsyncBlock state={siteState} skeleton={<Skeleton lines={6} />}>
            {(data) =>
              data.kind === 'parcel' ? (
                // 필지가 바뀌면 접기·펼치기 같은 카드 안 상태를 새로
                <div key={data.parcel.pnu} className={styles.summary}>
                  <NoticeBanner notices={data.parcel.dataNotices} />
                  <SummaryGrid parcel={data.parcel} />
                  {data.parcel.inSuseong ? (
                    <ReviewStatus state={rulesState} progress={reviewProgress} onRetry={retryReview} />
                  ) : (
                    <Unsupported />
                  )}
                  {data.parcel.landUses && <LandUsePlan landUses={data.parcel.landUses} />}
                  {data.parcel.buildings.length > 0 && <Buildings buildings={data.parcel.buildings} />}
                </div>
              ) : (
                <div className={styles.summary}>
                  <PendingNotice site={data} />
                  {data.hit && !data.hit.inSuseong && <Unsupported />}
                </div>
              )
            }
          </AsyncBlock>
        )}

        {parcel && (
          <div className={styles.sources}>
            <p className={styles.source}>{parcelSourceLine(parcel)}</p>
          </div>
        )}
      </div>

      <footer className={styles.footer}>
        <Button
          variant="primary"
          iconRight="arrow-right"
          block
          disabled={!canOpenResult}
          aria-describedby={pnu ? hintId : undefined}
          onClick={onOpenResult}
        >
          {t.footer.result}
        </Button>
        {pnu && (
          <p id={hintId} className={styles.footerHint}>
            {footerHint}
          </p>
        )}
      </footer>
    </div>
  );
}

function parcelSourceLine(parcel: Parcel): string {
  const t = content.site;
  if (parcel.isExample) return t.sourceParcelExample;
  const providers = parcel.source.providers.join(' · ') || '—';
  return parcel.source.queriedAt
    ? fmt(t.sourceParcel, { providers, date: parcel.source.queriedAt })
    : fmt(t.sourceParcelNoDate, { providers });
}

/** 주소·지번·PNU (+ 수성구 외 · 예시 데이터 · 대기 배지). 도로명주소가 없으면 지번을 제목으로 */
function AddressBlock({ site }: { site: SiteData }) {
  const t = content.site;
  const road = site.kind === 'parcel' ? site.parcel.address.road : site.hit?.roadAddress;
  const jibun = site.kind === 'parcel' ? site.parcel.address.jibun : site.hit?.jibunAddress;
  const pnu = site.kind === 'parcel' ? site.parcel.pnu : site.pnu;
  const inSuseong = site.kind === 'parcel' ? site.parcel.inSuseong : (site.hit?.inSuseong ?? true);
  const primary = road || jibun;
  const secondary = road && road !== jibun ? jibun : undefined;

  return (
    <div className={styles.address}>
      {primary ? <strong className={styles.road}>{primary}</strong> : <strong className={styles.roadUnknown}>{t.addressUnknown}</strong>}
      {secondary && <span className={styles.jibun}>{secondary}</span>}
      <span className={styles.meta}>
        <span className={cx(styles.pnu, 'numeric')}>
          {t.pnu} {pnu}
        </span>
        {!inSuseong && <Badge variant="manual">{content.search.outside}</Badge>}
        {site.kind === 'parcel' && site.parcel.isExample && <Badge variant="neutral">{t.exampleBadge}</Badge>}
        {site.kind === 'pending' && (
          <Badge variant="check" icon="alert-circle">
            {t.pending.badge}
          </Badge>
        )}
      </span>
    </div>
  );
}

/** BE data_notices — 조회 실패·미반영 개정 등 */
function NoticeBanner({ notices }: { notices: DataNotice[] }) {
  if (notices.length === 0) return null;
  return (
    <div className={styles.banner} role="note" aria-label={content.site.noticesLabel}>
      <Icon name="info" size={18} className={styles.bannerIcon} />
      <ul className={styles.bannerList}>
        {notices.map((n, i) => (
          <li key={`${n.code}:${i}`}>{n.message}</li>
        ))}
      </ul>
    </div>
  );
}

/** 대지 요약 카드: 대지면적(㎡·평) · 용도지역 · 지목 · 접도(도로접면) · 공시지가 */
function SummaryGrid({ parcel }: { parcel: Parcel }) {
  const s = content.site.summary;
  const areaHint = parcel.areaSource === 'geometry' ? s.areaGeometry : parcel.areaSource === 'ledger' ? s.areaLedger : undefined;
  const roadValue = parcel.road.access ?? s.roadUnknown;

  return (
    <div className={styles.grid}>
      <StatCard
        label={s.area}
        hint={areaHint}
        value={parcel.area !== null ? formatArea(parcel.area) : s.areaUnknown}
        unit={parcel.area !== null ? s.areaUnit : undefined}
        sub={
          parcel.area !== null && parcel.areaPyeong !== undefined
            ? fmt(s.areaPyeong, { pyeong: formatArea(parcel.areaPyeong, 1) })
            : undefined
        }
        numeric={parcel.area !== null}
        wide
      />
      <StatCard label={s.zoning} value={parcel.zoning || s.zoningUnknown} />
      <StatCard label={s.jimok} value={parcel.jimok || '—'} />
      {parcel.zoning2 && <StatCard label={s.zoning2} value={parcel.zoning2} wide />}
      <StatCard label={s.road} hint={parcel.road.access ? s.roadAccess : undefined} value={roadValue} wide />
      {parcel.landPrice !== undefined && (
        <StatCard label={s.landPrice} hint={s.landPriceHint} value={formatInt(parcel.landPrice)} unit={s.landPriceUnit} numeric wide />
      )}
    </div>
  );
}

/** 검토 상태 3값 → 개수 배지 모양 (RuleItem 의 StatusBadge 와 같은 색) */
const COUNT_BADGE: Record<RuleStatus, { variant: BadgeVariant; icon: IconName }> = {
  REVIEWABLE: { variant: 'ok', icon: 'check-circle' },
  CONDITIONAL: { variant: 'check', icon: 'alert-circle' },
  MANUAL_CHECK: { variant: 'manual', icon: 'info' },
};

const STEP_CLASS: Record<StepState, string | undefined> = {
  done: styles.stepDone,
  running: styles.stepRunning,
  pending: styles.stepPending,
  failed: styles.stepFailed,
};

/**
 * 법규 검토 진행 상태 — 검토 중이면 BE 단계(대지정보 → 용도지역 → 건축 법규 → 주차 기준 → 쉬운 설명),
 * 끝나면 상태별 개수 한 줄. 항목 내용·근거는 결과 리포트에서 봅니다.
 *
 * 맨 윗줄(ReviewStatusLine)은 검토 중 → 완료로 바뀌어도 같은 요소로 남는 알림 영역(role="status")이라,
 * 보조기술이 지금 단계와 완료 요약을 차례로 읽어 줍니다. 오류는 오류 카드의 role="alert" 가 알립니다.
 */
function ReviewStatus({
  state,
  progress,
  onRetry,
}: {
  state: AsyncState<RuleReview>;
  progress: ReviewProgress | null;
  onRetry: () => void;
}) {
  const r = content.site.review;
  const titleId = useId();
  const review = state.status === 'success' ? state.data : null;
  if (review && !review.supported) return <Unsupported />;
  const failed = state.status === 'error';
  const steps = progressSteps(progress);

  return (
    <section className={styles.review} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.reviewTitle}>
        <Icon name="shield-check" size={16} className={styles.reviewTitleIcon} />
        {r.title}
      </h3>
      <div className={styles.reviewBody}>
        <ReviewStatusLine review={review} steps={steps} failed={failed} />
        {failed ? (
          <ReviewError error={state.error} onRetry={onRetry} />
        ) : review ? (
          <ReviewDone review={review} />
        ) : (
          <ReviewRunning steps={steps} />
        )}
      </div>
    </section>
  );
}

/** 검토 상태 한 줄 — 늘 같은 <p role="status"> 를 그려 내용만 바꿉니다 (요소가 바뀌면 알림이 끊깁니다) */
function ReviewStatusLine({ review, steps, failed }: { review: RuleReview | null; steps: ProgressStep[]; failed: boolean }) {
  const r = content.site.review;
  const rr = content.site.rules;

  // 오류 때는 비워 두되 영역은 남깁니다 (다시 시도하면 다음 단계부터 다시 알리도록)
  if (failed) return <p role="status" className="visually-hidden" />;

  if (review) {
    const counts = countReviewStatuses(review);
    return (
      <p role="status" className={styles.reviewDone}>
        <Icon name="check-circle" size={16} className={styles.reviewDoneIcon} />
        <span>{r.done}</span>
        {/* 눈으로는 '핵심 법규 n항목' + 아래 개수 배지, 보조기술에는 같은 내용을 한 문장으로 */}
        <span className={styles.reviewCore} aria-hidden="true">
          {fmt(rr.coreTitle, { n: counts.total })}
        </span>
        <span className="visually-hidden">{fmt(r.summary, reviewSummaryVars(counts))}</span>
      </p>
    );
  }

  const current = currentStep(steps);
  return (
    <p role="status" className={styles.progress}>
      <Icon name="loader" size={14} className={styles.spin} />
      {current ? fmt(rr.reviewingStep, { label: current.label }) : rr.reviewing}
    </p>
  );
}

function ReviewRunning({ steps }: { steps: ProgressStep[] }) {
  const r = content.site.review;
  return (
    <>
      {steps.length > 0 ? (
        <ol className={styles.steps} aria-label={r.stepsLabel}>
          {steps.map((s) => (
            <li key={s.key} className={cx(styles.step, STEP_CLASS[s.state])}>
              <StepMark state={s.state} />
              <span className={styles.stepLabel}>{s.label}</span>
              <span className="visually-hidden">{r.stepState[s.state]}</span>
            </li>
          ))}
        </ol>
      ) : (
        <Skeleton lines={3} />
      )}
      <p className={styles.reviewNote}>{r.background}</p>
    </>
  );
}

function StepMark({ state }: { state: StepState }) {
  if (state === 'done') return <Icon name="check-circle" size={14} className={styles.stepIcon} />;
  if (state === 'running') return <Icon name="loader" size={14} className={cx(styles.stepIcon, styles.spin)} />;
  if (state === 'failed') return <Icon name="alert-circle" size={14} className={styles.stepIcon} />;
  return <span className={styles.stepDot} aria-hidden="true" />;
}

function ReviewDone({ review }: { review: RuleReview }) {
  const r = content.site.review;
  const rr = content.site.rules;
  const counts = countReviewStatuses(review);

  return (
    <>
      {/* 개수는 위 상태 줄이 문장으로 읽어 주므로 보조기술에서는 숨깁니다. 0건은 흐리게 하지 않고 중립 색으로 (글자 대비 유지) */}
      <ul className={styles.counts} aria-hidden="true">
        {statusCountList(counts).map(({ status, n }) => (
          <li key={status}>
            <Badge variant={n === 0 ? 'neutral' : COUNT_BADGE[status].variant} icon={COUNT_BADGE[status].icon}>
              {fmt(r.counts[status], { n })}
            </Badge>
          </li>
        ))}
      </ul>
      {counts.manualChecks > 0 && <p className={styles.reviewNote}>{fmt(r.manualChecks, { n: counts.manualChecks })}</p>}
      <p className={styles.reviewNext}>
        <Icon name="arrow-right" size={14} className={styles.reviewNextIcon} />
        {r.next}
      </p>
      <p className={styles.source}>{fmt(rr.source, { date: review.asOfDate, version: review.engineVersion ?? '—' })}</p>
    </>
  );
}

function ReviewError({ error, onRetry }: { error: Error | null; onRetry: () => void }) {
  const { title, desc, retryable } = describeApiError(error);
  return (
    <div className={cx(styles.reviewBody, styles.reviewError)} role="alert">
      <p className={styles.reviewErrorTitle}>
        <Icon name="alert-circle" size={16} className={styles.reviewErrorIcon} />
        {title}
      </p>
      {desc && <p className={styles.reviewNote}>{desc}</p>}
      {retryable && (
        <Button variant="secondary" size="sm" iconLeft="refresh" onClick={onRetry} className={styles.retry}>
          {content.status.retry}
        </Button>
      )}
    </div>
  );
}

/** 토지이용계획에서 처음 보여 줄 줄 수 — 나머지는 '더 보기' (전체 표는 결과 리포트) */
const LAND_USE_PREVIEW = 5;

function LandUsePlan({ landUses }: { landUses: LandUse[] }) {
  const s = content.site.summary;
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  const rest = landUses.length - LAND_USE_PREVIEW;
  const shown = expanded || rest <= 0 ? landUses : landUses.slice(0, LAND_USE_PREVIEW);

  return (
    <div className={styles.card}>
      <span className={styles.cardLabel}>{s.landUses}</span>
      {landUses.length ? (
        <>
          <ul id={listId} className={styles.useList}>
            {shown.map((u, i) => (
              <li key={`${u.code ?? ''}:${u.name}:${i}`} className={styles.useItem}>
                {u.relation ? <Badge variant={relationVariant(u.relation)}>{u.relation}</Badge> : <Badge variant="neutral">—</Badge>}
                <span className={styles.useName}>{u.name}</span>
                <span className={styles.useKind}>{s.landUseKinds[u.kind]}</span>
              </li>
            ))}
          </ul>
          {rest > 0 && (
            <Button
              variant="ghost"
              size="sm"
              iconRight={expanded ? 'chevron-up' : 'chevron-down'}
              aria-expanded={expanded}
              aria-controls={listId}
              onClick={() => setExpanded((e) => !e)}
              className={styles.more}
            >
              {expanded ? s.landUsesLess : fmt(s.landUsesMore, { n: rest })}
            </Button>
          )}
        </>
      ) : (
        <p className={styles.cardEmpty}>{s.landUsesEmpty}</p>
      )}
    </div>
  );
}

function Buildings({ buildings }: { buildings: ParcelBuilding[] }) {
  const s = content.site.summary;
  return (
    <div className={styles.card}>
      <span className={styles.cardLabel}>{s.buildings}</span>
      <ul className={styles.buildingList}>
        {buildings.map((b, i) => (
          <li key={i} className={styles.building}>
            {buildings.length > 1 && <span className={styles.buildingNo}>{fmt(s.buildingN, { n: i + 1 })}</span>}
            <dl className={styles.landInfo}>
              <div>
                <dt>{s.mainUse}</dt>
                <dd>{b.mainUse ?? '—'}</dd>
              </div>
              <div>
                <dt>{s.grossFloorArea}</dt>
                <dd className="numeric">{b.grossFloorArea !== null ? `${formatArea(b.grossFloorArea)}㎡` : '—'}</dd>
              </div>
              <div>
                <dt>{s.approvalDate}</dt>
                <dd className="numeric">{b.approvalDate ?? '—'}</dd>
              </div>
            </dl>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 주소·PNU 는 확정, 필지 데이터는 아직 — 좌표가 없으면 그 이유도 함께 */
function PendingNotice({ site }: { site: Extract<SiteData, { kind: 'pending' }> }) {
  const p = content.site.pending;
  return (
    <div className={styles.notice}>
      <Icon name="info" size={22} className={cx(styles.noticeIcon, styles.noticeInfo)} />
      <div>
        <strong>{p.title}</strong>
        <p>{p.desc}</p>
        {!site.hit?.center && <p className={styles.noticeSub}>{p.noCoord}</p>}
      </div>
    </div>
  );
}

function Unsupported() {
  const u = content.site.unsupported;
  return (
    <div className={styles.notice}>
      <Icon name="alert-circle" size={22} className={styles.noticeIcon} />
      <div>
        <strong>{u.title}</strong>
        <p>{u.desc}</p>
      </div>
    </div>
  );
}
