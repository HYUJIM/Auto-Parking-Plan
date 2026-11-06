# 보안 점검 기록 (2026-11-06)

비로그인 공개 서비스라 누구나 API 를 부를 수 있다는 전제로, 로컬에 서버를 띄워 실제로 공격해 보고 뚫린 곳을 고쳤습니다.
고친 뒤 같은 공격을 다시 보내 막히는 것을 확인했고, 공격 요청은 회귀 테스트와 종단 검증(`scripts/e2e_verify.py` 의 '보안' 단계)에 넣었습니다.

## 1. 점검 범위와 방법

| 대상 | 방법 |
| --- | --- |
| 공개 API 8개, 관리자 API 14개 | 로컬 서버(모의 외부 API + 별도 DB)에 공격 요청: 입력 조작(SQL·경로·CRLF·스크립트 문자열, NaN/Infinity, 거대·깊은 JSON), 요청 폭주, 관리자 토큰 대입·비ASCII 토큰, X-Forwarded-For 위조, 악성 링크·경계 저장 후 공개 결과 확인 |
| 코드 | 인증·입력 검증·출력·로그·외부 호출·계산식·LLM 입력 경로 검토 |
| 의존성 | `pip-audit` (설치된 60개 패키지) |
| 저장소 | git 전체 이력에서 키·토큰 패턴 검색, 테스트 표본의 키 값 확인 |
| Docker / compose / Terraform / CI | 설정 검토 후 이미지 빌드, 읽기 전용 루트로 컨테이너 실행, 컨테이너에 같은 공격, `terraform validate` |

## 2. 발견하고 고친 것 — 애플리케이션

공격 결과의 '고치기 전'은 실제로 보낸 요청의 응답입니다.

