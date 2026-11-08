# APP (Auto Parking Plan) — 백엔드 + AI, 스프링판

`BE/`(파이썬 FastAPI)와 **같은 HTTP API·같은 JSON·같은 판단 결과**를 내는 Spring Boot 구현입니다.
주소를 입력하면 그 땅에 적용되는 건축·주차 법규를 근거(법률 → 시행령 → 조례)와 함께 정리하고,
주차장을 지을 수 있는 부지라면 법정 치수로 계산한 주차장 배치안을 지도용 GeoJSON 으로 돌려줍니다.
무엇을 하는 서비스인지, 법규 데이터 원칙은 [`BE/README.md`](../BE/README.md)와 같습니다.

- 실행 환경: macOS · Windows · Linux, **JDK 21** / 운영: AWS (ECS Fargate + RDS PostgreSQL + S3)
- 스택: Spring Boot 4.1 (Spring MVC, Data JPA/Hibernate 7, Flyway), Jackson 3, JTS·Proj4J(배치 기하), jsoup·PDFBox(법령 원문), Anthropic Java SDK
- DB: 로컬·테스트 **H2**(설치 불필요, `var/app.mv.db`), 운영 **PostgreSQL 16**
- 환경변수 이름·기본값은 파이썬판과 같습니다 (`DATABASE_URL`, `ADMIN_TOKEN`, `LLM_PROVIDER` …). `.env` 도 같은 방식으로 읽습니다.

## 빠르게 실행하기

아래 명령은 모두 `BE-spring/` 폴더에서 실행합니다. 윈도우는 `./gradlew` 대신 `gradlew.bat` 를 씁니다. JDK 21 이 없으면 Gradle 이 자동으로 받습니다.

### 1) 빌드 + 테스트

```bash
./gradlew build
```

실행 파일은 `server/build/libs/app.jar` 입니다.

### 2) 한 번에 검증 (권장)

파이썬판의 종단 검증 스크립트가 같은 모의 공공 API·같은 법령 원문으로 **스프링 서버 프로세스**를 띄워 같은 점검을 돌립니다.
파이썬 환경은 `BE/README.md` 의 설치 절차로 준비합니다.

```bash
cd ../BE && python scripts/e2e_verify.py --server spring --report ../BE-spring/var/e2e_report.json
```

### 3) 직접 띄워 보기

터미널 1 — 모의 공공 API (파이썬판, 예시 필지 12개):

```bash
cd ../BE && python -m mockserver --port 8090
```

터미널 2 — DB 준비(원문 적재 + 기록된 추출 결과 + 개발용 일괄 승인) 후 API 서버. 맥/리눅스:

```bash
export JUSO_API_KEY=mock JUSO_BASE_URL=http://127.0.0.1:8090 VWORLD_API_KEY=mock VWORLD_DATA_URL=http://127.0.0.1:8090 VWORLD_NED_URL=http://127.0.0.1:8090 BUILDING_HUB_KEY=mock BUILDING_HUB_URL=http://127.0.0.1:8090 LLM_PROVIDER=fake ADMIN_TOKEN=local-admin-token
```

```bash
java -jar server/build/libs/app.jar bootstrap --approve-as dev-reviewer --ack-warnings --confirm-transcriptions
```

```bash
java -jar server/build/libs/app.jar serve --port 8000
```

윈도우(PowerShell)는 환경변수를 `$env:JUSO_API_KEY="mock"` 처럼 넣거나, 같은 값을 `.env` 파일에 적어 둡니다 (`.env.example` 참고).

확인: <http://127.0.0.1:8000/docs> (API 문서, 운영에서는 꺼짐), `curl http://127.0.0.1:8000/healthz`

### 4) Docker (PostgreSQL + 별도 워커 포함)

```bash
docker compose up --build
```

```bash
docker compose run --rm bootstrap
```

모의 공공 API 컨테이너는 파이썬판(`../BE`) 이미지를 씁니다.

## 운영 명령 (CLI)

`java -jar app.jar <명령>` — 파이썬판 `python -m app.cli <명령>` 과 같은 명령·옵션·출력입니다.

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
| `check-config` | 설정만 검사 (운영 배포 전 확인: 약한 `ADMIN_TOKEN` 이면 종료 코드 2) |
| `fetch-lawgo-sources`, `fetch-lawgo-supplements` | (개발용) law.go.kr 화면에서 원문 번들·보충 자료(별표 PDF·표 이미지) 수집 → `data/legal_sources` (파이썬판 `scripts/fetch_lawgo_*.py` 와 같은 옵션·출력) |
| `build-seed-extractions` | (개발용) 원문 번들에서 규칙형 추출기로 `data/seed/extractions/*.json` 을 다시 만든다 (파이썬판 `scripts/build_seed_extractions.py` 와 같은 바이트) |

