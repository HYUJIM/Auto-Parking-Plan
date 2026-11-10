import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { isPnu, type AddressHit, type Parcel, type RuleReview, type SiteData } from '@/lib/api/types';
import { getRuleReview, resolveSite } from '@/lib/api/parcel';
import { getCachedHit } from '@/lib/api/hitCache';
import { addRecentSearch } from '@/lib/api/recent';
import { useAsync } from '@/lib/useAsync';
import { useMap } from '@/lib/map/MapContext';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatArea, formatInt, formatRoad } from '@/lib/format';
import { AsyncBlock, Skeleton } from '@/components/AsyncBlock';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { ManualCheckItem, RuleItem } from '@/components/RuleItem';
import { relationVariant } from '@/lib/landUse';
import { SearchBox } from '@/components/SearchBox';
import { StatCard } from '@/components/StatCard';
import styles from './SitePanel.module.css';

type Tab = 'summary' | 'rules' | 'layout';

/**
 * 2단계 — 실제 이용화면 (/site?pnu=…) 좌측 패널.
 * 상단: 검색창(재검색 가능) + 주소·PNU, 탭 3개(대지 요약 · 법규 검토 · 배치안 잠금),
 * 하단 고정: 배치안 생성 · PDF 저장 (자리만).
 *
 * 필지 데이터(resolveSite)와 법규(getRuleReview)는 따로 불러오므로 한쪽이 실패해도 다른 카드는 그대로 보입니다.
 * 필지 데이터를 받지 못한 PNU(브이월드 키 없음·연속지적도에 없음)는 주소·좌표만으로 '대기' 화면을 보여 줍니다.
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
  const headRef = useRef<HTMLElement>(null);

  const siteState = useAsync(() => resolveSite(pnu), [pnu], Boolean(pnu));
  const site = siteState.status === 'success' ? siteState.data : null;
  const parcel = site?.kind === 'parcel' ? site.parcel : null;
  const rulesState = useAsync(() => getRuleReview(parcel as Parcel), [parcel?.pnu], Boolean(parcel));
  /** 필지 조회가 실패해도 주소·PNU 는 검색 결과(세션 캐시)로 보여 줍니다 */
  const cachedHit = getCachedHit(pnu);

  // 필지가 바뀌면 요약 탭으로 (렌더 중 상태 조정 — effect 안의 setState 를 피합니다)
  const [tabPnu, setTabPnu] = useState(pnu);
  if (tabPnu !== pnu) {
    setTabPnu(pnu);
    setTab('summary');
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
      setScene({ kind: 'site', parcel: site.parcel });
      addRecentSearch({ pnu: site.parcel.pnu, label: site.parcel.address.road ?? site.parcel.address.jibun });
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
  const tabs: { id: Tab; label: string; locked?: boolean }[] = [
    { id: 'summary', label: t.tabs.summary },
    { id: 'rules', label: t.tabs.rules },
    { id: 'layout', label: t.tabs.layout, locked: true },
  ];
  const currentAddress =
    parcel?.address.road ??
    parcel?.address.jibun ??
    (site?.kind === 'pending' ? (site.hit?.roadAddress ?? '') : siteState.status === 'error' ? (cachedHit?.roadAddress ?? '') : '');

  return (
    <div className={styles.panel}>
      <header className={styles.head} ref={headRef} tabIndex={-1}>
        <div className={styles.headRow}>
          <Button variant="icon" size="sm" icon="arrow-left" aria-label={t.back} onClick={() => navigate('/')} />
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
              className={cx(styles.tab, tab === tb.id && styles.tabActive, tb.locked && styles.tabLocked)}
              onClick={() => setTab(tb.id)}
            >
              {tb.locked && <Icon name="lock" size={14} />}
              {tb.label}
              {tb.locked && <span className={styles.soonTag}>{t.layoutSoon}</span>}
            </button>
          ))}
        </div>
      </header>

      <div className={styles.body} role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {pnu && tab === 'summary' && (
          <AsyncBlock state={siteState} skeleton={<Skeleton lines={6} />}>
            {(data) => (data.kind === 'parcel' ? <SiteSummary parcel={data.parcel} /> : <PendingNotice site={data} />)}
          </AsyncBlock>
        )}

        {pnu && tab === 'rules' && (
          <AsyncBlock state={siteState} skeleton={<Skeleton lines={9} />}>
            {(data) =>
              data.kind === 'parcel' ? (
                <AsyncBlock state={rulesState} skeleton={<Skeleton lines={9} />}>
                  {(review) => (review.supported ? <RuleReviewView review={review} /> : <Unsupported />)}
                </AsyncBlock>
              ) : data.hit && !data.hit.inSuseong ? (
                <Unsupported />
              ) : (
                <PendingNotice site={data} />
              )
            }
          </AsyncBlock>
        )}

        {tab === 'layout' && <LockedLayout />}

        {(parcel || (rulesState.status === 'success' && rulesState.data)) && (
          <div className={styles.sources}>
            {parcel && (
              <p className={styles.source}>
                {parcel.source?.provider === 'vworld' ? fmt(t.sourceParcel, { date: parcel.source.queriedAt }) : t.sourceParcelMock}
              </p>
            )}
            {tab === 'rules' && rulesState.status === 'success' && rulesState.data && (
              <p className={styles.source}>
                {fmt(t.source, { providers: rulesState.data.source.providers.join(' · '), date: rulesState.data.source.date })}
              </p>
            )}
          </div>
        )}
      </div>

      <footer className={styles.footer}>
        <div className={styles.footerButtons}>
          <Button variant="primary" iconLeft="layout-grid" disabled block>
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

