# 보안 (스프링판)

이식 전 파이썬판(FastAPI, git 이력 커밋 d70ca97 의 `BE/`)에서 2026-11-06 실제 모의 공격으로 찾은 문제와 보완 조치(1장 A1~A16)를 모두 같은 동작으로
옮겼고, 종단 검증(`app.jar e2e-verify`)의 보안 단계가 실제 서버 프로세스에 공격 요청을 보내 같은 결과를 확인한다. 2026-11-09 에는 스프링판 자체를 다시 점검했다(4장).

## 1. 애플리케이션 — 이식 전 점검에서 찾은 문제와 구현

| # | 문제 | 구현 |
| --- | --- | --- |
| A1 | 요청 제한 없음 | `server.security.TokenBucketLimiter` + `RequestGuardFilter` (서블릿 필터). IP·경로 묶음별 분당 허용량 (검토 생성 10, 배치 30, 주소 검색 30, 필지 60, 지도 타일 1200·순간 300, 그 밖 240), 초과 시 429 + `Retry-After`. `/healthz`·OPTIONS 제외 |
| A2 | `?refresh=true` 로 외부 API 반복 호출 | `ParcelService.getParcel`: 같은 필지는 `PARCEL_REFRESH_MIN_INTERVAL_S`(600초) 안에 다시 부르지 않음 |
| A3 | X-Forwarded-For 위조로 제한·잠금 회피 | `server.forward-headers-strategy=none` (Tomcat 이 전달 헤더로 연결 주소를 바꾸지 않음) + `ClientIp`: `TRUSTED_PROXY_HOPS`(ALB=1)만큼 X-Forwarded-For 뒤에서 센 값만 믿음 |
| A4 | 관리자 토큰 무차별 대입 | `AuthFailureGuard`: IP 별 10회 실패 → 15분 잠금 (맞는 토큰도 거부). 운영에서 32자 미만·`change-me`·단조로운 토큰이면 시작 거부 (`AppSettings`, `check-config` 종료 코드 2) |
| A5 | 비ASCII 토큰 → 500 | `AdminAuthInterceptor`: UTF-8 바이트로 `MessageDigest.isEqual` 상수 시간 비교 → 401 |
| A6 | 저장형 XSS (`javascript:` 링크) | 입력: `/admin/zones` 의 `notice_url` 은 http/https 만. 출력: 근거 링크는 모두 `Sanitize.safeUrl` 을 거친다 |
| A7 | 본문 크기 제한 없음 | `RequestGuardFilter`: 공개 64KB, 관리자 2MB. Content-Length 와 실제로 읽은 양(청크 전송) 둘 다 확인 → 413. 숫자가 아닌 Content-Length 는 Tomcat 이 거절하고 `JsonErrorReportValve` 가 같은 JSON 400 으로 답함 |
| A8 | 운영에서 문서·서버 정보 노출 | 운영(`APP_ENV=prod`)에서 springdoc(`/docs`, `/openapi.json`) 끔, `Server` 헤더 없음, `/healthz` 는 `{"status":"ok"}` 만 |
| A9 | 보안 헤더 없음 | `SecurityHeadersFilter`: nosniff, `X-Frame-Options: DENY`, CSP `default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, COOP, 관리자 `Cache-Control: no-store`, 운영 HSTS |
| A10 | 국가법령정보 `OC` 노출 가능 | DRF 클라이언트 오류 메시지에 URL 없음, 요청 로그는 `Redact` 로 가림, 기본 주소 https |
| A11 | 관리자 L4 구역 입력 검증 부족 | `Sanitize.validateZoneGeometry` (Polygon/MultiPolygon, 유한한 대한민국 경위도, 꼭짓점 5만 개, 중첩 4단계, JTS 유효성·넓이 > 0), PNU 19자리, 목록 길이·`limit` 상한 |
| A12 | NaN·Infinity 수치 | 본문 JSON 의 NaN 은 해석 단계에서 422, 수치 범위·경위도 범위·각도 8개 상한 (`api.Req`) |
| A13 | 실패한 검토의 내부 오류 상세 노출 | 공개 응답은 오류 코드 + 고정 문구만 (상세는 로그·`/admin/alerts`) |
| A14 | 검토 대기열 무한 | 대기 500건(`MAX_PENDING_REVIEWS`) 넘으면 503 `SERVICE_BUSY` + `Retry-After: 30` |
| A15 | 오류 형식 불일치·정보 노출 | `ApiExceptionHandler` + `JsonErrorController` + Tomcat `JsonErrorReportValve`: 404/405/400/413/500 모두 `{"error": {code, message}}`, 처리 안 된 예외는 `INTERNAL_ERROR` 고정 문구 (스택·예외 이름 없음) |
| A16 | 계산식 평가 | `RulesLogic.evalFormula`: 허용 문법(변수 4개, + - * /, 단항 -, ceil·floor·min·max)만 받는 파서. 코드 실행 경로 없음, 0 나누기·무한대는 '입력 필요'로, 500자 상한. 파이썬 결과와 대조 테스트 |

## 2. 배포 설정

`deploy/aws`, `docker-compose.yml`, `Dockerfile`, `.github/workflows/be-ci.yml`:
AWS 는 항상 `APP_ENV=prod`, compose 포트는 127.0.0.1 에만·관리자 토큰 기본값 없음, ALB WAF(IP 당 요청 수 + AWS 관리형 공통 규칙, 선택적 `/admin*` IP 허용 목록),
운영 HTTPS 강제, 컨테이너 non-root(UID 10001)·읽기 전용 루트·capabilities 제거, ECR 태그 변경 불가·푸시 시 검사, 최소 권한 IAM,
S3 TLS 전용·DB `sslmode=require`, 빌드 문맥에서 비밀 파일 제외, CI 읽기 전용 토큰·액션 SHA 고정.
JVM 시작 동안 ECS 헬스체크 유예 120초, 작업 메모리 2GB(`task_memory`).

## 3. 남은 위험

- 요청 제한은 컨테이너(ECS 작업) 단위로 센다 — 여러 작업 전체를 묶는 제한은 ALB 앞의 WAF rate-based 규칙이 맡는다
- 공개 응답의 설명·근거 문장은 프론트엔드가 **텍스트로** 그려야 한다 (HTML 로 넣으면 저장형 XSS 가 된다; 링크는 http(s) 만 내려간다)
- 외부 공공 API 와 국가법령정보 원문은 신뢰할 수밖에 없는 입력이다 — 수집한 원문은 해시·버전으로 보관하고, 값은 원문에 있는 것만 쓴다
- 실제 AWS·실제 API 키로는 미검증 (모의 서버·SDK 대조 테스트로만 확인)
- 4.3 에 스프링판 고유의 남은 것을 적었다.

## 4. 스프링판 자체 점검 (2026-11-09, 로컬)

파이썬판에서 옮긴 조치 위에, 스프링판만의 구조(서블릿 컨테이너·JDK HTTP 클라이언트·H2·JPA)에서 생기는 문제를 로컬에서 다시 점검했다.
방법: 소스 검토(server 와 라이브러리 모듈 전체), 실행 중인 서버에 변형 요청 60여 건(경로 변형·Accept·헤더·본문 한계·구문 분석기 한계),
의존성 취약점 조회(OSV, 실행 클래스패스 184개), CI 액션 고정 SHA·Gradle 래퍼 체크섬 확인, 저장소·git 이력의 비밀값 패턴 검색.

### 4.1 고친 것

| # | 문제 (확인 방법) | 조치 |
| --- | --- | --- |
| B1 | **요청 제한 묶음 우회** — 필터가 원시 URI 로 경로를 분류해 `/v1;a/reviews`·`/v1/reviews;a`·`/v1/revie%77s` 가 검토 핸들러로 가면서 기본 묶음(240/분)으로 세어졌다 (검토 버킷이 다 찬 뒤에도 변형 주소는 422 로 처리됨을 실제 요청으로 확인). 관리자 경로 판별(본문 상한 2MB, `Cache-Control: no-store`)도 `/%61dmin/…` 에서 빠졌다 | `security.RoutePath`: 컨테이너가 라우팅에 쓰는 서블릿 경로(%xx 해제·`;` 매개변수 제거)로 분류. `RequestGuardFilter`·`SecurityHeadersFilter` 공통. 회귀 테스트 `SecurityApiTest.rateLimitClassificationFollowsRoutingNotRawUri` |
| B2 | `Accept: text/html` 등 JSON 을 받지 않는 요청에 본문 없는 406 — "모든 오류는 같은 JSON" 약속 위반 | 내용 협상에서 Accept 를 무시하고 항상 JSON (FastAPI 와 같음). `/error` 를 직접 부르면 가짜 500 대신 404 |
| B3 | **H2 AUTO_SERVER 가 모든 인터페이스에 열림** — 로컬 파일 DB 를 여러 프로세스(API·워커·CLI)가 열 때 H2 가 띄우는 TCP 서버(`-tcpAllowOthers`, sa/빈 비밀번호, 무작위 키로만 보호)가 `*:포트` 로 바인드됨을 `lsof` 로 확인 | 시작 시 `h2.bindAddress=127.0.0.1` (`Cli`). 운영(`APP_ENV=prod`)에서 H2 를 쓰면 경고 — 운영 DB 는 PostgreSQL |
| B4 | 운영에서 외부 API·LLM 주소를 `http://` 로 주면 인증키(`confmKey`·`x-api-key`·`OC`)가 평문으로 나간다 (코드 어디서도 거부하지 않았음) | `APP_ENV=prod` 에서 `JUSO_BASE_URL`·`VWORLD_*_URL`·`BUILDING_HUB_URL`·`LAWGO_BASE_URL`·`ANTHROPIC_BASE_URL`·`OPENAI_BASE_URL` 은 https 만, 아니면 시작 거부 (이 컴퓨터 안 127.0.0.1·localhost 는 예외 — 종단 검증이 운영 설정으로 모의 서버를 쓴다) |
| B5 | 외부 응답 본문의 크기·시간 상한 없음 — `BodyHandlers.ofByteArray` 는 무제한이고 요청 timeout 은 응답 헤더까지만 적용 → 느리게 흘려보내거나 거대한 본문을 주는 원천이 요청 스레드·힙을 붙잡는다 | `JdkHttpTransport`: 본문 32MB 상한(Content-Length 선검사 + 받으면서 계수, 넘으면 끊고 IOException), 헤더+본문 전체를 timeout×2 안에 (넘으면 `HttpTimeoutException`). 테스트 `JdkHttpTransportTest` |
| B6 | XML 해석기: 외부 엔티티는 막았지만 DTD 처리가 켜져 있어 내부 엔티티 폭발이 JDK 기본 한도에만 의존했고, 클래스패스에 다른 StAX 구현이 들어오면 보호가 사라졌다 | `SUPPORT_DTD=false` + `XMLInputFactory.newDefaultFactory()`(JDK 구현 고정), 풀리지 않은 엔티티 null 처리. 테스트 `XmlElementSecurityTest` |
| B7 | law.go.kr 수집기의 리다이렉트 따라가기가 호스트·스킴을 가리지 않음 — 가로채인 응답이 수집기를 내부망·다른 호스트로 보내거나(SSRF) https→http 로 내릴 수 있었다 | 같은 사이트(law.go.kr ↔ www.law.go.kr 처럼 레이블 3개 이상의 공통 상위)의 http(s) 만, https→http 내려가기 금지. 그 밖은 3xx 를 그대로 돌려줘 호출한 쪽이 `Redirect response` 오류로 알린다. 테스트 `RedirectFollowerTest` |
| B8 | 경로 조작 — 화면에서 읽은 일련번호(`lsiSeq`)가 저장 파일 이름에 들어가 `../` 로 raw 폴더 밖에 쓸 수 있었고, 번들의 `raw_file` 이 `..` 로 폴더 밖을 읽을 수 있었다 | `LegalPy.requireSafeFileName`(수집기·DRF 클라이언트의 저장 이름), `Ingest.resolveInside`(번들·보충자료의 raw_file) |
| B9 | 기본 주소가 잘못되면 `URI.create` 예외 메시지에 인증키가 든 전체 URL 이 실려 로그·500 처리기로 갔다 | `HttpJsonClient`: URL 없는 `UpstreamError` 로 바꿈 (DRF 클라이언트는 이미 그렇게 하고 있었다) |
| B10 | 의존성 — `io.opentelemetry:opentelemetry-api 1.62.0` (openai-java 가 끌어옴, Spring Boot BOM 기준 버전) 에 CVE-2026-118259 (MODERATE, W3C tracestate 해석 시 CPU 소모). 추적 SDK 를 쓰지 않아 닿는 경로는 없다 | 1.67.0 으로 올림 (`ai/build.gradle.kts` constraints). OSV 조회 184개 패키지 중 이 건만 |
| B11 | 공급망 — Gradle 배포판 체크섬을 검증하지 않았고 래퍼 jar 가 9.2.1 것이었다 | `distributionSha256Sum` 추가, 래퍼를 9.9.0 으로 재생성 (공식 체크섬과 일치). CI 의 `gradle/actions/setup-gradle` 은 래퍼 jar 를 기본으로 검증한다 |
| B12 | 공개 조회 `GET /v1/reviews/{id}` 가 `displayable_explanation` 뷰 전체를 읽었다 — 설명이 쌓일수록 느려지는 비인증 경로 | 결과에 든 보고서 id 만 묻는다 (`WHERE … IN`) |
| B13 | 워커 재시작 때 '오래된 running' 되돌리기가 생성 시각 기준 → 밀린 대기열에서 방금 다른 워커가 가져간 요청까지 되돌려 두 번 실행될 수 있었다 | `review_request.claimed_at`(V2 마이그레이션) 기준으로. `(status, created_at)` 색인 추가. 테스트 `reclaimStaleUsesClaimTimeNotCreationTime` |
| B14 | 같은 필지의 첫 조회가 동시에 오면 한쪽이 기본키 충돌로 500 | 한 번 다시 시도 (두 번째는 먼저 저장된 행을 읽음) — 필지 조회·검토 생성 |
| B15 | 작은 것들 | PDF 32MB·300쪽 상한, 부지 꼭짓점 2만 개 상한(넘으면 `SITE_GEOMETRY_INVALID`), OpenAI 빈 `choices` → LLM 실패 경로(500 아님), 경고 있는 draft 규칙은 값을 고쳐도 draft(승인 때 `acknowledge_warnings` 필요), L4 중첩 검사 실패를 로그로 남김, 우아한 종료(`server.shutdown=graceful`, ECS stopTimeout 안), Tomcat connection-timeout 20초·keep-alive 75초(ALB idle 60초보다 길게), `DB_ECHO` 가 실제로 SQL 로그를 켬, 죽은 코드 제거 |

