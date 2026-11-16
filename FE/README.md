# APP 웹사이트 — FE

「APP 웹사이트 제작 계획서 (초기화면 → 이용화면 → 결과 리포트)」를 React 로 구현한 프론트엔드입니다.
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

| 명령                              | 설명                                                                                                                      |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `npm run dev`                     | 개발 서버. `/v1/*` 를 BE 로 프록시합니다 (`VITE_PROXY_TARGET`, 기본 `http://127.0.0.1:8000`)                              |
| `npm run build`                   | 배포용 빌드 (`dist/`). 타입 검사는 `typecheck` 가 따로 합니다                                                             |
| `npm run preview`                 | 빌드 결과 미리보기 (`/v1` 프록시 포함)                                                                                    |
| `npm run typecheck`               | 타입 검사 — 브라우저 코드(`tsconfig.app.json`)와 설정 파일(`tsconfig.node.json`)                                          |
| `npm run lint` / `lint:fix`       | ESLint (TypeScript 권장 + React Hooks + Fast Refresh 규칙)                                                                |
| `npm run format` / `format:check` | Prettier                                                                                                                  |
| `npm test` / `test:watch`         | Vitest 단위 테스트 (BE 응답 어댑터·오류 분류·검토 폴링·지도 기하·저장소 값 검사·DXF·검토 요약·결과 리포트 계산·화면 주소) |
| `npm run check:colors`            | 컴포넌트 코드에 색 코드(`#`)가 직접 들어갔는지 검사                                                                       |
| `npm run check`                   | 위 검사 전부 + 빌드 — 푸시 전에 한 번                                                                                     |

**Node 22.18 이상(권장 24, `.nvmrc`)** 이 필요합니다. 들여쓰기는 웹 관례대로 2칸입니다(`FE/.editorconfig`, 저장소 공통 규칙은 4칸).

### 환경 변수

`.env.example` 을 `.env` 로 복사하고 값을 채웁니다. `.env*` 는 전부 git 에서 제외되고(템플릿 `.env.example` 만 올라감), **템플릿에는 실제 키를 적지 않습니다.**

| 변수                          | 용도                                                                  | 없을 때                                         |
| ----------------------------- | --------------------------------------------------------------------- | ----------------------------------------------- |
| `VITE_NAVER_MAP_KEY_ID`       | 네이버 지도 (브라우저 노출)                                           | 기본 지도(브이월드 타일 + 자체 SVG 오버레이)    |
| `VITE_MAP_TILE_URL`           | 기본 지도의 배경 타일 템플릿(`{z}` `{x}` `{y}`), `off` 면 도식 격자만 | BE 가 주는 브이월드 타일, 없으면 OpenStreetMap  |
| `VITE_MAP_TILE_URL_SATELLITE` | '위성' 레이어 타일 템플릿, `off` 면 일반 타일 사용                    | BE 가 주는 브이월드 위성, 없으면 Esri           |
| `VITE_API_BASE`               | BE 공개 API origin — FE 를 정적 호스팅하고 BE 를 직접 부를 때만       | 같은 origin 의 `/v1` (개발 서버가 BE 로 프록시) |
| `VITE_PROXY_TARGET`           | 개발·미리보기 서버가 `/v1` 을 넘길 BE 주소 (`vite.config.ts` 만 읽음) | `http://127.0.0.1:8000`                         |

공공 API 승인키(도로명주소·브이월드·건축HUB)는 FE 에 없습니다 — `../BE/.env` 에 둡니다.

## 화면

흐름: **초기화면(`/app`) → 이용화면(`/site`, 대지 요약 + '결과 보기') → 결과 리포트(`/report`) → 법규 상세(`/report/rules/:category`)**

