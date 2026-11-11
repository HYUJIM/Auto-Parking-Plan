import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { isPnu, type AddressHit, type DataNotice, type Parcel, type ReviewProgress, type RuleReview, type SiteData } from '@/lib/api/types';
import { resolveSite } from '@/lib/api/parcel';
import { getRuleReview } from '@/lib/api/review';
import { getCachedHit } from '@/lib/api/hitCache';
import { addRecentSearch } from '@/lib/api/recent';
import { useAsync } from '@/lib/useAsync';
import { useMap } from '@/lib/map/MapContext';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatArea, formatInt } from '@/lib/format';
import { AsyncBlock, Skeleton } from '@/components/AsyncBlock';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { ManualCheckItem, RuleItem } from '@/components/RuleItem';
import { relationVariant } from '@/lib/landUse';
import { SearchBox } from '@/components/SearchBox';
import { StatCard } from '@/components/StatCard';
import { LayoutTab } from './LayoutTab';
import styles from './SitePanel.module.css';

type Tab = 'summary' | 'rules' | 'layout';

/**
 * 2단계 — 실제 이용화면 (/site?pnu=…) 좌측 패널.
 * 상단: 검색창(재검색 가능) + 주소·PNU, 탭 3개(대지 요약 · 법규 검토 · 배치안), 하단 고정: 배치안 생성 · PDF 저장(다음 단계).
 *
 * 필지(GET /v1/parcels/{pnu})와 법규 검토(POST /v1/reviews → 폴링)는 따로 불러오므로 한쪽이 실패해도 다른 카드는 그대로 보입니다.
 * BE 에 없는 PNU(404)는 주소·좌표만으로 '대기' 화면을, 시범 지역 밖 필지는 법규·배치안 대신 안내를 보여 줍니다.
 */
