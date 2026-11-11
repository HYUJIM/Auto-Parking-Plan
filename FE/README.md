# APP 웹사이트 — FE

「APP 웹사이트 제작 계획서 (초기화면 → 이용화면)」를 React 로 구현한 프론트엔드입니다.
디자인은 **A안 · 클리어 블루프린트(시원한 블루)** 로 확정되어 있습니다.

> **BE 연동 상태: 연동 완료.** 주소 검색·필지·법규 검토·주차장 배치안을 모두 BE(Spring, `../BE`)의 공개 API(`/v1/...`)에서 받습니다.
> FE 자체 개발 API 와 브라우저 안 목업 법규 엔진은 제거되었습니다. 공공 API 승인키는 BE 에만 있고, 브라우저는 BE 만 부릅니다.
> 필드 대응과 어댑터 위치는 [docs/BE-API-MAPPING.md](docs/BE-API-MAPPING.md).

## 실행

**BE 를 먼저 띄웁니다** (`../BE`, JDK 21 · 자세한 설명은 [`../BE/README.md`](../BE/README.md)). 외부 API 키 없이 모의 공공 API 로 전체 흐름이 돕니다.

```bash
cd ../BE && ./gradlew build
java -jar server/build/libs/app.jar mockserver --port 8090                    # 터미널 1 — 모의 공공 API (예시 필지 12개)
# 터미널 2 — 환경변수(BE/README.md 의 export 한 줄) 를 넣은 뒤
java -jar server/build/libs/app.jar bootstrap --approve-as dev-reviewer --ack-warnings --confirm-transcriptions
java -jar server/build/libs/app.jar serve --port 8000                         # http://127.0.0.1:8000
```

또는 `cd ../BE && docker compose up --build` 후 `docker compose run --rm bootstrap`.

그다음 FE:

```bash
npm install
npm run dev        # http://localhost:5173  — /v1/* 는 BE(127.0.0.1:8000)로 프록시
```

| 명령                              | 설명                                                                                         |
| --------------------------------- | -------------------------------------------------------------------------------------------- |
| `npm run dev`                     | 개발 서버. `/v1/*` 를 BE 로 프록시합니다 (`VITE_PROXY_TARGET`, 기본 `http://127.0.0.1:8000`) |
| `npm run build`                   | 배포용 빌드 (`dist/`). 타입 검사는 `typecheck` 가 따로 합니다                                |
| `npm run preview`                 | 빌드 결과 미리보기 (`/v1` 프록시 포함)                                                       |
| `npm run typecheck`               | 타입 검사 — 브라우저 코드(`tsconfig.app.json`)와 설정 파일(`tsconfig.node.json`)             |
| `npm run lint` / `lint:fix`       | ESLint (TypeScript 권장 + React Hooks + Fast Refresh 규칙)                                   |
| `npm run format` / `format:check` | Prettier                                                                                     |
| `npm test` / `test:watch`         | Vitest 단위 테스트 (BE 응답 어댑터·오류 분류·검토 폴링·지도 기하·저장소 값 검사)             |
| `npm run check:colors`            | 컴포넌트 코드에 색 코드(`#`)가 직접 들어갔는지 검사                                          |
| `npm run check`                   | 위 검사 전부 + 빌드 — 푸시 전에 한 번                                                        |

**Node 22.18 이상(권장 24, `.nvmrc`)** 이 필요합니다. 들여쓰기는 웹 관례대로 2칸입니다(`FE/.editorconfig`, 저장소 공통 규칙은 4칸).

### 환경 변수

`.env.example` 을 `.env` 로 복사하고 값을 채웁니다. `.env*` 는 전부 git 에서 제외되고(템플릿 `.env.example` 만 올라감), **템플릿에는 실제 키를 적지 않습니다.**

| 변수                          | 용도                                                                  | 없을 때                                         |
| ----------------------------- | --------------------------------------------------------------------- | ----------------------------------------------- |
| `VITE_NAVER_MAP_KEY_ID`       | 네이버 지도 (브라우저 노출)                                           | 기본 지도(OSM 타일 + 자체 SVG 오버레이)         |
| `VITE_MAP_TILE_URL`           | 기본 지도의 배경 타일 템플릿(`{z}` `{x}` `{y}`), `off` 면 도식 격자만 | OpenStreetMap 표준 타일                         |
| `VITE_MAP_TILE_URL_SATELLITE` | '위성' 레이어 타일 템플릿, `off` 면 일반 타일 사용                    | Esri World Imagery                              |
| `VITE_API_BASE`               | BE 공개 API origin — FE 를 정적 호스팅하고 BE 를 직접 부를 때만       | 같은 origin 의 `/v1` (개발 서버가 BE 로 프록시) |
| `VITE_PROXY_TARGET`           | 개발·미리보기 서버가 `/v1` 을 넘길 BE 주소 (`vite.config.ts` 만 읽음) | `http://127.0.0.1:8000`                         |

