import { afterEach, describe, expect, it, vi } from 'vitest';
import { content } from '@/lib/content';
import { hasInvalidQueryChars, searchAddress } from './address';
import { ApiError } from './types';
import addressSearch from './__fixtures__/address_search.json';
import addressNotFound from './__fixtures__/address_search_404.json';

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function stubFetch(response: () => Response) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      calls.push(String(url));
      return response();
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('주소 검색어 문자 검사 (BE JusoKeyword 와 같은 기준)', () => {
  it('주소에 쓰는 문자는 통과한다', () => {
    for (const q of [
      '범어동 177-1',
      '대구광역시 수성구 달구벌대로 2450 (범어동)',
      '수성구 범어로[12]',
      '4·19로 3',
      '범어 SK뷰, 101동',
      '범어ㄷ',
    ]) {
      expect(hasInvalidQueryChars(q), q).toBe(false);
    }
  });

  it('그 밖의 문자는 걸러 낸다', () => {
    for (const q of [
      '<script>alert(1)</script>',
      "' OR '1'='1",
      '범어동%',
      '범어동=1',
      '범어동;',
      '범어동/1',
      '범어동\\',
      '범어동\u0000',
      '범어동😀',
      '漢字 1',
    ]) {
      expect(hasInvalidQueryChars(q), q).toBe(true);
    }
  });
});

describe('searchAddress', () => {
  it('쓸 수 없는 문자가 있으면 요청하지 않고 안내를 돌려준다', async () => {
    const calls = stubFetch(() => json(200, addressSearch));
    const out = await searchAddress('수성구 <script>alert(1)</script>');
    expect(out).toEqual({ hits: [], total: 0, notice: content.search.invalidChars });
    expect(calls).toHaveLength(0);
  });

  it('정상 검색어는 BE 로 보낸다', async () => {
    const calls = stubFetch(() => json(200, addressSearch));
    const out = await searchAddress(' 수성구 범어동 ');
    expect(calls).toHaveLength(1);
    expect(new URL(calls[0], 'http://test.invalid').searchParams.get('q')).toBe('수성구 범어동');
    expect(out.hits.length).toBeGreaterThan(0);
  });

  it('BE 가 검색어를 거절(422)하면 그 안내를 빈 결과와 함께 돌려준다', async () => {
    const msg = '검색어에 쓸 수 없는 문자나 단어가 들어 있습니다.';
    stubFetch(() =>
      json(422, { error: { code: 'INVALID_REQUEST', message: '요청 형식이 올바르지 않습니다.', details: [{ loc: ['query', 'q'], msg }] } }),
    );
    expect(await searchAddress('수성구 union 1')).toEqual({ hits: [], total: 0, notice: msg });
  });

  it('검색어와 무관한 422 는 오류로 올린다', async () => {
    stubFetch(() =>
      json(422, { error: { code: 'INVALID_REQUEST', message: '요청 형식이 올바르지 않습니다.', details: [{ loc: ['body'], msg: 'x' }] } }),
    );
    await expect(searchAddress('수성구 범어동')).rejects.toBeInstanceOf(ApiError);
  });

  it('결과 없음(404)은 빈 결과로, 도로명주소 API 장애(503)는 UPSTREAM 오류로 올린다', async () => {
    stubFetch(() => json(404, addressNotFound));
    expect((await searchAddress('없는주소입니다')).hits).toEqual([]);
    stubFetch(() =>
      json(503, { error: { code: 'UPSTREAM_UNAVAILABLE', message: '공공데이터 조회가 지연되고 있습니다.' } }, { 'Retry-After': '42' }),
    );
    const err = await searchAddress('수성구 범어동').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('UPSTREAM');
    expect((err as ApiError).retryAfter).toBe(42);
  });
});
