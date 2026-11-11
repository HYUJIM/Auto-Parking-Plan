import { describe, expect, it } from 'vitest';
import { apiUrl, classify, toApiError } from './base';
import { ApiError } from './types';
import { describeApiError } from './errors';
import addressNotFound from './__fixtures__/address_search_404.json';
import parcelNotFound from './__fixtures__/parcel_404.json';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

describe('HTTP 상태 + BE error.code → ApiErrorCode', () => {
  it('422·400·413 → BAD_REQUEST, 404 → NOT_FOUND, 409 → CONFLICT, 429 → RATE_LIMITED', () => {
    expect(classify(422, 'INVALID_REQUEST')).toBe('BAD_REQUEST');
    expect(classify(400)).toBe('BAD_REQUEST');
    expect(classify(413)).toBe('BAD_REQUEST');
    expect(classify(404, 'PARCEL_NOT_FOUND')).toBe('NOT_FOUND');
    expect(classify(409)).toBe('CONFLICT');
    expect(classify(429, 'RATE_LIMITED')).toBe('RATE_LIMITED');
  });

  it('503 은 BE 코드로 구분: SERVICE_BUSY → BUSY, UPSTREAM_UNAVAILABLE → UPSTREAM, 그 밖 5xx → SERVER', () => {
    expect(classify(503, 'SERVICE_BUSY')).toBe('BUSY');
    expect(classify(503, 'UPSTREAM_UNAVAILABLE')).toBe('UPSTREAM');
    expect(classify(503)).toBe('SERVER');
    expect(classify(500, 'INTERNAL_ERROR')).toBe('SERVER');
    expect(classify(502)).toBe('SERVER');
  });

  it('그 밖(401·403 등)은 UNKNOWN — 501 NOT_READY 는 더 이상 없음', () => {
    expect(classify(401)).toBe('UNKNOWN');
    expect(classify(403)).toBe('UNKNOWN');
    expect(classify(501)).toBe('SERVER');
  });
});

describe('toApiError', () => {
  it('BE 오류 바디의 code·message·details 를 보존한다 (주소 검색 404)', async () => {
    const err = await toApiError(jsonResponse(404, addressNotFound));
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('NOT_FOUND');
    expect(err.beCode).toBe('ADDRESS_NOT_FOUND');
    expect(err.message).toBe('주소를 찾을 수 없습니다. 도로명 또는 지번주소를 다시 확인해주세요.');
    expect(err.details).toEqual({ reason: '주소를 상세히 입력해 주시기 바랍니다.' });
    expect(err.status).toBe(404);
  });

  it('PARCEL_NOT_FOUND 는 details 없이', async () => {
    const err = await toApiError(jsonResponse(404, parcelNotFound));
    expect(err.beCode).toBe('PARCEL_NOT_FOUND');
    expect(err.details).toBeUndefined();
  });

  it('503 SERVICE_BUSY + Retry-After → BUSY, retryAfter 초', async () => {
    const err = await toApiError(
      jsonResponse(503, { error: { code: 'SERVICE_BUSY', message: '요청이 많아 잠시 후 다시 시도해주세요.' } }, { 'Retry-After': '30' }),
    );
    expect(err.code).toBe('BUSY');
    expect(err.retryAfter).toBe(30);
    expect(describeApiError(err).retryable).toBe(true);
  });

  it('JSON 이 아닌 본문(게이트웨이 오류 등)이면 고정 문구로', async () => {
    const err = await toApiError(new Response('<html>Bad Gateway</html>', { status: 502 }));
    expect(err.code).toBe('SERVER');
    expect(err.beCode).toBeUndefined();
    expect(err.message.length).toBeGreaterThan(0);
  });

  it('422 INVALID_REQUEST 는 다시 시도해도 소용없는 오류로 설명한다', async () => {
    const err = await toApiError(
      jsonResponse(422, {
        error: { code: 'INVALID_REQUEST', message: '요청 형식이 올바르지 않습니다.', details: [{ loc: ['body', 'pnu'] }] },
      }),
    );
    expect(err.code).toBe('BAD_REQUEST');
    expect(describeApiError(err).retryable).toBe(false);
  });
});

describe('apiUrl', () => {
  it('빈 값·undefined 파라미터는 빼고 나머지는 인코딩한다', () => {
    const url = new URL(apiUrl('/v1/address/search', { q: '범어동 9001', limit: undefined, x: '' }));
    expect(url.pathname).toBe('/v1/address/search');
    expect(url.searchParams.get('q')).toBe('범어동 9001');
    expect([...url.searchParams.keys()]).toEqual(['q']);
  });
});
