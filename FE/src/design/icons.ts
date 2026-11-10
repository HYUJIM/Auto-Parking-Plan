/**
 * 아이콘 목록.
 *
 * 아이콘 추가: public/icons/<이름>.svg 파일을 넣고 아래 배열에 이름을 한 줄 추가합니다.
 * 아이콘 교체: 같은 이름의 SVG 파일로 덮어쓰면 새로고침 후 사이트 전체에 반영됩니다.
 *
 * 규격: 24×24, stroke="currentColor", fill="none" (Lucide 아이콘 규격). 선 색은 버튼 글자색을 따라갑니다.
 */
export const icons = [
  'search',
  'map-pin',
  'locate',
  'history',
  'list',
  'arrow-left',
  'arrow-right',
  'chevron-down',
  'chevron-up',
  'chevron-right',
  'lock',
  'file-text',
  'layout-grid',
  'download',
  'plus',
  'minus',
  'layers',
  'map',
  'globe',
  'grid',
  'compass',
  'navigation',
  'check-circle',
  'alert-circle',
  'info',
  'x',
  'external-link',
  'menu',
  'user',
  'bell',
  'book-open',
  'shield-check',
  'car',
  'ruler',
  'refresh',
  'loader',
  'maximize',
  'palette',
  'check',
] as const;

export type IconName = (typeof icons)[number];

export function isIconName(value: string): value is IconName {
  return (icons as readonly string[]).includes(value);
}
