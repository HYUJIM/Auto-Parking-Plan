import { describe, expect, it } from 'vitest';
import { JusoError, buildPnu, sanitizeKeyword, searchJuso, toAddressHit, type JusoRow } from './juso.ts';

function row(partial: Partial<JusoRow>): JusoRow {
  return {
    admCd: '2726010100',
    rnMgtSn: '272603007004',
    bdMgtSn: '2726010100101770003000001',
    udrtYn: '0',
    buldMnnm: '336',
    buldSlno: '0',
    lnbrMnnm: '177',
    lnbrSlno: '3',
    mtYn: '0',
    roadAddr: '대구광역시 수성구 동대구로 336 (범어동)',
    roadAddrPart1: '대구광역시 수성구 동대구로 336',
    jibunAddr: '대구광역시 수성구 범어동 177-3',
    bdNm: '',
    siNm: '대구광역시',
    sggNm: '수성구',
    emdNm: '범어동',
    zipNo: '42028',
    ...partial,
  };
}

function fakeFetch(body: unknown, status = 200): typeof fetch {
  return (async () =>
    new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })) as unknown as typeof fetch;
}

function okResponse(rows: JusoRow[], totalCount = rows.length) {
  return {
    results: {
      common: { errorCode: '0', errorMessage: '정상', totalCount: String(totalCount), currentPage: '1', countPerPage: '10' },
      juso: rows,
    },
  };
}

describe('buildPnu', () => {
  it('법정동코드 + 산구분 + 본번4 + 부번4 = 19자리', () => {
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '177', lnbrSlno: '3' })).toBe('2726010100101770003');
    expect(buildPnu({ admCd: '2726010100', mtYn: '1', lnbrMnnm: '12', lnbrSlno: '' })).toBe('2726010100200120000');
  });

  it('숫자가 아니거나 범위를 벗어난 지번은 거른다', () => {
    expect(buildPnu({ admCd: '27260', mtYn: '0', lnbrMnnm: '1', lnbrSlno: '0' })).toBeNull();
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '', lnbrSlno: '0' })).toBeNull();
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '0', lnbrSlno: '0' })).toBeNull();
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '1e3', lnbrSlno: '0' })).toBeNull();
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '12.5', lnbrSlno: '0' })).toBeNull();
    expect(buildPnu({ admCd: '2726010100', mtYn: '0', lnbrMnnm: '12345', lnbrSlno: '0' })).toBeNull();
  });
});

describe('sanitizeKeyword', () => {
  it('juso 가 거부하는 특수문자를 공백으로 바꾸고 공백을 정리한다', () => {
    expect(sanitizeKeyword("  범어동   177-3 <script>'; ")).toBe('범어동 177-3 script');
    expect(sanitizeKeyword('a=b%c')).toBe('a b c');
  });
});

describe('toAddressHit', () => {
  it('수성구(27260) 여부와 건물명을 반영한다', () => {
    const hit = toAddressHit(row({ bdNm: '마크팰리스 범어' }));
    expect(hit?.inSuseong).toBe(true);
    expect(hit?.roadAddress).toBe('대구광역시 수성구 동대구로 336 (마크팰리스 범어)');
    expect(hit?.center).toBeNull();
    expect(toAddressHit(row({ admCd: '2711011000' }))?.inSuseong).toBe(false);
  });

  it('도로명주소 Part1 이 비어 있으면 전체 도로명주소에 건물명을 붙인다', () => {
    const hit = toAddressHit(row({ bdNm: '빌딩', roadAddrPart1: '' }));
    expect(hit?.roadAddress).toBe('대구광역시 수성구 동대구로 336 (범어동) (빌딩)');
    expect(hit).not.toHaveProperty('zipNo');
  });
});

describe('searchJuso', () => {
  const keys = { searchKey: 'KEY' };

  it('같은 필지의 여러 건물은 한 건으로 합치고 total 도 그만큼 줄인다', async () => {
    const rows = [row({ bdNm: 'A동' }), row({ bdNm: 'B동' }), row({ lnbrMnnm: '178', lnbrSlno: '0' })];
    const result = await searchJuso('범어동 177', keys, { count: 10 }, fakeFetch(okResponse(rows, 3)));
    expect(result.hits.map((h) => h.pnu)).toEqual(['2726010100101770003', '2726010100101780000']);
    expect(result.total).toBe(2);
    expect(result.source).toBe('juso');
  });

  it('마지막 페이지가 아니면 합쳐진 수를 뺀 전체 건수를 돌려준다', async () => {
    const rows = Array.from({ length: 10 }, (_, i) => row({ lnbrMnnm: String(100 + Math.floor(i / 2)), bdNm: `${i}동` }));
    const result = await searchJuso('범어동', keys, { count: 10 }, fakeFetch(okResponse(rows, 40)));
    expect(result.hits).toHaveLength(5);
    expect(result.total).toBe(35);
  });

  it('2페이지 이상의 짧은 마지막 페이지는 앞 페이지 수를 더해 전체 건수를 돌려준다', async () => {
    const rows = Array.from({ length: 3 }, (_, i) => row({ lnbrMnnm: String(200 + i) }));
    const result = await searchJuso('범어동', keys, { page: 2, count: 10 }, fakeFetch(okResponse(rows, 13)));
    expect(result.hits).toHaveLength(3);
    expect(result.total).toBe(13);
  });

  it('검색어 오류(E0013 등)는 예외가 아니라 안내 문구로 돌려준다', async () => {
    const body = {
      results: {
        common: {
          errorCode: 'E0013',
          errorMessage: 'SQL 예약어 또는 특수문자는 검색이 불가능 합니다.',
          totalCount: '0',
          currentPage: '1',
          countPerPage: '10',
        },
        juso: null,
      },
    };
    const result = await searchJuso('select 빌딩', keys, {}, fakeFetch(body));
    expect(result.hits).toEqual([]);
    expect(result.notice).toContain('SQL 예약어');
  });

  it('승인키 오류(E0001)는 config 종류의 JusoError 로 던진다', async () => {
    const body = {
      results: {
        common: { errorCode: 'E0001', errorMessage: '승인되지 않은 KEY 입니다.', totalCount: '0', currentPage: '1', countPerPage: '10' },
        juso: null,
      },
    };
    await expect(searchJuso('범어동', keys, {}, fakeFetch(body))).rejects.toMatchObject({
      name: 'JusoError',
      kind: 'config',
      code: 'E0001',
    });
  });

  it('HTTP 오류와 JSON 이 아닌 응답은 network 종류로 던진다', async () => {
    await expect(searchJuso('범어동', keys, {}, fakeFetch({}, 503))).rejects.toBeInstanceOf(JusoError);
    const html = (async () => new Response('<html>oops</html>', { status: 200 })) as unknown as typeof fetch;
    await expect(searchJuso('범어동', keys, {}, html)).rejects.toMatchObject({ kind: 'network' });
  });

  it('두 글자 미만이면 호출 없이 빈 결과', async () => {
    const never = (async () => {
      throw new Error('호출되면 안 됨');
    }) as unknown as typeof fetch;
    expect(await searchJuso('범', keys, {}, never)).toEqual({ hits: [], total: 0, source: 'juso' });
  });
});
