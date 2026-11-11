# APP (Auto Parking Plan) — 백엔드 + AI

주소를 입력하면 그 땅에 적용되는 **건축·주차 법규를 근거(법률 → 시행령 → 조례)와 함께 정리**하고,
주차장을 지을 수 있는 부지라면 **법정 치수로 계산한 주차장 배치안을 지도용 GeoJSON** 으로 돌려주는 웹 서비스의 서버입니다.
로그인·결제 없이 누구나 쓰는 공개 API 와, 검수자용 관리자 API 로 구성됩니다. (프론트엔드는 별도)

- 1차 지원 지역: **대구광역시 수성구**
- 실행 환경: macOS · Windows · Linux, **JDK 21** / 운영: AWS (ECS Fargate + RDS PostgreSQL + S3)
- 스택: Spring Boot 4.1 (Spring MVC, Data JPA/Hibernate 7, Flyway), Jackson 3, JTS·Proj4J(배치 기하), jsoup·PDFBox(법령 원문), Anthropic Java SDK
- DB: 로컬·테스트 **H2**(설치 불필요, `var/app.mv.db`), 운영 **PostgreSQL 16**
- 설정은 환경변수 또는 `.env` (`DATABASE_URL`, `ADMIN_TOKEN`, `LLM_PROVIDER` …, [`.env.example`](.env.example))

## 무엇을 하나요

1. **주소 검색** → 필지(PNU) 확정 → 대지면적·지목·용도지역·경계 조회 (도로명주소·브이월드 API)
2. **법규 검토** — 용도지역별 건축 가능 용도, 건폐율·용적률(참고 면적 계산), 주차구획·차로 폭, 별도 확인 항목(도로·건축선·높이·특화구역)
   - 결과는 `검토 가능 / 조건부 검토 / 별도 확인 필요` 세 상태와 근거 원문으로 답하고, 허가 여부를 단정하지 않습니다.
3. **쉬운 설명 (AI)** — 확정된 검토 결과를 토지소유주·설계 실무자용 문장으로 바꾸고, **원문과 대조하는 사후 검증을 통과한 문장만** 보여줍니다.
4. **주차장 배치 시뮬레이션** — 조례상 주차장을 검토할 수 있는 부지에서 직각·60°·45°·평행 배치안(주차구획·차로·출입구)을 계산합니다.

원칙: 법규 값은 수집한 **원문에 그대로 있는 것만** 쓰고, **사람이 승인한 규칙만** 검토에 씁니다. AI 는 법규를 판단하지 않습니다.

## 빠르게 실행하기

아래 명령은 모두 `BE/` 폴더에서 실행합니다. 윈도우는 `./gradlew` 대신 `gradlew.bat` 를 씁니다. JDK 21 이 없으면 Gradle 이 자동으로 받습니다.
외부 API 키 없이도 **모의 공공 API + 실제 수집한 법규 원문**으로 전체 흐름을 돌릴 수 있습니다.

### 1) 빌드 + 테스트

```bash
./gradlew build
```

실행 파일은 `server/build/libs/app.jar` 입니다.

### 2) 한 번에 검증 (권장)

모의 공공 API 서버와 백엔드(bootstrap → serve)를 실제 프로세스로 띄워 전체 흐름을 점검합니다 (예시 필지 10개 × 조건 2개, 오류·보안·검수 흐름, 점검 543건).

```bash
java -jar server/build/libs/app.jar e2e-verify --report var/e2e_report.json
```

별도 검토 워커 프로세스(AWS 구성과 같음)로 돌리려면 `--executor worker`, PostgreSQL 로 돌리려면 `E2E_DATABASE_URL=postgresql://app:app@localhost:5432/app` 를 줍니다.

### 3) 직접 띄워 보기

터미널 1 — 모의 공공 API (도로명주소·브이월드·건축HUB 응답 재현, 예시 필지 12개, `data/mock/parcels.json`. 배경지도 타일은 타일 경계선만 있는 빈 그림):

```bash
java -jar server/build/libs/app.jar mockserver --port 8090
```

