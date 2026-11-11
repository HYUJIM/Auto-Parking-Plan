/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 네이버 지도 Web Dynamic Map Client ID (ncpKeyId). 비우면 미리보기 지도로 동작 */
  readonly VITE_NAVER_MAP_KEY_ID?: string;
  /** 미리보기 지도의 배경 타일 템플릿({z} {x} {y}). 비우면 OpenStreetMap, `off` 면 타일 없음 */
  readonly VITE_MAP_TILE_URL?: string;
  /** '위성' 레이어 타일 템플릿. 비우면 Esri World Imagery, `off` 면 일반 타일 사용 */
  readonly VITE_MAP_TILE_URL_SATELLITE?: string;
  /** BE 공개 API origin (정적 호스팅 + BE 직접 호출일 때). 비우면 같은 origin 의 /v1 (개발 서버가 BE 로 프록시) */
  readonly VITE_API_BASE?: string;
  /** 개발 서버 프록시가 /v1 을 넘길 BE 주소 (vite.config.ts 에서만 읽음). 기본 http://127.0.0.1:8000 */
  readonly VITE_PROXY_TARGET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
