import { useMap } from '@/lib/map/MapContext';
import { mapControlsProps } from '@/lib/map/layoutAnchors';
import { content } from '@/lib/content';
import { Button } from '../Button';
import styles from './MapControls.module.css';

/** 지도 우측 컨트롤: 일반 · 위성 · 지적도 토글, 확대/축소, (이용화면) 필지에 맞춤 */
export function MapControls() {
  const { status, tileProvider, mapType, cadastral, scene, setMapType, toggleCadastral, zoomIn, zoomOut, fitScene } = useMap();
  const t = content.map;
  // 기본 지도(타일 배경)에는 지적 경계 타일이 없어 지적도 토글을 잠급니다 — 타일을 끈 미리보기 지도는 도식 격자를 그립니다
  const cadastralUnavailable = tileProvider !== 'none' && (status === 'mock' || status === 'mock-fallback');

  return (
    <div className={styles.controls} {...mapControlsProps}>
      <div className={styles.group} role="group" aria-label={t.layers.label}>
        <Button
          variant="secondary"
          size="sm"
          iconLeft="map"
          active={mapType === 'normal'}
          aria-label={t.layers.normal}
          onClick={() => setMapType('normal')}
        >
          {t.layers.normal}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          iconLeft="globe"
          active={mapType === 'satellite'}
          aria-label={t.layers.satellite}
          onClick={() => setMapType('satellite')}
        >
          {t.layers.satellite}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          iconLeft="grid"
          active={cadastral && !cadastralUnavailable}
          disabled={cadastralUnavailable}
          title={cadastralUnavailable ? t.layers.cadastralUnavailable : undefined}
          aria-label={t.layers.cadastral}
          onClick={toggleCadastral}
        >
          {t.layers.cadastral}
        </Button>
      </div>

      <div className={styles.group}>
        <Button variant="icon" size="sm" icon="plus" aria-label={t.zoomIn} onClick={zoomIn} />
        <Button variant="icon" size="sm" icon="minus" aria-label={t.zoomOut} onClick={zoomOut} />
        {scene.kind !== 'overview' && <Button variant="icon" size="sm" icon="maximize" aria-label={t.fit} onClick={fitScene} />}
      </div>
    </div>
  );
}
