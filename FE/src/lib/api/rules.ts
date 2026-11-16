import type { BeRuleBasis } from './beTypes';
import { isUuid, toRuleBasisDetail } from './beAdapters';
import { getJson } from './base';
import { ApiError, type RuleBasisDetail } from './types';
import { content } from '@/lib/content';

/** 같은 규칙·같은 기준일 원문은 이 탭 안에서 다시 받지 않습니다 (원문은 승인된 뒤 바뀌지 않습니다) */
const cache = new Map<string, Promise<RuleBasisDetail>>();

/**
 * 승인된 법규 규칙 하나의 원문 근거 — `GET /v1/rules/{ruleId}/basis?as_of_date=`.
 * 기준일은 검토의 기준일(as_of_date)을 넘겨 검토 때와 같은 판의 조문을 봅니다.
 */
export function getRuleBasis(ruleId: string, asOfDate: string | null, signal?: AbortSignal): Promise<RuleBasisDetail> {
  if (!isUuid(ruleId)) return Promise.reject(new ApiError(content.status.errorDesc, 'BAD_REQUEST'));
  if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  const date = asOfDate && /^\d{4}-\d{2}-\d{2}$/.test(asOfDate) ? asOfDate : undefined;
  const key = `${ruleId}@${date ?? ''}`;
  let p = cache.get(key);
  if (!p) {
    p = getJson<BeRuleBasis>(`/v1/rules/${encodeURIComponent(ruleId)}/basis`, { as_of_date: date }).then(toRuleBasisDetail);
    cache.set(key, p);
    p.catch(() => cache.delete(key));
  }
  if (!signal) return p;
  const pending = p;
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'));
    const onAbort = () => reject(new DOMException('Aborted', 'AbortError'));
    signal.addEventListener('abort', onAbort, { once: true });
    pending.then(resolve, reject).finally(() => signal.removeEventListener('abort', onAbort));
  });
}

/** 테스트용 */
export function resetRuleBasisCache(): void {
  cache.clear();
}