| # | 심각도 | 문제 | 고치기 전 (실제 공격) | 조치 | 고친 뒤 |
| --- | --- | --- | --- | --- | --- |
| A1 | 높음 | 요청 제한 없음 → 외부 공공 API 할당량 소진, DB·CPU 폭주 | 검토 생성 300건 동시 요청이 전부 202 | IP·경로 묶음별 토큰 버킷(`app/security/ratelimit.py`). 검토 생성 10/분, 주소 검색 30/분, 필지 60/분, 배치 30/분, 그 밖 240/분. 429 + `Retry-After`. 헬스체크 제외 | 50건 동시 → 4~5건만 202, 나머지 429 |
| A2 | 높음 | `?refresh=true` 로 누구나 외부 API 를 다시 부름 (일일 할당량 → 서비스 전체 정지) | 20번 요청 → 외부 API 100회 호출 | 같은 필지는 10분(`PARCEL_REFRESH_MIN_INTERVAL_S`) 안에 다시 부르지 않음 | 20번 요청 → 외부 호출 0회 |
| A3 | 높음 | 요청 제한·잠금을 X-Forwarded-For 위조로 회피. uvicorn 이 127.0.0.1(로컬 실행 기본값)이나 `*`(도커 이미지) 에서 온 헤더를 믿고 클라이언트 IP 를 바꿔 씀 | 관리자 토큰 12번 틀려도 잠기지 않음 (매번 다른 IP 로 보임) | 앱이 실제 IP 를 직접 계산: 프록시 수(`TRUSTED_PROXY_HOPS`, ALB=1)만큼 뒤에서 센 값만 믿음. `app.cli serve` 는 `proxy_headers=False`. 이미지는 `--forwarded-allow-ips *` 제거, AWS 는 `FORWARDED_ALLOW_IPS`=VPC 대역 | 위조 헤더로도 10번째 실패에서 잠김 (로컬·컨테이너 모두 확인) |
| A4 | 중간 | 관리자 토큰 무차별 대입 제한 없음 | 300회 대입 2초, 차단 없음 | IP 별 10회 실패 → 15분 잠금(맞는 토큰도 거부). 운영에서 32자 미만·`change-me`·단조로운 토큰이면 서버가 시작을 거부 | 10회 실패 후 429, `Retry-After: 900` |
| A5 | 중간 | 관리자 토큰 헤더에 한글 등 비ASCII → 서버 오류 | 500 Internal Server Error (`hmac.compare_digest` 가 str 비교에서 TypeError) | 바이트로 바꿔 상수 시간 비교 | 401 |
| A6 | 중간 | 저장형 XSS: 관리자가 넣은 `javascript:` 고시 링크가 공개 검토 결과 `related_basis.url` 로 그대로 나감 (프론트가 `<a href>` 로 그리면 실행) | 공개 결과에 `"url": "javascript:alert(1)"` | 입력: `notice_url` 은 http(s) 만. 출력: 근거 링크·`source_url` 은 `safe_url()` 통과분만 (이미 저장된 값도 걸러짐). 결과 계약(`review_result.schema.json`)에 `^https?://` | 등록 422, 기존 저장분도 공개 결과에서 사라짐 |
| A7 | 중간 | 요청 본문 크기 제한 없음 (메모리 소진) | 30MB 본문을 끝까지 읽고 처리 | 공개 64KB, 관리자 2MB. Content-Length 와 실제로 읽은 양(청크 전송) 둘 다 확인 → 413 | 413 |
| A8 | 중간 | 운영에서도 API 문서(`/docs`, `/openapi.json`)가 관리자 경로까지 공개, `server: uvicorn` 헤더, healthz 가 LLM 제공자·외부 API 설정 여부 노출 | `/openapi.json` 에 `/admin/*` 14개 | 운영(`APP_ENV=prod`)에서 문서 끔, server 헤더 제거, healthz 는 `{"status":"ok"}` 만 | 문서 404, 헤더 없음 |
| A9 | 중간 | 보안 헤더 없음 | 없음 | `nosniff`, `X-Frame-Options: DENY`, `CSP: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, 관리자 `Cache-Control: no-store`, 운영 HSTS | 모든 응답에 붙음 (429·413 포함) |
| A10 | 낮음 | 국가법령정보 인증값 `OC` 가 로그·오류에 노출 가능: httpx 로그 가리기 규칙에 `OC=` 없음, `raise_for_status()` 예외 메시지에 URL 전체, 기본 주소가 `http://` | — (OC 미발급이라 실호출은 못 함, 코드·테스트로 확인) | 가리기 규칙에 `OC` 추가, URL 없는 `DrfError` 로 바꿈, 기본 주소 `https://www.law.go.kr/DRF` (https 응답 동일 확인) | 단위 테스트 |
| A11 | 낮음 | 관리자 L4 구역 입력 검증 부족: 깨진 GeoJSON·대한민국 밖 좌표·자기 교차·꼭짓점 수·PNU 목록 형식 무제한, 목록 `limit` 무제한 | `"coordinates": "garbage"` 가 201 로 저장 | 경계 검사(`validate_zone_geometry`: Polygon/MultiPolygon, 유한한 경위도, 범위, 5만 꼭짓점, 유효 도형, 중첩 깊이), PNU 19자리, 목록 상한, `limit` 1~500 | 422 |
| A12 | 낮음 | NaN·Infinity 수치를 받아들임 (`gate_point: [NaN, NaN]` 이 200) | 200 | 공개 요청 모델 `allow_inf_nan=False`, 경위도 범위, 각도 목록 8개 상한 | 422 |
| A13 | 낮음 | 실패한 검토의 내부 오류 상세(결과 계약 위반 경로·메시지)가 공개 응답에 | `error.message` 에 스키마 경로 | 공개 응답은 코드 + 고정 문구만 (상세는 로그·`/admin/alerts`) | 테스트 |
| A14 | 낮음 | 검토 대기열 무한 (폭주 시 메모리·DB 증가) | 300건 대기 | 대기 500건(`MAX_PENDING_REVIEWS`) 넘으면 503 `SERVICE_BUSY` + `Retry-After` | 테스트 |
| A15 | 낮음 | 오류 형식 불일치·정보 노출 여지: 프레임워크 404/405/본문 해석 실패는 `{"detail": …}`, 처리 안 된 예외는 평문 | — | 같은 `{"error": {code, message}}` 형식, 처리 안 된 예외는 `INTERNAL_ERROR` 고정 문구 (예외 내용은 로그에만) | 테스트 |
| A16 | 정보 | 계산식 평가기: 0 나누기·overflow 가 검토 전체를 실패시킴 | — | 산술 오류도 `ValueError`(→ '입력 필요'), 식 길이 500자 상한. `eval()` 미사용 AST 화이트리스트는 원래대로 | 테스트 |

