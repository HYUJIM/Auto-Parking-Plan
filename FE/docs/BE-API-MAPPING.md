# FE ↔ BE API 대응표 (연동 준비 메모)

BE(`../BE`, Spring Boot) 의 공개 API 소스를 읽어 정리한 응답 구조와, FE 타입(`src/lib/api/types.ts`)과의 대응입니다.
**아직 연동하지 않았습니다.** 연동 때는 `src/lib/api/address.ts`·`parcel.ts` 의 `fetch` 대상과 "BE JSON → FE 타입" 변환만 바꾸면 되도록
이 문서를 기준으로 어댑터를 작성합니다. (조사 기준: 2026-11-10, BE `server/src/main/java/com/autoparkingplan/server/api/PublicController.java` 등)

## 0. 공통 차이

| 항목      | FE 개발용 API (`server/`)                  | BE 공개 API                                                                                                                     | 연동 시 처리                                                                                        |
| --------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 경로      | `/api/address?q=&limit=`                   | `GET /v1/address/search?q=` (limit 없음, 최대 10건)                                                                             | `VITE_API_BASE` 를 BE origin 으로, 또는 Vite proxy 로 `/v1` 전달                                    |
|           | `/api/parcel?pnu=`                         | `GET /v1/parcels/{pnu}` (path 변수)                                                                                             |                                                                                                     |
|           | `/api/parcel/at?lng=&lat=`                 | `GET /v1/parcels/at?lng=&lat=` (같은 파라미터, 응답은 필지 전체)                                                                |                                                                                                     |
| 키 표기   | camelCase                                  | snake_case                                                                                                                      | 어댑터에서 변환                                                                                     |
| 오류 바디 | `{ error, message }`                       | `{ error: { code, message, details? } }`                                                                                        | `toApiError` 가 `body.error.message` 를 읽도록                                                      |
| 상태 코드 | 501 NOT_READY · 502 UPSTREAM · 504 TIMEOUT | 503 UPSTREAM_UNAVAILABLE(외부 API 장애·키 미설정 모두) · 422 INVALID_REQUEST · 429 RATE_LIMITED · 503 SERVICE_BUSY(Retry-After) | 503 → `UPSTREAM`, 422 → `BAD_REQUEST`, 429 재시도 안내                                              |
| 404       | NOT_FOUND                                  | `error.code` 로 ADDRESS_NOT_FOUND / PARCEL_NOT_FOUND / REVIEW_NOT_FOUND 구분. **검색 결과 0건도 404 ADDRESS_NOT_FOUND**         | 주소 검색 404 는 예외가 아니라 `{ hits: [], total: 0, notice: error.details?.reason }` 로 정상 처리 |
| 좌표 순서 | `{ lat, lng }`                             | GeoJSON 관례 `[lng, lat]` 배열(centroid·geometry), 주소 검색은 `{ lng, lat }` 객체                                              | 반드시 명시적으로 `{ lat: c[1], lng: c[0] }`                                                        |
| 날짜      | `YYYY-MM-DD`                               | `fetched_at`·`checked_at` 은 ISO-8601 datetime, `as_of_date` 등은 `YYYY-MM-DD`                                                  | `.slice(0, 10)`                                                                                     |
| 지번 표기 | 목업 '대구 수성구 …'                       | 브이월드 원문 '대구광역시 수성구 범어동 177-3'                                                                                  | 그대로 사용                                                                                         |

## 1. 주소 검색 — `AddressSearchResult` ← `GET /v1/address/search?q=`

응답: `{ query, count, items: [ { pnu, road_address, jibun_address, sido, sigungu, region_code(5자리), location: { lng, lat } | null, supported, building_name | null } ] }`
요청 `q` 는 2~200자(아니면 422). 결과 0건은 404 ADDRESS_NOT_FOUND(juso 안내문은 `error.details.reason`).

| FE                 | BE                                                   | 비고                                                                          |
| ------------------ | ---------------------------------------------------- | ----------------------------------------------------------------------------- |
| `hits[]`           | `items[]`                                            |                                                                               |
| `hit.pnu`          | `items[].pnu`                                        | 19자리 동일                                                                   |
| `hit.roadAddress`  | `items[].road_address` (+ `building_name`)           | FE 개발용 API 처럼 `${road_address} (${building_name})` 로 합칠 수 있음       |
| `hit.jibunAddress` | `items[].jibun_address`                              |                                                                               |
| `hit.center`       | `items[].location`                                   | 키 순서만 다름. BE 좌표는 필지 중심(지적도), FE 개발용은 juso 출입구 좌표였음 |
| `hit.inSuseong`    | `items[].supported` (또는 `region_code === '27260'`) | BE 는 '법규 데이터 지원 관할' 의미                                            |
| `total`            | `count` (= items.length, ≤10)                        | '전체 건수' 의미는 없어짐 → `showingTop` 안내 조건 재검토                     |
| `source`           | 없음                                                 | 상수 `'juso'`                                                                 |
| `notice`           | 404 응답의 `error.details.reason`                    |                                                                               |

