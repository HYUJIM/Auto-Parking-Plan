import { useMap, useMapCursor } from '@/lib/map/MapContext';
import { tileSourceText } from '@/lib/map/tileCopy';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { formatCoord } from '@/lib/format';
import styles from './MapStatusBar.module.css';

/** 지도 하단 바: 마우스 좌표(위도·경도) · 데이터 출처 · "참고용, 최종 확인은 인허가 기관" 고지 */
export function MapStatusBar() {
  const cursor = useMapCursor();
  const { status, tileProvider } = useMap();
  const isMock = status === 'mock' || status === 'mock-fallback';
  // 미리보기 지도는 실제로 깔린 타일의 출처를 적습니다 (브이월드 / OpenStreetMap·Esri / 별도 타일 / 도식)
  const source = isMock ? tileSourceText(tileProvider) : content.map.source;
  return (
    <div className={styles.bar}>
      <span className={cx(styles.coords, 'numeric')}>
        {cursor ? fmt(content.map.coords, { lat: formatCoord(cursor.lat), lng: formatCoord(cursor.lng) }) : content.map.coordsEmpty}
      </span>
      <span className={styles.right}>
        <span className={styles.source}>{source}</span>
        <span className={styles.disclaimer}>{content.map.disclaimer}</span>
      </span>
    </div>
  );
}