### 4.2 점검했고 문제없던 것

- 경로 변형으로 관리자 인증을 피할 수 없다 (`/%61dmin/rules`·`/admin;x=y/rules`·`/admin/rules;x=y` → 401, `/admin//rules`·`/ADMIN/rules`·`/admin/rules/`·`/v1/../admin/rules` → 404). 인터셉터는 라우팅 경로에 붙는다. 토큰을 질의 문자열로 보내도 401
- 구문 분석기 한계: JSON 3000단계 중첩·2만 자리 숫자·거대 지수·중복 키·잘못된 UTF-8·비JSON Content-Type → 모두 422 JSON. 12KB 헤더·`%2F`·역슬래시 경로 → Tomcat 단계에서 400 JSON(`JsonErrorReportValve`). TRACE → 405 JSON
- CORS: 허용 안 된 Origin 의 preflight 403, 자격 증명 없음, 허용 메서드 GET·POST. `Server` 헤더 없음. 404·405·500 모두 같은 JSON 형식, 보안 헤더 모든 응답에
- 관리자 토큰: 바이트 상수 시간 비교, 비ASCII 토큰 401, IP 잠금. X-Forwarded-For 위조는 `TRUSTED_PROXY_HOPS=0` 이면 무시(제한 버킷 공유), IPv6 표기 정규화는 DNS 조회 없음
- 외부 호출 URL 은 모두 설정의 기본 주소 + 고정 endpoint + 인코딩된 매개변수 — DB·LLM 출력·외부 JSON 에 든 URL 을 가져다 호출하는 곳이 없다. 공개 응답의 모든 `url` 은 `Sanitize.safeUrl` 을 거친다
- LLM 출력은 저장 전에 JSON Schema(`additionalProperties: false`, 길이·패턴)와 인용 검증을 거치고, 설명은 `PASS` 판정만 저장·표시. 계산식은 화이트리스트 파서. JPQL·SQL 은 모두 바인딩 매개변수
- 비밀값: `Redact` 로그 가림, DRF 클라이언트 예외에 URL 없음, SDK 빌더로만 키 전달, `LlmSettings.toString` 가림. 저장소와 git 이력에 비밀값 패턴(AKIA·sk-ant-·PEM·ghp_ 등) 없음, `.env` 미추적
- JDK HttpClient: 리다이렉트 안 따라감(수집기만 `RedirectFollower`), TLS 검증 기본값, 재시도 상한(HTTP 1+retries, SDK 2, 리다이렉트 20)
- GitHub Actions 5개의 고정 SHA 가 표기한 태그(v4.4.0·v6.1.0·v6.4.0·v5.6.0·v4.6.2)의 커밋과 일치, `permissions: contents: read`, `persist-credentials: false`
- Terraform: 기본 보안그룹 비움, RDS 비공개·암호화·`sslmode=require`, S3 TLS 전용·공개 차단·버킷 소유자 강제, 최소 권한 IAM, ECR 태그 불변·푸시 시 검사, WAF, 컨테이너 읽기 전용 루트·capabilities 제거