공공 API 승인키(도로명주소·브이월드·건축HUB)는 FE 에 없습니다 — `../BE/.env` 에 둡니다.

## 화면

| URL           | 화면                                                                                                           |
| ------------- | -------------------------------------------------------------------------------------------------------------- |
| `/`           | 소개 페이지 — 제품 소개(도면 그림·동작 방식·법규 근거·기능)와 '시작하기'. 모션은 anime.js                      |
| `/app`        | 1단계 초기화면 — 좌측 패널(헤드라인·주소 검색·서비스·이용 흐름) + 수성구 전체 지도(검색 결과 미리보기 핀)      |
| `/site?pnu=…` | 2단계 이용화면 — 검색창·주소·PNU, 탭(대지 요약 · 법규 검토 · 배치안), 위성+지적도 위 대지 경계와 배치안 레이어 |

`/app` 과 `/site` 는 같은 레이아웃(`MapShell`) 안에서 **좌측 패널만 바뀌고 지도는 그대로 이어집니다.**
`/` 의 '시작하기' 는 `/app` 으로 갑니다 (소개 페이지는 지도를 띄우지 않습니다).
`?pnu=` 는 숫자 19자리만 받습니다 — 형식이 다르면 요청 없이 '올바르지 않은 요청' 안내만 보여 줍니다.

## 데이터 흐름 (전부 BE 공개 API)

```
주소 입력 ──GET /v1/address/search?q=──────▶ BE ─▶ 행정안전부 도로명주소 API → PNU·필지 중심 좌표·지원 관할 여부 (최대 10건)
/site?pnu= ─GET /v1/parcels/{pnu}───────────▶ BE ─▶ 브이월드 연속지적도(경계)·토지특성(면적·지목·도로접면·공시지가)·토지이용계획 + 건축물대장
지도 클릭 ──GET /v1/parcels/at?lng=&lat=─────▶ BE ─▶ 그 지점의 필지 전체 → /site?pnu=
법규 검토 ──POST /v1/reviews {pnu,audience}─▶ 202 {review_id} ─▶ GET /v1/reviews/{id} 를 Retry-After 간격으로 폴링 ─▶ review_result@1.0
배치안 ─────POST /v1/reviews/{id}/parking-simulations ─▶ 배치안 A/B/C + GeoJSON 레이어 → 지도에 주차구획·차로·출입구
```

- **어댑터**: BE 응답(snake_case, `src/lib/api/beTypes.ts`)은 `src/lib/api/beAdapters.ts`(순수 함수)가 FE 모델(`types.ts`)로 바꿉니다.
  좌표는 `[lng, lat]` → `{ lat, lng }`, 경계는 MultiPolygon 중 가장 큰 폴리곤의 외곽 링, 용도지역은 `designations` 중 접함이 아닌 zoning 1·2번째.
  BE 에서 받은 응답 원문은 `src/lib/api/__fixtures__/` 에 두고 어댑터 테스트의 입력으로 씁니다.
- **오류**: BE 는 `{ error: { code, message, details? } }` 로 답합니다. `base.ts` 가 422 → BAD_REQUEST, 404 → NOT_FOUND, 429 → RATE_LIMITED,
  503 SERVICE_BUSY → BUSY, 503 UPSTREAM_UNAVAILABLE → UPSTREAM, 409 → CONFLICT, 그 밖 5xx → SERVER 로 분류하고 BE 의 고정 안내 문구를 그대로 보여 줍니다.
  주소 검색 404 ADDRESS_NOT_FOUND 는 오류가 아니라 '결과 없음'(+ BE 안내), 필지 404 PARCEL_NOT_FOUND 는 주소·좌표만 보여 주는 '대기' 화면입니다.
- **법규 검토**: 6항목(용도·건폐율·용적률·주차대수·주차구획·차로 폭)은 `검토 가능 / 조건부 검토 / 별도 확인 필요` 세 상태로, 기준값(`criteria_text`)·적용값(참고값·용도 목록·기준표)·
  상태 사유(`reason_code`)·BE 가 원문과 대조해 검증한 쉬운 설명·근거 트리(법률 → 시행령 → 조례, 인용·원문 링크)를 보여 줍니다.
  별도 확인 항목(도로·건축선·높이·추가 규제)과 데이터 안내, 고지는 BE 가 준 그대로입니다. 시범 지역 밖 필지는 검토를 요청하지 않고 안내만 보여 줍니다.
