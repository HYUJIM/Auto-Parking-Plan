import type { FitPadding, LatLng, MapAdapter, MapEvents, MapMarker, MapMountOptions, MapType, SiteOverlay } from './types';
import { centroid, metersPerPixel } from './geo';
import { getMapStyle } from './mapStyle';

/**
 * 네이버 지도 (NCP Maps · Web Dynamic Map v3) 어댑터.
 *
 * - 스크립트: https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=<Client ID>
 * - 인증 실패(키 오류·미등록 URL)는 window.navermap_authFailure 로 옵니다 → mount 가 reject 되고
 *   MapProvider 가 미리보기 지도로 대체합니다. mount 이후에 늦게 오는 실패는 'authfailure' 이벤트로 알립니다.
 * - 컨테이너(el)는 호출 쪽이 소유합니다. destroy() 는 이 어댑터가 만든 것만 정리합니다.
 * - 네이버 SDK 는 타입 선언을 제공하지 않으므로 여기서만 any 를 허용합니다.
 */

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window {
    naver?: any;
    navermap_authFailure?: () => void;
  }
}

type Handler<K extends keyof MapEvents> = (payload: MapEvents[K]) => void;

const DEFAULT_PADDING: FitPadding = { top: 48, right: 48, bottom: 48, left: 48 };

let scriptPromise: Promise<void> | null = null;
let authFailed = false;
const authFailureHandlers = new Set<() => void>();

