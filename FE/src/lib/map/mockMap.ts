import type { FitPadding, LatLng, LayoutLayers, MapAdapter, MapEvents, MapMarker, MapMountOptions, MapType, SiteOverlay } from './types';
import type { GeoJsonPosition } from '@/lib/api/types';
import { centroid, fitZoom, metersPerPixel, project, unproject } from './geo';
import { TileLayer } from './tileLayer';
import { loadTileChoice } from './tileConfig';
import type { TileChoice, TileProvider } from './tileSource';

/**
 * 미리보기 지도 — 네이버 지도 Client ID 가 없거나 인증에 실패했을 때 쓰는 자체 지도.
 * 아래에는 실제 지도 타일을 깔고, 위에 SVG 로 필지·마커·배치안을 그립니다. 타일은 BE 가 대신 받아 주는 브이월드 타일이 기본이고
 * (필지 경계와 같은 기준이라 정확히 겹침), BE 에 브이월드 키가 없으면 OpenStreetMap·Esri 로 대체합니다 — 선택 규칙은 tileConfig.ts.
 * 웹 메르카토르 좌표계를 그대로 쓰므로 필지 경계·마커 위치·축척은 네이버 지도와 같습니다.
 * 타일을 끄면(VITE_MAP_TILE_URL=off) 배경(거리·블록)을 도식으로 그립니다. 화면 코드는 이 어댑터와 네이버 어댑터를 구분하지 않습니다.
 */

const SVG_NS = 'http://www.w3.org/2000/svg';
const MIN_ZOOM = 6;
const MAX_ZOOM = 19;
const DEFAULT_PADDING: FitPadding = { top: 48, right: 48, bottom: 48, left: 48 };

type Handler<K extends keyof MapEvents> = (payload: MapEvents[K]) => void;

interface Viewport {
  width: number;
  height: number;
  /** 화면 좌상단의 월드 픽셀 좌표 */
  left: number;
  top: number;
}

export class MockMapAdapter implements MapAdapter {
  readonly kind = 'mock' as const;

  private container: HTMLElement | null = null;
  private root: HTMLDivElement | null = null;
  private tiles: TileLayer | null = null;
  private gridLayer: SVGGElement | null = null;
  private cadastralLayer: SVGGElement | null = null;
  private featureLayer: SVGGElement | null = null;

  private center: LatLng = { lat: 0, lng: 0 };
  private zoom = 13;
  private mapType: MapType = 'normal';
  private cadastral = false;

  private markers: MapMarker[] = [];
  private onMarkerClick?: (marker: MapMarker) => void;
  private site: SiteOverlay | null = null;
  private layout: LayoutLayers | null = null;

  private listeners = {
    mousemove: new Set<Handler<'mousemove'>>(),
    click: new Set<Handler<'click'>>(),
    zoom: new Set<Handler<'zoom'>>(),
    authfailure: new Set<Handler<'authfailure'>>(),
  };

  private resizeObserver: ResizeObserver | null = null;
  private frame = 0;
  private drag: { startX: number; startY: number; lastX: number; lastY: number; moved: boolean; pointerId: number } | null = null;
  private width = 0;
  private height = 0;

  private provider: TileProvider = 'none';

  /** tileChoice 를 주지 않으면 mount 때 정합니다 (.env → BE 의 /v1/map/config → 기본 타일, tileConfig.ts) */
  constructor(private readonly tileChoice?: TileChoice) {}

  /** 지금 깔린 배경 타일의 공급자 (mount 뒤에 정해짐) — 배지·출처 문구용 */
  get tileProvider(): TileProvider {
    return this.provider;
  }

