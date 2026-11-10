import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// 단위 테스트: 순수 함수(좌표 변환·PNU·필지 조합·법규 엔진·지도 기하)와 /api 핸들러를 Node 환경에서 돌립니다.
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['server/**/*.test.ts', 'shared/**/*.test.ts', 'src/**/*.test.ts'],
    // 개발자가 .env 에 VITE_MOCK_FAIL_RATE 를 올려 두어도 테스트는 항상 결정적으로
    env: { VITE_MOCK_FAIL_RATE: '0' },
  },
});
