import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { isPnu, type ExistingBuildingsMode, type LayoutAlternative, type Parcel, type ReviewProgress } from '@/lib/api/types';
import { isUuid } from '@/lib/api/beAdapters';
import { resolveSite } from '@/lib/api/parcel';
import { getCachedHit } from '@/lib/api/hitCache';
import { isParkingNotPermitted, reviewById, reviewForParcel, simulationFor } from '@/lib/api/review';
import { useAsync } from '@/lib/useAsync';
import type { TileProvider } from '@/lib/map/tileSource';
import { buildResultDxf } from '@/lib/export/resultDxf';
import { downloadText } from '@/lib/export/download';
import { parkingVerdict } from '@/lib/report/verdict';
import { hasRunningStep, reportSteps } from '@/lib/report/steps';
import { landUseParcelFromReview, landUseRows } from '@/lib/report/landUseRows';
import { dxfFilename, dxfNotes, dxfTitle, pdfTitle, regionLabel, reportAddress } from '@/lib/report/exportNames';
import { siteHref } from '@/lib/report/links';
import {
  buildingOverlay,
  buildingSourceLine,
  canChooseBuildings,
  dxfBuildingsStatus,
  pickAlternative,
  simulationForMode,
  type AlternativePick,
} from '@/lib/report/existingBuildings';
import {
  addressCandidate,
  checklistPhase,
  layoutBlocker,
  outsidePilot,
  ownToRemember,
  reviewOutcome,
  reviewSource,
  reviewUrlFix,
  sitePolygon,
  type OwnReview,
} from '@/lib/report/pageState';
import { mergeNotices, parcelSourceLine, reviewSourceLine, verdictChip } from '@/lib/report/summary';
import { printWithTitle, waitForAssets } from '@/lib/report/print';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Button } from '@/components/Button';
import { Skeleton } from '@/components/AsyncBlock';
import { useToast } from '@/components/Toast';
import { ErrorNotice, ReportNotice } from '@/components/report/ReportNotice';
import { StepIndicator } from '@/components/report/StepIndicator';
import { SiteMapCard } from './SiteMapCard';
import { LayoutCard } from './LayoutCard';
import { LandUseCard } from './LandUseCard';
import { RuleChecklistCard } from './RuleChecklistCard';
import { PrintRuleDetails } from './PrintRuleDetails';
import { ReportFootnotes } from './ReportFootnotes';
import styles from './ReportPage.module.css';

/**
 * 3단계 결과 리포트 (/report?pnu=…&review=…).
 *
 * 데이터: 필지(resolveSite) · 법규 검토(주소에 검토 id 가 있으면 그 검토를 GET 으로, 없으면 필지로 새 검토 — 탭 안에서 기억) ·
 * 배치 계산(simulationFor — 기존 건물 유지가 기본, '철거 가정'을 고르면 그 조건으로 한 번 더, 둘 다 탭 안에서 기억).
 * 검토가 정해지면 주소에 &review= 를 붙여 새로고침·인쇄·상세 페이지가 같은 검토를 다시 씁니다.
 * 어떤 검토를 부를지 · 주소 정리 · 법규 검토 카드 상태는 lib/report/pageState.ts 의 순수 함수가 정합니다 (화면 없이 시험).
 * 화면: 머리(주소 · PNU · 기준일 · 지역, 진행 단계, 저장 버튼) → 3단(현황 부지 · 주차 배치 결과 · 토지이용계획 + 법규 검토) → 안내·고지·출처.
 * 저장: PDF 는 인쇄 창(1쪽 = 머리 + 3단, 2쪽 = 법규 검토 상세 + 안내), CAD 는 대지 경계와 고른 배치안의 DXF(EPSG:5186).
 * 화면에 그린 결과(유지/철거 가정 · 고른 배치안)가 곧 저장 대상이라 PDF·DXF 가 화면과 같습니다.
 */
