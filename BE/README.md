# APP (Auto Parking Plan) — 백엔드 + AI

주소를 입력하면 그 땅에 적용되는 **건축·주차 법규를 근거(법률 → 시행령 → 조례)와 함께 정리**하고,
주차장을 지을 수 있는 부지라면 **법정 치수로 계산한 주차장 배치안을 지도용 GeoJSON** 으로 돌려주는 웹 서비스의 서버입니다.
로그인·결제 없이 누구나 쓰는 공개 API 와, 검수자용 관리자 API 로 구성됩니다. (프론트엔드는 별도)

- 1차 지원 지역: **대구광역시 수성구**
- 실행 환경: macOS · Windows · Linux, Python 3.11 이상 / 운영: AWS (ECS Fargate + RDS PostgreSQL + S3)

## 무엇을 하나요

1. **주소 검색** → 필지(PNU) 확정 → 대지면적·지목·용도지역·경계 조회 (도로명주소·브이월드 API)
2. **법규 검토** — 용도지역별 건축 가능 용도, 건폐율·용적률(참고 면적 계산), 주차구획·차로 폭, 별도 확인 항목(도로·건축선·높이·특화구역)
   - 결과는 `검토 가능 / 조건부 검토 / 별도 확인 필요` 세 상태와 근거 원문으로 답하고, 허가 여부를 단정하지 않습니다.
3. **쉬운 설명 (AI)** — 확정된 검토 결과를 토지소유주·설계 실무자용 문장으로 바꾸고, **원문과 대조하는 사후 검증을 통과한 문장만** 보여줍니다.
4. **주차장 배치 시뮬레이션** — 조례상 주차장을 검토할 수 있는 부지에서 직각·60°·45°·평행 배치안(주차구획·차로·출입구)을 계산합니다.

원칙: 법규 값은 수집한 **원문에 그대로 있는 것만** 쓰고, **사람이 승인한 규칙만** 검토에 씁니다. AI 는 법규를 판단하지 않습니다.

## 빠르게 실행하기

외부 API 키 없이도 **모의 공공 API + 실제 수집한 법규 원문**으로 전체 흐름을 돌릴 수 있습니다. 아래 명령은 모두 **`BE/` 폴더에서** 실행합니다.

```bash
cd BE
```

### 1) 설치

맥:

```bash
python3 -m venv .venv && source .venv/bin/activate
```

윈도우 (PowerShell, 막히면 `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`):

```bash
py -3.12 -m venv .venv; .venv\Scripts\Activate.ps1
```

공통:

```bash
python -m pip install -e ".[dev]"
```

### 2) 한 번에 검증 (권장)

모의 API 서버·API 서버를 띄우고 수성구 예시 필지 20건 시나리오를 모두 확인한 뒤 보고서를 남깁니다.

```bash
python scripts/e2e_verify.py --report var/e2e_report.json
```

### 3) 직접 띄워 보기

터미널 1 — 모의 공공 API (예시 필지 12개):

```bash
python -m mockserver --port 8090
```

터미널 2 — `.env.example` 을 `.env` 로 복사하고, '로컬 모의 서버를 쓸 때' 아래 주석 4줄을 풀고 `LLM_PROVIDER=fake`, 키 3개(`JUSO_API_KEY`, `VWORLD_API_KEY`, `BUILDING_HUB_KEY`)에 아무 값(예: `mock`)을 넣은 뒤:

```bash
python -m app.cli bootstrap --approve-as "개발자" --ack-warnings --confirm-transcriptions
```

```bash
python -m app.cli serve --port 8000
```

API 문서: http://127.0.0.1:8000/docs (예: `GET /v1/address/search?q=대구광역시 수성구 만촌예시로 34`)

> `bootstrap --approve-as …` 는 **개발용 일괄 승인**입니다. 운영(`APP_ENV=prod`)에서는 막혀 있고, 검수자가 관리자 API 로 하나씩 승인합니다.

### 4) Docker (PostgreSQL 포함)

```bash
docker compose up --build -d
```

```bash
docker compose run --rm bootstrap
```

