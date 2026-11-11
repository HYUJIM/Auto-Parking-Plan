import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';

// 글꼴(자체 호스팅, 동적 서브셋) → 토큰 → 확정 디자인(클리어 블루프린트) → 전역 기본 → 지도 공용 스타일 (순서 유지)
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import './design/tokens.css';
import './design/themes/clear-blue.css';
import './design/base.css';
import './lib/map/map.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
