import { useLayoutEffect, useRef } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { TopBar } from '@/components/TopBar';
import styles from './ReportLayout.module.css';

/**
 * 결과 리포트 화면의 틀 (/report, /report/rules/…): 상단 바 + 전체 폭 본문(자체 스크롤).
 * 이용화면(MapShell)과 달리 움직이는 지도가 없고, 인쇄(PDF 저장)하면 상단 바는 빠지고 본문만 나옵니다.
 */
export function ReportLayout() {
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  // 결과 리포트 ↔ 법규 상세는 같은 스크롤 상자를 씁니다 — 화면(경로)이 바뀔 때만 맨 위에서 시작합니다.
  // 같은 화면 안의 #rule-… 이동(원문 카드로 건너뛰기)과 그 뒤로 가기는 건드리지 않습니다
  const { pathname } = location;
  useLayoutEffect(() => {
    if (!window.location.hash) mainRef.current?.scrollTo({ top: 0, behavior: 'instant' });
  }, [pathname]);
  return (
    <div className={styles.shell}>
      <div data-print="hide">
        <TopBar />
      </div>
      <main ref={mainRef} className={styles.main}>
        <ErrorBoundary resetKey={location.pathname + location.search}>
          <Outlet />
        </ErrorBoundary>
      </main>
    </div>
  );
}