export function ReportPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const toast = useToast();
  const rootRef = useRef<HTMLDivElement>(null);
  const t = content.report;

  // 주소의 값은 모양을 검사한 것만 씁니다 — 조작된 값은 화면에 되비치지도, 서버로 보내지도 않습니다
  const rawPnu = params.get('pnu') ?? '';
  const pnu = isPnu(rawPnu) ? rawPnu : '';
  const rawReview = params.get('review') ?? '';
  const reviewParam = isUuid(rawReview) ? rawReview : '';

  const [progress, setProgress] = useState<ReviewProgress | null>(null);
  const [provider, setProvider] = useState<TileProvider | null>(null);
  const [printing, setPrinting] = useState(false);

  // ── 필지 ──
  const siteState = useAsync((signal) => resolveSite(pnu, signal), [pnu], Boolean(pnu));
  const site = siteState.status === 'success' ? siteState.data : null;
  const parcel = site?.kind === 'parcel' ? site.parcel : null;

  // ── 법규 검토 ──
  // 필지로 만든 검토의 id 는 기억해 둡니다: 그 id 를 주소에 덧붙여도 같은 요청으로 보고 다시 불러오지 않습니다
  const [own, setOwn] = useState<OwnReview | null>(null);
  const source = reviewSource({ pnu, reviewParam, own, parcelInSuseong: parcel ? parcel.inSuseong : null });
  const byId = source.kind === 'byId';
  const reviewState = useAsync(
    (signal) =>
      source.kind === 'byId'
        ? reviewById(source.id, { signal, onProgress: setProgress })
        : reviewForParcel(parcel as Parcel, { signal, onProgress: setProgress }),
    [pnu, source.kind === 'byId' ? source.id : ''],
    source.kind !== 'none',
  );
  // 다른 필지의 검토(mismatch)·없는 검토(gone)는 쓰지 않습니다 (아래 effect 가 주소에서 뺍니다)
  const { mismatch, gone: reviewGone } = reviewOutcome(pnu, source, reviewState);
  const loaded = reviewState.status === 'success' ? reviewState.data : null;
  const review = loaded && !mismatch ? loaded : null;

  // 필지가 바뀌면 진행 표시를 지우고, 필지로 만든 검토의 id 를 기억 (렌더 중 상태 조정 — effect 안의 setState 를 피합니다)
  const [progressPnu, setProgressPnu] = useState(pnu);
  if (progressPnu !== pnu) {
    setProgressPnu(pnu);
    setProgress(null);
  }
  const remember = ownToRemember(pnu, source, review, own);
  if (remember) setOwn(remember);

  // 주소 정리: 검토 id 를 덧붙이거나(새로고침·인쇄·상세 링크가 같은 검토를 쓰도록), 쓸 수 없는 id 를 뺍니다
  const reviewIdForUrl = review?.reviewId ?? null;
  useEffect(() => {
    const href = reviewUrlFix({ pnu, rawReview, reviewId: reviewIdForUrl, mismatch, gone: reviewGone });
    if (href) navigate(href, { replace: true });
  }, [pnu, rawReview, reviewIdForUrl, mismatch, reviewGone, navigate]);

  // ── 배치 계산 ──
  const polygon = sitePolygon(parcel, review);
  const reviewId = review?.reviewId ?? '';
  const simState = useAsync((signal) => simulationFor(reviewId, { signal }), [reviewId], Boolean(review?.supported) && polygon.length >= 3);
  const sim = simState.status === 'success' ? simState.data : null;

  // 참고 배치: 주차장 설치 가능 여부가 확인되지 않아 계산하지 않았을 때만, 버튼을 누르면
  const referenceAllowed = Boolean(
    sim && !sim.simulated && !isParkingNotPermitted(sim) && sim.constraintReasons.includes('PARKING_USE_NOT_CONFIRMED'),
  );
  const [referenceFor, setReferenceFor] = useState<string | null>(null);
  const refState = useAsync(
    (signal) => simulationFor(reviewId, { includeUnverified: true, signal }),
    [reviewId],
    referenceAllowed && referenceFor === reviewId,
  );
  const refData = refState.status === 'success' ? refState.data : null;
  const refSim = refData?.simulated ? refData : null;
  const onReference = useCallback(() => setReferenceFor(reviewId), [reviewId]);

  // 기존 건물: 유지(기본 계산) · 철거 가정(고르면 한 번 더 계산). 결과(검증 · 참고 배치)가 바뀌면 유지로 돌아갑니다
  const base = refSim ?? sim;
  const resultKey = `${reviewId}:${refSim ? 'reference' : 'verified'}`;
  const buildingChoice = canChooseBuildings(base);
  const [buildingPick, setBuildingPick] = useState<{ key: string; mode: ExistingBuildingsMode } | null>(null);
  const buildingMode: ExistingBuildingsMode = buildingChoice && buildingPick?.key === resultKey ? buildingPick.mode : 'keep';
  const withReference = refSim !== null;
  const ignoreState = useAsync(
    (signal) => simulationFor(reviewId, { includeUnverified: withReference, existingBuildings: 'ignore', signal }),
    [reviewId, withReference],
    buildingMode === 'ignore',
  );
  const onBuildingMode = useCallback((mode: ExistingBuildingsMode) => setBuildingPick({ key: resultKey, mode }), [resultKey]);

  // 지도에 그리는 결과와 고른 배치안 (기본: 추천안 — 유지/철거 가정을 바꾸면 같은 주차 방식, 없으면 추천안)
  const shown = simulationForMode(base, ignoreState.data, buildingMode);
  const alternatives = shown?.simulated ? (shown.result?.alternatives ?? []) : [];
  const [pick, setPick] = useState<({ key: string } & AlternativePick) | null>(null);
  const selected = pickAlternative(alternatives, pick?.key === resultKey ? pick : null, shown?.result?.recommendedCode);
  const onSelect = useCallback((a: LayoutAlternative) => setPick({ key: resultKey, code: a.code, angle: a.angle }), [resultKey]);

  // ── 판정 · 단계 ──
  const verdict = parkingVerdict(review, sim);
  const pendingParcel = site?.kind === 'pending';
  const unsupported = outsidePilot(site, review, byId);
  const siteSettled = siteState.status === 'success' || siteState.status === 'error';
  const steps = reportSteps({
    site: siteState.status,
    siteMissing: pendingParcel,
    // 필지 조회가 실패해도 검토의 필지 스냅숏으로 대지를 그렸으면 대지 입력은 완료
    siteFromSnapshot: parcel === null && review !== null && polygon.length >= 3,
    review: mismatch || reviewGone ? 'loading' : reviewState.status,
    reviewSkipped: unsupported,
    layout: refState.status === 'loading' || ignoreState.status === 'loading' ? 'loading' : simState.status,
    layoutSkipped:
      unsupported ||
      (polygon.length < 3 && siteSettled && reviewState.status !== 'loading') ||
      Boolean(sim && !sim.simulated && !isParkingNotPermitted(sim) && !refSim),
  });
  const running = hasRunningStep(steps);
  const checking =
    !unsupported && !steps.some((s) => s.state === 'failed') && steps.some((s) => s.state === 'running' || s.state === 'pending');
  const phase = checklistPhase({
    unsupported,
    hasReview: review !== null,
    review: reviewState.status,
    mismatch,
    gone: reviewGone,
    site: siteState.status,
    pendingParcel,
  });

  // ── 표시 값 ──
  const address = reportAddress(addressCandidate(site, review, siteState.status === 'error' ? getCachedHit(pnu) : null));
  const region = regionLabel(parcel?.address.jibun ?? review?.parcel.jibunAddress, parcel?.address.gu);
  const asOfDate = review?.asOfDate || sim?.asOfDate || null;
  const area = parcel?.area ?? review?.parcel.areaM2 ?? null;
  const metaLine = [fmt(t.metaPnu, { pnu }), asOfDate ? fmt(t.metaAsOf, { date: asOfDate }) : '', region].filter(Boolean).join(' · ');

  const blocker = layoutBlocker({
    site: siteState,
    review: reviewState,
    sim: simState,
    hasReview: review !== null,
    hasPolygon: polygon.length >= 3,
    unsupported,
    pendingParcel,
    progress,
  });
  // 지도에 실제로 그린 결과 — 저장(DXF)과 현황 부지의 기존 건물도 이것을 따릅니다
  const drawnSim = !blocker && verdict.kind !== 'not_permitted' && shown?.simulated ? shown : null;
  const drawnAlt = drawnSim ? selected : null;
  const buildings = buildingOverlay(drawnSim);

  const landParcel = parcel ?? (review ? landUseParcelFromReview(review.parcel) : null);
  const rows = landParcel ? landUseRows({ parcel: landParcel, verdict, alternative: drawnAlt }) : null;

  // 경계·필지 정보가 없을 때 두 카드(현황 부지 · 토지이용계획)에 보여 줄 안내 — 다시 시도 버튼은 현황 부지 카드에만
  const siteLoading =
    siteState.status === 'loading' || siteState.status === 'idle' || (siteState.status === 'error' && reviewState.status === 'loading');
  const parcelFallback = (withRetry: boolean) =>
    siteLoading ? (
      <Skeleton lines={8} />
    ) : siteState.status === 'error' ? (
      <ErrorNotice error={siteState.error} onRetry={withRetry ? siteState.reload : undefined} />
    ) : pendingParcel ? (
      <ReportNotice title={content.site.pending.title}>
        <p>{content.site.pending.desc}</p>
      </ReportNotice>
    ) : (
      <ReportNotice>
        <p>{t.site.noPolygon}</p>
      </ReportNotice>
    );

  const notices = mergeNotices(review?.dataNotices, parcel?.dataNotices);
  const disclaimers = [...new Set([review?.disclaimer, sim?.disclaimer].filter((d): d is string => Boolean(d)))];
  const sources = [
    parcel ? parcelSourceLine(parcel) : '',
    review?.supported ? reviewSourceLine(review) : '',
    buildingSourceLine(drawnSim) ?? '',
  ].filter(Boolean);

  // ── 저장 ──
  // 인쇄 창이 열린 채 화면을 떠나면 문서 제목을 되돌립니다
  const restoreTitle = useRef<(() => void) | null>(null);
  // 그림을 기다리는(최대 몇 초) 사이 화면을 떠났거나 다른 필지로 바뀌었는지 — 그러면 인쇄하지 않습니다
  const mounted = useRef(false);
  const currentPnu = useRef(pnu);
  useEffect(() => {
    currentPnu.current = pnu;
  }, [pnu]);
  useEffect(() => {
    mounted.current = true;
    const ref = restoreTitle;
    return () => {
      mounted.current = false;
      ref.current?.();
    };
  }, []);

  const onPdf = async () => {
    if (printing) return;
    const forPnu = pnu;
    setPrinting(true);
    try {
      const images = rootRef.current ? Array.from(rootRef.current.querySelectorAll('img')) : [];
      await waitForAssets(images, document.fonts?.ready ?? null);
    } finally {
      if (mounted.current) setPrinting(false);
    }
    if (!mounted.current || currentPnu.current !== forPnu) return;
    restoreTitle.current?.();
    restoreTitle.current = printWithTitle(pdfTitle(address, pnu, asOfDate), { doc: document, win: window });
  };

  const onCad = () => {
    if (polygon.length < 3) return;
    const parcelForDxf = {
      pnu,
      polygon,
      area,
      address: {
        jibun: parcel?.address.jibun ?? review?.parcel.jibunAddress ?? '',
        road: parcel?.address.road ?? review?.parcel.roadAddress ?? undefined,
      },
    };
    const file = dxfFilename(address, pnu, drawnAlt, dxfBuildingsStatus(drawnSim) === 'ignored');
    const dxf = buildResultDxf({
      parcel: parcelForDxf,
      alternative: drawnAlt,
      buildings: buildings?.footprints,
      title: dxfTitle(address, pnu, drawnAlt),
      notes: dxfNotes(pnu, asOfDate, Boolean(drawnAlt && shown?.referenceOnly), dxfBuildingsStatus(drawnSim)),
    });
    downloadText(file, dxf, 'application/dxf');
    toast.show(fmt(t.actions.cadSaved, { file }));
  };

  if (!pnu) {
    return (
      <div className={styles.page}>
        <ReportNotice
          tone="warning"
          title={rawPnu ? content.site.badRequest : content.site.noPnu}
          action={
            <Button variant="primary" size="sm" iconLeft="search" onClick={() => navigate('/app')}>
              {t.actions.home}
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className={styles.page} ref={rootRef}>
      <header className={styles.header}>
        <p className={styles.printBrand}>{t.printBrand}</p>
        <div className={styles.titleBlock}>
          <span className={styles.eyebrow}>{t.eyebrow}</span>
          {address ? (
            <h1 className={styles.title}>{address}</h1>
          ) : siteLoading ? (
            <Skeleton lines={1} className={styles.titleSkeleton} />
          ) : (
            <h1 className={cx(styles.title, styles.titleUnknown)}>{content.site.addressUnknown}</h1>
          )}
          <p className={cx(styles.meta, 'numeric')}>{metaLine}</p>
        </div>
        <div className={styles.side}>
          <StepIndicator steps={steps} />
          <div className={styles.actions} data-print="hide" role="group" aria-label={t.actions.label}>
            <Button variant="ghost" size="sm" iconLeft="arrow-left" onClick={() => navigate(siteHref(pnu))}>
              {t.actions.back}
            </Button>
            <Button
              variant="secondary"
              size="sm"
              iconLeft="file-text"
              loading={printing}
              disabled={running}
              title={t.actions.pdfHint}
              onClick={() => void onPdf()}
            >
              {t.actions.pdf}
            </Button>
            <Button
              variant="primary"
              size="sm"
              iconLeft="download"
              disabled={polygon.length < 3}
              title={polygon.length < 3 ? t.actions.cadDisabled : t.actions.cadHint}
              onClick={onCad}
            >
              {t.actions.cad}
            </Button>
          </div>
        </div>
      </header>

      <div className={styles.grid}>
        <SiteMapCard
          address={address}
          polygon={polygon}
          area={area}
          region={region}
          provider={provider}
          onProvider={setProvider}
          buildings={buildings}
          loading={siteLoading}
          fallback={parcelFallback(true)}
        />
        <LayoutCard
          address={address}
          polygon={polygon}
          blocker={blocker}
          verdict={verdict}
          sim={sim}
          shown={shown}
          selected={selected}
          onSelect={onSelect}
          reference={{ allowed: referenceAllowed, state: refState, onRequest: onReference }}
          buildings={{ available: buildingChoice, mode: buildingMode, state: ignoreState, onChange: onBuildingMode }}
          onProvider={setProvider}
        />
        <div className={styles.aside}>
          <LandUseCard rows={rows} fallback={parcelFallback(false)} />
          <RuleChecklistCard
            chip={verdictChip(verdict, checking)}
            review={review}
            phase={phase}
            error={reviewState.error}
            onRetry={reviewState.reload}
            progress={progress}
            pnu={pnu}
          />
        </div>
      </div>

      {/* 인쇄 2쪽: 법규 검토 상세(화면에서는 숨김 — 항목별 '상세 보기' 페이지가 대신) + 안내·고지·출처 */}
      <div data-print="page-break" className={styles.after}>
        <PrintRuleDetails review={review} />
        <ReportFootnotes notices={notices} disclaimers={disclaimers} sources={sources} />
      </div>
    </div>
  );
}
