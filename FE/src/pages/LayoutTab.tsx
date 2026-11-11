import { useCallback, useEffect, useRef, useState } from 'react';
import type { LayoutAlternative, Parcel, ParkingSimulation, RuleReview } from '@/lib/api/types';
import { getParkingSimulation } from '@/lib/api/review';
import { describeApiError } from '@/lib/api/errors';
import type { AsyncState } from '@/lib/useAsync';
import { useMap } from '@/lib/map/MapContext';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatNumber } from '@/lib/format';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { StatusBadge } from '@/components/RuleItem';
import { isRuleStatus } from '@/lib/api/types';
import panelStyles from './SitePanel.module.css';
import styles from './LayoutTab.module.css';

type SimState =
  | { status: 'idle' }
  | { status: 'loading'; includeUnverified: boolean }
  | { status: 'success'; data: ParkingSimulation; includeUnverified: boolean }
  | { status: 'error'; error: unknown; includeUnverified: boolean };

interface LayoutTabProps {
  parcel: Parcel;
  review: AsyncState<RuleReview>;
  /** 하단 '배치안 생성' 버튼이 눌린 횟수 — 바뀌면 계산을 시작합니다 */
  autoRun: number;
  hidden: boolean;
}

/**
 * 배치안 탭 — `POST /v1/reviews/{id}/parking-simulations`.
 * 법규 검토(review_id)가 끝나야 계산할 수 있습니다. 결과의 배치안 A/B/C 중 하나를 고르면 지도에 주차구획·차로·출입구를 그립니다.
 * 주차장 설치 가능 여부가 확인되지 않아 BE 가 계산하지 않으면(simulated=false) 이유를 보여 주고 '참고 배치 보기'(include_unverified)를 제안합니다.
 * 탭을 벗어나도 상태와 지도 표시는 유지되고, 필지가 바뀌면(부모가 key 로 다시 마운트) 지워집니다.
 */
export function LayoutTab({ parcel, review, autoRun, hidden }: LayoutTabProps) {
  const l = content.site.layout;
  const { setLayout } = useMap();
  const [state, setState] = useState<SimState>({ status: 'idle' });
  const [selected, setSelected] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const reviewId = review.status === 'success' && review.data ? review.data.reviewId : null;
  const supported = review.status === 'success' && review.data ? review.data.supported : parcel.inSuseong;

  const run = useCallback(
    async (includeUnverified: boolean) => {
      if (!reviewId) return;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setState({ status: 'loading', includeUnverified });
      try {
        const sim = await getParkingSimulation(reviewId, { includeUnverified, signal: controller.signal });
        if (controller.signal.aborted) return;
        setState({ status: 'success', data: sim, includeUnverified });
        setSelected(sim.result?.recommendedCode ?? null);
      } catch (error) {
        if (controller.signal.aborted) return;
        setState({ status: 'error', error, includeUnverified });
      }
    },
    [reviewId],
  );

  // 하단 '배치안 생성' — 눌린 뒤 검토가 끝나면 그때 시작
  const lastAutoRun = useRef(0);
  useEffect(() => {
    if (autoRun > lastAutoRun.current && reviewId) {
      lastAutoRun.current = autoRun;
      void run(false);
    }
  }, [autoRun, reviewId, run]);

  const alternatives = state.status === 'success' ? (state.data.result?.alternatives ?? []) : [];
  const selectedAlt = alternatives.find((a) => a.code === selected) ?? null;

  // 고른 배치안을 지도에 — 바뀔 때마다 다시 그리고, 탭이 사라지면(필지 변경) 지움
  useEffect(() => {
    setLayout(selectedAlt?.layers ?? null);
  }, [selectedAlt, setLayout]);
  useEffect(
    () => () => {
      abortRef.current?.abort();
      setLayout(null);
    },
    [setLayout],
  );

  const loading = state.status === 'loading';

  return (
    <div className={styles.wrap} hidden={hidden}>
      <section className={panelStyles.section}>
        <h3 className={panelStyles.h3}>{l.title}</h3>
        <p className={panelStyles.sectionDesc}>{l.desc}</p>
      </section>

      {!supported ? (
        <Notice icon="alert-circle" text={l.unsupported} />
      ) : parcel.polygon.length < 3 ? (
        <Notice icon="info" text={l.pending} />
      ) : review.status === 'error' ? (
        <Notice icon="alert-circle" text={l.needsReviewError} />
      ) : !reviewId ? (
        <Notice icon="loader" text={l.needsReview} spin />
      ) : (
        <>
          <div className={styles.actions}>
            <Button variant="primary" iconLeft="layout-grid" loading={loading} onClick={() => void run(false)}>
              {state.status === 'success' ? l.rerun : l.run}
            </Button>
            {loading && <span className={styles.running}>{l.running}</span>}
          </div>

          {state.status === 'error' && <ErrorNotice error={state.error} onRetry={() => void run(state.includeUnverified)} />}

          {state.status === 'success' && (
            <SimulationView
              sim={state.data}
              selected={selected}
              onSelect={setSelected}
              onViewUnverified={() => void run(true)}
              loading={loading}
            />
          )}
        </>
      )}
    </div>
  );
}

