import { content } from '@/lib/content';
import type { TileProvider } from './tileSource';

/**
 * 미리보기 지도의 배지·설명·출처 문구 — 실제로 깔린 타일 공급자에 맞춰 고릅니다 (문구는 ko.json 의 map).
 * 출처 표기는 타일 공급자의 이용 조건이기도 하므로, 보이는 지도와 다른 이름이 나가지 않게 한 곳에서 정합니다.
 */

/** 하단 바의 데이터 출처 */
export function tileSourceText(provider: TileProvider): string {
  switch (provider) {
    case 'vworld':
      return content.map.sourceVworld;
    case 'default':
      return content.map.sourceTiles;
    case 'custom':
      return content.map.sourceTilesCustom;
    case 'none':
      return content.map.sourceMock;
  }
}

/** 좌측 상단 배지: 타일 배경이면 '기본 지도', 타일까지 껐으면 '미리보기 지도' */
export function tileBadgeText(provider: TileProvider): string {
  return provider === 'none' ? content.map.previewBadge : content.map.tilesBadge;
}

/** 배지 설명(툴팁) */
export function tileBadgeDesc(provider: TileProvider): string {
  switch (provider) {
    case 'vworld':
      return content.map.tilesDescVworld;
    case 'default':
      return content.map.tilesDesc;
    case 'custom':
      return content.map.tilesDescCustom;
    case 'none':
      return content.map.previewDesc;
  }
}