| URL                                      | 화면                                                                                                                                                                                                          |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                                      | 소개 페이지 — 제품 소개(도면 그림·동작 방식·법규 근거·기능)와 '시작하기'. 모션은 anime.js                                                                                                                     |
| `/app`                                   | 1단계 초기화면 — 좌측 패널(헤드라인·주소 검색·서비스·이용 흐름) + 수성구 전체 지도(검색 결과 미리보기 핀)                                                                                                     |
| `/site?pnu=…`                            | 2단계 이용화면 — 검색창·주소·PNU, 대지 요약(면적·용도지역·지목·접도·공시지가·토지이용계획·건축물), 법규 검토 진행 상태 한 줄, '결과 보기'                                                                     |
| `/report?pnu=…&review=…`                 | 3단계 결과 리포트 — 머리(주소·PNU·기준일·진행 단계) · 현황 부지 · 주차 배치 결과(정지 위성 지도, 기존 건물 유지/철거 가정, 배치안 A/B/C) · 토지이용계획 · 법규 검토 체크리스트, 'PDF 저장' · 'CAD 저장 (DXF)' |
| `/report/rules/:category?pnu=…&review=…` | 법규 상세 — 항목 하나의 요약(원문 인용 또는 검증을 통과한 AI 요약) · 원문 근거 · 위임 관계                                                                                                                    |

`/app` 과 `/site` 는 같은 레이아웃(`MapShell`) 안에서 **좌측 패널만 바뀌고 지도는 그대로 이어집니다.**
주소를 고르거나 지도에서 필지를 클릭하면 `/site` 로 갑니다. 이용화면은 결과를 패널에 펼쳐 놓지 않고, 필지를 받는 즉시 법규 검토를 뒤에서 시작해
진행 단계와 상태별 개수(검토 가능 · 조건부 · 별도 확인)만 보여 줍니다. 하단 '결과 보기'는 필지를 받으면 바로 누를 수 있고(검토가 끝나기 전이어도)
`/report?pnu=…` 로, 검토가 끝났으면 `&review=<id>` 를 붙여 엽니다. BE 에 없는 필지(대기)·조회 실패면 버튼이 꺼지고 그 이유를 한 줄로 알립니다.
결과 리포트는 검토가 정해지면 주소에 `&review=<id>` 를 스스로 붙여(히스토리 교체) 새로고침·인쇄·법규 상세가 같은 검토를 다시 씁니다.
시범 지역(대구 수성구) 밖 필지도 '결과 보기'로 결과 리포트를 열 수 있습니다 — 현황 부지·토지이용계획은 그대로 보여 주고,
법규 검토·자동 배치 단계는 '해당 없음'으로 두고, 법규 검토 카드와 주차 배치 결과 카드(대지 경계만 그림)에는 검토·배치 대신 시범 지역 안내를 보여 줍니다.
`/report` 와 `/report/rules/…` 는 지도 없는 레이아웃(`ReportLayout`, 상단 바 + 전체 폭 본문)에서 그려집니다.
`/` 의 '시작하기' 는 `/app` 으로 갑니다 (소개 페이지는 지도를 띄우지 않습니다).
`?pnu=` 는 숫자 19자리만 받습니다 — 형식이 다르면 요청 없이 '올바르지 않은 요청' 안내만 보여 줍니다. `review=` 는 uuid 모양일 때만 BE 로 보냅니다(`isUuid`).
이용화면·결과 리포트·법규 상세 사이의 주소는 `src/lib/report/links.ts`(`reportHref` · `ruleDetailHref` · `siteHref`) 한 곳에서만 만듭니다.

### 결과 저장 (PDF · CAD)

- **PDF**: 결과 리포트 머리의 **'PDF 저장'**은 브라우저 인쇄 대화상자를 엽니다(`window.print()`). 대상에서 **'PDF로 저장'**을 고르세요.
  진행 중인 단계(필지 조회·법규 검토·배치 계산)가 있으면 버튼이 꺼져 있습니다. 누르면 글꼴과 지도 타일 이미지가 다 그려질 때까지(최대 8초) 기다린 뒤
  문서 제목을 `주차검토_<주소>_<기준일>` 로 바꿔 인쇄 창을 열고(브라우저가 이 제목을 저장 파일 이름으로 씀), 인쇄 창이 닫히면 되돌립니다 (`src/lib/report/print.ts`).
  A4 가로 **2쪽 구성**입니다 — 1쪽: 머리(주소·PNU·기준일·지역·진행 단계) + 3단(현황 부지 · 주차 배치 결과 · 토지이용계획 + 법규 검토),
  2쪽(`data-print="page-break"`): 법규 검토 상세(항목별 상태·기준값·요약 문장과 그 출처·근거 문서 + 별도 확인 항목 — 화면에서는 숨기고 인쇄 때만) + 데이터 안내·고지·출처.
  공통 인쇄 규칙(용지·여백, 화면 높이 고정 풀기, `data-print="hide"`·`"page-break"`)은 `src/design/print.css`, 카드별 인쇄 모양은 각 CSS 모듈의 `@media print` 에 있습니다.
  상단 바·저장 버튼(`data-print="hide"`)과 '상세 보기' 링크(카드의 인쇄 규칙)는 인쇄에서 빠집니다. 지도는 움직이는 지도 대신 정지 지도(`StaticMap`: 타일 이미지 + SVG)라 인쇄에도 그대로 나옵니다.
