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

  // Node 코드 (개발용 API 서버, 공유 타입, 스크립트, 설정) — 브라우저 전역 없음
  {
    files: ['server/**/*.ts', 'shared/**/*.ts', 'scripts/**/*.mjs', 'vite.config.ts', 'vitest.config.ts', 'eslint.config.js'],
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

  // server/ 와 shared/ 는 Node 가 타입 스트리핑으로 바로 실행합니다: src 의 `@/` 별칭·JSON import 를 쓰면 tsc 는 통과해도 기동이 깨집니다
  {
    files: ['server/**/*.ts', 'shared/**/*.ts'],
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/*', '../src/*', '../src/**', '../../src/**'],
              message: 'server/ 와 shared/ 는 src/ 를 import 하지 않습니다. 함께 쓰는 타입은 shared/types.ts 에 두세요.',
            },
          ],
        },
      ],
      // 매개변수 프로퍼티(constructor(private x))는 타입 스트리핑이 거부합니다 (tsconfig.node.json 의 erasableSyntaxOnly 와 같은 규칙)
      '@typescript-eslint/parameter-properties': ['error', { prefer: 'class-property' }],
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
