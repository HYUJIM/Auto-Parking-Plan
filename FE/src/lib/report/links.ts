import { isUuid } from '@/lib/api/beAdapters';
import { isPnu } from '@/lib/api/types';
import { isDetailCategory } from '@/lib/ruleDetail/params';

/**
 * 결과 리포트 주변의 화면 주소 — 이용화면·결과 리포트·법규 상세가 모두 이 함수로만 링크를 만듭니다.
 * 주소에 들어가는 값은 모양을 검사한 것만 씁니다 (PNU 19자리 · 검토 id UUID · 정해진 항목 코드 10개).
 * 검토 id 를 함께 넘기면 새로고침·인쇄·상세 페이지가 같은 검토를 다시 받습니다 (새 검토를 만들지 않음).
 */

/** 법규 상세 페이지가 다루는 항목 코드 (핵심 6 + 별도 확인 4) */
export function isRuleCategory(value: unknown): value is string {
  return isDetailCategory(value);
}

function query(pnu: string, reviewId?: string | null): string {
  const q = new URLSearchParams();
  if (isPnu(pnu)) q.set('pnu', pnu);
  if (reviewId && isUuid(reviewId)) q.set('review', reviewId);
  const s = q.toString();
  return s ? `?${s}` : '';
}

/** /report?pnu=…&review=… */
export function reportHref(pnu: string, reviewId?: string | null): string {
  return `/report${query(pnu, reviewId)}`;
}

/** /report/rules/{category}?pnu=…&review=… — 코드가 이상하면 null */
export function ruleDetailHref(category: string, pnu: string, reviewId?: string | null): string | null {
  if (!isRuleCategory(category)) return null;
  return `/report/rules/${category}${query(pnu, reviewId)}`;
}

/** 이용화면 /site?pnu=… */
export function siteHref(pnu: string): string {
  return isPnu(pnu) ? `/site${query(pnu)}` : '/app';
}
