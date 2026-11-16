import { useId, type KeyboardEvent } from 'react';
import type { ExistingBuildingsMode, LatLng, LayoutAlternative, ParkingSimulation } from '@/lib/api/types';
import type { AsyncState } from '@/lib/useAsync';
import type { TileProvider } from '@/lib/map/tileSource';
import type { ParkingVerdict } from '@/lib/report/verdict';
import type { LayoutBlocker } from '@/lib/report/pageState';
import { altLetter, angleLabel, efficiencyText, layoutMetricRows } from '@/lib/report/layoutMetrics';
import { radioKeyTarget } from '@/lib/report/radioKeys';
import { buildingNotes, buildingOverlay, reasonsWithoutNotes, type BuildingNote } from '@/lib/report/existingBuildings';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatInt } from '@/lib/format';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { Skeleton } from '@/components/AsyncBlock';
import { ReportCard } from '@/components/report/ReportCard';
import { ErrorNotice, ReportNotice } from '@/components/report/ReportNotice';
import { StaticMap } from '@/components/report/StaticMap';
import { MapAlert, MapStatBox, NorthArrow, type MapStat } from '@/components/report/MapOverlays';
import { NotPermittedPanel } from './NotPermittedPanel';
import mapStyles from './ReportMaps.module.css';
import styles from './LayoutCard.module.css';

interface LayoutCardProps {
  address: string;
  polygon: LatLng[];
  blocker: LayoutBlocker | null;
  /** 판정 (검증된 조건의 배치 계산에서) */
  verdict: ParkingVerdict;
  /** 검증된 조건의 배치 계산 결과 */
  sim: ParkingSimulation | null;
  /** 지도에 그리는 결과 — 참고 배치를 받았으면 그것 */
  shown: ParkingSimulation | null;
  selected: LayoutAlternative | null;
  onSelect: (alt: LayoutAlternative) => void;
  /** 참고 배치(include_unverified) — 주차장 설치 가능 여부 미확인으로 계산하지 않았을 때만 */
  reference: { allowed: boolean; state: AsyncState<ParkingSimulation>; onRequest: () => void };
  /**
   * 기존 건물 유지/철거 가정 — available(기본 계산이 건물 1동 이상을 피함)일 때만 전환 버튼.
   * mode 는 고른 쪽, state 는 철거 가정 계산 (기다리는 동안·실패면 지도는 유지 결과 그대로)
   */
  buildings: {
    available: boolean;
    mode: ExistingBuildingsMode;
    state: AsyncState<ParkingSimulation>;
    onChange: (mode: ExistingBuildingsMode) => void;
  };
  onProvider: (provider: TileProvider) => void;
}

/**
 * 카드 02 주차 배치 결과 — 위성 지도 위 배치안(주차구획·차로·출입구·가용 영역)과 기존 건물 + 주차 가능 대수·주차 효율,
 * 기존 건물 안내 문장 · 유지/철거 가정 전환, 배치안 A/B/C 전환, 지표 줄.
 * 건설 불가면 경계만 붉게 그리고 판정 근거를, 계산하지 않았으면 이유와 참고 배치 버튼을 보여 줍니다.
 */
