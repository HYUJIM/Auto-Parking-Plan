import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { MapProvider, useMap } from '@/lib/map/MapContext';
import { mapPanelProps } from '@/lib/map/layoutAnchors';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { TopBar } from '@/components/TopBar';
import { MapView } from '@/components/map/MapView';
import styles from './MapShell.module.css';

/**
 * 한 페이지 구조: 상단 바 + 좌측 패널(라우트에 따라 내용만 교체) + 우측 지도(계속 유지).
 * `/` 와 `/site?pnu=` 가 모두 이 레이아웃 안에서 그려지므로 지도는 다시 로드되지 않습니다.
 * 모바일에서는 패널이 아래에서 올라오는 시트가 되고, 손잡이를 눌러 펼치거나 접을 수 있습니다.
 */
export function MapShell() {
  return (
    <MapProvider>
      <ShellBody />
    </MapProvider>
  );
}

function ShellBody() {
  const { fitScene } = useMap();
  const location = useLocation();
  const [expanded, setExpanded] = useState(false);
  const [sheetHeight, setSheetHeight] = useState(0);
  const panelRef = useRef<HTMLElement>(null);

  // 시트 높이를 CSS 변수로 알려 지도 하단 바·축척이 시트 위에 놓이게 합니다
  useEffect(() => {
    const el = panelRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setSheetHeight(el.offsetHeight));
    ro.observe(el);
    setSheetHeight(el.offsetHeight);
    return () => ro.disconnect();
  }, []);

  const toggle = () => {
    setExpanded((e) => !e);
    // 높이 전환이 끝난 뒤 필지가 가려지지 않게 다시 맞춤
    window.setTimeout(fitScene, 260);
  };

  const bodyStyle = { '--sheet-height': `${sheetHeight}px` } as CSSProperties;

  return (
    <div className={styles.shell}>
      <TopBar />
      <div className={styles.body} style={bodyStyle}>
        <aside ref={panelRef} className={cx(styles.panel, expanded && styles.panelExpanded)} {...mapPanelProps}>
          <button
            type="button"
            className={styles.handle}
            aria-label={expanded ? content.shell.collapse : content.shell.expand}
            aria-expanded={expanded}
            onClick={toggle}
          >
            <span className={styles.handleBar} aria-hidden="true" />
          </button>
          {/* 패널이 깨져도 지도는 남도록 패널만 따로 감쌉니다 */}
          <ErrorBoundary resetKey={location.pathname + location.search}>
            <Outlet />
          </ErrorBoundary>
        </aside>
        <main className={styles.map}>
          <MapView />
        </main>
      </div>
    </div>
  );
}
