import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { ExistingBuilding, ExistingBuildingsMode, LatLng, LayoutLayers } from '@/lib/api/types';
import { loadTileChoice } from '@/lib/map/tileConfig';
import { tileUrl, type TileChoice, type TileProvider } from '@/lib/map/tileSource';
import { layoutShapes, ringPath, staticView } from '@/lib/map/staticView';
import { cx } from '@/lib/cx';
import styles from './StaticMap.module.css';

/** 논리 크기 — 이 비율로 그리고 화면·인쇄 폭에 맞춰 늘립니다 */
const W = 800;
const H = 600;

interface StaticMapProps {
  /** 대지 경계 */
  polygon: LatLng[];
  /** 주차장 배치안 (없으면 경계만) */
  layout?: LayoutLayers | null;
  /** 기존 건물 외곽선 (없으면 그리지 않음) — 같은 배열을 넘기면 지도 계산을 다시 하지 않습니다 */
  buildings?: readonly ExistingBuilding[] | null;
  /** 기존 건물 모양: keep = 진한 회색 채움(유지 · 현황) · ignore = 점선 외곽선(철거 가정) */
  buildingMode?: ExistingBuildingsMode;
  /** 위성(기본) 또는 일반 지도 */
  mapType?: 'satellite' | 'normal';
  /** 지도 위에 얹는 표시 (방위표·말풍선 등) */
  children?: ReactNode;
  /** 스크린리더용 설명 */
  label: string;
  className?: string;
  /** 대지 경계 색: 기본(블루) · 위험(건설 불가) */
  tone?: 'default' | 'danger';
  /** 실제로 깔린 타일 공급자 — 지도 아래 출처 문구를 보이는 지도와 맞추려고 (tileCopy.ts) */
  onProvider?: (provider: TileProvider) => void;
}

/**
 * 결과 페이지의 정지 지도 — 대지 경계(와 배치안 · 기존 건물)를 위성 타일 위에 그립니다.
 * 이용화면의 움직이는 지도와 달리 끌거나 확대하지 않고, 한 번 정한 범위를 비율로 그려서 인쇄(PDF 저장)에도 그대로 나옵니다.
 * 타일은 이용화면과 같은 출처(브이월드는 BE 경유)를 씁니다.
 */
export function StaticMap({
  polygon,
  layout,
  buildings,
  buildingMode = 'keep',
  mapType = 'satellite',
  children,
  label,
  className,
  tone = 'default',
  onProvider,
}: StaticMapProps) {
  const [choice, setChoice] = useState<TileChoice | null>(null);
  const [failed, setFailed] = useState<ReadonlySet<string>>(() => new Set());

  useEffect(() => {
    let alive = true;
    loadTileChoice().then(
      (c) => alive && setChoice(c),
      () => alive && setChoice({ provider: 'none', source: null }),
    );
    return () => {
      alive = false;
    };
  }, []);

  const provider = choice?.provider ?? null;
  useEffect(() => {
    if (provider) onProvider?.(provider);
  }, [provider, onProvider]);

  const view = useMemo(
    () =>
      staticView(polygon, {
        width: W,
        height: H,
        padding: 56,
        range: choice?.source ? { minZoom: choice.source.minZoom, maxZoom: choice.source.maxZoom } : undefined,
      }),
    [polygon, choice],
  );
  const shapes = useMemo(() => (view ? layoutShapes(view, layout, buildings ?? undefined) : []), [view, layout, buildings]);
  const template = choice?.source ? (mapType === 'satellite' ? choice.source.satellite : choice.source.normal) : null;

  return (
    <figure className={cx(styles.map, mapType === 'satellite' ? styles.satellite : styles.normal, className)} aria-label={label}>
      {view && template && (
        <div className={styles.tiles} aria-hidden="true">
          {view.tiles.map((t) =>
            failed.has(t.key) ? null : (
              <img
                key={t.key}
                className={styles.tile}
                src={tileUrl(template, t.z, t.x, t.y)}
                alt=""
                draggable={false}
                decoding="async"
                style={{ left: `${t.left}%`, top: `${t.top}%`, width: `${t.size}%`, height: `${(t.size * W) / H}%` }}
                onError={() => setFailed((s) => new Set(s).add(t.key))}
              />
            ),
          )}
        </div>
      )}
      {view && (
        <svg className={styles.overlay} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
          {shapes.map((s, i) =>
            s.point ? (
              <circle key={i} className={styles[`l_${s.layer}`]} cx={s.point.x} cy={s.point.y} r={7} />
            ) : (
              <path
                key={i}
                className={cx(styles[`l_${s.layer}`], s.layer === 'building' && buildingMode === 'ignore' && styles.buildingDemolished)}
                d={s.d}
                fillRule="evenodd"
              />
            ),
          )}
          <path
            className={cx(styles.site, layout && styles.siteWithLayout, tone === 'danger' && styles.siteDanger)}
            d={ringPath(view, polygon)}
          />
        </svg>
      )}
      {children}
    </figure>
  );
}