  async mount(el: HTMLElement, opts: MapMountOptions): Promise<void> {
    // loadTileChoice 는 실패해도 기본 타일을 돌려주고, 한 번 정한 값을 계속 씁니다
    const choice = this.tileChoice ?? (await loadTileChoice());
    this.provider = choice.source ? choice.provider : 'none';
    this.container = el;
    this.center = opts.center;
    this.zoom = opts.zoom;
    this.mapType = opts.mapType;
    this.cadastral = opts.cadastral;

    const root = document.createElement('div');
    root.className = 'mock-map';
    root.dataset.type = this.mapType;
    root.dataset.cadastral = String(this.cadastral);
    if (choice.source) {
      this.tiles = new TileLayer(root, choice.source);
      root.dataset.tiles = 'true';
      root.dataset.tileProvider = this.provider;
    }

    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('aria-hidden', 'true');
    svg.innerHTML = `
      <defs>
        <linearGradient id="mm-satellite" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" class="mm-sat-1"/>
          <stop offset="1" class="mm-sat-2"/>
        </linearGradient>
      </defs>
      <rect class="mm-bg" width="100%" height="100%"/>
      <g class="mm-grid"></g>
      <g class="mm-cadastral-layer"></g>
      <g class="mm-features"></g>`;
    root.appendChild(svg);
    el.appendChild(root);

    this.root = root;
    this.gridLayer = svg.querySelector('.mm-grid');
    this.cadastralLayer = svg.querySelector('.mm-cadastral-layer');
    this.featureLayer = svg.querySelector('.mm-features');

    root.addEventListener('pointerdown', this.handlePointerDown);
    root.addEventListener('pointermove', this.handlePointerMove);
    root.addEventListener('pointerup', this.handlePointerUp);
    root.addEventListener('pointercancel', this.handlePointerUp);
    root.addEventListener('pointerleave', this.handlePointerLeave);
    root.addEventListener('wheel', this.handleWheel, { passive: false });
    root.addEventListener('dblclick', this.handleDoubleClick);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(el);
    this.resize();
  }

  destroy(): void {
    cancelAnimationFrame(this.frame);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.tiles?.destroy();
    this.tiles = null;
    if (this.root) {
      this.root.removeEventListener('pointerdown', this.handlePointerDown);
      this.root.removeEventListener('pointermove', this.handlePointerMove);
      this.root.removeEventListener('pointerup', this.handlePointerUp);
      this.root.removeEventListener('pointercancel', this.handlePointerUp);
      this.root.removeEventListener('pointerleave', this.handlePointerLeave);
      this.root.removeEventListener('wheel', this.handleWheel);
      this.root.removeEventListener('dblclick', this.handleDoubleClick);
      this.root.remove();
    }
    this.root = null;
    this.container = null;
    this.listeners.mousemove.clear();
    this.listeners.click.clear();
    this.listeners.zoom.clear();
  }

  resize(): void {
    if (!this.container) return;
    this.width = this.container.clientWidth;
    this.height = this.container.clientHeight;
    this.scheduleRender();
  }

  getCenter(): LatLng {
    return this.center;
  }

  setCenter(center: LatLng): void {
    this.center = center;
    this.scheduleRender();
  }

  getZoom(): number {
    return this.zoom;
  }

