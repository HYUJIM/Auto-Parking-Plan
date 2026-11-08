# Python(BE) → Spring(BE-spring) 이식 규칙

`BE/`(FastAPI)와 **같은 HTTP API·같은 JSON·같은 판단 결과**를 내는 것이 목표다. 그래서 파이썬판의 동작을 바꾸지 않고
옮기는 것을 원칙으로 하고, 다르게 할 수밖에 없는 곳은 이 문서와 코드 주석에 적는다.

## 모듈과 대응

| Gradle 모듈 | 패키지 | 파이썬 원본 | 비고 |
|---|---|---|---|
| `common` | `com.autoparkingplan.common` | (새로 만듦) | 파이썬 호환 도우미 `Py`, `PyJson`, `Json`, HTTP 전송 `common.http` |
| `external` | `com.autoparkingplan.external` | `app/external/*` | 도로명주소·브이월드·건축HUB 클라이언트, PNU, 용도지역 코드 |
| `legal` | `com.autoparkingplan.legal` | `app/legal/{parsed,structure,lawgo_html,lawgo_drf}.py`, `scripts/fetch_lawgo_*.py` | law.go.kr 원문 수집·해석 |
| `parking` | `com.autoparkingplan.parking` | `app/parking/{types,geo,engine}.py` | shapely → JTS, pyproj → proj4j |
| `ai` | `com.autoparkingplan.ai` | `app/llm/*`, `app/verification/*`, `app/resources/{schemas,prompts}` | LLM 호출·사후 검증기 |
| `db` | `com.autoparkingplan.db` | `app/db/*` | JPA 엔티티·저장소·Flyway |
| `server` | `com.autoparkingplan.server` | 나머지 (`api`, `review`, `explain`, `extraction`, `parcels`, `security`, `storage`, `cli` …) | Spring Boot 앱 |

`external`·`legal`·`parking`·`ai`·`common` 은 Spring 에 의존하지 않는 순수 자바 라이브러리다 (단위 테스트가 빠르고, 다른 앱에서도 쓸 수 있다).

## 이름

* 파이썬 모듈 하나 → 같은 이름의 자바 클래스 (`juso.py` → `JusoClient`/`Juso`, 모듈 함수는 `final` 유틸 클래스의 `static` 메서드).
* `snake_case` 함수·변수 → `camelCase`. 상수는 `UPPER_SNAKE` 그대로. 예외 클래스 이름은 그대로 (`UpstreamError` → `UpstreamError extends RuntimeException`).
* `dataclass`/`NamedTuple` → `record`. 값이 없을 수 있는 숫자는 `Double`/`Integer`(박싱, null = 미확인). `Optional` 은 반환값에만.
* `dict`·`list` 를 그대로 주고받는 곳(GeoJSON, 원천 응답, LLM 출력, 원장 JSON)은 Jackson 3 `tools.jackson.databind.JsonNode`.
  API 로 나가는 JSON 의 필드 이름은 파이썬과 같은 `snake_case` 다 (record 컴포넌트에 `@JsonProperty("snake_name")` 또는 서버의 SNAKE_CASE 전략).

## 파이썬과 결과가 달라지기 쉬운 곳 (반드시 지킨다)

| 파이썬 | 자바에서 | 이유 |
|---|---|---|
| `json.dumps(x, ensure_ascii=False, sort_keys=True)` 후 sha256 | `PyJson.dumpsSorted(node)` + `Py.sha256Hex` | 구분자 `", "`/`": "`, 실수 표기(`1e-05`), 이스케이프가 다르면 해시가 달라진다 |
| `repr(float)`, `str(float)`, f-string `{x:g}` | `Py.floatRepr`, `Py.formatG` | 자바 `Double.toString` 은 `1.0E-5` |
| `round(x, n)`, `round(x)` | `Py.round` | 파이썬은 은행원 반올림 |
| `s.strip()`, `s.split()`, `s.splitlines()`, `isspace()` | `Py.strip`, `Py.split`, `Py.splitlines`, `Py.isSpace` | 자바 `strip()` 은 NBSP 를 공백으로 보지 않는다 |
| `len(s)`, `s[a:b]`, `s.find()` | `Py.len`, `Py.slice`, `Py.find` | 파이썬은 코드포인트, 자바는 UTF-16 단위 (근거 인용 위치가 어긋난다) |
| `re` 의 `\d \w \s \b` | `Pattern.UNICODE_CHARACTER_CLASS` 플래그 | 파이썬 str 패턴은 유니코드 기준 (전각 숫자·전각 공백 포함) |
| `re.match` / `re.fullmatch` / `re.search` | `lookingAt()` / `matches()` / `find()` | |
| `//`, `%` (음수) | `Math.floorDiv`, `Math.floorMod` | |
| `dict` 순서 | `LinkedHashMap`, `ObjectNode` | 삽입 순서 유지 |
| `sorted(key=…)` | `List.sort(Comparator…)` (안정 정렬) | |
| 문자열 비교 정렬 | `PyJson.compareCodePoints` | 보조 평면 문자 순서 |

