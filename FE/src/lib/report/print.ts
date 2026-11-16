/**
 * 'PDF 저장' — 브라우저 인쇄 창(window.print)으로 PDF 를 만듭니다. 서버 왕복도, 별도 라이브러리도 없습니다.
 * 인쇄 전에 글꼴과 지도 타일 이미지가 다 그려질 때까지(최대 몇 초) 기다리고, 문서 제목을 저장 파일 이름으로 바꿨다가
 * 인쇄 창이 닫히면(afterprint) 되돌립니다.
 */

export const PRINT_WAIT_MS = 8000;

/** HTMLImageElement 중 여기서 쓰는 부분 (테스트에서 가짜로 바꿀 수 있게) */
export interface ImageLike {
  complete: boolean;
  decode?: () => Promise<void>;
}

/** 이미지 하나가 그려질 준비가 될 때까지 — 실패한 이미지(타일 없음)도 끝난 것으로 봅니다 */
export function imageReady(img: ImageLike): Promise<void> {
  if (img.complete || typeof img.decode !== 'function') return Promise.resolve();
  return img.decode().then(
    () => undefined,
    () => undefined,
  );
}

/** 모두 준비되면 true, 제한 시간이 먼저 지나면 false (그래도 인쇄는 진행) */
export function waitForAssets(images: Iterable<ImageLike>, fonts: Promise<unknown> | null, timeoutMs = PRINT_WAIT_MS): Promise<boolean> {
  const all = Promise.all([...Array.from(images, imageReady), (fonts ?? Promise.resolve()).catch(() => undefined)]).then(() => true);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<boolean>((resolve) => {
    timer = setTimeout(() => resolve(false), timeoutMs);
  });
  return Promise.race([all, timeout]).finally(() => clearTimeout(timer));
}

export interface PrintEnv {
  doc: { title: string };
  win: {
    print: () => void;
    addEventListener: (type: 'afterprint', fn: () => void) => void;
    removeEventListener: (type: 'afterprint', fn: () => void) => void;
  };
}

/**
 * 문서 제목을 바꿔 인쇄 창을 열고, 인쇄 창이 닫히면 제목을 되돌립니다.
 * 돌려주는 함수를 부르면 바로 되돌립니다 (화면을 떠날 때 — 여러 번 불러도 한 번만 동작).
 */
export function printWithTitle(title: string, env: PrintEnv): () => void {
  const previous = env.doc.title;
  let restored = false;
  const restore = () => {
    if (restored) return;
    restored = true;
    env.doc.title = previous;
    env.win.removeEventListener('afterprint', restore);
  };
  env.doc.title = title;
  env.win.addEventListener('afterprint', restore);
  try {
    env.win.print();
  } catch {
    restore();
  }
  return restore;
}