## 3. 발견하고 고친 것 — 배포 설정 (Docker · compose · Terraform · CI)

| 심각도 | 문제 | 조치 |
| --- | --- | --- |
| 높음 | AWS 작업에 `APP_ENV=dev` 가 들어가 앱이 시작하지 못함 (`local/test/prod` 만 허용) | AWS 는 항상 `APP_ENV=prod` (인터넷 공개) → 일괄 승인 금지·토큰 강도 검사·문서 끔이 자동 적용 |
| 높음 | compose 가 API·모의 서버 포트를 `0.0.0.0` 에 열고 관리자 토큰 기본값 `change-me-local-admin` | 포트는 `127.0.0.1` 에만(`HOST_BIND`), 토큰 기본값 제거 (.env 에 넣어야 관리자 API 가 켜짐) |
| 중간 | WAF 없음 | ALB 에 WAF (IP 당 5분 1000회, AWS 관리형 공통 규칙, 선택: `/admin*` IP 허용 목록) |
| 중간 | 운영이 HTTP 만으로도 배포 가능 (관리자 토큰 평문 전송) | prod 는 `acm_certificate_arn` 없으면 plan 실패. HTTPS(TLS 1.2/1.3) + 80→443 |
| 중간 | 컨테이너 루트 파일시스템 쓰기 가능, 권한(capabilities) 그대로 | `readonlyRootFilesystem`, `capabilities.drop=ALL`, `/tmp` 만 쓰기. 숫자 UID 10001 비루트 실행 |
| 중간 | ECR 태그 덮어쓰기 가능 (매일 도는 `refresh-sources` 가 바꿔치기된 `latest` 를 받음) | 태그 변경 불가(IMMUTABLE) + 푸시 시 취약점 검사 → 배포는 고유 태그(git SHA) |
| 중간 | IAM 범위 과다 (실행 역할 관리형 정책 `*`, 작업 역할 버킷 전체, 스케줄러 PassRole 무조건) | ECR 저장소·로그 그룹·`law-raw/` 접두사·클러스터로 한정, 혼동된 대리인 방지 조건 |
| 중간 | ALB 가 잘못된 헤더 필드를 그대로 전달 | `drop_invalid_header_fields`, 운영 삭제 보호 |
| 낮음 | S3 평문(HTTP) 요청 허용, ACL 가능 / DB 연결 TLS 미강제 / 보안그룹 송신 전체 허용 | TLS 전용 버킷 정책·ACL 끔 / `sslmode=require` / 송신 포트·대상 한정 |
| 낮음 | 도커 빌드 문맥에 `.env`·tfstate·키 파일이 들어갈 수 있음 | `.dockerignore` 보강 (이미지에 없음 확인) |
| 낮음 | CI 토큰 권한 기본값, 액션 태그 고정 | `permissions: contents: read`, 액션 커밋 SHA 고정, `persist-credentials: false` |

## 4. 확인했고 문제없던 것

- **SQL 인젝션**: 모든 쿼리가 SQLAlchemy 바인딩. 문자열로 만든 SQL 없음 (`text()` 는 고정 문장 + 바인딩 변수).
- **경로 조작**: 원문 보관소 키는 상위 경로 탈출을 막고 있음. 공개 API 에 파일 경로 입력 없음. `../` PNU 는 404.
- **코드 실행**: 계산식은 AST 화이트리스트(eval 미사용). `pickle`·`yaml.load`·셸 실행 없음.
- **프롬프트 인젝션**: 사용자 입력(주소·용도 등)은 LLM 프롬프트에 들어가지 않음. LLM 입력은 검수 승인된 사실 원장뿐이고, 출력은 사후 검증(원문 인용·숫자 대조)을 통과해야 화면에 나감.
- **XML**: 법령 XML 파서는 expat 2.6.3 (외부 엔티티 미처리, billion laughs 방어 내장).
- **CORS**: 허용 출처 목록 방식, 자격 증명(쿠키) 미허용. 낯선 출처의 preflight 는 400.
- **검증 오류 응답**: 입력값을 되돌려 싣지 않음 (필드 위치와 메시지만).
- **의존성**: `pip-audit` 알려진 취약점 0건.
- **비밀값**: git 전체 이력에 실제 키 없음 (테스트용 가짜 값뿐), 외부 API 키는 httpx 로그에서 `***` 로 가려짐 (실제 로그 확인).