export function LayoutCard({
  address,
  polygon,
  blocker,
  verdict,
  sim,
  shown,
  selected,
  onSelect,
  reference,
  buildings,
  onProvider,
}: LayoutCardProps) {
  const t = content.report.layout;
  const notPermitted = !blocker && verdict.kind === 'not_permitted' ? verdict : null;
  const drawn = !blocker && !notPermitted && shown?.simulated ? shown : null;
  const alternatives = drawn?.result?.alternatives ?? [];
  const alt = drawn ? selected : null;
  const overlay = buildingOverlay(drawn);
  const notes = buildingNotes(drawn, alt);
  const efficiency = alt ? efficiencyText(alt) : null;
  const stats: MapStat[] = alt
    ? [
        { label: t.stallCount, value: formatInt(alt.stallCount), unit: t.stallUnit },
        ...(efficiency ? [{ label: t.efficiency, value: efficiency, unit: t.percentUnit, hint: t.efficiencyHint }] : []),
      ]
    : [];

  return (
    <ReportCard
      no={t.no}
      tone="primary"
      title={t.title}
      desc={t.desc}
      aside={
        drawn?.referenceOnly ? (
          <Badge variant="check" icon="alert-circle">
            {t.referenceBadge}
          </Badge>
        ) : undefined
      }
    >
      {polygon.length >= 3 ? (
        <StaticMap
          polygon={polygon}
          layout={alt?.layers ?? null}
          buildings={overlay?.footprints}
          buildingMode={overlay?.drawAs}
          tone={notPermitted ? 'danger' : 'default'}
          label={alt ? fmt(t.mapLabel, { address, letter: altLetter(alt.code) }) : fmt(t.mapLabelSite, { address })}
          onProvider={onProvider}
        >
          <NorthArrow />
          {notPermitted && <MapAlert>{content.report.notPermitted.badge}</MapAlert>}
          {stats.length > 0 && <MapStatBox stats={stats} />}
        </StaticMap>
      ) : (
        <div className={cx(mapStyles.placeholder, blocker?.kind === 'loading' && mapStyles.loading)} aria-hidden="true" />
      )}

      {blocker ? (
        <Blocker blocker={blocker} />
      ) : notPermitted ? (
        <NotPermittedPanel verdict={notPermitted} />
      ) : drawn ? (
        <>
          <BuildingNotes notes={notes} />
          {drawn.referenceOnly && (
            <p className={styles.reference}>
              <Badge variant="check" icon="alert-circle">
                {t.referenceBadge}
              </Badge>
              <span>{drawn.eligibility.reason ?? content.site.layout.referenceOnlyDesc}</span>
            </p>
          )}
          {buildings.available && <BuildingToggle {...buildings} />}
          {alternatives.length > 0 ? (
            <AlternativeSwitcher alternatives={alternatives} selected={alt} onSelect={onSelect} />
          ) : (
            <ReportNotice>{reasonText('NO_FEASIBLE_LAYOUT')}</ReportNotice>
          )}
          {alt && <Metrics alt={alt} />}
          {(alt || overlay) && <Legend layout={Boolean(alt)} building={overlay?.drawAs ?? null} />}
          <Reasons codes={reasonsWithoutNotes([...drawn.constraintReasons, ...(alt?.constraintReasons ?? [])], notes)} />
        </>
      ) : sim && !sim.simulated ? (
        <NotSimulated sim={sim} reference={reference} />
      ) : null}
    </ReportCard>
  );
}

function Blocker({ blocker }: { blocker: LayoutBlocker }) {
  if (blocker.kind === 'loading') {
    return (
      <div className={styles.loading}>
        <ReportNotice tone="loading" title={blocker.text} />
        <Skeleton lines={3} />
      </div>
    );
  }
  if (blocker.kind === 'error') return <ErrorNotice error={blocker.error} onRetry={blocker.onRetry} />;
  return (
    <ReportNotice tone={blocker.tone ?? 'info'} title={blocker.title}>
      <p>{blocker.text}</p>
    </ReportNotice>
  );
}

/** 지도 아래 기존 건물 안내 — 피해 배치(면적·이격) · 철거 가정 · 반영 못함(경고) · 영역마다 출입구. 인쇄에도 남습니다 */
function BuildingNotes({ notes }: { notes: BuildingNote[] }) {
  if (notes.length === 0) return null;
  return (
    <ul className={styles.buildingNotes}>
      {notes.map((n) => (
        <li key={n.text} className={cx(styles.buildingNote, n.tone === 'warning' && styles.buildingNoteWarning)}>
          <Icon name={n.tone === 'warning' ? 'alert-circle' : 'info'} size={14} />
          {n.text}
        </li>
      ))}
    </ul>
  );
}