### 4.3 남은 것 (이번에 고치지 않음)

- `REVIEW_EXECUTOR=thread`(기본, 개발용)는 프로세스 재시작 때 진행 중이던 검토를 복구하지 않는다 — 클라이언트는 `queued`/`running` 을 계속 보고, 오래 남은 `queued` 는 대기 상한(`MAX_PENDING_REVIEWS`)에 센다. 운영은 `worker` 모드(시작 시 되돌림)를 쓴다
- `Ingest.linkDelegations`(매일 재수집·관리자 재수집)가 모든 조문을 한 영속성 컨텍스트에 올린다 — 말뭉치가 커지면 메모리·시간이 늘어난다
- 파이썬 호환 도우미(repr·isdigit·정규식 변환)가 `external`·`legal`·`ai` 에 비슷하게 세 벌 있다 — `common` 으로 모으는 정리는 대조 테스트 보호 아래 별도 작업
- 실행 파일 `app.jar` 가 212MB (openai-java-core 75MB, anthropic-java-core 44MB — SDK 가 생성 코드를 모두 포함). 이미지 pull·시작 시간에 영향
- GEOS 이식 파일의 LGPL 검토 (`THIRD_PARTY_NOTICES.md`)
- 실제 AWS·실제 API 키로는 미검증. 컨테이너 OS 패키지 취약점은 ECR scan-on-push 에 맡긴다 (로컬 trivy 미실행)