## 2. 필지 — `Parcel` ← `GET /v1/parcels/{pnu}`

응답(요약): `{ pnu, road_address | null, jibun_address, jurisdiction: { code, sido, sigungu, supported }, land_area_m2 | null, land_area_pyeong | null, land_category | null, official_land_price | null, road_side | null, designations: [ { kind: 'zoning'|'district'|'zone'|'other', name, conflict: 'included'|'partially_included'|'adjacent'|'unknown', code | null } ] | null, location: { centroid: [lng, lat] | null, geometry: GeoJSON MultiPolygon | null }, buildings: [ { main_use, gross_floor_area, approval_date } ], sources: [ { field, source, fetched_at } ], data_notices: [ { code: 'PARCEL_PARTIAL'|'DESIGNATION_UNAVAILABLE', message } ], is_example }`

| FE                              | BE                                                                                   | 변환                                                                                                                                                                         |
| ------------------------------- | ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `address.jibun`                 | `jibun_address`                                                                      |                                                                                                                                                                              |
| `address.road`                  | `road_address`                                                                       | null → undefined (hitCache 보충 로직 그대로 유효)                                                                                                                            |
| `address.gu` / `address.dong`   | `jurisdiction.sigungu` / 없음                                                        | dong 은 `jibun_address` 에서 파싱 (`server/vworld.ts` 의 `splitLdName` 을 클라이언트로 이동)                                                                                 |
| `inSuseong`                     | `jurisdiction.supported`                                                             |                                                                                                                                                                              |
| `center`                        | `location.centroid` → `{ lat: c[1], lng: c[0] }`                                     | null 이면 폴리곤에서 계산                                                                                                                                                    |
| `polygon` (열린 단일 링)        | `location.geometry` (MultiPolygon, 닫힌 링, 다중 파트·구멍 가능)                     | 가장 큰 폴리곤의 외곽 링 → 닫는 점 제거 → 키 변환 (`server/vworld.ts` 의 `outerRing`/`toRing` 을 `src/lib/map/geo.ts` 로 이동)                                               |
| `road.access`                   | `road_side`                                                                          | `rules.json` 의 도로접면 추정표 그대로 사용                                                                                                                                  |
| `road.side/width/frontage/path` | 없음                                                                                 | 목업 전용 → undefined                                                                                                                                                        |
| `area` / `areaSource`           | `land_area_m2`                                                                       | null 이면 폴리곤 면적 계산 후 `'geometry'`, 값 있으면 `'ledger'`                                                                                                             |
| `zoning` / `zoning2`            | `designations` 중 `kind === 'zoning' && conflict !== 'adjacent'` 의 1·2번째 `name`   | BE 검토 엔진도 같은 규칙. 2건 이상이면 '확인 필요'                                                                                                                           |
| `jimok`                         | `land_category`                                                                      |                                                                                                                                                                              |
| `landPrice`                     | `official_land_price`                                                                |                                                                                                                                                                              |
| `landUses[]`                    | `designations[]`                                                                     | `relation` ← conflict 매핑 `{ included: '포함', partially_included: '저촉', adjacent: '접함', unknown: '' }`; designations null → undefined + `DESIGNATION_UNAVAILABLE` 안내 |
| `land.*` (형상·지세·이용상황)   | 없음                                                                                 | undefined                                                                                                                                                                    |
| `source.provider` / `queriedAt` | `is_example` ? 'mock' : 'vworld' / `sources` 중 geometry 의 `fetched_at.slice(0,10)` |                                                                                                                                                                              |
| (추가 활용)                     | `buildings[]`, `data_notices[]`, `jurisdiction.code`                                 | `data_notices` 는 '정보 확인 필요' 배지로 표시 권장                                                                                                                          |

## 3. 지도 클릭 — `ParcelAtResult` ← `GET /v1/parcels/at?lng=&lat=`

FE 개발용 API 의 `ParcelAtResult` 는 `{ pnu }` 하나입니다(화면은 PNU 로 `/site` 에 이동한 뒤 필지를 다시 받습니다).
BE 응답은 2절의 필지 전체이므로, `getParcelAt` 이 바로 `Parcel` 을 돌려주게 바꾸면 `/v1/parcels/{pnu}` 재호출이 필요 없습니다.
범위 밖 좌표는 422, 필지 없음은 404 PARCEL_NOT_FOUND.

