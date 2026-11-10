/**
 * 디자인 안 전체 교체는 이 파일의 `activeTheme` 한 줄만 바꾸면 됩니다.
 *
 *   'clear-blue'    A안 · 클리어 블루프린트 (1순위 — 확정)
 *   'drafting-mono' B안 · 드래프팅 모노
 *   'warm-land'     C안 · 웜 랜드
 *
 * 각 안의 토큰은 ./themes/<id>.css 에 있고, main.tsx 에서 세 파일을 모두 불러옵니다.
 * /kit 페이지에서는 세션 동안만 다른 안을 미리 볼 수 있습니다(새 탭을 열면 확정 안으로 돌아옴).
 */
export const themes = {
  'clear-blue': { label: 'A안 · 클리어 블루프린트', short: 'A' },
  'drafting-mono': { label: 'B안 · 드래프팅 모노', short: 'B' },
  'warm-land': { label: 'C안 · 웜 랜드', short: 'C' },
} as const;

export type ThemeId = keyof typeof themes;

export const activeTheme: ThemeId = 'clear-blue';

const PREVIEW_KEY = 'app:theme-preview';

export function isThemeId(value: unknown): value is ThemeId {
  // `in` 은 프로토타입 체인까지 보므로('constructor' 등) 자기 키만 확인합니다
  return typeof value === 'string' && Object.hasOwn(themes, value);
}

export function getInitialTheme(): ThemeId {
  try {
    const preview = sessionStorage.getItem(PREVIEW_KEY);
    if (isThemeId(preview)) return preview;
  } catch {
    /* 저장소 접근 불가 — 확정 안 사용 */
  }
  return activeTheme;
}

export function applyTheme(id: ThemeId): void {
  document.documentElement.dataset.theme = id;
}

/** /kit 에서 다른 안을 잠깐 볼 때 사용. 확정 안을 고르면 미리보기 기록을 지웁니다. */
export function previewTheme(id: ThemeId): void {
  applyTheme(id);
  try {
    if (id === activeTheme) sessionStorage.removeItem(PREVIEW_KEY);
    else sessionStorage.setItem(PREVIEW_KEY, id);
  } catch {
    /* 무시 */
  }
}

export function getCurrentTheme(): ThemeId {
  const current = document.documentElement.dataset.theme;
  return isThemeId(current) ? current : activeTheme;
}
