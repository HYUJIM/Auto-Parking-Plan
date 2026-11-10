import ko from '@/content/ko.json';

/**
 * 화면에 보이는 문구는 전부 src/content/ko.json 에서 읽습니다.
 * 사용: content.home.headline / fmt(content.map.coords, { lat, lng })
 */
export const content = ko;
export type Content = typeof ko;

/** "{n}건" 같은 자리표시자를 채웁니다. */
export function fmt(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) => (Object.hasOwn(vars, key) ? String(vars[key]) : match));
}
