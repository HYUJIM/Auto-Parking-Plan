// ESLint 9+ 플랫 설정 — 타입스크립트 권장 규칙 + React Hooks 규칙 + Vite Fast Refresh 규칙
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

const reactHooksFlat = reactHooks.configs?.flat?.recommended ?? reactHooks.configs['recommended-latest'];

const typeImports = ['error', { prefer: 'type-imports', fixStyle: 'inline-type-imports' }];
const unusedVars = ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }];

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },

  // 브라우저 코드
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, reactHooksFlat],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: { 'react-refresh': reactRefresh },
    rules: {
      // 컨텍스트 파일은 Provider 와 훅을 함께 내보냅니다 (Fast Refresh 가 그 파일만 전체 갱신)
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true, allowExportNames: ['useMap', 'useMapCursor', 'useToast'] },
      ],
      '@typescript-eslint/consistent-type-imports': typeImports,
      '@typescript-eslint/no-unused-vars': unusedVars,
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },

  // Node 코드 (스크립트, 설정) — 브라우저 전역 없음
  {
    files: ['scripts/**/*.mjs', 'vite.config.ts', 'vitest.config.ts', 'eslint.config.js'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.node,
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': typeImports,
      '@typescript-eslint/no-unused-vars': unusedVars,
    },
  },

  // 테스트
  {
    files: ['**/*.test.ts', '**/*.test.tsx'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
);
