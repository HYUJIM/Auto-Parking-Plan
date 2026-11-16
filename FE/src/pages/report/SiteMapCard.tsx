import type { ReactNode } from 'react';
import type { LatLng } from '@/lib/api/types';
import type { TileProvider } from '@/lib/map/tileSource';
import { tileSourceText } from '@/lib/map/tileCopy';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatArea } from '@/lib/format';
import { ReportCard } from '@/components/report/ReportCard';
import { StaticMap } from '@/components/report/StaticMap';
import { MapBubble, MapChip, NorthArrow } from '@/components/report/MapOverlays';
import styles from './ReportMaps.module.css';

interface SiteMapCardProps {
  address: string;
  polygon: LatLng[];
  area: number | null;
  /** '대구광역시 수성구' */
  region: string;
  provider: TileProvider | null;
  onProvider: (provider: TileProvider) => void;
  loading: boolean;
  /** 경계가 없을 때 지도 자리에 보여 줄 안내 (대기 필지 · 오류) */
  fallback: ReactNode;
}

/** 지역 칩의 지도 이름 — 실제로 깔린 타일에 맞춥니다 (타일이 없으면 '미리보기 지도', 아직 모르면 빼기) */
function mapKindLabel(provider: TileProvider | null): string {
  if (!provider) return '';
  return provider === 'none' ? content.map.previewBadge : content.report.site.satellite;
}

/** 카드 01 현황 부지 — 위성 지도 위 대지 경계 + 방위표 + 대지면적 말풍선 + 지역 칩, 아래에 타일 출처 */
export function SiteMapCard({ address, polygon, area, region, provider, onProvider, loading, fallback }: SiteMapCardProps) {
  const t = content.report.site;
  const chip = [region, mapKindLabel(provider)].filter(Boolean).join(' · ');
  return (
    <ReportCard no={t.no} tone="dark" title={t.title} desc={t.desc}>
      {polygon.length >= 3 ? (
        <>
          <StaticMap polygon={polygon} label={fmt(t.mapLabel, { address })} onProvider={onProvider}>
            <NorthArrow />
            {area !== null && <MapBubble>{fmt(content.map.areaLabel, { area: formatArea(area) })}</MapBubble>}
            {chip && <MapChip icon="map-pin">{chip}</MapChip>}
          </StaticMap>
          {provider && <p className={styles.attribution}>{tileSourceText(provider)}</p>}
        </>
      ) : loading ? (
        <div className={cx(styles.placeholder, styles.loading)} aria-busy="true" aria-label={content.report.layout.loadingSite} />
      ) : (
        fallback
      )}
    </ReportCard>
  );
}
