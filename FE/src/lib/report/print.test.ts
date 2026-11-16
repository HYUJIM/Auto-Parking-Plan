import { afterEach, describe, expect, it, vi } from 'vitest';
import { imageReady, printWithTitle, waitForAssets, type PrintEnv } from './print';

afterEach(() => {
  vi.useRealTimers();
});

describe('인쇄 전 기다리기', () => {
  it('이미 받은 이미지는 바로, 받는 중이면 decode 까지, 실패해도 끝난 것으로', async () => {
    await expect(imageReady({ complete: true })).resolves.toBeUndefined();
    const decode = vi.fn(() => Promise.reject(new Error('broken')));
    await expect(imageReady({ complete: false, decode })).resolves.toBeUndefined();
    expect(decode).toHaveBeenCalledOnce();
  });

  it('모두 준비되면 true', async () => {
    const ok = await waitForAssets([{ complete: true }, { complete: false, decode: () => Promise.resolve() }], Promise.resolve(), 1000);
    expect(ok).toBe(true);
  });

  it('제한 시간이 지나면 false (인쇄는 그래도 진행)', async () => {
    vi.useFakeTimers();
    const never = new Promise<void>(() => {});
    const p = waitForAssets([{ complete: false, decode: () => never }], null, 8000);
    await vi.advanceTimersByTimeAsync(8000);
    await expect(p).resolves.toBe(false);
  });
});

describe('문서 제목 바꿔 인쇄', () => {
  function env(): PrintEnv & { listeners: Set<() => void> } {
    const listeners = new Set<() => void>();
    return {
      listeners,
      doc: { title: 'APP' },
      win: {
        print: vi.fn(),
        addEventListener: (_t, fn) => listeners.add(fn),
        removeEventListener: (_t, fn) => listeners.delete(fn),
      },
    };
  }

  it('인쇄하는 동안 제목을 바꾸고, afterprint 에 되돌린다', () => {
    const e = env();
    let titleAtPrint = '';
    e.win.print = () => {
      titleAtPrint = e.doc.title;
    };
    printWithTitle('주차검토_범어동_2026-11-10', e);
    expect(titleAtPrint).toBe('주차검토_범어동_2026-11-10');
    expect(e.doc.title).toBe('주차검토_범어동_2026-11-10');
    e.listeners.forEach((fn) => fn());
    expect(e.doc.title).toBe('APP');
    expect(e.listeners.size).toBe(0);
  });

  it('되돌리기는 한 번만, 인쇄가 실패하면 바로 되돌린다', () => {
    const e = env();
    const restore = printWithTitle('x', e);
    restore();
    e.doc.title = '다른 화면';
    restore();
    expect(e.doc.title).toBe('다른 화면');

    const f = env();
    f.win.print = () => {
      throw new Error('blocked');
    };
    printWithTitle('y', f);
    expect(f.doc.title).toBe('APP');
  });
});
