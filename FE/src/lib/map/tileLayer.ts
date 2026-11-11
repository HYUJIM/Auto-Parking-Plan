import type { MapType } from './types';
import { tileUrl, visibleTiles, type TileSource, type TileViewport } from './tileSource';

/**
 * 미리보기 지도의 배경 타일 층 — <img> 타일을 뷰포트에 맞춰 놓고, 보이지 않게 된 타일은 뗍니다.
 * 위의 SVG 층(필지·마커·배치안)과 같은 월드 좌표를 쓰므로 그림이 타일 위에 정확히 겹칩니다.
 */
export class TileLayer {
  private readonly el: HTMLDivElement;
  private readonly tiles = new Map<string, HTMLImageElement>();

  constructor(
    parent: HTMLElement,
    private readonly source: TileSource,
  ) {
    this.el = document.createElement('div');
    this.el.className = 'mm-tiles';
    this.el.setAttribute('aria-hidden', 'true');
    parent.appendChild(this.el);
  }

  render(vp: TileViewport, zoom: number, type: MapType): void {
    const template = type === 'satellite' ? this.source.satellite : this.source.normal;
    const keep = new Set<string>();
    for (const t of visibleTiles(vp, zoom)) {
      const key = `${template}|${t.key}`;
      keep.add(key);
      let img = this.tiles.get(key);
      if (!img) {
        img = createTile(tileUrl(template, t.z, t.x, t.y));
        this.tiles.set(key, img);
        this.el.appendChild(img);
      }
      img.style.transform = `translate(${t.left}px, ${t.top}px)`;
      img.style.width = `${t.size}px`;
      img.style.height = `${t.size}px`;
    }
    for (const [key, img] of this.tiles) {
      if (keep.has(key)) continue;
      img.remove();
      this.tiles.delete(key);
    }
  }

  destroy(): void {
    for (const img of this.tiles.values()) img.remove();
    this.tiles.clear();
    this.el.remove();
  }
}

function createTile(src: string): HTMLImageElement {
  const img = document.createElement('img');
  img.className = 'mm-tile';
  img.alt = '';
  img.draggable = false;
  img.decoding = 'async';
  img.addEventListener('load', () => img.classList.add('is-loaded'), { once: true });
  img.addEventListener('error', () => img.classList.add('is-error'), { once: true });
  img.src = src;
  return img;
}
