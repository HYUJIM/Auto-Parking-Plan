import { isUuid } from '@/lib/api/beAdapters';
import { isPnu } from '@/lib/api/types';

/**
 * 법규 상세 페이지(/report/rules/:category?pnu=…&review=…)의 주소 검사. 링크는 @/lib/report/links 로 만듭니다.
 *
 * 주소창의 값은 누구나 바꿀 수 있으므로 정해진 모양만 믿습니다.
 *  - category: 핵심 법규 6항목 + 별도 확인 4항목 이름만
 *  - pnu: 숫자 19자리, review: UUID
 * 모양이 틀린 값은 화면에 되비치지도, 서버로 보내지도, 링크에 다시 싣지도 않습니다.
 */

/** 핵심 법규 항목 (review_result.items[].category) */
export const ITEM_CATEGORIES = [
  'ZONING_USE',
  'BUILDING_COVERAGE',
  'FLOOR_AREA_RATIO',
  'PARKING_REQUIREMENT',
  'PARKING_STALL_SIZE',
  'DRIVE_AISLE_WIDTH',
] as const;

/** 자동 판정하지 않는 별도 확인 항목 (review_result.manual_checks[].category) */
export const MANUAL_CATEGORIES = ['ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE'] as const;

export type ItemCategory = (typeof ITEM_CATEGORIES)[number];
export type ManualCategory = (typeof MANUAL_CATEGORIES)[number];
export type DetailCategory = ItemCategory | ManualCategory;

export function isItemCategory(value: unknown): value is ItemCategory {
  return typeof value === 'string' && (ITEM_CATEGORIES as readonly string[]).includes(value);
}

export function isManualCategory(value: unknown): value is ManualCategory {
  return typeof value === 'string' && (MANUAL_CATEGORIES as readonly string[]).includes(value);
}

export function isDetailCategory(value: unknown): value is DetailCategory {
  return isItemCategory(value) || isManualCategory(value);
}

/** 검토를 찾는 단서 — review(검토 id)가 있으면 그것, 없으면 pnu 로 검토를 이어받거나 새로 만듭니다 */
export interface ReviewTarget {
  pnu: string | null;
  reviewId: string | null;
}

export interface RuleDetailParams extends ReviewTarget {
  category: DetailCategory | null;
}

const RULE_CARD_PREFIX = 'rule-';

/** 원문 카드의 요소 id — 위임 관계의 '원문으로' 링크가 #rule-<규칙 id> 로 가리킵니다 */
export function ruleCardId(ruleId: string): string {
  return `${RULE_CARD_PREFIX}${ruleId}`;
}

/** 주소의 #… 가 원문 카드(#rule-<UUID>)를 가리키는지 — 새로고침·공유한 '원문으로' 주소 */
export function isRuleCardHash(hash: string): boolean {
  const prefix = `#${RULE_CARD_PREFIX}`;
  return hash.startsWith(prefix) && isUuid(hash.slice(prefix.length));
}

/** 주소의 :category 와 ?pnu= · ?review= 를 검사합니다. 모양이 틀린 값은 null */
export function parseRuleDetailParams(category: string | undefined, search: URLSearchParams): RuleDetailParams {
  const pnu = search.get('pnu');
  const review = search.get('review');
  return {
    category: isDetailCategory(category) ? category : null,
    pnu: isPnu(pnu) ? pnu : null,
    reviewId: isUuid(review) ? review : null,
  };
}
