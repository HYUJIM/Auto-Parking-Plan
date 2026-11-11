import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// 단위 테스트: 순수 함수(BE 응답 어댑터·오류 분류·검토 폴링·지도 기하·저장소 값 검사)를 Node 환경에서 돌립니다.
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
