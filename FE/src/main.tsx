import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { applyTheme, getInitialTheme } from './design/theme.config';

// 글꼴(자체 호스팅, 동적 서브셋) → 토큰 → 디자인 안 3종 → 전역 기본 → 지도 공용 스타일 (순서 유지)
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css';
import './design/tokens.css';
import './design/themes/clear-blue.css';
import './design/themes/drafting-mono.css';
import './design/themes/warm-land.css';
import './design/base.css';
import './lib/map/map.css';

applyTheme(getInitialTheme());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
