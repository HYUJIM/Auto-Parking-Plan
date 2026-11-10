import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { apiPlugin } from './server/vitePlugin.ts';

const root = fileURLToPath(new URL('.', import.meta.url));

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // VITE_ 접두사가 없는 서버 전용 키(JUSO_API_KEY 등)는 여기서만 읽습니다. 브라우저 번들에는 들어가지 않습니다.
  const env = loadEnv(mode, root, '');

  return {
    plugins: [
      react(),
      apiPlugin({
        jusoKey: env.JUSO_API_KEY,
        coordKey: env.JUSO_COORD_API_KEY,
        vworldKey: env.VWORLD_API_KEY,
        vworldDomain: env.VWORLD_DOMAIN,
      }),
    ],
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    server: {
      port: 5173,
      strictPort: true,
    },
  };
});
