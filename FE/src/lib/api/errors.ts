import { content } from '@/lib/content';
import { ApiError } from './types';

export interface ErrorDescription {
  title: string;
  /** BE 가 준 고정 안내 문구(ApiError.message) 또는 일반 설명 */
  desc?: string;
  /** '다시 시도' 가 의미 있는 오류인지 */
  retryable: boolean;
}

/**
 * 오류 → 화면 문구. AsyncBlock(카드) · SearchBox(검색창) · 지도 클릭 토스트 · 배치안 탭이 모두 이 함수를 써서
 * 같은 오류가 화면마다 다르게 설명되지 않게 합니다.
 * BE 의 error.message 는 고정 안내 문구만 오므로 그대로 보여 줘도 안전합니다. 브라우저 내부 오류 문구는 숨깁니다.
 */
export function describeApiError(err: unknown): ErrorDescription {
  const s = content.status;
  if (err instanceof ApiError) {
    const desc = err.message || undefined;
    switch (err.code) {
      case 'BAD_REQUEST':
        return { title: content.site.badRequest, desc, retryable: false };
      case 'NOT_FOUND':
      case 'CONFLICT':
        return { title: s.error, desc, retryable: false };
      case 'RATE_LIMITED':
      case 'BUSY':
        return { title: s.rateLimited, desc, retryable: true };
      case 'UPSTREAM':
        return { title: s.upstream, desc, retryable: true };
      default:
        return { title: s.error, desc: desc ?? s.errorDesc, retryable: true };
    }
  }
  return { title: s.error, desc: s.errorDesc, retryable: true };
}