- **CAD**: **'CAD 저장 (DXF)'**은 **DXF R12**(ASCII) 파일을 브라우저에서 만들어 내려받습니다(서버 왕복 없음, `src/lib/export/resultDxf.ts` · `dxf.ts` · `download.ts`).
  담기는 것은 대지 경계와 지도에 그린 배치안 하나(배치안 A/B/C 중 고른 것, 기존 건물 유지/철거 가정 중 고른 쪽으로 계산한 것)와 그 기존 건물 외곽선입니다 — 파일 이름 `<주소>_배치안A.dxf`.
  PDF·DXF 모두 화면에 그린 결과를 그대로 저장합니다 (철거 가정을 보고 있으면 철거 가정 배치).
  그린 배치안이 없으면(건설 불가·계산 안 함·시범 지역 밖) 대지 경계만 `<주소>_대지경계.dxf` 로 저장하고, 대지 경계가 없으면 버튼이 꺼집니다.
  좌표는 **EPSG:5186(중부원점 TM), 단위 m** 라 지적도·현황 측량 도면 위에 그대로 겹칩니다.
  계층(`DXF_LAYERS`): `SITE_BOUNDARY`(대지 경계) · `USABLE_AREA`(가용 영역) · `AISLE`(차로) · `STALL`(주차구획) · `GATE`(출입구 — 건물로 나뉜 대지는 영역마다 하나) ·
  `EXISTING_BUILDING`(기존 건물 외곽선, 색 251 — 링마다 닫힌 폴리선) ·
  `ANNOTATION`(제목 · 좌표계 · PNU·기준일 · 참고 배치면 그 표시 · 기존 건물 처리('피해서 배치' / '철거 가정' / 정보 없음) · '참고용' 고지).

## 데이터 흐름 (전부 BE 공개 API)

```
주소 입력 ──GET /v1/address/search?q=──────▶ BE ─▶ 행정안전부 도로명주소 API → PNU·필지 중심 좌표·지원 관할 여부 (최대 10건)
/site?pnu= ─GET /v1/parcels/{pnu}───────────▶ BE ─▶ 브이월드 연속지적도(경계)·토지특성(면적·지목·도로접면·공시지가)·토지이용계획 + 건축물대장
지도 클릭 ──GET /v1/parcels/at?lng=&lat=─────▶ BE ─▶ 그 지점의 필지 전체 → /site?pnu=
법규 검토 ──POST /v1/reviews {pnu,audience}─▶ 202 {review_id} ─▶ GET /v1/reviews/{id} 를 Retry-After 간격으로 폴링 ─▶ review_result@1.0
            (/site 에서 필지를 받으면 시작 · /report?pnu= 만으로 들어오면 진행 중인 검토를 이어받거나 새로 시작 · ?review= 가 있으면 POST 없이 GET 폴링만)
배치 계산 ──POST /v1/reviews/{id}/parking-simulations ─▶ 배치안 A/B/C + GeoJSON 레이어 + 기존 건물 → 결과 리포트의 정지 지도·DXF (/report)
            (참고 배치 include_unverified=true 는 '참고 배치 보기'를 누를 때만, 철거 가정 existing_buildings="ignore" 는 '철거 가정'을 고를 때만)
법규 원문 ──GET /v1/rules/{ruleId}/basis?as_of_date= ─▶ 승인 규칙의 원문 근거·위임 관계 (/report/rules/:category)
```