터미널 2 — DB 준비(원문 적재 + 기록된 추출 결과 + 개발용 일괄 승인) 후 API 서버. 맥/리눅스:

```bash
export JUSO_API_KEY=mock JUSO_BASE_URL=http://127.0.0.1:8090 VWORLD_API_KEY=mock VWORLD_DATA_URL=http://127.0.0.1:8090 VWORLD_NED_URL=http://127.0.0.1:8090 VWORLD_WMTS_URL=http://127.0.0.1:8090 BUILDING_HUB_KEY=mock BUILDING_HUB_URL=http://127.0.0.1:8090 LLM_PROVIDER=fake ADMIN_TOKEN=local-admin-token
```

```bash
java -jar server/build/libs/app.jar bootstrap --approve-as dev-reviewer --ack-warnings --confirm-transcriptions
```

```bash
java -jar server/build/libs/app.jar serve --port 8000
```

윈도우(PowerShell)는 환경변수를 `$env:JUSO_API_KEY="mock"` 처럼 넣거나, 같은 값을 `.env` 파일에 적어 둡니다 (`.env.example` 참고).

확인: <http://127.0.0.1:8000/docs> (API 문서, 운영에서는 꺼짐), `curl http://127.0.0.1:8000/healthz`

### 4) Docker (PostgreSQL + 별도 워커 + 모의 공공 API)

```bash
docker compose up --build
```

```bash
docker compose run --rm bootstrap
```

모든 컨테이너가 같은 이미지(`app.jar`)를 씁니다 — 모의 공공 API 도 `app.jar mockserver` 입니다.

## 운영·개발 명령 (CLI)

`java -jar app.jar <명령>`

| 명령 | 하는 일 |
|---|---|
| `serve [--host] [--port]` | API 서버 (기본 127.0.0.1:8000) |
| `worker [--poll 1.0]` | 검토 작업 워커 (`REVIEW_EXECUTOR=worker`) |
| `bootstrap [--approve-as 이름]` | 관할 + 원문 적재 + 기록된 추출 결과 적재 (+ 개발용 일괄 승인, 운영에서는 거부) |
| `ingest-sources [--dir]`, `load-extractions [--dir]` | 원문 번들·추출 결과만 적재 |
| `extract --document-key K` | LLM 으로 규칙 추출 (LLM 키 필요) |
| `pending`, `approve RULE_ID --reviewer 이름` | 검수 대기 목록, 규칙 승인 |
| `refresh-sources` | 국가법령정보 Open API 로 원문 재수집 (매일, `LAWGO_OC` 필요) |
| `init-db` | 스키마 생성 (Flyway) |
| `check-config` | 설정만 검사 (운영 배포 전 확인: 약한 `ADMIN_TOKEN`·http 외부 주소면 종료 코드 2) |
| `mockserver [--host] [--port] [--slow-delay] [--fixtures]` | 모의 공공 API 서버 (예시 필지 12개, 인증키 `invalid` 는 인증 오류, `slow` 필지는 지연 응답) |
| `e2e-verify [--report] [--executor thread\|worker] [--keep]` | 종단 검증 — 모의 서버 + bootstrap + serve(+worker) 를 실제 프로세스로 띄워 점검 (`E2E_DATABASE_URL` 로 PostgreSQL) |
| `fetch-lawgo-sources`, `fetch-lawgo-supplements` | (개발용) law.go.kr 화면에서 원문 번들·보충 자료(별표 PDF·표 이미지) 수집 → `data/legal_sources` |
| `build-seed-extractions` | (개발용) 원문 번들에서 규칙형 추출기로 `data/seed/extractions/*.json` 을 다시 만든다 |

## API

공개 API(비로그인)와 관리자 API(`X-Admin-Token` 헤더). 자세한 요청·응답은 서버의 `/docs`(Swagger, 운영에서는 꺼짐).