/** 주소·지번·PNU (+ 수성구 외 배지). 도로명주소가 없으면 지번을 제목으로 */
function AddressBlock({ site }: { site: SiteData }) {
  const t = content.site;
  const road = site.kind === 'parcel' ? site.parcel.address.road : site.hit?.roadAddress;
  const jibun = site.kind === 'parcel' ? site.parcel.address.jibun : site.hit?.jibunAddress;
  const pnu = site.kind === 'parcel' ? site.parcel.pnu : site.pnu;
  const inSuseong = site.kind === 'parcel' ? site.parcel.inSuseong : (site.hit?.inSuseong ?? true);
  const primary = road || jibun;
  const secondary = road ? jibun : undefined;

  return (
    <div className={styles.address}>
      {primary ? <strong className={styles.road}>{primary}</strong> : <strong className={styles.roadUnknown}>{t.addressUnknown}</strong>}
      {secondary && <span className={styles.jibun}>{secondary}</span>}
      <span className={styles.meta}>
        <span className={cx(styles.pnu, 'numeric')}>
          {t.pnu} {pnu}
        </span>
        {!inSuseong && <Badge variant="manual">{content.search.outside}</Badge>}
        {site.kind === 'pending' && (
          <Badge variant="check" icon="alert-circle">
            {t.pending.badge}
          </Badge>
        )}
      </span>
    </div>
  );
}

/** 대지 요약 카드: 대지면적 · 용도지역 · 지목 · 접도 조건 · 공시지가 · 토지이용계획 · 토지 특성 */
function SiteSummary({ parcel }: { parcel: Parcel }) {
  const s = content.site.summary;
  const road = parcel.road;
  // 접도 문장은 법규 탭과 같은 템플릿(formatRoad) — 두 탭이 다르게 보이지 않도록
  const measuredRoad = formatRoad(road);
  const roadValue = measuredRoad ?? road.access ?? s.roadUnknown;
  const areaHint = parcel.areaSource === 'geometry' ? s.areaGeometry : parcel.areaSource === 'ledger' ? s.areaLedger : undefined;
  // 공시지가 기준연도: 토지특성 → 연속지적도 고시연도 → 미확인 (매년 바뀌는 값이라 연도를 꼭 함께 보여 줍니다)
  const priceYear = parcel.land?.baseYear ?? parcel.source?.gosiYear;
  const priceHint = priceYear ? fmt(s.baseYear, { year: priceYear }) : s.baseYearUnknown;
  const land = parcel.land;
  const hasLandInfo = Boolean(land?.shape || land?.terrain || land?.use);

  return (
    <div className={styles.grid}>
      <StatCard label={s.area} hint={areaHint} value={formatArea(parcel.area)} unit={s.areaUnit} numeric wide />
      <StatCard label={s.zoning} value={parcel.zoning || '—'} />
      <StatCard label={s.jimok} value={parcel.jimok || '—'} />
      {parcel.zoning2 && <StatCard label={s.zoning2} value={parcel.zoning2} wide />}
      <StatCard label={s.road} hint={!measuredRoad && road.access ? s.roadAccess : undefined} value={roadValue} wide />
      {parcel.landPrice != null && (
        <StatCard label={s.landPrice} hint={priceHint} value={formatInt(parcel.landPrice)} unit={s.landPriceUnit} numeric wide />
      )}

      {parcel.landUses && (
        <div className={cx(styles.card, styles.wideCard)}>
          <span className={styles.cardLabel}>{s.landUses}</span>
          {parcel.landUses.length ? (
            <ul className={styles.useList}>
              {parcel.landUses.map((u) => (
                <li key={`${u.code}:${u.name}`} className={styles.useItem}>
                  <Badge variant={relationVariant(u.relation)}>{u.relation}</Badge>
                  <span>{u.name}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.cardEmpty}>{s.landUsesEmpty}</p>
          )}
        </div>
      )}

      {hasLandInfo && (
        <div className={cx(styles.card, styles.wideCard)}>
          <span className={styles.cardLabel}>{s.landInfo}</span>
          <dl className={styles.landInfo}>
            {land?.shape && (
              <div>
                <dt>{s.shape}</dt>
                <dd>{land.shape}</dd>
              </div>
            )}
            {land?.terrain && (
              <div>
                <dt>{s.terrain}</dt>
                <dd>{land.terrain}</dd>
              </div>
            )}
            {land?.use && (
              <div>
                <dt>{s.use}</dt>
                <dd>{land.use}</dd>
              </div>
            )}
          </dl>
        </div>
      )}
    </div>
  );
}

/** 핵심 법규 6항목 + 별도 확인 4항목 */
function RuleReviewView({ review }: { review: RuleReview }) {
  const r = content.site.rules;
  return (
    <div className={styles.rules}>
      <section className={styles.section}>
        <h3 className={styles.h3}>
          {r.coreTitle}
          <span className={styles.h3sub}>{r.coreDesc}</span>
        </h3>
        <ul className={styles.list}>
          {review.items.map((item) => (
            <RuleItem key={item.id} item={item} />
          ))}
        </ul>
      </section>

      <section className={styles.section}>
        <h3 className={styles.h3}>{r.manualTitle}</h3>
        <p className={styles.sectionDesc}>{r.manualDesc}</p>
        <ul className={styles.list}>
          {review.manual.map((m) => (
            <ManualCheckItem key={m.id} item={m} />
          ))}
        </ul>
      </section>

      <p className={styles.mockNote}>
        <Icon name="info" size={14} />
        {r.mockNote}
      </p>
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

function LockedLayout() {
  const l = content.site.layout;
  return (
    <div className={cx(styles.notice, styles.locked)}>
      <Icon name="lock" size={22} className={styles.noticeIcon} />
      <div>
        <strong>{l.lockedTitle}</strong>
        <p>{l.lockedDesc}</p>
      </div>
    </div>
  );
}