## 4. 법규 검토 — `RuleReview` ← `POST /v1/reviews` → `GET /v1/reviews/{id}`

흐름: `POST { pnu, as_of_date?, audience?: 'owner'|'architect', inputs? }` → 202 `{ review_id }` → `GET /v1/reviews/{id}` 를 `Retry-After`(1초) 간격으로 폴링.
바디에 `steps` 가 있으면 진행 중(`status: queued|running|failed`, 5단계 `steps[].state`), `items` 가 있으면 완료(`review_result@1.0`).

| FE                              | BE                                                                                                                 | 변환                                                                                                                                                                                                                            |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `supported`                     | `parcel.jurisdiction.supported` (미지원이면 `items`·`manual_checks` 빈 배열 + `data_notices` REGION_NOT_SUPPORTED) |                                                                                                                                                                                                                                 |
| `items[].id`                    | `category`                                                                                                         | BUILDING_COVERAGE→`bcr`, FLOOR_AREA_RATIO→`far`, PARKING_REQUIREMENT→`parking`, DRIVE_AISLE_WIDTH→`lane-width`, ZONING_USE·PARKING_STALL_SIZE→신규. FE 전용 `road`·`access` 는 BE 자동 항목에 없음(ROAD_ACCESS 는 manual_check) |
| `items[].standard`              | `applied.criteria_text`                                                                                            |                                                                                                                                                                                                                                 |
| `items[].applied`               | `applied.reference_value` 또는 uses/table 요약                                                                     | ZONING_USE·PARKING_STALL_SIZE·DRIVE_AISLE_WIDTH 는 표를 그대로 그리는 카드 권장                                                                                                                                                 |
| `items[].status` ('ok'/'check') | `REVIEWABLE` / `CONDITIONAL` / `MANUAL_CHECK`                                                                      | 3값으로 확장 권장 — 라벨 '검토 가능 / 조건부 검토 / 별도 확인 필요'                                                                                                                                                             |
| `items[].evidence`              | `basis[]` (법률→시행령→조례 트리) 중 L3_LOCAL 우선                                                                 | `law ← document_title`, `article ← article_label`, `effective ← effective_from`, `url ← url`. `RuleEvidence[]` 로 바꾸면 손실 없음                                                                                              |
| `items[].note`                  | `reason_code`                                                                                                      | NO_LOCAL_VALUE·ZONE_OVERLAY·OUT_OF_RANGE·DATA_MISSING·EXCEPTION_CLAUSE·NEEDS_USE_INPUT 한글화                                                                                                                                   |
| (없음)                          | `items[].explanation`                                                                                              | `display_source !== 'basis_only'` 면 쉬운 설명 표시                                                                                                                                                                             |
| `manual[]`                      | `manual_checks[]` (ROAD_ACCESS·SETBACK·HEIGHT_SUNLIGHT·SPECIAL_ZONE)                                               | FE 의 4항목(구역 종류별)과 **다른 집합**. `related_basis`·`triggered_by` 가 FE `hits` 역할                                                                                                                                      |
| `source.date`                   | `as_of_date`                                                                                                       |                                                                                                                                                                                                                                 |
| (없음)                          | `data_notices[]`, `disclaimer`, `ruleset_hash`, `engine_version`                                                   | `disclaimer` 는 화면 하단 고정 문구로                                                                                                                                                                                           |

## 5. 지원 지역 — `GET /v1/regions`

`{ supported: [ { code, name } ], message, roadmap }` — FE 의 `inSuseong` 하드코딩(`27260`)을 이 목록으로 대체할 수 있습니다.

## 6. 연동 순서 제안

1. `src/lib/api/beAdapters.ts` 에 BE JSON → `AddressHit` / `Parcel` / `RuleReview` 변환을 작성 (위 표 기준).
2. `address.ts`·`parcel.ts` 의 `fetch` 대상을 BE 경로로 바꾸고, 오류 바디 파싱을 `{ error: { code, message } }` 로 변경.
3. 법규 검토를 브라우저 생성(목업)에서 `POST /v1/reviews` 폴링으로 교체하고 상태를 3값으로 확장.
4. BE `.env` 의 `CORS_ORIGINS` 에 `http://localhost:5173` 추가(기본값은 3000). 또는 Vite `server.proxy` 로 `/v1` 을 BE 로 전달.
5. `server/` 는 삭제하거나 개발용(목업)으로만 남김.
