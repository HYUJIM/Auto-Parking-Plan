# 보안 (스프링판)

파이썬판 보안 점검([`BE/docs/SECURITY.md`](../../BE/docs/SECURITY.md), 2026-11-06, 실제 모의 공격으로 찾은 문제와 보완)의
조치를 모두 같은 동작으로 옮겼다. 종단 검증의 보안 단계(실제 서버 프로세스에 공격 요청)를 스프링 서버에 그대로 돌려 같은 결과를 확인한다.

## 1. 애플리케이션 — 파이썬판 조치와 스프링판 구현

| # | 문제 | 스프링판 구현 |
| --- | --- | --- |
| A1 | 요청 제한 없음 | `server.security.TokenBucketLimiter` + `RequestGuardFilter` (서블릿 필터). IP·경로 묶음별 분당 허용량 (검토 생성 10, 배치 30, 주소 검색 30, 필지 60, 그 밖 240), 초과 시 429 + `Retry-After`. `/healthz`·OPTIONS 제외 |
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

파이썬판과 같다 (`deploy/aws`, `docker-compose.yml`, `Dockerfile`, `.github/workflows/be-spring-ci.yml`):
AWS 는 항상 `APP_ENV=prod`, compose 포트는 127.0.0.1 에만·관리자 토큰 기본값 없음, ALB WAF(IP 당 요청 수 + AWS 관리형 공통 규칙, 선택적 `/admin*` IP 허용 목록),
운영 HTTPS 강제, 컨테이너 non-root(UID 10001)·읽기 전용 루트·capabilities 제거, ECR 태그 변경 불가·푸시 시 검사, 최소 권한 IAM,
S3 TLS 전용·DB `sslmode=require`, 빌드 문맥에서 비밀 파일 제외, CI 읽기 전용 토큰·액션 SHA 고정.
스프링판에서 추가로: JVM 시작 동안 ECS 헬스체크 유예 120초, 작업 메모리 2GB(`task_memory`).

## 3. 남은 위험

파이썬판 문서 5장과 같다 (요청 제한은 컨테이너 단위 → 여러 작업 전체는 WAF 가 맡음, 프론트는 설명·근거를 텍스트로 그려야 함, 실제 AWS·실제 API 키로는 미검증 등).
