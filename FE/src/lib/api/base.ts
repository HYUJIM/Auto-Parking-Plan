import { content } from '@/lib/content';
import type { BeErrorBody } from './beTypes';
import { ApiError, type ApiErrorCode } from './types';

/**
 * BE 공개 API(`/v1/...`) 호출 공통.
 *  - 개발: VITE_API_BASE 가 비어 있으면 같은 origin 의 `/v1` 을 부르고, Vite 개발 서버가 BE 로 프록시합니다 (vite.config.ts).
 *  - 배포: FE 를 정적 호스팅하고 BE 를 직접 부르면 VITE_API_BASE=https://api.example.com (BE 의 CORS_ORIGINS 에 FE origin 등록).
 */
export const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '');

export function apiUrl(path: string, params?: Record<string, string | number | undefined>): string {
  // 단위 테스트(Node)에는 window 가 없으므로 기준 origin 을 대신 둡니다 — 상대 경로 해석에만 쓰입니다
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
  const url = new URL(`${API_BASE}${path}`, origin);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

/** BE 오류 바디 `{ error: { code, message, details? } }` 와 HTTP 상태를 ApiError 로 분류합니다 */
export async function toApiError(res: Response): Promise<ApiError> {
  let message = content.status.error;
  let beCode: string | undefined;
  let details: unknown;
  try {
    const body = (await res.json()) as BeErrorBody;
    if (typeof body?.error?.message === 'string' && body.error.message) message = body.error.message;
    if (typeof body?.error?.code === 'string') beCode = body.error.code;
    details = body?.error?.details;
  } catch {
    /* 본문이 JSON 이 아님 (프록시·게이트웨이 오류 등) */
  }
  const retryAfterRaw = Number(res.headers.get('Retry-After'));
  const retryAfter = Number.isFinite(retryAfterRaw) && retryAfterRaw > 0 ? retryAfterRaw : undefined;
  return new ApiError(message, classify(res.status, beCode), { beCode, details, status: res.status, retryAfter });
}

export function classify(status: number, beCode?: string): ApiErrorCode {
  if (status === 400 || status === 413 || status === 422) return 'BAD_REQUEST';
  if (status === 404) return 'NOT_FOUND';
  if (status === 409) return 'CONFLICT';
  if (status === 429) return 'RATE_LIMITED';
  if (status === 503) return beCode === 'SERVICE_BUSY' ? 'BUSY' : beCode === 'UPSTREAM_UNAVAILABLE' ? 'UPSTREAM' : 'SERVER';
  if (status >= 500) return 'SERVER';
  return 'UNKNOWN';
}

export interface RequestOptions {
  signal?: AbortSignal;
}

/** 요청 하나의 제한 시간. 앞단(CloudFront·프록시)이 30초에 끊으므로 그보다 조금 길게 — 응답이 영영 안 와도 화면이 멈춰 있지 않게 */
export const REQUEST_TIMEOUT_MS = 35_000;

/**
 * fetch + 오류 분류. 연결 실패(TypeError)와 제한 시간 초과는 ApiError('NETWORK'), 2xx 가 아니면 toApiError.
 * 호출 쪽이 끊은 중단(AbortError)은 그대로 던져 호출 쪽이 무시할 수 있게 둡니다.
 */
export async function request(url: string, init: RequestInit = {}): Promise<Response> {
  let res: Response;
  // 호출 쪽 신호와 제한 시간을 하나로 묶습니다. AbortSignal.any/timeout 은 조금 오래된 브라우저(사파리 17.3 이하)에 없어 직접 잇습니다
  const controller = new AbortController();
  const caller = init.signal ?? undefined;
  const onCallerAbort = () => controller.abort();
  if (caller?.aborted) throw new DOMException('Aborted', 'AbortError'); // 이미 끊긴 요청은 보내지 않습니다
  caller?.addEventListener('abort', onCallerAbort, { once: true });
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    res = await fetch(url, { ...init, signal: controller.signal, headers: { Accept: 'application/json', ...(init.headers ?? {}) } });
  } catch (err) {
    // 호출 쪽이 끊은 경우만 AbortError 로 올립니다 (제한 시간으로 끊긴 것은 연결 문제로 알립니다)
    if (caller?.aborted) throw new DOMException('Aborted', 'AbortError');
    void err;
    throw new ApiError(content.status.errorDesc, 'NETWORK');
  } finally {
    clearTimeout(timer);
    caller?.removeEventListener('abort', onCallerAbort);
  }
  if (!res.ok) throw await toApiError(res);
  return res;
}

export async function getJson<T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  opts: RequestOptions = {},
): Promise<T> {
  const res = await request(apiUrl(path, params), { signal: opts.signal });
  return (await res.json()) as T;
}

export async function postJson<T>(path: string, body: unknown, opts: RequestOptions = {}): Promise<T> {
  const res = await request(apiUrl(path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
    signal: opts.signal,
  });
  return (await res.json()) as T;
}
