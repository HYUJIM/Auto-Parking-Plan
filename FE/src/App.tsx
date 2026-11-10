import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { ToastProvider } from '@/components/Toast';
import { MapShell } from '@/layouts/MapShell';
import { HomePanel } from '@/pages/HomePanel';
import { SitePanel } from '@/pages/SitePanel';
import { KitPage } from '@/pages/KitPage';

/**
 * 라우트
 *   /               1단계 초기화면 (좌측 패널 = HomePanel, 지도 유지)
 *   /site?pnu=…     2단계 이용화면 (좌측 패널 = SitePanel, 지도 유지)
 *   /kit            디자인 키트
 */
export default function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.pathname + location.search}>
      <ToastProvider>
        <Routes>
          <Route element={<MapShell />}>
            <Route index element={<HomePanel />} />
            <Route path="site" element={<SitePanel />} />
          </Route>
          <Route path="kit" element={<KitPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </ErrorBoundary>
  );
}
