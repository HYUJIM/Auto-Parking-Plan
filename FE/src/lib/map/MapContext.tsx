import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import type { FitPadding, LatLng, MapAdapter, MapMarker, MapType } from './types';
import type { AddressHit, Parcel } from '@/lib/api/types';
import { describeApiError } from '@/lib/api/errors';
import { createMapAdapter } from './createMapAdapter';
import { MockMapAdapter } from './mockMap';
import { SUSEONG_BOUNDS, SUSEONG_CENTER, metersPerPixel, project, unproject } from './geo';
import { MAP_CONTROLS_ATTR, MAP_PANEL_ATTR, queryAnchor } from './layoutAnchors';
import { pickParcelAt as pickParcel } from './pickParcel';
import { suseongParcels } from '@/lib/api/mock';
import { rememberHit } from '@/lib/api/hitCache';
import { content, fmt } from '@/lib/content';
import { formatArea } from '@/lib/format';
import { useToast } from '@/components/Toast';

/**
 * 지도 상태 한 곳 — 좌측 패널(초기화면·이용화면)이 바뀌어도 지도는 그대로 이어집니다.
 * 패널은 setScene() 으로 "무엇을 보여줄지"만 말하고, 지도 공급자는 MapAdapter 뒤에 숨어 있습니다.
 */
export type MapScene =
  | { kind: 'overview' }
  | { kind: 'site'; parcel: Parcel }
  /** 주소·좌표만 확정된 필지 (경계 데이터 없음) — 점 하나로 표시 */
  | { kind: 'point'; pnu: string; center: LatLng; title: string };

/** loading → naver | mock(키 없음) | mock-fallback(네이버 실패) */
export type MapStatus = 'loading' | 'naver' | 'mock' | 'mock-fallback';
/** mock-fallback 의 이유: 인증 실패 / 스크립트 로드 실패 */
export type FallbackReason = 'auth' | 'script' | null;

interface MapContextValue {
  status: MapStatus;
  fallbackReason: FallbackReason;
  mapType: MapType;
  cadastral: boolean;
  zoom: number;
  metersPerPixel: number;
  scene: MapScene;
  attach: (el: HTMLElement) => () => void;
  setMapType: (type: MapType) => void;
  toggleCadastral: () => void;
  zoomIn: () => void;
  zoomOut: () => void;
  /** 화면 픽셀 단위로 지도 이동 (키보드 조작) */
  panBy: (dx: number, dy: number) => void;
  fitScene: () => void;
  setScene: (scene: MapScene) => void;
  setPreviewPins: (hits: AddressHit[]) => void;
  locate: () => Promise<void>;
}

const MapContext = createContext<MapContextValue | null>(null);
/** 마우스 좌표는 자주 바뀌므로 별도 컨텍스트 — 하단 바만 다시 그립니다 */
const MapCursorContext = createContext<LatLng | null>(null);

const INITIAL_ZOOM = 13;

/**
 * fitBounds 여백 — 실제 DOM(모바일 하단 시트 높이, 우측 컨트롤 폭)을 재서 계산합니다.
 * 기준점 속성 이름은 layoutAnchors.ts 한 곳에서 관리합니다 (MapShell 의 패널, MapControls 의 컨트롤).
 */
function getFitPadding(): FitPadding {
  const mobile = window.matchMedia('(max-width: 900px)').matches;
  const controls = queryAnchor(MAP_CONTROLS_ATTR);
  const controlsWidth = controls ? controls.offsetWidth + 24 : 0;
  if (mobile) {
    const sheet = queryAnchor(MAP_PANEL_ATTR);
    const sheetHeight = sheet ? sheet.offsetHeight : Math.round(window.innerHeight * 0.5);
    return { top: 72, right: Math.max(24, controlsWidth), bottom: sheetHeight + 16, left: 24 };
  }
  return { top: 88, right: Math.max(96, controlsWidth + 24), bottom: 96, left: 96 };
}

function sceneKeyOf(scene: MapScene): string {
  if (scene.kind === 'site') return `site:${scene.parcel.pnu}`;
  if (scene.kind === 'point') return `point:${scene.pnu}`;
  return 'overview';
}

function siteBounds(parcel: Parcel): LatLng[] {
  return [...parcel.polygon, ...(parcel.road.path ?? [])];
}