## 5. 남은 위험과 권장 사항

- **AWS 실환경은 확인하지 못했습니다.** `terraform plan/apply`, WAF 오탐, Fargate `/tmp` 권한, RDS `sslmode=require`, 좁힌 송신 규칙으로 외부 API 가 되는지는 배포 후 확인이 필요합니다.
- **요청 제한은 컨테이너마다 따로 셉니다.** 작업이 N개면 한도가 N배가 됩니다. 여러 작업을 묶는 한도는 WAF rate-based 규칙이 맡습니다. 둘 다 켜 두세요.
- **프론트엔드는 API 문자열을 HTML 로 넣지 말고 텍스트로 출력**해야 합니다 (주소·고시명 등은 외부·관리자 입력). 링크는 API 가 http(s) 만 내보냅니다.
- **CORS**: 운영에서는 `cors_origins` 변수에 실제 프론트 주소만 넣으세요 (기본값 `http://localhost:3000`).
- **Terraform 상태 파일**에 DB 비밀번호가 들어갑니다. 암호화된 S3 원격 상태(+ 잠금)를 쓰거나 RDS `manage_master_user_password` 로 바꾸는 것을 권장합니다.
- **로그**: ALB 접근 로그·WAF 로그·VPC 흐름 로그가 꺼져 있습니다 (비용). 공격 추적이 필요해지면 켜세요. uvicorn 접근 로그의 IP 는 `FORWARDED_ALLOW_IPS` 를 VPC 대역으로 둔 경우에만 실제 클라이언트입니다.
- **공급망**: 베이스 이미지는 다이제스트가 아닌 태그, Python 의존성은 잠금 파일이 없습니다. 정식 운영 전 `pip-compile --generate-hashes` 등으로 고정을 권장합니다.
- **관리자 API**: 토큰 하나로 보호됩니다. 가능하면 `admin_allowed_cidrs` 로 사무실 IP 만 허용하세요.
- 국가법령정보 `OC` 는 발급받지 못해 실호출로는 확인하지 못했습니다 (코드·단위 테스트로 확인).

## 6. 새 보안 설정 (환경변수)

| 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `TRUSTED_PROXY_HOPS` | 0 | 앞단 프록시 수. AWS ALB 뒤 = 1 (Terraform 이 설정) |
| `RATE_LIMIT_ENABLED` | 테스트만 끔 | 요청 제한 켜기/끄기 |
| `RATE_LIMIT_REVIEWS_PER_MIN` / `_SEARCH_` / `_PARCELS_` / `_SIMULATIONS_` / `_DEFAULT_` | 10 / 30 / 60 / 30 / 240 | IP 당 분당 요청 수 (순간 허용량은 절반) |
| `MAX_BODY_BYTES` / `MAX_ADMIN_BODY_BYTES` | 64KB / 2MB | 요청 본문 상한 |
| `ADMIN_MAX_FAILURES` / `ADMIN_LOCK_MINUTES` | 10 / 15 | 관리자 토큰 실패 잠금 |
| `PARCEL_REFRESH_MIN_INTERVAL_S` | 600 | 같은 필지 외부 API 재조회 최소 간격 |
| `MAX_PENDING_REVIEWS` | 500 | 대기 검토 상한 (넘으면 503) |
| `ENABLE_DOCS` | 운영만 끔 | API 문서 공개 |
| `FORWARDED_ALLOW_IPS` (uvicorn) | 127.0.0.1 | 프록시 헤더를 믿을 주소. AWS 는 VPC 대역 (Terraform 이 설정) |

## 7. 재현

```bash
python -m pytest tests/unit/test_security.py tests/integration/test_security_api.py
```

```bash
python scripts/e2e_verify.py
```

종단 검증의 '보안 (모의 공격)' 단계가 실제 서버 프로세스를 요청 제한을 켠 채로 띄워 위 공격을 다시 보냅니다.
