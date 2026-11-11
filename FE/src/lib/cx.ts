/** 클래스명 합치기: cx('a', cond && 'b', undefined) → 'a b' */
export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
