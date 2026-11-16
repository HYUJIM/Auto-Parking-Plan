import { describe, expect, it } from 'vitest';
import { radioKeyTarget } from './radioKeys';

describe('라디오 그룹 키보드 이동', () => {
  it('화살표는 다음·이전으로, 끝에서는 반대쪽 끝으로 돈다', () => {
    expect(radioKeyTarget('ArrowRight', 0, 3)).toBe(1);
    expect(radioKeyTarget('ArrowDown', 2, 3)).toBe(0);
    expect(radioKeyTarget('ArrowLeft', 1, 3)).toBe(0);
    expect(radioKeyTarget('ArrowUp', 0, 3)).toBe(2);
  });

  it('Home·End 는 처음·끝, 다른 키와 빈 그룹은 null', () => {
    expect(radioKeyTarget('Home', 2, 3)).toBe(0);
    expect(radioKeyTarget('End', 0, 3)).toBe(2);
    expect(radioKeyTarget('Enter', 0, 3)).toBeNull();
    expect(radioKeyTarget('ArrowRight', 0, 0)).toBeNull();
  });
});