const BUILDING_MODES: readonly ExistingBuildingsMode[] = ['keep', 'ignore'];

/**
 * 기존 건물 유지 / 철거 가정 — 배치안 전환과 같은 라디오 그룹(APG, 화살표·Home·End). 고르면 그 조건으로 다시 계산하고(탭 안에서 기억),
 * 계산하는 동안은 지도에 유지 결과를 그대로 두고 '다시 계산하는 중'을 알립니다. 인쇄에서는 빠집니다.
 */
function BuildingToggle({ mode, state, onChange }: LayoutCardProps['buildings']) {
  const t = content.report.layout.buildings;
  const labelId = useId();
  const busy = mode === 'ignore' && state.status === 'loading';
  const label = (m: ExistingBuildingsMode) => (m === 'keep' ? t.keep : t.ignore);

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next = radioKeyTarget(e.key, index, BUILDING_MODES.length);
    if (next === null) return;
    e.preventDefault();
    onChange(BUILDING_MODES[next]);
    e.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus();
  };

  return (
    <div className={styles.buildingToggle} data-print="hide">
      <div className={styles.buildingToggleRow}>
        <span className={styles.switcherLabel} id={labelId}>
          {t.label}
        </span>
        <div className={styles.segments} role="radiogroup" aria-labelledby={labelId} aria-busy={busy || undefined}>
          {BUILDING_MODES.map((m, i) => {
            const active = m === mode;
            return (
              <button
                key={m}
                type="button"
                role="radio"
                aria-checked={active}
                tabIndex={active ? 0 : -1}
                className={cx(styles.segment, active && styles.segmentActive)}
                onClick={() => onChange(m)}
                onKeyDown={(e) => onKeyDown(e, i)}
              >
                {label(m)}
              </button>
            );
          })}
        </div>
        <span className={styles.toggleStatus} role="status">
          {busy && (
            <>
              <Icon name="loader" size={13} className={styles.spin} />
              {t.recalculating}
            </>
          )}
        </span>
      </div>
      {mode === 'ignore' && state.status === 'error' && <ErrorNotice error={state.error} onRetry={state.reload} />}
    </div>
  );
}

function reasonText(code: string): string {
  return (content.site.layout.constraintReasons as Record<string, string>)[code] ?? code;
}

/** BE constraint_reasons → 한글 (같은 코드는 한 번, 모르는 코드는 그대로) */
function Reasons({ codes }: { codes: string[] }) {
  const unique = [...new Set(codes)];
  if (unique.length === 0) return null;
  return (
    <ul className={styles.reasons}>
      {unique.map((c) => (
        <li key={c}>
          <Icon name="info" size={13} />
          {reasonText(c)}
        </li>
      ))}
    </ul>
  );
}

/**
 * 배치안 A/B/C 전환 — 각도 이름 · 대수 · 추천 표시.
 * 라디오 그룹(APG): 버튼이 그룹의 바로 아래 자식이고, Tab 으로는 고른 배치안 하나에만 들어오며 화살표·Home·End 로 옮겨 고릅니다.
 */
