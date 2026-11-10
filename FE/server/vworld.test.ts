import { describe, expect, it } from 'vitest';
import { VworldError, assembleParcel, centroid, getParcelFeature, pickZoning, polygonAreaM2 } from './vworld.ts';

const keys = { key: 'KEY', domain: 'localhost:5173' };

/** 위도 35.86 부근, 한 변 약 100m 의 정사각형 (시계 반대 방향, 닫는 점 없음) */
const SQUARE = [
  { lat: 35.86, lng: 128.63 },
  { lat: 35.86, lng: 128.631108 },
  { lat: 35.8609, lng: 128.631108 },
  { lat: 35.8609, lng: 128.63 },
];

function fakeFetch(body: unknown, status = 200): typeof fetch {
  return (async () =>
    new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })) as unknown as typeof fetch;
}

function featureResponse(coordinates: unknown, type = 'MultiPolygon') {
  return {
    response: {
      status: 'OK',
      result: {
        featureCollection: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              properties: {
                pnu: '2726010100101770003',
                jibun: '177-3대',
                addr: '대구광역시 수성구 범어동 177-3',
                jiga: '7372000',
                gosi_year: '2025',
              },
              geometry: { type, coordinates },
            },
          ],
        },
      },
    },
  };
}

describe('polygonAreaM2 / centroid', () => {
  it('한 변 100m 정사각형의 면적은 약 10,000㎡', () => {
    const area = polygonAreaM2(SQUARE);
    expect(area).toBeGreaterThan(9_700);
    expect(area).toBeLessThan(10_300);
  });

  it('정사각형의 면적 중심은 꼭짓점 평균과 거의 같다 (수치 오차 0.1m 미만)', () => {
    const c = centroid(SQUARE);
    const mean = { lat: 35.86045, lng: 128.630554 };
    expect(Math.abs(c.lat - mean.lat) * 111_320).toBeLessThan(0.1);
    expect(Math.abs(c.lng - mean.lng) * 90_220).toBeLessThan(0.1);
  });

  it('가늘고 긴 다각형(30m × 1m)에서도 중심이 수 m 씩 튀지 않는다', () => {
    const sliver = [
      { lat: 35.86, lng: 128.63 },
      { lat: 35.86, lng: 128.630332 },
      { lat: 35.860009, lng: 128.630332 },
      { lat: 35.860009, lng: 128.63 },
    ];
    const c = centroid(sliver);
    expect(Math.abs(c.lat - 35.8600045) * 111_320).toBeLessThan(0.1);
    expect(Math.abs(c.lng - 128.630166) * 90_220).toBeLessThan(0.1);
  });

  it('점이 3개 미만이면 평균으로 돌려준다', () => {
    expect(centroid([{ lat: 1, lng: 2 }])).toEqual({ lat: 1, lng: 2 });
    expect(polygonAreaM2([{ lat: 1, lng: 2 }])).toBe(0);
  });
});

describe('pickZoning', () => {
  it('상위 분류(도시지역·주거지역)를 건너뛰고 구체 용도지역을 고른다', () => {
    expect(
      pickZoning([
        { name: '도시지역', code: 'UQA100', relation: '포함' },
        { name: '주거지역', code: 'UQA110', relation: '포함' },
        { name: '제3종일반주거지역', code: 'UQA123', relation: '포함' },
      ]),
    ).toBe('제3종일반주거지역');
  });

  it('접함(인접)인 용도지역은 고르지 않는다', () => {
    expect(pickZoning([{ name: '일반상업지역', code: 'UQA130', relation: '접함' }])).toBeUndefined();
  });
});

