import { describe, expect, it } from 'vitest';
import { isThemeId, themes } from './theme.config';

describe('isThemeId', () => {
  it('정의된 디자인 안 id 만 통과한다', () => {
    for (const id of Object.keys(themes)) expect(isThemeId(id)).toBe(true);
  });

  it('프로토타입 키·다른 타입은 거른다 (sessionStorage 값으로 전체 스타일이 날아가지 않도록)', () => {
    expect(isThemeId('constructor')).toBe(false);
    expect(isThemeId('toString')).toBe(false);
    expect(isThemeId('hasOwnProperty')).toBe(false);
    expect(isThemeId('__proto__')).toBe(false);
    expect(isThemeId('')).toBe(false);
    expect(isThemeId(null)).toBe(false);
    expect(isThemeId(1)).toBe(false);
  });
});