function SimulationView({
  sim,
  selected,
  onSelect,
  onViewUnverified,
  loading,
}: {
  sim: ParkingSimulation;
  selected: string | null;
  onSelect: (code: string) => void;
  onViewUnverified: () => void;
  loading: boolean;
}) {
  const l = content.site.layout;
  const r = content.site.rules;
  const alternatives = sim.result?.alternatives ?? [];
  const eligibilityStatus = isRuleStatus(sim.eligibility.status) ? sim.eligibility.status : 'MANUAL_CHECK';

  return (
    <>
      <div className={styles.card}>
        <span className={styles.cardLabel}>{l.eligibility}</span>
        <div className={styles.eligibility}>
          <StatusBadge status={eligibilityStatus} />
          <span className={styles.eligibilityText}>
            {sim.eligibility.reason && <span>{sim.eligibility.reason}</span>}
            {sim.eligibility.useName && <span className={styles.muted}>{fmt(l.eligibilityUse, { use: sim.eligibility.useName })}</span>}
          </span>
        </div>
      </div>

      {!sim.simulated && (
        <div className={cx(panelStyles.notice, styles.notSimulated)}>
          <Icon name="info" size={22} className={cx(panelStyles.noticeIcon, panelStyles.noticeInfo)} />
          <div className={styles.notSimulatedBody}>
            <strong>{l.notSimulated}</strong>
            {sim.message && <p>{sim.message}</p>}
            <ConstraintReasons codes={sim.constraintReasons} />
            <Button
              variant="secondary"
              size="sm"
              iconLeft="layout-grid"
              loading={loading}
              onClick={onViewUnverified}
              className={styles.unverifiedBtn}
            >
              {l.viewUnverified}
            </Button>
          </div>
        </div>
      )}

      {sim.simulated && sim.result && (
        <>
          {sim.referenceOnly && (
            <p className={styles.referenceOnly}>
              <Badge variant="check" icon="alert-circle">
                {l.referenceOnly}
              </Badge>
              {l.referenceOnlyDesc}
            </p>
          )}
          {sim.constraintReasons.length > 0 && <ConstraintReasons codes={sim.constraintReasons} />}

          <section className={panelStyles.section}>
            <h3 className={panelStyles.h3}>
              {fmt(l.alternatives, { n: alternatives.length })}
              {sim.result.siteAreaM2 !== null && (
                <span className={panelStyles.h3sub}>
                  {l.metrics.site_area_m2} <span className="numeric">{formatNumber(sim.result.siteAreaM2)}</span>㎡
                </span>
              )}
            </h3>
            <ul className={styles.alts} role="radiogroup" aria-label={fmt(l.alternatives, { n: alternatives.length })}>
              {alternatives.map((alt) => (
                <AlternativeCard key={alt.code} alt={alt} active={alt.code === selected} onSelect={() => onSelect(alt.code)} />
              ))}
            </ul>
            {alternatives.length === 0 && (
              <p className={styles.muted}>{(l.constraintReasons as Record<string, string>).NO_FEASIBLE_LAYOUT}</p>
            )}
          </section>

          <div className={styles.legend} aria-label={l.legend.title}>
            <span className={styles.legendItem}>
              <span className={cx(styles.swatch, styles.swatchStall)} />
              {l.legend.stall}
            </span>
            <span className={styles.legendItem}>
              <span className={cx(styles.swatch, styles.swatchAisle)} />
              {l.legend.aisle}
            </span>
            <span className={styles.legendItem}>
              <span className={cx(styles.swatch, styles.swatchGate)} />
              {l.legend.gate}
            </span>
            <span className={styles.legendItem}>
              <span className={cx(styles.swatch, styles.swatchUsable)} />
              {l.legend.usable}
            </span>
          </div>
        </>
      )}

      {sim.legalBasis.length > 0 && (
        <div className={styles.card}>
          <span className={styles.cardLabel}>{l.legalBasis}</span>
          <ul className={styles.basisList}>
            {sim.legalBasis.map((b) => (
              <li key={b.category} className={styles.basisRow}>
                {isRuleStatus(b.status) && <StatusBadge status={b.status} className={styles.basisBadge} />}
                <span>{b.criteria ?? r.none}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {sim.disclaimer && <p className={styles.disclaimer}>{sim.disclaimer}</p>}
    </>
  );
}

const METRIC_KEYS = [
  'usable_area_m2',
  'parking_area_m2',
  'efficiency_percent',
  'area_per_stall_m2',
  'aisle_width_m',
  'gate_width_m',
] as const;

function metricUnit(key: string): string {
  if (key.endsWith('_m2')) return '㎡';
  if (key.endsWith('_percent')) return '%';
  if (key.endsWith('_deg')) return '°';
  if (key.endsWith('_m')) return 'm';
  return '';
}

function AlternativeCard({ alt, active, onSelect }: { alt: LayoutAlternative; active: boolean; onSelect: () => void }) {
  const l = content.site.layout;
  const letter = alt.code.replace(/^ALT_/, '');
  const angle = (l.angles as Record<string, string>)[alt.angle] ?? alt.angle;
  const m = alt.metrics;
  const stallSize =
    m.stall_width_m !== undefined && m.stall_length_m !== undefined
      ? `${formatNumber(m.stall_width_m)} × ${formatNumber(m.stall_length_m)} m`
      : null;

  return (
    <li>
      <button type="button" role="radio" aria-checked={active} className={cx(styles.alt, active && styles.altActive)} onClick={onSelect}>
        <span className={styles.altCode}>{letter}</span>
        <span className={styles.altMain}>
          <span className={styles.altTitle}>
            {angle}
            {alt.isRecommended && (
              <Badge variant="primary" className={styles.altBadge}>
                {l.recommended}
              </Badge>
            )}
          </span>
          <span className={styles.altMeta}>
            <span className={cx(styles.altCount, 'numeric')}>{fmt(l.stallCount, { n: alt.stallCount })}</span>
            {m.efficiency_percent !== undefined && (
              <span className={styles.muted}>
                {l.metrics.efficiency_percent} <span className="numeric">{formatNumber(m.efficiency_percent)}</span>%
              </span>
            )}
          </span>
          {active && (
            <>
              <dl className={styles.metrics}>
                {METRIC_KEYS.filter((k) => m[k] !== undefined).map((k) => (
                  <div key={k}>
                    <dt>{(l.metrics as Record<string, string>)[k] ?? k}</dt>
                    <dd className="numeric">
                      {formatNumber(m[k])}
                      {metricUnit(k)}
                    </dd>
                  </div>
                ))}
                {stallSize && (
                  <div>
                    <dt>{l.metrics.stall_size}</dt>
                    <dd className="numeric">{stallSize}</dd>
                  </div>
                )}
              </dl>
              {alt.constraintReasons.length > 0 && <ConstraintReasons codes={alt.constraintReasons} />}
              <span className={styles.selectedTag}>
                <Icon name="map" size={13} />
                {l.selected}
              </span>
            </>
          )}
        </span>
      </button>
    </li>
  );
}

/** BE constraint_reasons 코드 → 한글 (모르는 코드는 코드 그대로) */
function ConstraintReasons({ codes }: { codes: string[] }) {
  if (codes.length === 0) return null;
  const dict = content.site.layout.constraintReasons as Record<string, string>;
  return (
    <ul className={styles.reasons}>
      {codes.map((c) => (
        <li key={c}>
          <Icon name="info" size={13} />
          {dict[c] ?? c}
        </li>
      ))}
    </ul>
  );
}

function Notice({ icon, text, spin }: { icon: 'info' | 'alert-circle' | 'loader'; text: string; spin?: boolean }) {
  return (
    <div className={panelStyles.notice}>
      <Icon
        name={icon}
        size={22}
        className={cx(panelStyles.noticeIcon, icon !== 'alert-circle' && panelStyles.noticeInfo, spin && styles.spin)}
      />
      <div>
        <p className={styles.noticeText}>{text}</p>
      </div>
    </div>
  );
}

function ErrorNotice({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { title, desc, retryable } = describeApiError(error);
  return (
    <div className={panelStyles.notice} role="alert">
      <Icon name="alert-circle" size={22} className={panelStyles.noticeIcon} />
      <div className={styles.errorBody}>
        <strong>{title}</strong>
        {desc && <p>{desc}</p>}
        {retryable && (
          <Button variant="secondary" size="sm" iconLeft="refresh" onClick={onRetry} className={styles.retry}>
            {content.status.retry}
          </Button>
        )}
      </div>
    </div>
  );
}