describe('getParcelFeature', () => {
  it('MultiPolygon 의 바깥 링을 닫는 점 없이 돌려준다', async () => {
    const ring = [
      [128.6269, 35.8604],
      [128.6279, 35.8604],
      [128.6279, 35.8606],
      [128.6269, 35.8606],
      [128.6269, 35.8604],
    ];
    const f = await getParcelFeature('2726010100101770003', keys, fakeFetch(featureResponse([[ring]])));
    expect(f?.pnu).toBe('2726010100101770003');
    expect(f?.polygon).toHaveLength(4);
    expect(f?.polygon[0]).toEqual({ lat: 35.8604, lng: 128.6269 });
    expect(f?.jiga).toBe(7372000);
  });

  it('여러 폴리곤이면 가장 큰 것을 고른다', async () => {
    const small = [
      [128.6269, 35.8604],
      [128.62691, 35.8604],
      [128.62691, 35.86041],
      [128.6269, 35.8604],
    ];
    const big = [
      [128.63, 35.86],
      [128.631, 35.86],
      [128.631, 35.861],
      [128.63, 35.861],
      [128.63, 35.86],
    ];
    const f = await getParcelFeature('2726010100101770003', keys, fakeFetch(featureResponse([[small], [big]])));
    expect(f?.polygon[0]).toEqual({ lat: 35.86, lng: 128.63 });
  });

  it('NOT_FOUND 는 null, 인증 오류는 config 종류의 VworldError', async () => {
    expect(await getParcelFeature('2726010100101770003', keys, fakeFetch({ response: { status: 'NOT_FOUND' } }))).toBeNull();
    const err = { response: { status: 'ERROR', error: { level: '1', code: 'INCORRECT_KEY', text: '등록되지 않은 인증키입니다.' } } };
    await expect(getParcelFeature('2726010100101770003', keys, fakeFetch(err))).rejects.toMatchObject({ kind: 'config' });
    await expect(getParcelFeature('2726010100101770003', keys, fakeFetch({}, 500))).rejects.toBeInstanceOf(VworldError);
  });

  it('요청한 PNU 와 다른 필지만 오면 null (다른 필지를 그려 주지 않음)', async () => {
    const ring = [
      [128.6269, 35.8604],
      [128.6279, 35.8604],
      [128.6279, 35.8606],
      [128.6269, 35.8604],
    ];
    expect(await getParcelFeature('2726010100101770004', keys, fakeFetch(featureResponse([[ring]])))).toBeNull();
  });

  it('PNU 형식이 아니면 호출하지 않는다', async () => {
    const never = (async () => {
      throw new Error('호출되면 안 됨');
    }) as unknown as typeof fetch;
    expect(await getParcelFeature('abc', keys, never)).toBeNull();
  });
});

describe('assembleParcel', () => {
  const feature = { pnu: '2726010100101770003', jibun: '177-3대', addr: '대구광역시 수성구 범어동 177-3', polygon: SQUARE, jiga: 7372000 };

  it('토지특성이 없으면 면적은 경계로, 지목은 지번 꼬리로, 공시지가는 지적도 값으로 채운다', () => {
    const p = assembleParcel(feature, null, []);
    expect(p.areaSource).toBe('geometry');
    expect(p.area).toBeGreaterThan(9_700);
    expect(p.jimok).toBe('대');
    expect(p.landPrice).toBe(7372000);
    expect(p.inSuseong).toBe(true);
    expect(p.address).toMatchObject({ gu: '수성구', dong: '범어동', jibun: '대구광역시 수성구 범어동 177-3' });
    expect(p.zoning).toBe('');
  });

  it('토지특성이 있으면 토지대장 값을 우선한다', () => {
    const p = assembleParcel(
      feature,
      {
        area: 2771,
        jimok: '대',
        zoning1: '중심상업지역',
        zoning2: undefined,
        roadSide: '광대소각',
        landPrice: 7633000,
        ldName: '대구광역시 수성구 범어동',
        jibunNo: '177-3',
        baseYear: '2026',
      },
      [{ name: '제1종지구단위계획구역', code: 'UQQ310', relation: '접함' }],
    );
    expect(p.area).toBe(2771);
    expect(p.areaSource).toBe('ledger');
    expect(p.zoning).toBe('중심상업지역');
    expect(p.road.access).toBe('광대소각');
    expect(p.landUses).toHaveLength(1);
    expect(p.source?.provider).toBe('vworld');
  });

  it('토지대장 면적이 0 이면 경계로 계산한 면적을 쓴다', () => {
    const p = assembleParcel(feature, { area: 0, jimok: '대' }, []);
    expect(p.areaSource).toBe('geometry');
    expect(p.area).toBeGreaterThan(9_700);
  });
});