- **배치안**: 법규 검토가 끝나면(`review_id`) '배치안 계산'. 주차장 설치 가능 여부가 확인되지 않아 BE 가 계산하지 않으면(`simulated=false`) 이유를 보여 주고
  '참고 배치 보기'(`include_unverified=true`)를 제안합니다. 배치안을 고르면 지도 어댑터 `setLayout()` 이 GeoJSON 레이어(가용 영역·차로·주차구획·출입구)를 그립니다.
- **PNU** = 법정동코드(10) + 산구분(1) + 본번(4) + 부번(4). 형식·상수는 `src/lib/api/types.ts`.
- 브라우저가 접속하는 외부 origin 은 지도뿐입니다 — 네이버 지도(키 설정 시) 또는 기본 지도 타일(`tile.openstreetmap.org`, 위성 `server.arcgisonline.com`).
  글꼴(Pretendard)은 npm 패키지에서 번들에 포함되어 CDN 을 쓰지 않습니다.

## 소개 페이지 (`/`)

- 움직임은 [anime.js](https://animejs.com) v4 로 만듭니다 (`animejs`, MIT, 의존성 없음). 코드는 `src/pages/landing/useLandingMotion.ts` 한 파일에 모여 있습니다.
- **마크업이 곧 정지 화면**입니다. 컴포넌트는 "다 그려진 화면"을 그리고, 모션 훅이 `data-anim`·`data-reveal`·`data-count` 표식을 찾아
  움직임을 입힙니다. 운영체제의 '동작 줄이기'(`prefers-reduced-motion`)를 켠 사용자에게는 아무것도 움직이지 않고 전부 보입니다.
- 쓰는 기능: 타임라인(등장 순서·배치안 A→B→C 반복), `stagger`(글자·눈금·점 격자 `grid`), SVG 선 그리기(`createDrawable`)·경로 따라가기(`createMotionPath`),
  스크롤 관찰자 `onScroll`(나타나기·숫자 세기·스크롤 동기화), `createScope`(페이지를 떠날 때 `revert()` 한 번으로 정리).
- 이 페이지는 body 가 아니라 자체 영역이 스크롤됩니다(서비스 화면이 지도 때문에 body 스크롤을 막아 둠) — `onScroll` 에는 항상 `container` 를 넘깁니다.
- 히어로 그림의 좌표(눈금 고리·점 격자·대지·배치안 3개의 구획 자리)는 `sceneGeometry.ts` 의 순수 함수이고,
  구획이 대지 안에 있는지·서로 겹치지 않는지·출입구를 비우는지를 단위 테스트로 확인합니다.
- 문구는 `src/content/ko.json` 의 `landing`, 색은 `themes/clear-blue.css` 의 `--landing-*` 토큰입니다. 그림 속 숫자는 예시이며 화면에 '예시'라고 표시합니다.

## 지도

- 기본은 **네이버지도(NCP Maps v3)** 입니다. `.env` 의 `VITE_NAVER_MAP_KEY_ID` 를 채우고 NCP 콘솔 Application 에 `http://localhost:5173` 을 등록하세요.
- 키가 없거나 인증에 실패하면 **기본 지도**로 자동 전환됩니다: 실제 지도 타일(일반 OpenStreetMap · 위성 Esri, `tileSource.ts`)을 아래에 깔고
  그 위에 자체 SVG 로 필지 경계·마커·배치안을 그립니다(같은 웹 메르카토르 좌표계). 좌측 상단 배지로 구분됩니다.
  OSM 타일 서버는 개발·소규모용이므로 실서비스는 네이버 키나 별도 타일 서버(`VITE_MAP_TILE_URL`)를 쓰세요. 지적도 레이어는 네이버 지도에서만 그려집니다.
  `VITE_MAP_TILE_URL=off` 면 타일 없이 도식 격자만 그리는 **미리보기 지도**(오프라인)가 됩니다.
- 지도 코드는 `src/lib/map/MapAdapter`(`types.ts`) 인터페이스 하나로 감싸져 있어, 카카오로 바꾸려면 `kakaoMap.ts` 를 만들고
  `createMapAdapter.ts` 한 곳만 바꾸면 됩니다. 클릭 지점 → 필지 규칙은 `pickParcel.ts`(지도 공급자와 무관).
- 장면(scene) 세 가지: `overview`(수성구 전체) · `site`(필지 경계 + 면적 말풍선, 위성+지적도) · `point`(좌표만 있을 때 핀).
  배치안 레이어(`setLayout`)는 `site` 장면 위에만 그려지고 필지가 바뀌면 지워집니다.

## 우리가 직접 편집하는 곳 (계획서 5장)

| 바꾸고 싶은 것        | 고칠 곳                                                   | 예시                                                                                  |
| --------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 메인 색·배경색·둥글기 | `src/design/themes/clear-blue.css`                        | `--color-primary: #1E5EFF;` 한 줄                                                     |
| 아이콘 모양           | `public/icons/<이름>.svg`                                 | `search.svg` 를 같은 이름으로 덮어쓰기                                                |
| 아이콘 추가           | `public/icons/` + `src/design/icons.ts`                   | 파일 넣고 이름 한 줄 등록                                                             |
| 버튼 모양             | 테마 파일의 `--button-*`                                  | `--button-radius: 10px;`                                                              |
| 버튼·안내 문구        | `src/content/ko.json`                                     | `"cta": "대지 분석 시작"` — 상태·사유·배치안 코드의 한글은 `site.rules`·`site.layout` |
| 지도 경계선·배치안 색 | 테마 파일의 `--map-site-*`, `--map-layout-*`              | `--map-layout-stall-fill: #1E5EFF;`                                                   |
| 로고                  | `public/brand/logo.svg`                                   | 파일 교체                                                                             |
| 법규 기준값·근거      | FE 에 없음 — BE 의 법규 DB(검수 승인된 규칙)에서만 옵니다 | `../BE` 관리자 API                                                                    |

규칙: 색·간격·둥글기는 CSS 변수로만, 아이콘은 24×24 `currentColor` SVG, 버튼은 `Button` 컴포넌트 하나, 문구는 전부 `ko.json`.
`npm run check:colors` 로 색 코드 직접 입력 0건을 확인합니다.

## 폴더 구조

```
src/
  main.tsx / App.tsx        진입점·라우트 (ErrorBoundary 로 감싸 패널이 깨져도 빈 화면이 되지 않음)
  layouts/MapShell          상단 바 + 좌측 패널 + 우측 지도 (지도 유지)
  pages/landing/            소개 페이지(/): LandingPage(구조·문구) · HeroScene · StepDemos · BasisTree · RippleGrid ·
                            sceneGeometry(그림 좌표, 순수 계산 + 테스트) · useLandingMotion(anime.js 움직임 전부)
  pages/HomePanel           1단계 초기화면 패널 (/app)
  pages/SitePanel           2단계 이용화면 패널 (대지 요약 · 법규 검토 · 대기/미지원 안내 · 하단 버튼)
  pages/LayoutTab           배치안 탭 (시뮬레이션 호출 · 배치안 선택 · 지도 레이어)
  components/               Button · Icon · Badge · SearchBox · StatCard · RuleItem(+StatusBadge·BasisList·ManualCheckItem) · AsyncBlock · ErrorBoundary · Toast · TopBar
  components/map/           MapView · MapControls · ScaleBar · MapStatusBar
  lib/map/                  MapAdapter(types) · naverMap · mockMap · createMapAdapter · MapContext · pickParcel · layoutAnchors · geo · mapStyle
  lib/api/                  beTypes(BE 와이어 타입) · beAdapters(BE → FE 변환) · types(FE 모델·ApiError) · base(fetch·오류 분류)
                            address · parcel · review(검토 폴링·배치안) · errors · guards · hitCache · recent · __fixtures__/
  lib/                      useAsync · content · format · landUse · cx
  design/                   tokens.css · themes/clear-blue.css(확정 디자인의 색·글꼴·둥글기) · icons.ts · base.css
  content/ko.json           화면 문구
scripts/                    check-hex-colors.mjs
public/icons/ · public/brand/
tsconfig.app.json / tsconfig.node.json   브라우저 코드(DOM lib)와 설정 파일(node types)을 따로 검사
eslint.config.js · vitest.config.ts · .prettierrc.json · .nvmrc · .npmrc
```

## 보안·운영 메모

- **승인키**: FE 에는 공공 API 키가 없습니다. 네이버 지도 Client ID 만 브라우저에 노출되며 `.env*` 는 전부 git 제외.
- **오류 문구**: BE 의 `error.message` 는 고정 안내 문구만 오므로 그대로 보여 주고, 브라우저 내부 오류·BE 예외 이름은 숨깁니다(`errors.ts`).
- **브라우저**: `sessionStorage`/`localStorage` 값은 모양을 검사해(`guards.ts`) 통과한 항목만 쓰고, 렌더 오류는 `ErrorBoundary` 가 받아 새로고침 버튼을 보여 줍니다.
- **입력**: 검색어 100자 제한(BE 는 2~200자), `?pnu=` 19자리 검사. 사용자 입력은 GET 쿼리로 전달되므로 호스팅 접속 로그 보존 기한을 정책에 적어 두세요.
- **요청 제한**: BE 의 429/503 은 `Retry-After` 와 함께 '잠시 기다려야 합니다' 카드 + 다시 시도 버튼으로 보여 줍니다. 검토 폴링은 60초 상한.

## 배포 메모

- FE 는 `npm run build` 결과(`dist/`)를 S3+CloudFront·Amplify 등 정적 호스팅에 올립니다.
  CI(`.github/workflows/fe-ci.yml`)가 타입 검사·린트·포맷 검사·단위 테스트·색 검사·빌드를 돌립니다 (`npm ci --ignore-scripts`, Node 는 `.nvmrc`).
- **BE 주소**: 빌드 시 `VITE_API_BASE=https://api.example.com` 으로 BE origin 을 넣고, BE 의 `CORS_ORIGINS` 에 FE 배포 도메인을 등록합니다.
  같은 도메인 아래 `/v1` 을 BE 로 라우팅(CloudFront 동작·ALB 경로 규칙)하면 `VITE_API_BASE` 를 비워 둘 수 있습니다.
- **SPA 경로**: `/app`·`/site?pnu=…` 는 클라이언트 라우트입니다. CloudFront 는 Custom Error Response 403/404 → `/index.html` (HTTP 200, 오류 캐시 TTL 0),
  Amplify 는 rewrite `</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json)$)([^.]+$)/>` → `/index.html` (200) 을 넣어야 새로고침·공유 링크가 열립니다.
- **캐시 헤더**: `assets/*` 는 해시 파일명이므로 `Cache-Control: public,max-age=31536000,immutable`, `index.html` 은 `no-cache` 로 올리고 배포 뒤 `/index.html` 을 무효화합니다.
  ```bash
  aws s3 sync dist s3://<bucket> --exclude index.html --cache-control 'public,max-age=31536000,immutable'
  aws s3 cp dist/index.html s3://<bucket>/index.html --cache-control 'no-cache'
  aws cloudfront create-invalidation --distribution-id <id> --paths '/index.html'
  ```
- **보안 헤더**(CloudFront Response Headers Policy 등으로): `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`,
  `Permissions-Policy: geolocation=(self)`, 그리고 CSP. 네이버 지도 SDK·타일 호스트는 배포 전 네트워크 패널에서 확인해 채웁니다. 시작점:
  ```
  default-src 'self'; script-src 'self' https://oapi.map.naver.com https://*.pstatic.net;
  img-src 'self' data: blob: https://*.pstatic.net https://*.naver.com https://tile.openstreetmap.org https://server.arcgisonline.com;
  style-src 'self' 'unsafe-inline'; font-src 'self'; connect-src 'self' https://api.example.com https://*.naver.com https://*.pstatic.net; frame-ancestors 'none'
  ```
  (개발 서버에는 넣지 않습니다 — Vite 의 React Refresh 프리앰블이 inline script 를 씁니다.)
- 네이버 지도는 배포 도메인을 NCP Application 에 등록해야 합니다. 브이월드·도로명주소 키는 BE 배포 설정(`../BE/docs/DEPLOY_AWS.md`)을 따릅니다.

## 데이터

- FE 에는 미리 저장된 필지·주소가 없습니다. 검색·조회 결과는 전부 BE 에서 옵니다.
  BE 가 **모의 공공 API**(`mockserver`)로 돌면 BE 모의 데이터셋의 예시 필지 12건만 검색·조회되고 실제 주소는 "검색 결과 없음"이 됩니다.
  실제 주소를 쓰려면 BE 에 도로명주소·브이월드·건축HUB 승인키를 넣고 모의 서버 주소(`*_URL`)를 비워 두세요(`../BE/.env.example`).
  BE 가 `is_example: true` 로 표시한 필지(모의 데이터)는 주소 옆에 '예시 데이터' 배지가 붙습니다.
- 화면 하단에 출처(`sources`)·조회일을, 법규 탭에는 검토 기준일·엔진 버전을 표시합니다. 법규 값은 BE 가 검수 승인한 규칙에서만 옵니다.

## 다음 단계

5. 모바일 다듬기(하단 시트 제스처) · AWS 배포 · PDF 저장(검토 결과 + 배치안 도면)