## 그 밖의 규칙

* 자바 21. Lombok 쓰지 않음. 소스·자원은 UTF-8, 줄끝 LF (`.editorconfig`, `.gitattributes`).
* **주석 안에 `\u` 를 쓰지 않는다** (자바 컴파일러가 주석 안의 유니코드 이스케이프도 해석해서 빌드가 깨진다).
* 파이썬 docstring·주석의 근거(설계서 장, 공식 문서 열람일, `[미확인]` 표시)는 한국어 그대로 옮긴다.
* HTTP 는 `common.http.HttpTransport` 로만 보낸다 (테스트는 `MockTransport`). 인증키는 예외 메시지·로그에 넣지 않는다 (`Redact`).
* 로그는 SLF4J. 시간은 `java.time` (UTC 저장, 화면·기준일은 Asia/Seoul).
* 테스트: 파이썬 테스트를 JUnit 5 + AssertJ 로 옮긴다. fixture 는 `src/test/resources/` 로 복사한다.
  파이썬 테스트의 이름·의도를 유지해서 대조할 수 있게 한다 (테스트 메서드 Javadoc 에 원래 파이썬 테스트 이름).
* 윈도우·맥 공통: 경로는 `java.nio.file.Path`, 줄바꿈은 `\n` 고정, 기본 문자셋에 기대지 않는다.

## 전체 동작 대조

파이썬판 종단 검증(`BE/scripts/e2e_verify.py`)을 `--server spring` 으로 실행하면 같은 모의 외부 API·같은 법령 원문으로
스프링판 서버를 띄워 같은 점검(543건)을 돌린다. 두 서버가 같은 점검을 통과하는 것이 이식 완료 기준이다.

## 모듈별로 파이썬과 다르게 한 곳 (알려진 차이)

결과(API 응답·판단·해시)에는 영향이 없고, 오류 문구·내부 동작만 다른 곳이다. 각 모듈 소스의 Javadoc 에 자세히 적었다.

* `external` — 응답 봉투가 예상과 다른 형식이면 빈 값으로 본다(파이썬은 AttributeError). 시간 초과는 요청 전체에 하나(httpx 는 단계별). 오류 문구의 예외 이름은 자바 이름(`ConnectException`).
* `legal` — HTML 은 jsoup(HTML5 파서)로 읽는다. 실제 수집한 원문 페이지 전부에서 파이썬(html.parser)과 같은 트리·같은 텍스트임을 대조 테스트로 확인했다. PDF 별표 텍스트는 PDFBox 로 뽑아 정규화한 결과가 pypdf 와 같다 (원시 추출 텍스트는 다를 수 있음).
* `ai` — JSON Schema 검증기는 파이썬 jsonschema 4.26 과 같은 오류 문구·순서를 내도록 직접 구현했다 (`ai.schema`). Anthropic SDK 는 인증 헤더(`x-api-key`)·beta 경로 표기가 파이썬 SDK 와 다르지만 요청 본문은 같다. 파이썬 동작을 흉내 내는 도우미는 `ai.pycompat`(repr, 정규식 변환, 참·거짓 판정).
* `db` — 스키마는 Flyway(`db/migration/{h2,postgresql}/V1__init.sql`, `db/tools/gen_ddl.py` 로 SQLAlchemy 모델에서 생성). 제약 이름을 `ck_/uq_/fk_<테이블>_<컬럼>` 로 통일했다 (H2 는 제약 이름이 스키마 전체에서 유일해야 함). JSON 컬럼은 H2 `JSON`, PostgreSQL `JSONB`. 행 id 는 시간순 UUIDv7 이고 자식 목록은 id 순(=저장 순서)으로 읽는다 — 파이썬판은 DB 가 돌려준 순서를 그대로 써서 SQLite·PostgreSQL 에서 목록 순서가 달랐다.
* `server` — 항목별 원장·설명 저장은 별도 트랜잭션으로 먼저 확정한다 (동시 검토의 캐시 키 충돌을 다시 읽기로 처리). 요청 검증 문구는 한국어 (pydantic 은 영어) — 오류 코드·HTTP 상태·`details[].loc` 는 같다.