  setZoom(zoom: number): void {
    const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(zoom)));
    if (next === this.zoom) return;
    this.zoom = next;
    this.scheduleRender();
    this.emit('zoom', this.zoom);
  }

  fitBounds(points: LatLng[], padding?: Partial<FitPadding>): void {
    if (!points.length) return;
    const pad = { ...DEFAULT_PADDING, ...padding };
    const { center, zoom } = fitZoom(points, this.width || 800, this.height || 600, pad, MIN_ZOOM, MAX_ZOOM);
    this.center = center;
    const changed = zoom !== this.zoom;
    this.zoom = zoom;
    this.scheduleRender();
    if (changed) this.emit('zoom', this.zoom);
  }

  setMapType(type: MapType): void {
    this.mapType = type;
    if (this.root) this.root.dataset.type = type;
    // 타일은 레이어별 템플릿이 달라 다시 그립니다 (도식 배경은 CSS 만 바뀜)
    if (this.tiles) this.scheduleRender();
  }

  setCadastral(on: boolean): void {
    if (on === this.cadastral) return;
    this.cadastral = on;
    if (this.root) this.root.dataset.cadastral = String(on);
    // 꺼져 있던 동안 비워 둔 격자를 다시 그립니다
    this.scheduleRender();
  }

  setMarkers(markers: MapMarker[], onClick?: (marker: MapMarker) => void): void {
    this.markers = markers;
    this.onMarkerClick = onClick;
    this.scheduleRender();
  }

  setSite(site: SiteOverlay | null): void {
    this.site = site;
    this.scheduleRender();
  }

  setLayout(layers: LayoutLayers | null): void {
    this.layout = layers;
    this.scheduleRender();
  }

  getMetersPerPixel(): number {
    return metersPerPixel(this.center.lat, this.zoom);
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

  private viewport(): Viewport {
    const c = project(this.center, this.zoom);
    return {
      width: this.width,
      height: this.height,
      left: c.x - this.width / 2,
      top: c.y - this.height / 2,
    };
  }

  private toScreen(p: LatLng, vp: Viewport): { x: number; y: number } {
    const w = project(p, this.zoom);
    return { x: w.x - vp.left, y: w.y - vp.top };
  }

  private toLatLng(screenX: number, screenY: number): LatLng {
    const vp = this.viewport();
    return unproject(vp.left + screenX, vp.top + screenY, this.zoom);
  }

  private scheduleRender(): void {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.render();
    });
  }

  private render(): void {
    if (!this.gridLayer || !this.cadastralLayer || !this.featureLayer || !this.width || !this.height) return;
    const vp = this.viewport();
    // 타일이 있으면 도식 배경·도식 지적 격자는 그리지 않습니다 (실제 지도 위에 가짜 선을 얹지 않도록)
    this.tiles?.render(vp, this.zoom, this.mapType);
    this.gridLayer.innerHTML = this.tiles ? '' : this.renderGrid(vp);
    this.cadastralLayer.innerHTML = this.cadastral && !this.tiles ? this.renderCadastral(vp) : '';
    this.featureLayer.innerHTML = this.renderFeatures(vp);
  }

  /** 거리(큰 격자)·골목(작은 격자)·블록(건물 덩어리)을 월드 좌표에 고정해 그립니다. */
  private renderGrid(vp: Viewport): string {
    const z = this.zoom;
    const streetLevel = z >= 15 ? 17 : z >= 12 ? 14 : 11;
    let out = this.gridLines(vp, streetLevel + 2, 'mm-minor', 24);
    if (z >= 16) out += this.renderBlocks(vp, 18);
    out += this.gridLines(vp, streetLevel, 'mm-street', 24);
    return out;
  }

  private renderCadastral(vp: Viewport): string {
    if (this.zoom < 15) return '';
    return this.gridLines(vp, 20, 'mm-cadastral', 14);
  }

  /** level 타일 경계선을 그립니다 (타일 한 칸이 minPx 보다 작으면 생략). */
  private gridLines(vp: Viewport, level: number, className: string, minPx: number): string {
    const tilePx = 256 * 2 ** (this.zoom - level);
    if (tilePx < minPx) return '';
    const parts: string[] = [];
    const startX = Math.floor(vp.left / tilePx) * tilePx;
    const startY = Math.floor(vp.top / tilePx) * tilePx;
    for (let x = startX; x < vp.left + vp.width; x += tilePx) {
      const sx = x - vp.left;
      parts.push(`<line class="${className}" x1="${sx}" y1="0" x2="${sx}" y2="${vp.height}"/>`);
    }
    for (let y = startY; y < vp.top + vp.height; y += tilePx) {
      const sy = y - vp.top;
      parts.push(`<line class="${className}" x1="0" y1="${sy}" x2="${vp.width}" y2="${sy}"/>`);
    }
    return parts.join('');
  }

  /** 타일마다 결정적(해시) 위치에 건물 블록을 놓습니다. */
  private renderBlocks(vp: Viewport, level: number): string {
    const tilePx = 256 * 2 ** (this.zoom - level);
    const parts: string[] = [];
    const tx0 = Math.floor(vp.left / tilePx);
    const ty0 = Math.floor(vp.top / tilePx);
    const tx1 = Math.floor((vp.left + vp.width) / tilePx);
    const ty1 = Math.floor((vp.top + vp.height) / tilePx);
    for (let tx = tx0; tx <= tx1; tx++) {
      for (let ty = ty0; ty <= ty1; ty++) {
        const rnd = seeded(tx, ty);
        const count = 1 + Math.floor(rnd() * 2);
        for (let i = 0; i < count; i++) {
          const w = tilePx * (0.18 + rnd() * 0.22);
          const h = tilePx * (0.14 + rnd() * 0.22);
          const x = tx * tilePx + tilePx * (0.08 + rnd() * 0.7) - vp.left;
          const y = ty * tilePx + tilePx * (0.08 + rnd() * 0.7) - vp.top;
          parts.push(
            `<rect class="mm-block" x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${Math.min(w, tilePx * 0.84).toFixed(1)}" height="${Math.min(h, tilePx * 0.84).toFixed(1)}" rx="${(tilePx * 0.01).toFixed(1)}"/>`,
          );
        }
      }
    }
    return parts.join('');
  }

  private renderFeatures(vp: Viewport): string {
    const parts: string[] = [];

    if (this.site) {
      if (this.site.polygon.length > 2) {
        const d = this.site.polygon.map((p, i) => `${i ? 'L' : 'M'}${this.pt(p, vp)}`).join(' ') + ' Z';
        parts.push(`<path class="mm-site" d="${d}"/>`);
      }
      if (this.site.areaLabel && this.site.polygon.length) {
        const c = this.toScreen(centroid(this.site.polygon), vp);
        const top = Math.min(...this.site.polygon.map((p) => this.toScreen(p, vp).y));
        parts.push(
          `<foreignObject class="mm-label" x="${c.x.toFixed(1)}" y="${(top - 8).toFixed(1)}" width="1" height="1">` +
            `<div xmlns="http://www.w3.org/1999/xhtml" class="map-area-label">${escapeHtml(this.site.areaLabel)}</div>` +
            `</foreignObject>`,
        );
      }
    }

    // 배치안 (어댑터가 그리는 순서 = features 순서: 가용 영역 → 차로 → 주차구획 → 출입구)
    for (const f of this.layout?.features ?? []) {
      const cls = `mm-layout-${escapeHtml(f.properties.layer)}`;
      const g = f.geometry;
      const ringPath = (ring: GeoJsonPosition[]) =>
        ring.map(([lng, lat], i) => `${i ? 'L' : 'M'}${this.pt({ lat, lng }, vp)}`).join(' ') + ' Z';
      if (g.type === 'Polygon' || g.type === 'MultiPolygon') {
        const polygons = g.type === 'Polygon' ? [g.coordinates] : g.coordinates;
        for (const rings of polygons) {
          const d = rings
            .filter((r) => r.length >= 3)
            .map(ringPath)
            .join(' ');
          if (d) parts.push(`<path class="${cls}" fill-rule="evenodd" d="${d}"/>`);
        }
      } else if (g.type === 'LineString' && g.coordinates.length >= 2) {
        const d = g.coordinates.map(([lng, lat], i) => `${i ? 'L' : 'M'}${this.pt({ lat, lng }, vp)}`).join(' ');
        parts.push(`<path class="${cls}" d="${d}"/>`);
      } else if (g.type === 'Point') {
        const [lng, lat] = g.coordinates;
        const s = this.toScreen({ lat, lng }, vp);
        parts.push(`<circle class="${cls}" cx="${s.x.toFixed(1)}" cy="${s.y.toFixed(1)}" r="6"/>`);
      }
    }

    for (const m of this.markers) {
      const s = this.toScreen(m.position, vp);
      if (s.x < -40 || s.y < -40 || s.x > vp.width + 40 || s.y > vp.height + 40) continue;
      parts.push(
        `<g class="mm-marker ${m.variant}" data-id="${escapeHtml(m.id)}" transform="translate(${s.x.toFixed(1)} ${s.y.toFixed(1)})">` +
          `<title>${escapeHtml(m.title ?? m.id)}</title>` +
          `<circle class="halo" r="13"/><circle class="dot" r="6.5"/>` +
          `</g>`,
      );
    }

    return parts.join('');
  }

  private pt(p: LatLng, vp: Viewport): string {
    const s = this.toScreen(p, vp);
    return `${s.x.toFixed(1)} ${s.y.toFixed(1)}`;
  }

  // ── 포인터 ───────────────────────────────────────────────────────

  private handlePointerDown = (e: PointerEvent): void => {
    if (e.button !== 0) return;
    this.drag = { startX: e.clientX, startY: e.clientY, lastX: e.clientX, lastY: e.clientY, moved: false, pointerId: e.pointerId };
    this.root?.setPointerCapture(e.pointerId);
  };

  private handlePointerMove = (e: PointerEvent): void => {
    const rect = this.root?.getBoundingClientRect();
    if (!rect) return;
    if (this.drag && this.drag.pointerId === e.pointerId) {
      const dx = e.clientX - this.drag.lastX;
      const dy = e.clientY - this.drag.lastY;
      if (Math.abs(dx) + Math.abs(dy) > 0) {
        // 누른 지점부터의 누적 이동으로 판단 — 천천히 끌어도 클릭으로 오인하지 않음
        if (!this.drag.moved && Math.abs(e.clientX - this.drag.startX) + Math.abs(e.clientY - this.drag.startY) > 4) {
          this.drag.moved = true;
          this.root?.classList.add('is-dragging');
        }
        const c = project(this.center, this.zoom);
        this.center = unproject(c.x - dx, c.y - dy, this.zoom);
        this.drag.lastX = e.clientX;
        this.drag.lastY = e.clientY;
        this.scheduleRender();
      }
      return;
    }
    this.emit('mousemove', this.toLatLng(e.clientX - rect.left, e.clientY - rect.top));
  };

  private handlePointerUp = (e: PointerEvent): void => {
    if (!this.drag || this.drag.pointerId !== e.pointerId) return;
    const { moved } = this.drag;
    this.drag = null;
    this.root?.classList.remove('is-dragging');
    try {
      this.root?.releasePointerCapture(e.pointerId);
    } catch {
      /* 이미 해제됨 */
    }
    if (moved || e.type === 'pointercancel') return;

    const markerEl = (e.target as Element | null)?.closest?.('.mm-marker') as SVGGElement | null;
    if (markerEl) {
      const id = markerEl.dataset.id;
      const marker = this.markers.find((m) => m.id === id);
      if (marker && this.onMarkerClick) {
        this.onMarkerClick(marker);
        return;
      }
    }
    const rect = this.root?.getBoundingClientRect();
    if (rect) this.emit('click', this.toLatLng(e.clientX - rect.left, e.clientY - rect.top));
  };

  private handlePointerLeave = (): void => {
    if (!this.drag) return;
  };

  private handleWheel = (e: WheelEvent): void => {
    e.preventDefault();
    const rect = this.root?.getBoundingClientRect();
    if (!rect) return;
    this.zoomAround(e.deltaY < 0 ? 1 : -1, e.clientX - rect.left, e.clientY - rect.top);
  };

  private handleDoubleClick = (e: MouseEvent): void => {
    const rect = this.root?.getBoundingClientRect();
    if (!rect) return;
    this.zoomAround(1, e.clientX - rect.left, e.clientY - rect.top);
  };

  /** 커서 아래 지점이 그대로 머물도록 줌 */
  private zoomAround(delta: number, px: number, py: number): void {
    const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, this.zoom + delta));
    if (next === this.zoom) return;
    const anchor = this.toLatLng(px, py);
    this.zoom = next;
    const a = project(anchor, this.zoom);
    this.center = unproject(a.x - (px - this.width / 2), a.y - (py - this.height / 2), this.zoom);
    this.scheduleRender();
    this.emit('zoom', this.zoom);
  }
}

/** 타일 좌표로 결정되는 간단한 난수 — 같은 위치는 항상 같은 블록 모양 */
function seeded(x: number, y: number): () => number {
  let state = ((x * 73856093) ^ (y * 19349663) ^ 0x9e3779b9) >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 0x100000000;
  };
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string);
}
