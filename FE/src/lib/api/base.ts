import { content } from '@/lib/content';
import { ApiError } from './types';

/** API 서버 주소. 개발 중에는 비어 있어 같은 origin 의 /api (Vite 미들웨어)를 씁니다. */
export const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '');

export function apiUrl(path: string, params?: Record<string, string | number | undefined>): string {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

/** 실패 응답의 { message } 를 읽어 ApiError 로 바꿉니다 */
export async function toApiError(res: Response): Promise<ApiError> {
  let message = content.status.error;
  try {
    const body = (await res.json()) as { message?: string };
    if (body?.message) message = body.message;
  } catch {
    /* 본문이 JSON 이 아님 */
  }
  if (res.status === 400 || res.status === 422) return new ApiError(message, 'BAD_REQUEST');
  if (res.status === 404) return new ApiError(message, 'NOT_FOUND');
  if (res.status === 429) return new ApiError(message, 'RATE_LIMITED');
  if (res.status === 501) return new ApiError(message, 'NOT_READY');
  if (res.status === 503) return new ApiError(message, 'BUSY');
  if (res.status === 502 || res.status === 504) return new ApiError(message, 'UPSTREAM');
  if (res.status >= 500) return new ApiError(message, 'SERVER');
  return new ApiError(message, 'NETWORK');
}