- **한 번만 요청**: 검토·배치 결과는 이 탭 안에서 30분 동안 기억합니다(`review.ts` 의 `reviewForParcel` · `reviewById` · `simulationFor`). 승인된 원문은 바뀌지 않으므로 `rules.ts` 가 탭을 닫을 때까지 기억합니다.
  배치 결과는 검토 id · 참고 배치 여부 · 기존 건물(유지/철거 가정)마다 따로 기억해, 유지 ↔ 철거 가정을 오가도 두 번째부터는 바로 바뀝니다.
  이용화면 → 결과 리포트 → 법규 상세로 옮겨 다녀도 같은 필지의 검토를 다시 만들지 않고, 검토 중에 '결과 보기'를 누르면 결과 리포트가 진행 중인 검토를 이어받습니다.
  진행 중인 요청은 Promise 로 기억해 두 화면이 동시에 불러도 한 번만 보내고, 실패하면 잊어 '다시 시도'가 새로 요청합니다.

- **어댑터**: BE 응답(snake_case, `src/lib/api/beTypes.ts`)은 `src/lib/api/beAdapters.ts`(순수 함수)가 FE 모델(`types.ts`)로 바꿉니다.
  좌표는 `[lng, lat]` → `{ lat, lng }`, 경계는 MultiPolygon 중 가장 큰 폴리곤의 외곽 링, 용도지역은 `designations` 중 접함이 아닌 zoning 1·2번째.
  BE 에서 받은 응답 원문은 `src/lib/api/__fixtures__/` 에 두고 어댑터 테스트의 입력으로 씁니다.
- **오류**: BE 는 `{ error: { code, message, details? } }` 로 답합니다. `base.ts` 가 422 → BAD_REQUEST, 404 → NOT_FOUND, 429 → RATE_LIMITED,
  503 SERVICE_BUSY → BUSY, 503 UPSTREAM_UNAVAILABLE → UPSTREAM, 409 → CONFLICT, 그 밖 5xx → SERVER 로 분류하고 BE 의 고정 안내 문구를 그대로 보여 줍니다.
  주소 검색 404 ADDRESS_NOT_FOUND 는 오류가 아니라 '결과 없음'(+ BE 안내), 필지 404 PARCEL_NOT_FOUND 는 주소·좌표만 보여 주는 '대기' 화면입니다.
- **주소 검색어**: 한글·영문·숫자·공백과 `- . , ( ) [ ] ·` 만 받습니다 (`address.ts`, 기준은 BE 의 `JusoKeyword`). 그 밖의 문자가 있으면 요청하지 않고 안내하며,
  BE 가 검색어를 거절(422)하면 그 안내를 보여 줍니다. 도로명주소 API 를 쓸 수 없을 때(503 UPSTREAM_UNAVAILABLE)는 지도에서 필지를 직접 고르도록 안내합니다.
- **법규 검토**: 6항목(용도·건폐율·용적률·주차대수·주차구획·차로 폭)은 `검토 가능 / 조건부 검토 / 별도 확인 필요` 세 상태입니다.
  이용화면은 진행 단계(대지정보 → 용도지역 → 건축 법규 → 주차 기준 → 쉬운 설명)와 끝난 뒤 상태별 개수만 보여 주고(`src/pages/siteReviewSummary.ts`),
  기준값(`criteria_text`)·적용값·상태 사유(`reason_code`)·근거는 결과 리포트의 체크리스트와 항목별 '상세 보기'(법규 상세)에서 봅니다.
  항목 요약은 출처를 구분합니다 — `ai_verified`(AI 가 쓰고 원문 대조 검증을 통과한 문장)만 'AI 요약'이라 부르고, `template_fallback`(원문 인용)은 AI 라고 표시하지 않습니다.
  별도 확인 항목(도로·건축선·높이·추가 규제)과 데이터 안내, 고지는 BE 가 준 그대로입니다. 시범 지역 밖 필지는 이용화면·결과 리포트 모두 검토를 요청하지 않고 안내만 보여 줍니다.
