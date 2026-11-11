import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { ToastProvider } from '@/components/Toast';
import { MapShell } from '@/layouts/MapShell';
import { HomePanel } from '@/pages/HomePanel';
import { LandingPage } from '@/pages/landing/LandingPage';
import { SitePanel } from '@/pages/SitePanel';

/**
 * 라우트
 *   /               소개 페이지 (제품 소개 + '시작하기')
 *   /app            1단계 초기화면 (좌측 패널 = HomePanel, 지도 유지)
 *   /site?pnu=…     2단계 이용화면 (좌측 패널 = SitePanel, 지도 유지)
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
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </ErrorBoundary>
  );
}
