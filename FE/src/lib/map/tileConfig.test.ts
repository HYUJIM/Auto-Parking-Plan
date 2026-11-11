import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchMapConfig, resolveTileChoice, tileSourceFromBe } from './tileConfig';
import { DEFAULT_TILE_SOURCE, TILE_MAX_ZOOM, tileUrl } from './tileSource';
import { tileBadgeDesc, tileBadgeText, tileSourceText } from './tileCopy';
import { content } from '@/lib/content';

/** BE 의 GET /v1/map/config 응답 (브이월드 키가 있을 때) — BE MapController 와 같은 모양 */
const BE_VWORLD = {
  tiles: {
    provider: 'vworld',
    normal: '/v1/map/tiles/base/{z}/{x}/{y}',
    satellite: '/v1/map/tiles/satellite/{z}/{x}/{y}',
    min_zoom: 6,
    max_zoom: 19,
  },
};

describe('tileSourceFromBe', () => {
  it('BE 가 준 경로 템플릿 앞에 API_BASE 를 붙인다 (비어 있으면 같은 origin 의 상대 경로)', () => {
    expect(tileSourceFromBe(BE_VWORLD, '')).toEqual({
      normal: '/v1/map/tiles/base/{z}/{x}/{y}',
      satellite: '/v1/map/tiles/satellite/{z}/{x}/{y}',
      minZoom: 6,
      maxZoom: 19,
    });
    const direct = tileSourceFromBe(BE_VWORLD, 'https://api.example.com');
    expect(direct?.normal).toBe('https://api.example.com/v1/map/tiles/base/{z}/{x}/{y}');
    expect(direct?.satellite).toBe('https://api.example.com/v1/map/tiles/satellite/{z}/{x}/{y}');
    // BE 경로는 z/x/y 순서
    expect(tileUrl(direct?.satellite ?? '', 19, 449482, 203286)).toBe('https://api.example.com/v1/map/tiles/satellite/19/449482/203286');
  });

  it('tiles 가 null 이거나 응답 모양이 다르면 null — 기본 타일로 대체', () => {
    expect(tileSourceFromBe({ tiles: null }, '')).toBeNull();
    expect(tileSourceFromBe({}, '')).toBeNull();
    expect(tileSourceFromBe(null, '')).toBeNull();
    expect(tileSourceFromBe('<html>', '')).toBeNull();
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, provider: 'other' } }, '')).toBeNull();
    expect(tileSourceFromBe({ tiles: { provider: 'vworld' } }, '')).toBeNull();
  });

  it('다른 호스트 주소나 자리표시자가 빠진 템플릿은 쓰지 않는다', () => {
    for (const normal of [
      'https://evil.example/{z}/{x}/{y}',
      '//evil.example/{z}/{x}/{y}',
      '/v1/map/tiles/base/{z}/{x}',
      'v1/{z}/{x}/{y}',
      42,
    ]) {
      expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, normal } }, '')).toBeNull();
    }
  });

  it('위성 템플릿이 없거나 잘못됐으면 일반 타일을 그대로 쓴다', () => {
    const source = tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, satellite: 'https://evil.example/{z}/{x}/{y}' } }, '');
    expect(source?.satellite).toBe(source?.normal);
  });

  it('max_zoom 을 따르고, 없거나 이상하면 표준 최대 줌', () => {
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, max_zoom: 18 } }, '')?.maxZoom).toBe(18);
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, max_zoom: undefined } }, '')?.maxZoom).toBe(TILE_MAX_ZOOM);
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, max_zoom: '19' } }, '')?.maxZoom).toBe(TILE_MAX_ZOOM);
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, max_zoom: 18.5 } }, '')?.maxZoom).toBe(TILE_MAX_ZOOM);
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, min_zoom: -1 } }, '')?.minZoom).toBeUndefined();
    expect(tileSourceFromBe({ tiles: { ...BE_VWORLD.tiles, min_zoom: 20, max_zoom: 19 } }, '')).toBeNull();
  });
});

