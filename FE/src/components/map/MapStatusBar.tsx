import { useMap, useMapCursor } from '@/lib/map/MapContext';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatCoord } from '@/lib/format';
import styles from './MapStatusBar.module.css';

/** 지도 하단 바: 마우스 좌표(위도·경도) · 데이터 출처 · "참고용, 최종 확인은 인허가 기관" 고지 */
export function MapStatusBar() {
  const cursor = useMapCursor();
  const { status } = useMap();
  const isMock = status === 'mock' || status === 'mock-fallback';
  return (
    <div className={styles.bar}>
      <span className={cx(styles.coords, 'numeric')}>
        {cursor ? fmt(content.map.coords, { lat: formatCoord(cursor.lat), lng: formatCoord(cursor.lng) }) : content.map.coordsEmpty}
      </span>
      <span className={styles.right}>
        <span className={styles.source}>{isMock ? content.map.sourceMock : content.map.source}</span>
        <span className={styles.disclaimer}>{content.map.disclaimer}</span>
      </span>
    </div>
  );
}
