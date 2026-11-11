import type { LatLng, LayoutLayers } from '@/lib/api/types';

/**
 * MapAdapter — 지도 공급자(네이버·카카오·미리보기)를 감싸는 단 하나의 인터페이스.
 * 화면 코드는 이 인터페이스만 쓰므로, 지도 회사를 바꿔도 lib/map 아래 파일 하나만 교체하면 됩니다.
 */
export type { LatLng, LayoutLayers };

export type MapType = 'normal' | 'satellite';

export type MapKind = 'naver' | 'mock';

export interface MapMarker {
  id: string;
  position: LatLng;
  /** 'parcel' = 좌표만 있는 필지의 점(point 장면), 'preview' = 검색 결과 미리보기 핀 */
  variant: 'parcel' | 'preview';
  title?: string;
}

export interface SiteOverlay {
  /** 대지 경계 */
  polygon: LatLng[];
  /** 대지면적 말풍선 문구 */
  areaLabel?: string;
}

export interface FitPadding {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

export interface MapMountOptions {
  center: LatLng;
  zoom: number;
  mapType: MapType;
  cadastral: boolean;
}

export interface MapEvents {
  mousemove: LatLng;
  click: LatLng;
  zoom: number;
  /** mount 이후에 늦게 온 인증 실패 (네이버) — 호출 쪽이 미리보기 지도로 대체 */
  authfailure: string;
}

export interface MapAdapter {
  readonly kind: MapKind;

  /** 컨테이너에 지도를 붙입니다. 인증 실패 등은 reject — 호출 쪽에서 미리보기 지도로 대체 */
  mount(el: HTMLElement, opts: MapMountOptions): Promise<void>;
  destroy(): void;
  resize(): void;

  getCenter(): LatLng;
  setCenter(center: LatLng): void;
  getZoom(): number;
  setZoom(zoom: number): void;
  fitBounds(points: LatLng[], padding?: Partial<FitPadding>): void;

  setMapType(type: MapType): void;
  setCadastral(on: boolean): void;

  setMarkers(markers: MapMarker[], onClick?: (marker: MapMarker) => void): void;
  setSite(site: SiteOverlay | null): void;
  /**
   * 주차장 배치안 레이어 (GeoJSON FeatureCollection, 각 feature 의 properties.layer 로 종류 구분).
   * null 이면 지웁니다. 대지 경계 위에, 마커 아래에 그립니다.
   */
  setLayout(layers: LayoutLayers | null): void;

  /** 현재 중심 위도·줌에서 1px 이 몇 m 인지 (축척 바) */
  getMetersPerPixel(): number;

  on<K extends keyof MapEvents>(event: K, handler: (payload: MapEvents[K]) => void): () => void;
}
