import { useEffect, useRef, type KeyboardEvent } from 'react';
import { useMap } from '@/lib/map/MapContext';
import { tileBadgeDesc, tileBadgeText } from '@/lib/map/tileCopy';
import { content } from '@/lib/content';
import { Icon } from '../Icon';
import { MapControls } from './MapControls';
import { MapStatusBar } from './MapStatusBar';
import { ScaleBar } from './ScaleBar';
import styles from './MapView.module.css';

const PAN_STEP_PX = 80;

/** 우측 지도 영역: 지도 캔버스 + 레이어/줌 컨트롤 + 방위 + 축척 + 하단 상태 바 */
export function MapView() {
  const map = useMap();
  const { attach, panBy, zoomIn, zoomOut } = map;
  const canvasRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    return attach(el);
  }, [attach]);

  const isMock = map.status === 'mock' || map.status === 'mock-fallback';
  // 키 없음: 타일 배경이면 '기본 지도'(설명은 실제 타일 공급자에 맞춤), 타일까지 껐으면 '미리보기 지도'. 네이버 실패(fallback)는 사유를 보여 줍니다
  const previewBadge = tileBadgeText(map.tileProvider);
  const previewDesc =
    map.status === 'mock'
      ? tileBadgeDesc(map.tileProvider)
      : map.fallbackReason === 'script'
        ? content.map.fallbackScriptDesc
        : content.map.fallbackDesc;

  // 키보드로도 지도를 움직일 수 있게: 화살표 = 이동, +/- = 확대·축소
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const steps: Record<string, [number, number]> = {
      ArrowUp: [0, -PAN_STEP_PX],
      ArrowDown: [0, PAN_STEP_PX],
      ArrowLeft: [-PAN_STEP_PX, 0],
      ArrowRight: [PAN_STEP_PX, 0],
    };
    if (e.key in steps) {
      e.preventDefault();
      panBy(...steps[e.key]);
    } else if (e.key === '+' || e.key === '=') {
      e.preventDefault();
      zoomIn();
    } else if (e.key === '-' || e.key === '_') {
      e.preventDefault();
      zoomOut();
    }
  };

  return (
    <div className={styles.wrap}>
      <div
        ref={canvasRef}
        className={styles.canvas}
        role="region"
        aria-label={content.map.label}
        aria-roledescription={content.map.roleDescription}
        aria-keyshortcuts="ArrowUp ArrowDown ArrowLeft ArrowRight + -"
        tabIndex={0}
        onKeyDown={onKeyDown}
      />

      {map.status === 'loading' && (
        <div className={styles.loading}>
          <Icon name="loader" size={18} className={styles.spin} />
          {content.map.loading}
        </div>
      )}

      <div className={styles.topLeft}>
        {isMock && (
          <div className={styles.previewWrap}>
            <span className={styles.preview} title={previewDesc}>
              <Icon name="info" size={14} />
              {previewBadge}
            </span>
            {map.status === 'mock-fallback' && <span className={styles.previewDesc}>{previewDesc}</span>}
          </div>
        )}
        {map.scene.kind !== 'overview' && (
          <span className={styles.north} role="img" aria-label={content.map.north}>
            <Icon name="navigation" size={13} className={styles.northIcon} />
            <span>{content.map.north}</span>
          </span>
        )}
      </div>

      <MapControls />
      <ScaleBar metersPerPixel={map.metersPerPixel} />
      <MapStatusBar />
    </div>
  );
}