| 공개 | |
|---|---|
| `GET /v1/address/search?q=` | 주소 후보 (도로명, 지번, PNU, 좌표, 지원 여부) |
| `GET /v1/parcels/{pnu}` | 필지 — 대지면적, 지목, 용도지역·지구·구역, GeoJSON 경계, 출처·조회시각 (`?refresh=true` 는 10분에 한 번만 외부 재조회) |
| `GET /v1/parcels/at?lng=&lat=` | 지도 클릭 → 필지 |
| `POST /v1/reviews` | 검토 시작 → 202 `{review_id}` (`pnu`, `as_of_date`, `audience`, `inputs`) |
| `GET /v1/reviews/{id}` | 진행 중이면 단계별 진행 상태, 끝나면 검토 결과 전체 (`review_result@1.0`) |
| `GET /v1/rules/{rule_id}/basis` | 근거 상세 — 근거 트리 + 원문 조문 + 국가법령정보센터 링크 |
| `GET /v1/regions` | 지원 지역 |
| `POST /v1/reviews/{id}/parking-simulations` | 주차장 배치 시뮬레이션 (지도 오버레이용 GeoJSON). 주차장 설치 가능 여부(`eligibility.status`)가 미확인(`MANUAL_CHECK`)이면 계산을 보류하고(`include_unverified=true` 로 참고 배치 요청 가능), 건설 불가(`NOT_PERMITTED`: 지목이 도로·하천 등 공공시설 용지이거나 승인된 용도 목록에 주차장 관련 용도가 없음)이면 계산하지 않고 경고 문구(`message`, 사유 코드 `PARKING_NOT_PERMITTED`)를 돌려준다 |
| `GET /v1/map/config` | 화면이 쓸 배경지도 타일 소스 — 브이월드 키가 있으면 `{"tiles": {"provider": "vworld", "normal", "satellite", "min_zoom", "max_zoom"}}`, 없으면 `{"tiles": null}` |
| `GET /v1/map/tiles/{layer}/{z}/{x}/{y}` | 배경지도 타일 이미지 (`layer` = `base`·`satellite`, 줌 6~19, 표준 XYZ 번호). 브이월드 타일을 서버 키로 대신 받아 준다 — `Cache-Control: public, max-age=86400` |
| **관리자** | |
| `GET /admin/extractions`, `GET /admin/rules`, `GET /admin/rules/{id}` | 추출 결과·검수 대기 규칙·원문 하이라이트 + 원장 초안 |
| `POST /admin/rules/{id}/approve\|reject\|edit` | 규칙 승인·반려·값 수정 |
| `POST /admin/ledgers/{id}/facts/{fact_key}`, `.../dismissals`, `.../approve` | 사실 원장 수정·후보 제외·승인 |
| `POST /admin/documents/{id}/refetch`, `POST /admin/extractions/run` | 원문 재수집(`LAWGO_OC`), AI 추출 실행(LLM) |
| `POST /admin/zones` | L4 특화구역 수동 등록 (고시, 경계 또는 PNU 목록) |
| `GET /admin/verifications/{report_id}`, `GET /admin/alerts`, `POST /admin/explanations/purge` | 사후 검증 보고서, 검수 알림, 설명 캐시 삭제 |

오류는 모두 `{"error": {"code", "message", "details"?}}` 형식입니다 (요청 제한 429 + `Retry-After`, 본문 상한 413, 대기열 상한 503).

배경지도 타일을 서버가 대신 받아 주는 이유: 필지 경계(브이월드 연속지적도)는 브이월드 배경지도 위에서만 건물·도로와 정확히 맞습니다 (OpenStreetMap·Esri 타일은 국내 지적과 6~10m 어긋남). 브이월드 타일 주소에는 인증키가 들어가므로 브라우저가 직접 부르지 않고 `/v1/map/tiles` 를 거칩니다. 타일 오류: 번호·레이어가 틀리면 422 `INVALID_REQUEST`, 그 타일이 없거나(서비스 제공영역 밖) 키가 설정되지 않았으면 404 `TILE_NOT_FOUND`, 브이월드 장애·시간 초과·키 오류는 503 `UPSTREAM_UNAVAILABLE`. 타일 주소는 `VWORLD_WMTS_URL`(기본 `https://api.vworld.kr`)로 바꿉니다 — 모의 서버를 쓸 때는 다른 브이월드 주소와 함께 모의 서버로 돌려야 합니다 (안 돌리면 모의 키로 실제 브이월드를 불러 타일이 전부 404).

