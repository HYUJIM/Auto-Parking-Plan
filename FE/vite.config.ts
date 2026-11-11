import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';

const root = fileURLToPath(new URL('.', import.meta.url));

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, root, '');
  /**
   * 개발·미리보기 서버는 BE 공개 API(`/v1/*`)를 BE 프로세스로 프록시합니다 — 브라우저 입장에서는 같은 origin 이라 CORS 가 필요 없습니다.
   * BE 가 다른 주소에 있으면 VITE_PROXY_TARGET (셸 환경변수 또는 .env) 로 바꿉니다. 운영 빌드는 VITE_API_BASE 로 BE 를 직접 부릅니다 (src/lib/api/base.ts).
   */
  const proxyTarget = process.env.VITE_PROXY_TARGET ?? env.VITE_PROXY_TARGET ?? 'http://127.0.0.1:8000';
  const proxy: Record<string, ProxyOptions> = {
    '/v1': { target: proxyTarget, changeOrigin: true },
  };

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy,
    },
    preview: {
      proxy,
    },
  };
});
