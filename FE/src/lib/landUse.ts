import type { BadgeVariant } from '@/components/Badge';

/** 토지이용계획 관계(포함·저촉·접함)에 따른 배지 색 */
export function relationVariant(relation: string): BadgeVariant {
  if (relation.includes('저촉')) return 'check';
  if (relation.includes('포함')) return 'info';
  return 'neutral';
}