## API

파이썬판과 같습니다 — 공개 API 8개(`/v1/...`)와 관리자 API 14개(`/admin/...`, `X-Admin-Token`), `/healthz`. 목록은 [`BE/README.md`](../BE/README.md#api-요약) 와 서버의 `/docs`.

## 구조

Gradle 멀티 모듈입니다. 모듈별 파이썬 원본 대응과 이식 규칙은 [`docs/PORTING.md`](docs/PORTING.md).

| 모듈 | 내용 |
|---|---|
| `common` | 파이썬과 같은 결과를 내기 위한 도우미 (JSON 직렬화·해시, 반올림, 문자열 공백·코드포인트), HTTP 전송 |
| `external` | 도로명주소·브이월드·건축HUB 클라이언트, PNU, 용도지역 코드 |
| `legal` | 국가법령정보 원문 수집·해석 (DRF XML, 화면 HTML, 조문 구조), 수집 도구 |
| `parking` | 주차장 배치 시뮬레이션 엔진 |
| `ai` | LLM 호출(Anthropic·OpenAI·fake), 사후 검증기, JSON Schema 검증, 프롬프트·스키마 자원 |
| `db` | JPA 엔티티 22개·저장소, Flyway 마이그레이션 (H2·PostgreSQL) |
| `server` | Spring Boot 앱: API, 검토 엔진·설명·추출·검수 서비스, 보안 필터, CLI |

`data/` 는 파이썬판과 같은 법령 원문 번들·기록된 추출 결과입니다.

## 보안

파이썬판 보안 점검([`BE/docs/SECURITY.md`](../BE/docs/SECURITY.md))의 보완 사항을 모두 같은 방식으로 옮겼습니다:
요청 제한(IP·경로 묶음별 토큰 버킷, 429 + Retry-After), 본문 크기 제한(청크 전송 포함 413), 관리자 토큰 상수 시간 비교 + 대입 잠금,
X-Forwarded-For 위조 방지(`TRUSTED_PROXY_HOPS`, 서버는 전달 헤더로 연결 주소를 바꾸지 않음), 보안 헤더·운영 HSTS,
운영에서 API 문서 비공개·약한 관리자 토큰이면 시작 거부, 저장형 XSS 차단(http/https 링크만), 구역 경계 GeoJSON 검사,
계산식 화이트리스트 파서, 오류 응답에 내부 정보 미노출(Tomcat 단계 오류 포함), 인증키 로그 가림.
컨테이너는 non-root·읽기 전용 루트 파일시스템, AWS 는 WAF·HTTPS·최소 권한 IAM (`deploy/aws`, [`docs/DEPLOY_AWS.md`](docs/DEPLOY_AWS.md)).

## 파이썬판과 다른 점

- 로컬 DB 가 SQLite 대신 H2 파일 DB 입니다. `DATABASE_URL` 은 `jdbc:…` 외에 파이썬판 형식(`postgresql+psycopg://…`, `sqlite:///경로`)도 받습니다.
- 스키마는 `create_all` 대신 Flyway 마이그레이션으로 만듭니다. 파이썬판이 만든 기존 PostgreSQL 스키마는 V1 기준선으로 그대로 씁니다.
- 여러 검토가 같은 원장·설명 캐시를 동시에 만들 때, 항목별로 따로 커밋하고 충돌하면 저장된 것을 다시 읽습니다 (파이썬판은 같은 트랜잭션 안의 savepoint).
- 목록 순서(규칙 값·근거·원장 사실 등)를 저장 순서로 고정했습니다. 행 id 를 시간순 UUID(v7)로 만들고 id 순으로 읽습니다.
  파이썬판은 순서를 정하지 않아 DB 실행 계획에 따라 달랐습니다 (예: SQLite 에서는 '검토 가능한 주요 용도' 목록이 값 키 문자열 순 2.1, 2.10, …, 2.2).
- 모의 공공 API 서버와 종단 검증 스크립트는 파이썬판 것을 함께 씁니다.