- **배치·건설 가능 여부**: 결과 리포트가 검토 id 로 배치를 계산하고(검토가 끝난 뒤, 대지 경계가 있을 때), 배치안 A/B/C 중 추천안을 먼저 지도에 그립니다.
  주차장 설치 가능 여부가 확인되지 않아(`PARKING_USE_NOT_CONFIRMED`) BE 가 계산하지 않으면(`simulated=false`) 그 이유와 **'참고 배치 보기'** 버튼을 보여 주고,
  누를 때만 참고 배치(`include_unverified=true`)를 계산해 **'참고 배치'** 배지와 안내 문장을 붙여 그립니다 (이때 저장한 DXF 에도 참고 배치라고 적힘).
  주차장을 건설할 수 없는 토지(`eligibility.status = NOT_PERMITTED`)이면 지도에 **'주차장 건설 불가 지역'**을 표시하고 대지 경계를 붉게 그린 뒤,
  그 이유를 검토 당시의 토지이용계획(지목이 도로·하천 등 공공시설 용지인지, 용도지역의 허용 용도에 주차장이 없는지)과 근거 조문으로 설명합니다 (`src/lib/report/verdict.ts`).
  판정은 BE 가 하고 화면은 근거만 붙입니다. 이때는 참고 배치를 제안하지 않습니다.
- **기존 건물**: BE 는 대지 위 건물 외곽선(브이월드 '도로명주소 건물')을 받아 기본으로 건물 + 이격(1 m)을 배치 영역에서 빼고 계산합니다(`existing_buildings`).
  주차 배치 결과 카드는 지도 아래에 한 줄로 알립니다 — 피해 배치(동수·면적·이격) · 철거 가정 · 건물 정보를 불러오지 못함(경고) · 건물로 나뉜 영역마다 출입구(개소 수는 출입구 도형에서 셈).
  대지 위 건물이 1동 이상이면 **'기존 건물 유지' / '철거 가정'** 전환(라디오 그룹, 인쇄에서는 빠짐)을 보여 주고, 철거 가정을 고르면 `existing_buildings: "ignore"` 로 한 번 더 계산합니다
  (계산하는 동안은 유지 결과를 그대로 두고, 고른 배치안은 같은 주차 방식(각도)을, 없으면 추천안을 보여 줌). 지도에는 건물을 유지면 진한 회색, 철거 가정이면 점선으로 그리고 범례에 넣습니다.
  현황 부지 지도에는 같은 건물을 늘 '있는 건물'(진한 회색)로 그립니다. 판단은 `src/lib/report/existingBuildings.ts`(순수 함수 + 테스트).
- **요청 중단과 재시도**: 필지가 바뀌거나 화면을 떠나면 진행 중인 필지 조회와 그 화면의 기다림을 끊습니다 (`useAsync` 가 `AbortSignal` 을 넘깁니다).
  법규 검토 폴링 자체는 탭 안에서 기억되어 끝까지 이어지므로(최대 60초) 다음 화면이 결과를 이어받습니다.
  폴링 중의 일시 오류(429·503·연결 끊김)는 `Retry-After` 만큼 기다렸다가 같은 검토를 이어서 확인하고, 요청 하나는 35초에 끊습니다.
- **BE 응답을 믿지 않는 곳**: 타일 주소 템플릿은 `/v1/map/tiles/…` 모양만(`bePathTemplate`), 원문 링크는 https 만, 배치 도형·건물 외곽선은 좌표 모양이 맞는 것만 받습니다
  (기존 건물의 모르는 status 는 '정보 없음'으로, 숫자가 아닌 면적·이격은 비움).
- **PNU** = 법정동코드(10) + 산구분(1) + 본번(4) + 부번(4). 형식·상수는 `src/lib/api/types.ts`.
- 브라우저가 접속하는 외부 origin 은 지도뿐입니다 — 네이버 지도(키 설정 시). 기본 지도의 타일은 BE(`/v1/map/tiles`, 같은 origin)에서 오고,
  BE 에 브이월드 키가 없을 때만 `tile.openstreetmap.org`(위성 `server.arcgisonline.com`)에 접속합니다.
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
- 키가 없거나 인증에 실패하면 **기본 지도**로 자동 전환됩니다: 실제 지도 타일을 아래에 깔고
  그 위에 자체 SVG 로 필지 경계·마커·배치안을 그립니다(같은 웹 메르카토르 좌표계). 좌측 상단 배지로 구분됩니다.
  지적도 레이어는 네이버 지도에서만 그려집니다.