> 포트는 이 컴퓨터(127.0.0.1)에만 열립니다. 관리자 API 를 쓰려면 `.env` 에 `ADMIN_TOKEN` 을 넣으세요 (비어 있으면 비활성).

### 테스트

```bash
python -m pytest
```

## 실제 서비스로 쓰려면 (.env)

| 항목 | 설정 |
| --- | --- |
| 도로명주소 / 브이월드 / 건축HUB | `JUSO_API_KEY`, `VWORLD_API_KEY`(+`VWORLD_DOMAIN`), `BUILDING_HUB_KEY` |
| 국가법령정보 Open API (원문 매일 재수집) | `LAWGO_OC` (서버 IP 등록 필요) |
| 프론티어 LLM | `LLM_PROVIDER=anthropic` + `ANTHROPIC_API_KEY` (작성 `claude-opus-5-5`, 판정 `claude-sonnet-5-5`) 또는 `openai` |
| DB / 원문 보관 | `DATABASE_URL=postgresql+psycopg://…`, `BLOB_STORE=s3`, `S3_BUCKET` |
| 관리자 API | `ADMIN_TOKEN` (운영은 32자 이상 무작위 값) |
| 요청 제한·본문 크기 등 보안 설정 | 기본값으로 켜져 있음. 바꾸려면 [`docs/SECURITY.md`](docs/SECURITY.md) 6장 |

AWS 배포는 [`docs/DEPLOY_AWS.md`](docs/DEPLOY_AWS.md) (Terraform: `deploy/aws/`).

## 보안

공개(비로그인) 서비스라 IP 별 요청 제한(429), 요청 본문 크기 제한(413), 보안 헤더, 관리자 토큰 대입 잠금, 링크(http/https만)·GeoJSON 입력 검사,
외부 공공 API 재조회 간격 제한이 들어 있습니다. 운영(`APP_ENV=prod`)에서는 API 문서가 꺼집니다.
로컬 모의 공격으로 찾은 취약점과 조치, 남은 위험은 [`docs/SECURITY.md`](docs/SECURITY.md) 에 있습니다.

## API 요약

| 메서드 · 경로 | 용도 |
| --- | --- |
| `GET /v1/address/search?q=` | 주소 후보 (PNU, 좌표, 지원 지역 여부) |
| `GET /v1/parcels/{pnu}` · `GET /v1/parcels/at?lng=&lat=` | 필지 정보 · 지도 클릭 |
| `POST /v1/reviews` → `GET /v1/reviews/{id}` | 법규 검토 시작(202) → 진행 상태 / 결과 |
| `GET /v1/rules/{rule_id}/basis` | 근거 상세 (원문 조문, 근거 트리, 원문 링크) |
| `POST /v1/reviews/{id}/parking-simulations` | 주차장 배치안 (GeoJSON) |
| `GET /v1/regions` | 지원 지역 |
| `/admin/*` (`X-Admin-Token`) | 규칙·원장 검수, 특화구역 등록, 원문 재수집, 알림 |

## 폴더 구조

```text
app/          서버 코드 (api, db, legal, extraction, review, explain, verification, llm, parcels, external, parking)
mockserver/   외부 공공 API 모의 서버 (예시 필지)
data/         수집한 법규 원문(legal_sources), 규칙 추출 결과(seed)
scripts/      종단 검증, 원문 수집·보충, 규칙 추출 결과 생성
deploy/aws/   Terraform
docs/         설계 문서 원본(reference), 검증 기록, 데이터 현황, 배포 안내
tests/        단위·통합 테스트
```

## 현재 상태

- 검증 결과와 아직 확인하지 못한 것: [`docs/VERIFICATION.md`](docs/VERIFICATION.md)
- 법규 데이터 출처·사람 확인이 필요한 항목(표 이미지 전사본 등): [`docs/DATA_STATUS.md`](docs/DATA_STATUS.md)
- 예시 필지는 실제 필지가 아닙니다(`is_example: true`). 실제 키로 검증: `python -m pytest -m real_api`