export function SitePanel() {
  const [params] = useSearchParams();
  // ?pnu= 는 숫자 19자리만 믿습니다 — 조작된 값은 화면에 되비치지도, 서버로 보내지도 않습니다
  const rawPnu = params.get('pnu') ?? '';
  const pnuValid = isPnu(rawPnu);
  const pnu = pnuValid ? rawPnu : '';
  const navigate = useNavigate();
  const { setScene, setPreviewPins } = useMap();
  const [tab, setTab] = useState<Tab>('summary');
  const [layoutRun, setLayoutRun] = useState(0);
  const [progress, setProgress] = useState<ReviewProgress | null>(null);
  const headRef = useRef<HTMLElement>(null);

  const siteState = useAsync(() => resolveSite(pnu), [pnu], Boolean(pnu));
  const site = siteState.status === 'success' ? siteState.data : null;
  const parcel = site?.kind === 'parcel' ? site.parcel : null;
  // 시범 지역 밖 필지는 BE 도 빈 결과만 주므로 검토를 요청하지 않습니다
  const reviewEnabled = Boolean(parcel?.inSuseong);
  const rulesState = useAsync(() => getRuleReview(parcel as Parcel, { onProgress: setProgress }), [parcel?.pnu], reviewEnabled);
  /** 필지 조회가 실패해도 주소·PNU 는 검색 결과(세션 캐시)로 보여 줍니다 */
  const cachedHit = getCachedHit(pnu);

  // 필지가 바뀌면 요약 탭으로 (렌더 중 상태 조정 — effect 안의 setState 를 피합니다)
  const [tabPnu, setTabPnu] = useState(pnu);
  if (tabPnu !== pnu) {
    setTabPnu(pnu);
    setTab('summary');
    setLayoutRun(0);
    setProgress(null);
  }

  // 필지가 바뀌면 남은 미리보기 핀은 지우고, 키보드·보조기술 초점을 패널 상단으로
  useEffect(() => {
    setPreviewPins([]);
    headRef.current?.focus({ preventScroll: true });
  }, [pnu, setPreviewPins]);

  // 지도 장면 + 최근 검색 기록
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

  const onSelect = useCallback((hit: AddressHit) => navigate(`/site?pnu=${encodeURIComponent(hit.pnu)}`), [navigate]);
  const onResults = useCallback((hits: AddressHit[]) => setPreviewPins(hits), [setPreviewPins]);

  const t = content.site;
  const tabs: { id: Tab; label: string }[] = [
    { id: 'summary', label: t.tabs.summary },
    { id: 'rules', label: t.tabs.rules },
    { id: 'layout', label: t.tabs.layout },
  ];
  const currentAddress =
    parcel?.address.road ??
    parcel?.address.jibun ??
    (site?.kind === 'pending' ? (site.hit?.roadAddress ?? '') : siteState.status === 'error' ? (cachedHit?.roadAddress ?? '') : '');

  const review = rulesState.status === 'success' ? rulesState.data : null;
  const canLayout = Boolean(parcel && parcel.inSuseong && parcel.polygon.length >= 3 && review?.supported);
  const onLayoutRun = () => {
    setTab('layout');
    setLayoutRun((n) => n + 1);
  };

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

        <div className={styles.tabs} role="tablist">
          {tabs.map((tb) => (
            <button
              key={tb.id}
              type="button"
              role="tab"
              id={`tab-${tb.id}`}
              aria-selected={tab === tb.id}
              aria-controls={`panel-${tb.id}`}
              className={cx(styles.tab, tab === tb.id && styles.tabActive)}
              onClick={() => setTab(tb.id)}
            >
              {tb.label}
            </button>
          ))}
        </div>
      </header>

      <div className={styles.body}>
        {pnu && tab === 'summary' && (
          <div role="tabpanel" id="panel-summary" aria-labelledby="tab-summary">
            <AsyncBlock state={siteState} skeleton={<Skeleton lines={6} />}>
              {(data) => (data.kind === 'parcel' ? <SiteSummary parcel={data.parcel} /> : <PendingNotice site={data} />)}
            </AsyncBlock>
          </div>
        )}

        {pnu && tab === 'rules' && (
          <div role="tabpanel" id="panel-rules" aria-labelledby="tab-rules">
            <AsyncBlock state={siteState} skeleton={<Skeleton lines={9} />}>
              {(data) =>
                data.kind === 'parcel' ? (
                  data.parcel.inSuseong ? (
                    <AsyncBlock state={rulesState} skeleton={<ReviewSkeleton progress={progress} />}>
                      {(review) => (review.supported ? <RuleReviewView review={review} /> : <Unsupported />)}
                    </AsyncBlock>
                  ) : (
                    <Unsupported />
                  )
                ) : data.hit && !data.hit.inSuseong ? (
                  <Unsupported />
                ) : (
                  <PendingNotice site={data} />
                )
              }
            </AsyncBlock>
          </div>
        )}

        {/* 배치안 탭은 상태(계산 결과·지도 표시)를 유지하기 위해 숨기기만 하고, 필지가 바뀌면 key 로 새로 마운트합니다 */}
        {pnu && tab === 'layout' && !parcel && (
          <div role="tabpanel" id="panel-layout" aria-labelledby="tab-layout">
            <AsyncBlock state={siteState} skeleton={<Skeleton lines={4} />}>
              {(data) => (data.kind === 'pending' ? <PendingNotice site={data} /> : null)}
            </AsyncBlock>
          </div>
        )}
        {parcel && (
          <div role="tabpanel" id="panel-layout" aria-labelledby="tab-layout" hidden={tab !== 'layout'}>
            <LayoutTab key={parcel.pnu} parcel={parcel} review={rulesState} autoRun={layoutRun} hidden={tab !== 'layout'} />
          </div>
        )}

        {(parcel || (tab === 'rules' && review)) && (
          <div className={styles.sources}>
            {parcel && <p className={styles.source}>{parcelSourceLine(parcel)}</p>}
            {tab === 'rules' && review && review.supported && (
              <p className={styles.source}>
                {fmt(content.site.rules.source, { date: review.asOfDate, version: review.engineVersion ?? '—' })}
              </p>
            )}
          </div>
        )}
      </div>

      <footer className={styles.footer}>
        <div className={styles.footerButtons}>
          <Button variant="primary" iconLeft="layout-grid" block disabled={!canLayout} onClick={onLayoutRun}>
            {t.footer.layout}
          </Button>
          <Button variant="secondary" iconLeft="file-text" disabled block>
            {t.footer.pdf}
          </Button>
        </div>
        <span className={styles.soon}>{t.footer.soon}</span>
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

/** BE data_notices — 조회 실패·미반영 개정 등 (요약·법규 탭 공용) */
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

/** 대지 요약 카드: 대지면적(㎡·평) · 지목 · 용도지역 · 접도(도로접면) · 공시지가 · 토지이용계획 · 건축물 */
function SiteSummary({ parcel }: { parcel: Parcel }) {
  const s = content.site.summary;
  const areaHint = parcel.areaSource === 'geometry' ? s.areaGeometry : parcel.areaSource === 'ledger' ? s.areaLedger : undefined;
  const roadValue = parcel.road.access ?? s.roadUnknown;

  return (
    <div className={styles.summary}>
      <NoticeBanner notices={parcel.dataNotices} />
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

        {parcel.landUses && (
          <div className={cx(styles.card, styles.wideCard)}>
            <span className={styles.cardLabel}>{s.landUses}</span>
            {parcel.landUses.length ? (
              <ul className={styles.useList}>
                {parcel.landUses.map((u, i) => (
                  <li key={`${u.code ?? ''}:${u.name}:${i}`} className={styles.useItem}>
                    {u.relation ? <Badge variant={relationVariant(u.relation)}>{u.relation}</Badge> : <Badge variant="neutral">—</Badge>}
                    <span className={styles.useName}>{u.name}</span>
                    <span className={styles.useKind}>{s.landUseKinds[u.kind]}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className={styles.cardEmpty}>{s.landUsesEmpty}</p>
            )}
          </div>
        )}

        {parcel.buildings.length > 0 && (
          <div className={cx(styles.card, styles.wideCard)}>
            <span className={styles.cardLabel}>{s.buildings}</span>
            <ul className={styles.buildingList}>
              {parcel.buildings.map((b, i) => (
                <li key={i} className={styles.building}>
                  {parcel.buildings.length > 1 && <span className={styles.buildingNo}>{fmt(s.buildingN, { n: i + 1 })}</span>}
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
        )}
      </div>
    </div>
  );
}

/** 법규 검토 결과 — 핵심 6항목 + 별도 확인 항목 + 데이터 안내 + 고지 */
function RuleReviewView({ review }: { review: RuleReview }) {
  const r = content.site.rules;
  return (
    <div className={styles.rules}>
      <NoticeBanner notices={review.dataNotices} />

      <section className={styles.section}>
        <h3 className={styles.h3}>
          {fmt(r.coreTitle, { n: review.items.length })}
          <span className={styles.h3sub}>{r.coreDesc}</span>
        </h3>
        <ul className={styles.list}>
          {review.items.map((item) => (
            <RuleItem key={item.id} item={item} />
          ))}
        </ul>
      </section>

      {review.manual.length > 0 && (
        <section className={styles.section}>
          <h3 className={styles.h3}>{fmt(r.manualTitle, { n: review.manual.length })}</h3>
          <p className={styles.sectionDesc}>{r.manualDesc}</p>
          <ul className={styles.list}>
            {review.manual.map((m) => (
              <ManualCheckItem key={m.id} item={m} />
            ))}
          </ul>
        </section>
      )}

      {review.disclaimer && (
        <p className={styles.disclaimer}>
          <Icon name="shield-check" size={14} />
          {review.disclaimer}
        </p>
      )}
    </div>
  );
}

/** 검토 폴링 중 — BE 가 알려 주는 단계(대지정보 → 용도지역 → 건축 법규 → 주차 기준 → 쉬운 설명) */
function ReviewSkeleton({ progress }: { progress: ReviewProgress | null }) {
  const r = content.site.rules;
  const running = progress?.steps.find((s) => s.state === 'running') ?? progress?.steps.find((s) => s.state === 'pending');
  return (
    <div>
      <p className={styles.progress} aria-live="polite">
        <Icon name="loader" size={14} className={styles.progressSpin} />
        {running ? fmt(r.reviewingStep, { label: running.label }) : r.reviewing}
      </p>
      <Skeleton lines={9} />
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