function AlternativeSwitcher({
  alternatives,
  selected,
  onSelect,
}: {
  alternatives: LayoutAlternative[];
  selected: LayoutAlternative | null;
  onSelect: (alt: LayoutAlternative) => void;
}) {
  const t = content.report.layout;
  const labelId = useId();
  // Tab 으로 들어올 항목: 고른 배치안 (없으면 첫 번째)
  const focusIndex = Math.max(
    0,
    alternatives.findIndex((a) => a.code === selected?.code),
  );

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next = radioKeyTarget(e.key, index, alternatives.length);
    if (next === null) return;
    e.preventDefault();
    onSelect(alternatives[next]);
    e.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[next]?.focus();
  };

  return (
    <div className={styles.switcher}>
      <span className={styles.switcherLabel} id={labelId}>
        {t.alternatives}
      </span>
      <div className={styles.alts} role="radiogroup" aria-labelledby={labelId}>
        {alternatives.map((a, i) => {
          const active = a.code === selected?.code;
          return (
            <button
              key={a.code}
              type="button"
              role="radio"
              aria-checked={active}
              tabIndex={i === focusIndex ? 0 : -1}
              className={cx(styles.alt, active && styles.altActive)}
              onClick={() => onSelect(a)}
              onKeyDown={(e) => onKeyDown(e, i)}
            >
              <span className={styles.altCode}>{altLetter(a.code)}</span>
              <span className={styles.altMain}>
                <span className={styles.altAngle}>{angleLabel(a.angle)}</span>
                <span className={cx(styles.altCount, 'numeric')}>{fmt(t.altCount, { n: formatInt(a.stallCount) })}</span>
              </span>
              {a.isRecommended && (
                <Badge variant="primary" className={styles.altBadge}>
                  {t.recommended}
                </Badge>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function Metrics({ alt }: { alt: LayoutAlternative }) {
  const rows = layoutMetricRows(alt);
  if (rows.length === 0) return null;
  return (
    <dl className={styles.metrics}>
      {rows.map((r) => (
        <div key={r.key} className={styles.metric}>
          <dt>{r.label}</dt>
          <dd className="numeric">{r.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** 범례 — 배치안 요소(배치안이 있을 때)와 기존 건물(그렸을 때: 유지 = 진한 회색, 철거 가정 = 점선) */
function Legend({ layout, building }: { layout: boolean; building: ExistingBuildingsMode | null }) {
  const l = content.site.layout.legend;
  return (
    <ul className={styles.legend} aria-label={l.title}>
      {layout && (
        <>
          <li>
            <span className={cx(styles.swatch, styles.swatchStall)} />
            {l.stall}
          </li>
          <li>
            <span className={cx(styles.swatch, styles.swatchAisle)} />
            {l.aisle}
          </li>
          <li>
            <span className={cx(styles.swatch, styles.swatchGate)} />
            {l.gate}
          </li>
          <li>
            <span className={cx(styles.swatch, styles.swatchUsable)} />
            {l.usable}
          </li>
        </>
      )}
      {building && (
        <li>
          <span className={cx(styles.swatch, building === 'ignore' ? styles.swatchBuildingDemolished : styles.swatchBuilding)} />
          {building === 'ignore' ? l.buildingDemolished : l.building}
        </li>
      )}
    </ul>
  );
}

/** BE 가 배치를 계산하지 않음 — 메시지 · 이유, 주차장 설치 가능 여부 미확인이면 참고 배치 버튼 */
function NotSimulated({ sim, reference }: { sim: ParkingSimulation; reference: LayoutCardProps['reference'] }) {
  const l = content.site.layout;
  const refData = reference.state.status === 'success' ? reference.state.data : null;
  // 참고 배치도 계산되지 않았으면 그 이유를 보여 주고 버튼은 거둡니다
  const shownSim = refData && !refData.simulated ? refData : sim;
  const showButton = reference.allowed && !refData;
  return (
    <div className={styles.notSimulated}>
      <ReportNotice
        title={l.notSimulated}
        action={
          showButton ? (
            <Button
              variant="secondary"
              size="sm"
              iconLeft="layout-grid"
              loading={reference.state.status === 'loading'}
              onClick={reference.onRequest}
            >
              {l.viewUnverified}
            </Button>
          ) : undefined
        }
      >
        {shownSim.message && <p>{shownSim.message}</p>}
        <Reasons codes={shownSim.constraintReasons} />
        {showButton && <p className={styles.hint}>{content.report.layout.notSimulatedHint}</p>}
      </ReportNotice>
      {reference.state.status === 'error' && <ErrorNotice error={reference.state.error} onRetry={reference.state.reload} />}
    </div>
  );
}