describe('resolveTileChoice — .env → BE → 기본 타일', () => {
  const deps = (fetchConfig: () => Promise<unknown>) => ({ fetchConfig, apiBase: '' });

  it('.env 에 템플릿이 있으면 BE 에 묻지 않고 그대로 쓴다', async () => {
    const fetchConfig = vi.fn(async () => BE_VWORLD);
    const custom = 'https://tiles.example/{z}/{x}/{y}.png';
    expect(await resolveTileChoice({ VITE_MAP_TILE_URL: custom }, deps(fetchConfig))).toEqual({
      provider: 'custom',
      source: { normal: custom, satellite: DEFAULT_TILE_SOURCE.satellite },
    });
    expect(await resolveTileChoice({ VITE_MAP_TILE_URL: 'off' }, deps(fetchConfig))).toEqual({ provider: 'none', source: null });
    expect(fetchConfig).not.toHaveBeenCalled();
  });

  it('.env 가 비어 있으면 BE 에 한 번 묻고, 브이월드 타일을 준다면 그것을 쓴다', async () => {
    const fetchConfig = vi.fn(async () => BE_VWORLD);
    const choice = await resolveTileChoice({}, { fetchConfig, apiBase: 'https://api.example.com' });
    expect(choice.provider).toBe('vworld');
    expect(choice.source?.normal).toBe('https://api.example.com/v1/map/tiles/base/{z}/{x}/{y}');
    expect(choice.source?.maxZoom).toBe(19);
    expect(fetchConfig).toHaveBeenCalledTimes(1);
  });

  it('BE 가 tiles: null 을 주면 기본 타일(OSM · Esri)', async () => {
    expect(
      await resolveTileChoice(
        {},
        deps(async () => ({ tiles: null })),
      ),
    ).toEqual({ provider: 'default', source: DEFAULT_TILE_SOURCE });
  });

  it('BE 요청이 실패해도 기본 타일로 대체한다 (던지지 않음)', async () => {
    const failing = async () => {
      throw new Error('network');
    };
    expect(await resolveTileChoice({}, deps(failing))).toEqual({ provider: 'default', source: DEFAULT_TILE_SOURCE });
    expect(
      await resolveTileChoice(
        {},
        deps(async () => null),
      ),
    ).toEqual({ provider: 'default', source: DEFAULT_TILE_SOURCE });
  });
});

describe('fetchMapConfig', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('GET /v1/map/config 를 부르고 본문 JSON 을 돌려준다', async () => {
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify(BE_VWORLD), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);
    expect(await fetchMapConfig()).toEqual(BE_VWORLD);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new URL(url).pathname).toBe('/v1/map/config');
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('2xx 가 아니면 던진다 (예: 이전 버전 BE 의 404)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"error":{"code":"NOT_FOUND"}}', { status: 404 })),
    );
    await expect(fetchMapConfig()).rejects.toThrow();
  });

  it('제한 시간 안에 응답이 없으면 요청을 끊는다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
          }),
      ),
    );
    const pending = fetchMapConfig(500);
    const settled = expect(pending).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(600);
    await settled;
  });
});

describe('배지·출처 문구는 실제 타일 공급자를 따른다', () => {
  it('출처', () => {
    expect(tileSourceText('vworld')).toBe(content.map.sourceVworld);
    expect(tileSourceText('vworld')).toContain('브이월드');
    expect(tileSourceText('vworld')).not.toContain('OpenStreetMap');
    expect(tileSourceText('default')).toContain('OpenStreetMap');
    expect(tileSourceText('default')).toContain('Esri');
    expect(tileSourceText('custom')).not.toContain('OpenStreetMap');
    expect(tileSourceText('none')).toBe(content.map.sourceMock);
  });

  it('배지와 설명', () => {
    expect(tileBadgeText('none')).toBe(content.map.previewBadge);
    for (const p of ['vworld', 'default', 'custom'] as const) expect(tileBadgeText(p)).toBe(content.map.tilesBadge);
    expect(tileBadgeDesc('vworld')).toContain('브이월드');
    expect(tileBadgeDesc('default')).toContain('OpenStreetMap');
    expect(tileBadgeDesc('none')).toBe(content.map.previewDesc);
  });
});
