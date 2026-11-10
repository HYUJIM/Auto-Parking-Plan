/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 네이버 지도 Web Dynamic Map Client ID (ncpKeyId). 비우면 미리보기 지도로 동작 */
  readonly VITE_NAVER_MAP_KEY_ID?: string;
  /** API 서버 주소 (정적 호스팅 + 별도 API 서버일 때). 비우면 같은 origin 의 /api */
  readonly VITE_API_BASE?: string;
  /** 목업 API 실패 비율(0~1). 로딩·오류 UI 확인용 */
  readonly VITE_MOCK_FAIL_RATE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