/** 점 하나를 보여줄 때 쓰는 작은 범위 (약 100m × 110m) */
function pointBounds(center: LatLng): LatLng[] {
  return [
    { lat: center.lat - 0.00045, lng: center.lng - 0.00055 },
    { lat: center.lat + 0.00045, lng: center.lng + 0.00055 },
  ];
}

function sceneBounds(scene: MapScene): LatLng[] {
  if (scene.kind === 'site') return siteBounds(scene.parcel);
  if (scene.kind === 'point') return pointBounds(scene.center);
  return SUSEONG_BOUNDS;
}

export function MapProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const toast = useToast();
  // navigate 는 경로가 바뀔 때마다 새 함수가 되므로 ref 로 들고 있어야 지도가 다시 마운트되지 않습니다.
  const navigateRef = useRef(navigate);
  useEffect(() => {
    navigateRef.current = navigate;
  }, [navigate]);

  const adapterRef = useRef<MapAdapter | null>(null);
  const sceneRef = useRef<MapScene>({ kind: 'overview' });
  const previewRef = useRef<MapMarker[]>([]);
  /** 미리보기 핀 id(PNU) → 검색 결과. 핀을 클릭해도 주소·좌표를 세션에 남기기 위해 */
  const previewHitsRef = useRef<Map<string, AddressHit>>(new Map());
  /** 진행 중인 지도 클릭 조회를 무효화하기 위한 일련번호 — 이동·장면 변경 때마다 증가 */
  const clickSeq = useRef(0);

  const [status, setStatus] = useState<MapStatus>('loading');
  const [fallbackReason, setFallbackReason] = useState<FallbackReason>(null);
  const [mapType, setMapTypeState] = useState<MapType>('normal');
  const [cadastral, setCadastralState] = useState(false);
  const [zoom, setZoomState] = useState(INITIAL_ZOOM);
  const [mpp, setMpp] = useState(() => metersPerPixel(SUSEONG_CENTER.lat, INITIAL_ZOOM));
  const [cursor, setCursor] = useState<LatLng | null>(null);
  const [scene, setSceneState] = useState<MapScene>({ kind: 'overview' });

  const goToParcel = useCallback((pnu: string) => {
    clickSeq.current++;
    const current = sceneRef.current;
    if (current.kind === 'site' && current.parcel.pnu === pnu) return;
    previewRef.current = [];
    previewHitsRef.current.clear();
    navigateRef.current(`/site?pnu=${encodeURIComponent(pnu)}`);
  }, []);

  const handleMarkerClick = useCallback(
    (m: MapMarker) => {
      if (m.variant === 'preview') {
        const hit = previewHitsRef.current.get(m.id);
        if (hit) rememberHit(hit);
      }
      goToParcel(m.id);
    },
    [goToParcel],
  );

  const buildMarkers = useCallback((): MapMarker[] => {
    const scene = sceneRef.current;
    const parcelMarkers: MapMarker[] =
      scene.kind === 'overview'
        ? suseongParcels.map((p) => ({
            id: p.pnu,
            position: p.center,
            variant: 'parcel',
            title: `${content.map.markerParcel} · ${p.address.road ?? p.address.jibun}`,
          }))
        : scene.kind === 'point'
          ? [{ id: scene.pnu, position: scene.center, variant: 'parcel', title: scene.title }]
          : [];
    return [...parcelMarkers, ...previewRef.current];
  }, []);

  const syncZoom = useCallback((adapter: MapAdapter) => {
    setZoomState(adapter.getZoom());
    setMpp(adapter.getMetersPerPixel());
  }, []);

  const applyScene = useCallback(
    (adapter: MapAdapter, next: MapScene) => {
      if (next.kind === 'overview') {
        adapter.setSite(null);
        adapter.setMapType('normal');
        adapter.setCadastral(false);
        setMapTypeState('normal');
        setCadastralState(false);
        adapter.setMarkers(buildMarkers(), handleMarkerClick);
        adapter.fitBounds(SUSEONG_BOUNDS, getFitPadding());
      } else if (next.kind === 'point') {
        adapter.setSite(null);
        adapter.setMarkers(buildMarkers(), handleMarkerClick);
        adapter.setMapType('satellite');
        adapter.setCadastral(true);
        setMapTypeState('satellite');
        setCadastralState(true);
        adapter.fitBounds(pointBounds(next.center), getFitPadding());
      } else {
        const p = next.parcel;
        adapter.setMarkers(buildMarkers(), handleMarkerClick);
        adapter.setSite({
          polygon: p.polygon,
          road: p.road.path,
          areaLabel: fmt(content.map.areaLabel, { area: formatArea(p.area) }),
        });
        adapter.setMapType('satellite');
        adapter.setCadastral(true);
        setMapTypeState('satellite');
        setCadastralState(true);
        adapter.fitBounds(siteBounds(p), getFitPadding());
      }
      syncZoom(adapter);
    },
    [buildMarkers, handleMarkerClick, syncZoom],
  );

  /**
   * 지도 클릭 → 필지 선택 (검색 결과 0건일 때의 대안). 규칙은 pickParcel.ts, 여기서는 결과를 라우팅·토스트로 옮기기만 합니다.
   */
  const pickParcelAt = useCallback(
    async (point: LatLng, adapter: MapAdapter) => {
      const seq = ++clickSeq.current;
      const result = await pickParcel(point, adapter.getMetersPerPixel());
      if (seq !== clickSeq.current) return; // 그 사이 다른 곳을 클릭함
      switch (result.kind) {
        case 'found':
          goToParcel(result.pnu);
          return;
        case 'none':
          toast.show(sceneRef.current.kind === 'overview' ? content.map.clickNoParcel : content.map.clickFailed);
          return;
        case 'not-ready':
          toast.show(content.map.clickNotReady);
          return;
        case 'failed': {
          const described = describeApiError(result.error);
          toast.show(described.retryable && described.desc ? described.desc : content.map.clickFailed);
        }
      }
    },
    [goToParcel, toast],
  );

  const attach = useCallback(
    (el: HTMLElement) => {
      let cancelled = false;
      const unsubs: Array<() => void> = [];
      // 마운트 시도마다 자기 호스트를 둡니다 — StrictMode 의 이중 마운트에서 서로의 DOM 을 지우지 않도록
      const host = document.createElement('div');
      host.style.cssText = 'position:absolute;inset:0;';
      el.appendChild(host);
      const opts = { center: SUSEONG_CENTER, zoom: INITIAL_ZOOM, mapType: 'normal' as MapType, cadastral: false };

      const unwire = () => {
        unsubs.splice(0).forEach((u) => u());
      };

      const mountMock = async (): Promise<MockMapAdapter | null> => {
        host.replaceChildren();
        const mock = new MockMapAdapter();
        await mock.mount(host, opts);
        if (cancelled) {
          mock.destroy();
          return null;
        }
        return mock;
      };

      const wire = (adapter: MapAdapter) => {
        let frame = 0;
        let last: LatLng | null = null;
        unsubs.push(
          adapter.on('mousemove', (p) => {
            last = p;
            if (!frame) {
              frame = requestAnimationFrame(() => {
                frame = 0;
                setCursor(last);
              });
            }
          }),
          adapter.on('zoom', () => syncZoom(adapter)),
          adapter.on('click', (p) => void pickParcelAt(p, adapter)),
          adapter.on('authfailure', () => void fallbackToMock('auth')),
        );
        const ro = new ResizeObserver(() => adapter.resize());
        ro.observe(el);
        unsubs.push(
          () => ro.disconnect(),
          () => cancelAnimationFrame(frame),
        );
      };

      const fallbackToMock = async (reason: 'auth' | 'script') => {
        unwire();
        adapterRef.current?.destroy();
        adapterRef.current = null;
        const mock = await mountMock();
        if (!mock) return;
        adapterRef.current = mock;
        setStatus('mock-fallback');
        setFallbackReason(reason);
        wire(mock);
        applyScene(mock, sceneRef.current);
      };

      (async () => {
        let adapter: MapAdapter = createMapAdapter();
        try {
          await adapter.mount(host, opts);
          if (cancelled) {
            adapter.destroy();
            return;
          }
          setStatus(adapter.kind);
          setFallbackReason(null);
        } catch (err) {
          console.warn('[map] 네이버 지도를 열지 못해 미리보기 지도로 대체합니다.', err);
          adapter.destroy();
          if (cancelled) return;
          const mock = await mountMock();
          if (!mock) return;
          adapter = mock;
          setStatus('mock-fallback');
          setFallbackReason(err instanceof Error && err.message === 'NAVER_AUTH_FAILURE' ? 'auth' : 'script');
        }
        adapterRef.current = adapter;
        wire(adapter);
        applyScene(adapter, sceneRef.current);
      })();

      return () => {
        cancelled = true;
        unwire();
        adapterRef.current?.destroy();
        adapterRef.current = null;
        host.remove();
        setStatus('loading');
      };
    },
    [applyScene, pickParcelAt, syncZoom],
  );

  // 장면이 바뀌면(초기화면 ↔ 필지) 지도에 반영. 진행 중이던 클릭 조회는 버립니다.
  const sceneKey = sceneKeyOf(scene);
  useEffect(() => {
    sceneRef.current = scene;
    clickSeq.current++;
    const adapter = adapterRef.current;
    if (adapter) applyScene(adapter, scene);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sceneKey]);

  const setScene = useCallback((next: MapScene) => {
    setSceneState((prev) => (sceneKeyOf(prev) === sceneKeyOf(next) ? prev : next));
  }, []);

  const setPreviewPins = useCallback(
    (hits: AddressHit[]) => {
      const withCenter = hits.filter((h): h is AddressHit & { center: LatLng } => h.center !== null);
      previewHitsRef.current = new Map(withCenter.map((h) => [h.pnu, h]));
      previewRef.current = withCenter.map((h) => ({
        id: h.pnu,
        position: h.center,
        variant: 'preview' as const,
        title: h.roadAddress,
      }));
      adapterRef.current?.setMarkers(buildMarkers(), handleMarkerClick);
    },
    [buildMarkers, handleMarkerClick],
  );

  const setMapType = useCallback((type: MapType) => {
    adapterRef.current?.setMapType(type);
    setMapTypeState(type);
  }, []);

  const toggleCadastral = useCallback(() => {
    setCadastralState((on) => {
      adapterRef.current?.setCadastral(!on);
      return !on;
    });
  }, []);

  const zoomIn = useCallback(() => {
    const a = adapterRef.current;
    if (a) a.setZoom(a.getZoom() + 1);
  }, []);

  const zoomOut = useCallback(() => {
    const a = adapterRef.current;
    if (a) a.setZoom(a.getZoom() - 1);
  }, []);

  const panBy = useCallback((dx: number, dy: number) => {
    const a = adapterRef.current;
    if (!a) return;
    const z = a.getZoom();
    const c = project(a.getCenter(), z);
    a.setCenter(unproject(c.x + dx, c.y + dy, z));
  }, []);

  const fitScene = useCallback(() => {
    const a = adapterRef.current;
    if (!a) return;
    a.fitBounds(sceneBounds(sceneRef.current), getFitPadding());
    syncZoom(a);
  }, [syncZoom]);

  const locate = useCallback(
    () =>
      new Promise<void>((resolve, reject) => {
        if (!('geolocation' in navigator)) {
          reject(new Error(content.map.locateUnsupported));
          return;
        }
        if (!adapterRef.current) {
          reject(new Error(content.map.loading));
          return;
        }
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            const a = adapterRef.current;
            if (a) {
              a.setCenter({ lat: pos.coords.latitude, lng: pos.coords.longitude });
              a.setZoom(17);
              syncZoom(a);
            }
            resolve();
          },
          (err) => reject(new Error(err.code === err.PERMISSION_DENIED ? content.map.locateDenied : content.map.locateFailed)),
          { timeout: 8000, maximumAge: 60000 },
        );
      }),
    [syncZoom],
  );

  const value = useMemo<MapContextValue>(
    () => ({
      status,
      fallbackReason,
      mapType,
      cadastral,
      zoom,
      metersPerPixel: mpp,
      scene,
      attach,
      setMapType,
      toggleCadastral,
      zoomIn,
      zoomOut,
      panBy,
      fitScene,
      setScene,
      setPreviewPins,
      locate,
    }),
    [
      status,
      fallbackReason,
      mapType,
      cadastral,
      zoom,
      mpp,
      scene,
      attach,
      setMapType,
      toggleCadastral,
      zoomIn,
      zoomOut,
      panBy,
      fitScene,
      setScene,
      setPreviewPins,
      locate,
    ],
  );

  return (
    <MapContext.Provider value={value}>
      <MapCursorContext.Provider value={cursor}>{children}</MapCursorContext.Provider>
    </MapContext.Provider>
  );
}

export function useMap(): MapContextValue {
  const ctx = useContext(MapContext);
  if (!ctx) throw new Error('useMap 은 MapProvider 안에서만 쓸 수 있습니다.');
  return ctx;
}

export function useMapCursor(): LatLng | null {
  return useContext(MapCursorContext);
}