- 기본 지도의 타일 소스는 이 순서로 정합니다 (`tileConfig.ts`, 지도를 붙일 때 한 번):
  1. `.env` 에 `VITE_MAP_TILE_URL`(또는 `_SATELLITE`)을 적었으면 그 템플릿. `off` 면 타일 없이 도식 격자만 그리는 **미리보기 지도**(오프라인).
  2. 비어 있으면 BE 에 묻습니다(`GET /v1/map/config`, 2.5초 제한). BE 에 브이월드 키가 있으면 **브이월드 타일**을 BE 경유(`/v1/map/tiles/{base|satellite}/{z}/{x}/{y}`)로 씁니다.
     필지 경계가 브이월드 연속지적도라서 브이월드 지도 위에서만 건물·도로와 정확히 맞습니다. 인증키는 BE 에만 있습니다. 줌 범위(`min_zoom`·`max_zoom`)도 BE 가 알려 줍니다.
  3. BE 가 `tiles: null` 을 주거나 요청이 실패하면 OpenStreetMap(일반) · Esri(위성). 국내 지적과 6~10m 어긋나 필지 선이 건물을 가로질러 보일 수 있고,
     OSM 타일 서버는 개발·소규모용입니다.
- 하단 출처 문구와 배지 설명은 실제로 깔린 타일을 따릅니다 (`MapContext` 의 `tileProvider` → `tileCopy.ts`, 문구는 `ko.json` 의 `map.source*`·`map.tilesDesc*`).
- 지도 코드는 `src/lib/map/MapAdapter`(`types.ts`) 인터페이스 하나로 감싸져 있어, 카카오로 바꾸려면 `kakaoMap.ts` 를 만들고
  `createMapAdapter.ts` 한 곳만 바꾸면 됩니다. 클릭 지점 → 필지 규칙은 `pickParcel.ts`(지도 공급자와 무관).
- 장면(scene) 세 가지: `overview`(수성구 전체) · `site`(필지 경계 + 면적 말풍선, 위성+지적도) · `point`(좌표만 있을 때 핀).
  이용화면은 장면만 정하고 배치안 레이어는 그리지 않습니다. 배치안은 결과 리포트의 정지 지도(`src/components/report/StaticMap.tsx`,
  계산은 `src/lib/map/staticView.ts`)가 같은 타일 출처 위에 SVG 로 그립니다 — 끌거나 확대하지 않고, 화면·인쇄 폭에 맞춰 비율로 늘어납니다.
  지도 어댑터의 `setLayout()` 은 남아 있지만 지금은 부르는 화면이 없습니다.

## 우리가 직접 편집하는 곳 (계획서 5장)

| 바꾸고 싶은 것        | 고칠 곳                                                          | 예시                                                                                  |
| --------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 메인 색·배경색·둥글기 | `src/design/themes/clear-blue.css`                               | `--color-primary: #1E5EFF;` 한 줄                                                     |
| 아이콘 모양           | `public/icons/<이름>.svg`                                        | `search.svg` 를 같은 이름으로 덮어쓰기                                                |
| 아이콘 추가           | `public/icons/` + `src/design/icons.ts`                          | 파일 넣고 이름 한 줄 등록                                                             |
| 버튼 모양             | 테마 파일의 `--button-*`                                         | `--button-radius: 10px;`                                                              |
| 버튼·안내 문구        | `src/content/ko.json`                                            | `"cta": "대지 분석 시작"` — 상태·사유·배치안 코드의 한글은 `site.rules`·`site.layout` |
| 지도 경계선·배치안 색 | 테마 파일의 `--map-site-*`, `--map-layout-*`, `--map-building-*` | `--map-layout-stall-fill: #1E5EFF;`                                                   |
| 로고                  | `public/brand/logo.svg`                                          | 파일 교체                                                                             |
| 법규 기준값·근거      | FE 에 없음 — BE 의 법규 DB(검수 승인된 규칙)에서만 옵니다        | `../BE` 관리자 API                                                                    |

