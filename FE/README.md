# APP 웹사이트 — FE

「APP 웹사이트 제작 계획서 (초기화면 → 이용화면)」를 React 로 구현한 프론트엔드입니다.
디자인은 **A안 · 클리어 블루프린트(시원한 블루)** 로 확정되어 있습니다.

> **BE 연동 상태:** 아직 연동하지 않습니다. 지금은 FE 안의 작은 개발용 API(`server/`)가 공공 API(도로명주소·브이월드)를 대신 호출하고,
> 법규 기준값은 목업(`src/mock/rules.json`)입니다. BE(Spring) 연동 시에는 `src/lib/api/*.ts` 의 호출 대상만 바꾸면 됩니다 — 아래 [BE 연동 메모](#be-연동-메모).

## 실행

```bash
npm install
npm run dev        # http://localhost:5173  (화면 + /api 미들웨어)
```

| 명령                              | 설명                                                                                 |
| --------------------------------- | ------------------------------------------------------------------------------------ |
| `npm run dev`                     | 개발 서버. `/api/*` 는 같은 프로세스의 미들웨어가 처리합니다                         |
| `npm run api`                     | 독립 실행 API 서버 (기본 http://127.0.0.1:8787). 정적 호스팅 뒤에서 `/api/*` 만 담당 |
| `npm run build`                   | 배포용 빌드 (`dist/`). 타입 검사는 `typecheck` 가 따로 합니다                        |
| `npm run preview`                 | 빌드 결과 미리보기 (`/api` 미들웨어 포함)                                            |
| `npm run typecheck`               | 타입 검사 — 브라우저 코드(`tsconfig.app.json`)와 Node 코드(`tsconfig.node.json`)     |
| `npm run lint` / `lint:fix`       | ESLint (TypeScript 권장 + React Hooks + Fast Refresh 규칙)                           |
| `npm run format` / `format:check` | Prettier                                                                             |
| `npm test` / `test:watch`         | Vitest 단위 테스트 (좌표 변환·PNU·필지 조합·법규 엔진·지도 기하·`/api` 핸들러)       |
| `npm run check:colors`            | 컴포넌트 코드에 색 코드(`#`)가 직접 들어갔는지 검사                                  |
| `npm run check`                   | 위 검사 전부 + 빌드 — 푸시 전에 한 번                                                |

**Node 22.18 이상(권장 24, `.nvmrc`)** 이 필요합니다. `npm run api` 는 Node 가 TypeScript 를 직접 실행하는 타입 스트리핑을 쓰므로
그보다 낮은 버전에서는 실행되지 않습니다 (`.npmrc` 의 `engine-strict` 가 설치 단계에서 막습니다).
들여쓰기는 웹 관례대로 2칸입니다(`FE/.editorconfig`, 저장소 공통 규칙은 4칸).

### 키 설정

`.env.example` 을 `.env` 로 복사하고 값을 채웁니다. `.env*` 는 전부 git 에서 제외되고(템플릿 `.env.example` 만 올라감), **템플릿에는 실제 키를 적지 않습니다.**

| 변수                              | 용도                                      | 없을 때                                                     |
| --------------------------------- | ----------------------------------------- | ----------------------------------------------------------- |
| `JUSO_API_KEY`                    | 도로명주소 검색 API (business.juso.go.kr) | 목업 주소 12건으로 검색 (목록 아래 '목업 주소 데이터' 표시) |
| `JUSO_COORD_API_KEY`              | 도로명주소 좌표제공 API (별도 신청)       | 검색 결과에 좌표 없음 → 미리보기 핀 없음                    |
| `VWORLD_API_KEY`, `VWORLD_DOMAIN` | 브이월드 연속지적도·토지특성·토지이용계획 | 목업 필지 12개만 조회, 그 밖의 PNU 는 '대기' 화면           |
| `VITE_NAVER_MAP_KEY_ID`           | 네이버 지도 (브라우저 노출)               | 미리보기 지도(자체 SVG)                                     |

`JUSO_*`·`VWORLD_*` 는 `VITE_` 접두사가 없어 번들에 포함되지 않고 서버 프로세스만 읽습니다. 키를 바꾸면 `npm run dev` 를 재시작하세요.

## 화면

| URL           | 화면                                                                                                  |
| ------------- | ----------------------------------------------------------------------------------------------------- |
| `/`           | 1단계 초기화면 — 좌측 패널(헤드라인·주소 검색·서비스·이용 흐름) + 수성구 전체 지도(검증 필지 10개 점) |
| `/site?pnu=…` | 2단계 이용화면 — 검색창·주소·PNU, 탭(대지 요약 · 법규 검토 · 배치안 잠금), 위성+지적도 위 대지 경계   |
| `/kit`        | 디자인 키트 — 색·글꼴·아이콘·버튼 전 상태·배지·지도 경계선 샘플, A/B/C 안 미리보기                    |

`/` 와 `/site` 는 같은 레이아웃(`MapShell`) 안에서 **좌측 패널만 바뀌고 지도는 그대로 이어집니다.**
`?pnu=` 는 숫자 19자리만 받습니다 — 형식이 다르면 요청 없이 '올바르지 않은 요청' 안내만 보여 줍니다.

## 데이터 흐름

```
주소 입력 ──GET /api/address?q=──▶ server/juso.ts ──▶ juso.go.kr addrLinkApi (검색) · addrCoordApi (좌표, 선택)
                                                        법정동코드·산·본번·부번 → PNU 19자리, EPSG:5179 → WGS84 (server/proj.ts)
/site?pnu= ─GET /api/parcel?pnu=──▶ server/vworld.ts ─▶ 브이월드 연속지적도 (경계)  +  토지특성 (면적·지목·용도지역·도로접면·공시지가·형상)
                                                        + 토지이용계획 (용도지역·지구·구역 — 포함/저촉/접함)
지도 클릭 ──GET /api/parcel/at ────▶ server/vworld.ts ─▶ 연속지적도 geomFilter=POINT → { pnu } → /site?pnu=
법규 검토 ───────── 브라우저 안 ──▶ src/lib/rules/engine.ts (목업 기준표 + 필지 값 → 핵심 6항목 · 별도 확인 4항목)
```

- **PNU** = 법정동코드(10) + 산구분(1: 대지=1, 산=2) + 본번(4) + 부번(4). 수성구 여부는 앞 5자리 `27260`. 형식·상수는 `shared/types.ts` 한 곳.
- **필지 응답 순서**: 목업 필지(검증 예시 12개) → 브이월드 → 없으면 404. 브이월드 키가 없으면 501 `NOT_READY`.
  404·501 모두 이용화면은 주소·PNU·좌표만으로 '대기' 화면을 보여 줍니다.
- 브이월드에는 도로 폭·접도 길이가 없어 **토지특성의 '도로접면'(광대소각·소로한면·맹지 …)으로 추정**하고,
  해당 법규 항목에 "도로접면 기준 추정" 단서를 붙입니다.
- 용도지역: 대구 조례 값을 아직 확인하지 않은 항목(`rules.json` 의 `basis: "decree"`)은 국토계획법 시행령 상한값을 쓰고 '확인 필요'로,
  표에 없는 용도지역은 값 없이 '확인 필요'만 표시합니다.
- 별도 확인 4항목(지구단위계획·문화재·경관/고도·비행안전)은 토지이용계획 목록에서 관련 항목을 찾아 함께 보여 주되 상태는 항상 '별도 확인'입니다.
- 대지면적은 토지대장(토지특성) 값을 쓰고, 없으면 경계 폴리곤으로 계산합니다(카드에 출처 표시). 공시지가는 기준연도를 함께 표시합니다.
- 승인키는 **브라우저로 나가지 않습니다.** 모든 공공 API 호출은 `server/` 안에서만 일어납니다.
- 브라우저가 접속하는 외부 origin 은 네이버 지도(키 설정 시)뿐입니다. 글꼴(Pretendard)은 npm 패키지에서 번들에 포함되어 CDN 을 쓰지 않습니다.

## 지도

- 기본은 **네이버지도(NCP Maps v3)** 입니다. `.env` 의 `VITE_NAVER_MAP_KEY_ID` 를 채우고 NCP 콘솔 Application 에 `http://localhost:5173` 을 등록하세요.
- 키가 없거나 인증에 실패하면 **미리보기 지도**(자체 SVG, 같은 좌표계)로 자동 전환됩니다. 좌측 상단 배지로 구분됩니다.
- 지도 코드는 `src/lib/map/MapAdapter`(`types.ts`) 인터페이스 하나로 감싸져 있어, 카카오로 바꾸려면 `kakaoMap.ts` 를 만들고
  `createMapAdapter.ts` 한 곳만 바꾸면 됩니다. 클릭 지점 → 필지 규칙은 `pickParcel.ts`(지도 공급자와 무관).
- 장면(scene) 세 가지: `overview`(수성구 전체 + 검증 필지 점) · `site`(필지 경계 + 면적 말풍선, 위성+지적도) · `point`(좌표만 있을 때 핀).

## 우리가 직접 편집하는 곳 (계획서 5장)

| 바꾸고 싶은 것        | 고칠 곳                                    | 예시                                                                                             |
| --------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| 메인 색·배경색·둥글기 | `src/design/themes/clear-blue.css`         | `--color-primary: #1E5EFF;` 한 줄                                                                |
| 디자인 안 전체 교체   | `src/design/theme.config.ts`               | `activeTheme: 'clear-blue'` → `'drafting-mono'`                                                  |
| 아이콘 모양           | `public/icons/<이름>.svg`                  | `search.svg` 를 같은 이름으로 덮어쓰기                                                           |
| 아이콘 추가           | `public/icons/` + `src/design/icons.ts`    | 파일 넣고 이름 한 줄 등록                                                                        |
| 버튼 모양             | 테마 파일의 `--button-*`                   | `--button-radius: 10px;`                                                                         |
| 버튼·안내 문구        | `src/content/ko.json`                      | `"cta": "대지 분석 시작"` — 법규 항목 문구는 `site.rules.items`                                  |
| 지도 경계선 색·두께   | 테마 파일의 `--map-site-*`, `--map-road-*` | `--map-site-stroke: #1E5EFF;`                                                                    |
| 로고                  | `public/brand/logo.svg`                    | 파일 교체                                                                                        |
| 법규 기준값(목업)     | `src/mock/rules.json`                      | 용도지역별 건폐율·용적률, 접도 기준 폭·길이, 주차 1면 크기, 도로접면 폭 추정표, 별도 확인 키워드 |

규칙: 색·간격·둥글기는 CSS 변수로만, 아이콘은 24×24 `currentColor` SVG, 버튼은 `Button` 컴포넌트 하나,
문구는 전부 `ko.json`(법규 엔진도 `ko.json` 템플릿에 `rules.json` 숫자를 넣어 문장을 만듭니다 — 기준값을 바꾸면 판정과 문구가 함께 바뀜).
`npm run check:colors` 로 색 코드 직접 입력 0건을 확인합니다.

## 폴더 구조

```
src/
  main.tsx / App.tsx        진입점·라우트 (ErrorBoundary 로 감싸 패널이 깨져도 빈 화면이 되지 않음)
  layouts/MapShell          상단 바 + 좌측 패널 + 우측 지도 (지도 유지)
  pages/HomePanel           1단계 초기화면 패널
  pages/SitePanel           2단계 이용화면 패널 (필지 / 대기 두 상태)
  pages/KitPage             /kit 디자인 키트
  components/               Button · Icon · Badge · SearchBox · StatCard · RuleItem · AsyncBlock · ErrorBoundary · Toast · TopBar
  components/map/           MapView · MapControls · ScaleBar · MapStatusBar
  lib/map/                  MapAdapter(types) · naverMap · mockMap · createMapAdapter · MapContext · pickParcel · layoutAnchors · geo · mapStyle
  lib/api/                  address · parcel(resolveSite·getParcelAt·getRuleReview) · base · errors · guards · hitCache · recent · mock · types
  lib/rules/                engine(법규 엔진, 순수 함수) · types(rules.json 모양)
  lib/                      useAsync · content · format · landUse · cx
  design/                   tokens.css · themes/*.css · theme.config.ts · icons.ts · base.css
  content/ko.json           화면 문구
  mock/                     검증 필지 예시 12개(parcels.json) · 법규 기준 목업(rules.json)
shared/types.ts             FE 와 server 가 함께 쓰는 와이어 타입·PNU 상수 (외부 import 없음)
server/                     FE 개발용 API — BE 연동 전까지의 대역 (src/ 를 import 하지 않음, ESLint 로 강제)
  api.ts                    /api/health · /api/address · /api/parcel · /api/parcel/at — 고정 오류 문구, 호출 예산, 캐시
  budget.ts                 공공 API 호출 예산(토큰 버킷·동시 호출) · TTL+LRU 캐시
  hostCheck.ts              Host 헤더 검사 (DNS 리바인딩 방어)
  juso.ts                   juso.go.kr 호출·정규화·PNU 생성
  vworld.ts                 브이월드 연속지적도·토지특성·토지이용계획 → Parcel 조합, 면적·중심 계산
  proj.ts                   EPSG:5179 ↔ WGS84
  vitePlugin.ts             개발·미리보기 서버에 /api 붙이기
  standalone.ts             독립 실행 API 서버 (npm run api) — CORS 허용 목록·IP 속도 제한·프록시 신뢰
  *.test.ts                 Vitest
public/icons/ · public/brand/
tsconfig.app.json / tsconfig.node.json   브라우저 코드(DOM lib)와 Node 코드(node types, erasableSyntaxOnly)를 따로 검사
eslint.config.js · vitest.config.ts · .prettierrc.json · .nvmrc · .npmrc
```

## 보안·운영 메모

- **승인키**: `.env` 만 읽고 `.env*` 전부 git 제외. 키 문구·공공 API 원문은 로그에만 남고 응답 `message` 는 고정 문구입니다(`server/api.ts` 의 `MESSAGES`).
- **개발 서버 `/api`**: Host 헤더가 `localhost`·`*.localhost`·IP·`server.allowedHosts` 가 아니면 403 — 다른 사이트가 DNS 리바인딩으로 개발자의 키 달린 프록시를 쓰지 못합니다.
- **호출 예산**: 프로세스 전체 분당 공공 API 호출 수(`UPSTREAM_PER_MIN`, 기본 600)·동시 호출 수(`UPSTREAM_MAX_INFLIGHT`, 기본 32)를 넘으면 503 `BUSY` + `Retry-After`.
  필지·클릭 지점 결과는 24시간 캐시(500건)되어 같은 필지를 다시 묻지 않습니다.
- **독립 서버(`npm run api`)**: 기본 `127.0.0.1` 바인드(운영 `0.0.0.0`), `CORS_ORIGIN` 은 적어 준 origin 만(비우면 전부 차단, `*` 는 운영에서 거부),
  IP 당 분당 요청 수(`RATE_LIMIT_PER_MIN`, 0 이면 끔), 프록시 뒤에서는 `TRUST_PROXY=1` 로 `X-Forwarded-For` 를 신뢰해야 모든 사용자가 한 IP 로 묶이지 않습니다.
- **브라우저**: `sessionStorage`/`localStorage` 값은 모양을 검사해(`guards.ts`) 통과한 항목만 쓰고, 렌더 오류는 `ErrorBoundary` 가 받아 새로고침 버튼을 보여 줍니다.
- **입력**: 검색어 100자 제한(클라이언트·서버), `?pnu=` 19자리 검사, 좌표 범위 검사. 사용자 입력은 GET 쿼리로 전달되므로 호스팅 접속 로그 보존 기한을 정책에 적어 두세요.

## BE 연동 메모

BE(`../BE`, Spring Boot) 의 공개 API 와 FE 개발용 API 의 대응입니다. 연동 때는 `src/lib/api/address.ts`·`parcel.ts` 의
`fetch` 대상과 응답 → `AddressHit`/`Parcel` 변환만 바꾸고, `server/` 는 지우거나 개발용으로 남깁니다.
BE 소스를 읽어 정리한 **필드 단위 대응표**는 [docs/BE-API-MAPPING.md](docs/BE-API-MAPPING.md) 에 있습니다.

| FE 개발용 API                  | BE 공개 API                                 | 비고                                               |
| ------------------------------ | ------------------------------------------- | -------------------------------------------------- |
| `GET /api/address?q=`          | `GET /v1/address/search?q=`                 | 도로명·지번·PNU·좌표·지원 여부                     |
| `GET /api/parcel?pnu=`         | `GET /v1/parcels/{pnu}`                     | 대지면적·지목·용도지역·지구·구역·GeoJSON 경계·출처 |
| `GET /api/parcel/at?lng=&lat=` | `GET /v1/parcels/at?lng=&lat=`              | 지도 클릭 → 필지                                   |
| 브라우저 안 법규 생성(목업)    | `POST /v1/reviews` → `GET /v1/reviews/{id}` | 검토 결과는 BE 가 근거 원문과 함께 돌려줌          |
| —                              | `POST /v1/reviews/{id}/parking-simulations` | 배치안 탭(잠금)에서 사용 예정                      |

BE 의 CORS 기본값은 `http://localhost:3000` 이므로 연동 시 BE `.env` 의 `CORS_ORIGINS` 에 `http://localhost:5173` 을 추가하거나, FE 에서 Vite 프록시로 `/v1` 을 BE 로 넘깁니다.

## 배포 메모

- FE 는 `npm run build` 결과(`dist/`)를 S3+CloudFront·Amplify 등 정적 호스팅에 올립니다.
  CI(`.github/workflows/fe-ci.yml`)가 타입 검사·린트·포맷 검사·단위 테스트·색 검사·빌드·API 서버 기동 확인을 돌립니다 (`npm ci --ignore-scripts`, Node 는 `.nvmrc`).
- **SPA 경로**: `/site?pnu=…`·`/kit` 는 클라이언트 라우트입니다. CloudFront 는 Custom Error Response 403/404 → `/index.html` (HTTP 200, 오류 캐시 TTL 0),
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
  default-src 'self'; script-src 'self' https://oapi.map.naver.com https://*.pstatic.net; img-src 'self' data: blob: https://*.pstatic.net https://*.naver.com;
  style-src 'self' 'unsafe-inline'; font-src 'self'; connect-src 'self' https://api.example.com https://*.naver.com https://*.pstatic.net; frame-ancestors 'none'
  ```
  (개발 서버에는 넣지 않습니다 — Vite 의 React Refresh 프리앰블이 inline script 를 씁니다.)
- BE 연동 전이라면 API 는 `npm run api`(Node) 또는 `server/api.ts` 의 `createApiHandler` 를 Lambda·Express 등에 얹어 띄우고,
  FE 빌드 시 `VITE_API_BASE=https://api.example.com` 으로 그 주소를 넣습니다. 운영 환경 변수는 [보안·운영 메모](#보안운영-메모) 와 `.env.example` 을 따릅니다.
- 네이버 지도는 배포 도메인을 NCP Application 에, 브이월드는 `VWORLD_DOMAIN` 에 등록 도메인을 넣어야 합니다.

## 데이터

- `src/mock/parcels.json` 의 주소·좌표·면적·법규 값은 **예시**이며 실제 데이터가 아닙니다(초기화면의 검증 필지 점 10개).
- 브이월드 응답은 원본을 그대로 씁니다. 화면 하단에 출처·조회일을 표시합니다.
- `VITE_MOCK_FAIL_RATE=0.3` 으로 두면 법규 카드의 '다시 시도' UI 를 확인할 수 있습니다(단위 테스트는 항상 0).

## 다음 단계

5. 모바일 다듬기(하단 시트 제스처)·AWS 배포 · BE 연동(법규 검토를 BE 결과로 교체, 배치안 탭 활성화)