function loadNaverMaps(keyId: string): Promise<void> {
  if (window.naver?.maps) return authFailed ? Promise.reject(new Error('NAVER_AUTH_FAILURE')) : Promise.resolve();
  if (!scriptPromise) {
    scriptPromise = new Promise<void>((resolve, reject) => {
      window.navermap_authFailure = () => {
        authFailed = true;
        authFailureHandlers.forEach((h) => h());
        reject(new Error('NAVER_AUTH_FAILURE'));
      };
      const script = document.createElement('script');
      script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(keyId)}`;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        scriptPromise = null;
        reject(new Error('NAVER_SCRIPT_LOAD_FAILED'));
      };
      document.head.appendChild(script);
    });
  }
  return scriptPromise;
}

export class NaverMapAdapter implements MapAdapter {
  readonly kind = 'naver' as const;

  private map: any = null;
  private cadastralLayer: any = null;
  private markerObjects: any[] = [];
  private markerListeners: any[] = [];
  private sitePolygon: any = null;
  private roadLine: any = null;
  private areaLabel: any = null;
  private sdkListeners: any[] = [];
  private lateAuthHandler: (() => void) | null = null;

  private listeners = {
    mousemove: new Set<Handler<'mousemove'>>(),
    click: new Set<Handler<'click'>>(),
    zoom: new Set<Handler<'zoom'>>(),
    authfailure: new Set<Handler<'authfailure'>>(),
  };

  constructor(private readonly keyId: string) {}

  async mount(el: HTMLElement, opts: MapMountOptions): Promise<void> {
    await loadNaverMaps(this.keyId);
    const { maps } = window.naver;

    this.map = new maps.Map(el, {
      center: new maps.LatLng(opts.center.lat, opts.center.lng),
      zoom: opts.zoom,
      minZoom: 6,
      maxZoom: 21,
      mapTypeId: this.mapTypeId(opts.mapType),
      zoomControl: false,
      scaleControl: false,
      mapDataControl: false,
      logoControlOptions: { position: maps.Position.BOTTOM_LEFT },
    });

    this.cadastralLayer = new maps.CadastralLayer();
    if (opts.cadastral) this.cadastralLayer.setMap(this.map);

    // 인증 실패는 지도 생성 직후 비동기로 올 수 있어 잠시 기다립니다.
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const finish = (err?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        authFailureHandlers.delete(fail);
        if (err) reject(err);
        else resolve();
      };
      const fail = () => finish(new Error('NAVER_AUTH_FAILURE'));
      authFailureHandlers.add(fail);
      const timer = setTimeout(() => finish(), 1500);
      maps.Event.once(this.map, 'init', () => setTimeout(() => finish(), 300));
    });

    // 느린 네트워크에서 기다린 뒤에 오는 인증 실패도 놓치지 않도록 계속 듣습니다.
    this.lateAuthHandler = () => this.emit('authfailure', 'NAVER_AUTH_FAILURE');
    authFailureHandlers.add(this.lateAuthHandler);

    this.sdkListeners.push(
      maps.Event.addListener(this.map, 'mousemove', (e: any) => {
        if (!e?.coord) return;
        this.emit('mousemove', { lat: e.coord.lat(), lng: e.coord.lng() });
      }),
      maps.Event.addListener(this.map, 'click', (e: any) => {
        if (!e?.coord) return;
        this.emit('click', { lat: e.coord.lat(), lng: e.coord.lng() });
      }),
      maps.Event.addListener(this.map, 'zoom_changed', () => this.emit('zoom', this.map.getZoom())),
    );
  }

  destroy(): void {
    const maps = window.naver?.maps;
    if (this.lateAuthHandler) authFailureHandlers.delete(this.lateAuthHandler);
    this.lateAuthHandler = null;
    if (maps) this.sdkListeners.forEach((l) => maps.Event.removeListener(l));
    this.sdkListeners = [];
    this.clearMarkers();
    this.clearSite();
    this.cadastralLayer?.setMap(null);
    this.map?.destroy?.();
    this.map = null;
    this.listeners.mousemove.clear();
    this.listeners.click.clear();
    this.listeners.zoom.clear();
    this.listeners.authfailure.clear();
  }

  resize(): void {
    const maps = window.naver?.maps;
    if (maps && this.map) maps.Event.trigger(this.map, 'resize');
  }

  getCenter(): LatLng {
    const c = this.map.getCenter();
    return { lat: c.lat(), lng: c.lng() };
  }

  setCenter(center: LatLng): void {
    const { maps } = window.naver;
    this.map.setCenter(new maps.LatLng(center.lat, center.lng));
  }

  getZoom(): number {
    return this.map.getZoom();
  }

  setZoom(zoom: number): void {
    this.map.setZoom(Math.round(zoom), true);
  }

  fitBounds(points: LatLng[], padding?: Partial<FitPadding>): void {
    if (!points.length) return;
    const { maps } = window.naver;
    const bounds = new maps.LatLngBounds();
    points.forEach((p) => bounds.extend(new maps.LatLng(p.lat, p.lng)));
    this.map.fitBounds(bounds, { ...DEFAULT_PADDING, ...padding });
  }

  setMapType(type: MapType): void {
    this.map.setMapTypeId(this.mapTypeId(type));
  }

  setCadastral(on: boolean): void {
    this.cadastralLayer?.setMap(on ? this.map : null);
  }

  setMarkers(markers: MapMarker[], onClick?: (marker: MapMarker) => void): void {
    const { maps } = window.naver;
    this.clearMarkers();
    this.markerObjects = markers.map((m) => {
      const marker = new maps.Marker({
        position: new maps.LatLng(m.position.lat, m.position.lng),
        map: this.map,
        title: m.title ?? '',
        zIndex: m.variant === 'preview' ? 20 : 10,
        icon: {
          content: `<div class="map-marker ${m.variant}" title="${escapeHtml(m.title ?? '')}"></div>`,
          anchor: new maps.Point(8, 8),
        },
      });
      if (onClick) this.markerListeners.push(maps.Event.addListener(marker, 'click', () => onClick(m)));
      return marker;
    });
  }

  setSite(site: SiteOverlay | null): void {
    const { maps } = window.naver;
    this.clearSite();
    if (!site) return;
    const style = getMapStyle();

    if (site.road && site.road.length > 1) {
      this.roadLine = new maps.Polyline({
        map: this.map,
        path: site.road.map((p) => new maps.LatLng(p.lat, p.lng)),
        strokeColor: style.roadStroke,
        strokeWeight: style.roadStrokeWidth,
        strokeOpacity: 0.9,
        strokeLineCap: 'round',
        clickable: false,
      });
    }

    this.sitePolygon = new maps.Polygon({
      map: this.map,
      paths: [site.polygon.map((p) => new maps.LatLng(p.lat, p.lng))],
      strokeColor: style.siteStroke,
      strokeWeight: style.siteStrokeWidth,
      strokeOpacity: 1,
      fillColor: style.siteFill,
      fillOpacity: style.siteFillOpacity,
      clickable: false,
    });

    if (site.areaLabel) {
      const top = site.polygon.reduce((best, p) => (p.lat > best.lat ? p : best), site.polygon[0]);
      const c = centroid(site.polygon);
      this.areaLabel = new maps.Marker({
        position: new maps.LatLng(top.lat, c.lng),
        map: this.map,
        clickable: false,
        zIndex: 30,
        icon: {
          content: `<div class="map-area-label">${escapeHtml(site.areaLabel)}</div>`,
          anchor: new maps.Point(0, 0),
        },
      });
    }
  }

  getMetersPerPixel(): number {
    return metersPerPixel(this.getCenter().lat, this.getZoom());
  }

  on<K extends keyof MapEvents>(event: K, handler: Handler<K>): () => void {
    const set = this.listeners[event] as Set<Handler<K>>;
    set.add(handler);
    return () => set.delete(handler);
  }

  // ── 내부 ─────────────────────────────────────────────────────────

  private emit<K extends keyof MapEvents>(event: K, payload: MapEvents[K]): void {
    (this.listeners[event] as Set<Handler<K>>).forEach((h) => h(payload));
  }

  private mapTypeId(type: MapType): any {
    const { maps } = window.naver;
    // 위성은 도로명이 함께 보이는 HYBRID 를 씁니다 (순수 위성은 maps.MapTypeId.SATELLITE).
    return type === 'satellite' ? maps.MapTypeId.HYBRID : maps.MapTypeId.NORMAL;
  }

  private clearMarkers(): void {
    const maps = window.naver?.maps;
    if (maps) this.markerListeners.forEach((l) => maps.Event.removeListener(l));
    this.markerListeners = [];
    this.markerObjects.forEach((m) => m.setMap(null));
    this.markerObjects = [];
  }

  private clearSite(): void {
    this.sitePolygon?.setMap(null);
    this.roadLine?.setMap(null);
    this.areaLabel?.setMap(null);
    this.sitePolygon = null;
    this.roadLine = null;
    this.areaLabel = null;
  }
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string);
}