규칙: 색·간격·둥글기는 CSS 변수로만, 아이콘은 24×24 `currentColor` SVG, 버튼은 `Button` 컴포넌트 하나, 문구는 전부 `ko.json`.
`npm run check:colors` 로 색 코드 직접 입력 0건을 확인합니다.

## 폴더 구조

```
src/
  main.tsx / App.tsx        진입점·라우트 (ErrorBoundary 로 감싸 패널이 깨져도 빈 화면이 되지 않음)
  layouts/MapShell          상단 바 + 좌측 패널 + 우측 지도 (지도 유지) — /app · /site
  layouts/ReportLayout      상단 바 + 전체 폭 본문(자체 스크롤, 인쇄 때 상단 바 숨김) — /report · /report/rules/…
  pages/landing/            소개 페이지(/): LandingPage(구조·문구) · HeroScene · StepDemos · BasisTree · RippleGrid ·
                            sceneGeometry(그림 좌표, 순수 계산 + 테스트) · useLandingMotion(anime.js 움직임 전부)
  pages/HomePanel           1단계 초기화면 패널 (/app)
  pages/SitePanel           2단계 이용화면 패널 (대지 요약 · 법규 검토 진행 상태 · 대기/미지원 안내 · '결과 보기')
  pages/siteReviewSummary   이용화면 검토 카드의 순수 계산 (상태별 개수 · 진행 단계) + 테스트
  pages/report/             3단계 결과 리포트 ReportPage + 카드(SiteMapCard · LayoutCard · NotPermittedPanel · LandUseCard · RuleChecklistCard ·
                            PrintRuleDetails(인쇄 2쪽) · ReportFootnotes) · 법규 상세 RuleDetailPage(+ RuleDetailSections · RuleDetailSource)
  components/               Button · Icon · Badge · SearchBox · StatCard · RuleItem(+StatusBadge·BasisList·ManualCheckItem) · AsyncBlock · ErrorBoundary · Toast · TopBar
  components/map/           MapView · MapControls · ScaleBar · MapStatusBar
  components/report/        StaticMap(결과 리포트·인쇄용 정지 지도: 타일 이미지 + SVG 경계·배치안) · MapOverlays · ReportCard · ReportNotice · StepIndicator
  lib/map/                  MapAdapter(types) · naverMap · mockMap · createMapAdapter · MapContext · pickParcel · layoutAnchors · geo · mapStyle · staticView
  lib/api/                  beTypes(BE 와이어 타입) · beAdapters(BE → FE 변환) · types(FE 모델·ApiError) · base(fetch·오류 분류)
                            address · parcel · review(검토 폴링·배치 계산·탭 안 기억) · rules(법규 원문) · errors · guards · hitCache · recent · __fixtures__/
  lib/report/               links(세 화면 사이 주소) · steps(진행 단계) · pageState · summary · verdict(건설 가능 여부의 근거 — 지목 · 용도지역) ·
                            landUseRows · layoutMetrics · radioKeys(배치안·기존 건물 선택 방향키) · existingBuildings(유지/철거 가정 · 안내 문장 · 외곽선) ·
                            exportNames(PDF 문서 제목 · DXF 파일 이름·안내 줄) · print(인쇄 전 기다림 · 문서 제목)
  lib/ruleDetail/           params(법규 상세 주소의 값 검사) · format
  lib/export/               resultDxf · dxf(DXF R12 쓰기) · tm(EPSG:5186 변환) · download(파일 내려받기)
  lib/                      useAsync · content · format · landUse · cx
  design/                   tokens.css · themes/clear-blue.css(확정 디자인의 색·글꼴·둥글기) · icons.ts · base.css · print.css(PDF 저장 인쇄 규칙)
  content/ko.json           화면 문구 전부 (결과 리포트는 report.*, 법규 상세는 ruleDetail.*)
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
- **SPA 경로**: `/app`·`/site?pnu=…`·`/report?…`·`/report/rules/…` 는 클라이언트 라우트입니다. 이 저장소의 배포(`deploy/aws-lite`)는 CloudFront Function 으로 마지막 경로 조각에 점이 없으면
  `/index.html` 을 줍니다 (Custom Error Response 는 API 의 404 까지 바꿔 버려 쓰지 않습니다). Amplify 는
  rewrite `</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json)$)([^.]+$)/>` → `/index.html` (200) 을 넣습니다.
- **캐시 헤더**: `assets/*` 는 해시 파일명이므로 `Cache-Control: public,max-age=31536000,immutable`, `index.html` 은 `no-cache` 로 올리고 배포 뒤 `/index.html` 을 무효화합니다.
  ```bash
  aws s3 sync dist s3://<bucket> --exclude index.html --cache-control 'public,max-age=31536000,immutable'
  aws s3 cp dist/index.html s3://<bucket>/index.html --cache-control 'no-cache'
  aws cloudfront create-invalidation --distribution-id <id> --paths '/index.html'
  ```
- **보안 헤더**: `deploy/aws-lite/template.yaml` 의 `FeSecurityHeaders` 가 CloudFront 응답에 붙입니다. 운영 빌드를 이 CSP 아래에서 전체 흐름으로 돌려 위반 0건을 확인했습니다.
  ```
  default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https://tile.openstreetmap.org https://server.arcgisonline.com;
  font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; upgrade-insecure-requests
  ```
  인라인 스크립트·`eval` 이 없고, 인라인 스타일은 모두 CSSOM(React `style`·`el.style`)으로 넣어 `'unsafe-inline'` 이 필요 없습니다. 이미지·글꼴에 `data:`·`blob:` 을 쓰지 않습니다.
  (CAD 저장은 `blob:` 주소를 `a[download]` 로 내려받는 것이라 CSP 의 불러오기 대상이 아니고, PDF 저장은 `window.print()` 입니다 — 둘 다 CSP 를 넓힐 필요가 없습니다.)
  네이버 지도를 켜면 그 SDK·타일 호스트를 `script-src`·`img-src`·`connect-src` 에 더해야 합니다 (실제 목록은 키를 넣고 네트워크 패널에서 확인).
  (개발 서버에는 넣지 않습니다 — Vite 의 React Refresh 프리앰블이 inline script 를 씁니다.)
  기본 지도의 타일은 BE 경유(`/v1/map/tiles`)라 화면과 API 가 같은 origin 이면 `img-src 'self'` 로 충분합니다. `VITE_API_BASE` 로 BE 를 직접 부르면
  그 origin 을 `img-src` 에도 넣고, BE 에 브이월드 키 없이 OSM·Esri 로 대체해 쓸 거라면 `https://tile.openstreetmap.org https://server.arcgisonline.com` 을 더합니다.
- 네이버 지도는 배포 도메인을 NCP Application 에 등록해야 합니다. 브이월드·도로명주소 키는 BE 배포 설정(`../BE/docs/DEPLOY_AWS.md`)을 따릅니다.

## 데이터

- FE 에는 미리 저장된 필지·주소가 없습니다. 검색·조회 결과는 전부 BE 에서 옵니다.
  BE 가 **모의 공공 API**(`mockserver`)로 돌면 BE 모의 데이터셋의 예시 필지 12건만 검색·조회되고 실제 주소는 "검색 결과 없음"이 됩니다.
  실제 주소를 쓰려면 BE 에 도로명주소·브이월드·건축HUB 승인키를 넣고 모의 서버 주소(`*_URL`)를 비워 두세요(`../BE/.env.example`).
  BE 가 `is_example: true` 로 표시한 필지(모의 데이터)는 주소 옆에 '예시 데이터' 배지가 붙습니다.
- 이용화면 하단에 출처(`sources`)·조회일을, 검토 카드에는 검토 기준일·엔진 버전을 표시합니다. 법규 값은 BE 가 검수 승인한 규칙에서만 옵니다.

## 다음 단계

5. 모바일 다듬기(하단 시트 제스처) · AWS 배포
