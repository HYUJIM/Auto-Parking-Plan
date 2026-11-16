import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { Skeleton } from '@/components/AsyncBlock';
import { ToastProvider } from '@/components/Toast';
import { MapShell } from '@/layouts/MapShell';
import { ReportLayout } from '@/layouts/ReportLayout';
import { HomePanel } from '@/pages/HomePanel';
import { LandingPage } from '@/pages/landing/LandingPage';
import { SitePanel } from '@/pages/SitePanel';

// 결과 리포트·법규 상세는 따로 받습니다 — 첫 화면(소개·지도)의 내려받기 크기를 줄이려고 (같은 출처 파일이라 CSP 변경 없음).
// 화면마다 Suspense 에 key 를 줘 리포트 → 상세로 처음 넘어갈 때도 이전 화면에 멈춰 있지 않고 불러오는 중 표시가 나오게 합니다
const ReportPage = lazy(() => import('@/pages/report/ReportPage').then((m) => ({ default: m.ReportPage })));
const RuleDetailPage = lazy(() => import('@/pages/report/RuleDetailPage').then((m) => ({ default: m.RuleDetailPage })));

function PageLoading() {
  return (
    <div style={{ padding: 24 }}>
      <Skeleton lines={8} />
    </div>
  );
}

/**
 * 라우트
 *   /               소개 페이지 (제품 소개 + '시작하기')
 *   /app            1단계 초기화면 (좌측 패널 = HomePanel, 지도 유지)
 *   /site?pnu=…     2단계 이용화면 (좌측 패널 = SitePanel, 지도 유지) — 대지 요약 + '결과 보기'
 *   /report?pnu=…&review=…                 3단계 결과 리포트 (현황 부지 · 주차 배치 · 토지이용계획 · 법규 검토, PDF·CAD 저장)
 *   /report/rules/:category?pnu=…&review=… 법규 상세 (요약 · 원문 근거 · 위임 관계)
 */
export default function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.pathname + location.search}>
      <ToastProvider>
        <Routes>
          <Route index element={<LandingPage />} />
          <Route element={<MapShell />}>
            <Route path="app" element={<HomePanel />} />
            <Route path="site" element={<SitePanel />} />
          </Route>
          <Route element={<ReportLayout />}>
            <Route
              path="report"
              element={
                <Suspense key="report" fallback={<PageLoading />}>
                  <ReportPage />
                </Suspense>
              }
            />
            <Route
              path="report/rules/:category"
              element={
                <Suspense key="rule-detail" fallback={<PageLoading />}>
                  <RuleDetailPage />
                </Suspense>
              }
            />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </ErrorBoundary>
  );
}