## 구조

Gradle 멀티 모듈입니다.

| 모듈 | 내용 |
|---|---|
| `common` | JSON 직렬화·해시, 반올림, 문자열 공백·코드포인트 도우미, HTTP 전송(응답 크기·시간 상한) |
| `external` | 도로명주소·브이월드·건축HUB 클라이언트, PNU, 용도지역 코드 |
| `legal` | 국가법령정보 원문 수집·해석 (DRF XML, 화면 HTML, 조문 구조), 수집 도구 |
| `parking` | 주차장 배치 시뮬레이션 엔진 |
| `ai` | LLM 호출(Anthropic·OpenAI·fake), 사후 검증기, JSON Schema 검증, 프롬프트·스키마 자원 |
| `db` | JPA 엔티티 22개·저장소, Flyway 마이그레이션 (H2·PostgreSQL) |
| `server` | Spring Boot 앱: API, 검토 엔진·설명·추출·검수 서비스, 보안 필터, CLI, 모의 공공 API 서버·종단 검증 도구 |

`data/` — 법령 원문 번들(`legal_sources`), 기록된 추출 결과·관할(`seed`), 모의 공공 API 의 예시 필지(`mock`).
`external`·`legal`·`parking`·`ai`·`common` 은 Spring 에 의존하지 않는 순수 자바 라이브러리입니다.

이 백엔드는 파이썬(FastAPI) 구현을 자바로 옮긴 것입니다 — 이식 규칙·알려진 차이는 [`docs/PORTING.md`](docs/PORTING.md), 대조 검증 기록은 [`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## 보안

요청 제한(IP·경로 묶음별 토큰 버킷, 429 + Retry-After — 경로 변형·%인코딩에도 같은 묶음), 본문 크기 제한(청크 전송 포함 413), 관리자 토큰 상수 시간 비교 + 대입 잠금,
X-Forwarded-For 위조 방지(`TRUSTED_PROXY_HOPS`, 서버는 전달 헤더로 연결 주소를 바꾸지 않음), 보안 헤더·운영 HSTS,
운영에서 API 문서 비공개·약한 관리자 토큰이나 http 외부 API 주소면 시작 거부, 저장형 XSS 차단(http/https 링크만), 구역 경계 GeoJSON 검사,
계산식 화이트리스트 파서, 오류 응답에 내부 정보 미노출(Tomcat 단계 오류 포함), 인증키 로그 가림, 외부 응답 크기·시간 상한, XML 외부 엔티티·DTD 미처리.
컨테이너는 non-root·읽기 전용 루트 파일시스템, AWS 는 WAF·HTTPS·최소 권한 IAM (`deploy/aws`, [`docs/DEPLOY_AWS.md`](docs/DEPLOY_AWS.md)). 자세한 점검 기록은 [`docs/SECURITY.md`](docs/SECURITY.md).

## 프론트엔드(FE/) 연결

FE(React/Vite, `../FE`)는 이 서버의 공개 API(`/v1/...`)만 호출합니다. 개발 중에는 Vite 개발 서버가 `/v1` 을 이 서버(기본 `http://127.0.0.1:8000`,
`VITE_PROXY_TARGET` 로 변경)로 프록시하므로 브라우저 입장에서는 같은 origin 입니다. FE 를 따로 배포(정적 호스팅)하고 이 서버를 직접 부르게 하려면
`CORS_ORIGINS` 에 FE 의 origin 을 넣고(기본값에 `http://localhost:5173` 포함, AWS 는 `cors_origins` 변수) FE 빌드에 `VITE_API_BASE` 로 이 서버 주소를 줍니다.
FE 가 쓰는 호출: 주소 검색 → 필지 → `POST /v1/reviews` 폴링 → `POST /v1/reviews/{id}/parking-simulations`. 자세한 대응은 [`../FE/README.md`](../FE/README.md).

## 저장소에 두지 않는 것

API 키·토큰은 `.env`(미추적) 또는 AWS Secrets Manager 에만 둡니다. 로컬 DB·로그·검증 보고서는 `var/` (미추적).
