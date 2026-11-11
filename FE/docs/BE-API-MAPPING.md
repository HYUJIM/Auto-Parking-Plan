# FE ↔ BE API 대응 — 구현됨

BE(`../BE`, Spring Boot) 공개 API 연동은 **완료**되었습니다. 이 문서는 연동 전 작성한 필드 대응표를 대신해 "어디를 보면 되는지"만 남깁니다.

| 무엇                                                                | 어디                                                                              |
| ------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| BE 응답 모양(와이어 타입, snake_case)                               | [`src/lib/api/beTypes.ts`](../src/lib/api/beTypes.ts)                             |
| BE JSON → FE 모델 변환 (좌표 순서·경계 링·용도지역·상태 3값·레이어) | [`src/lib/api/beAdapters.ts`](../src/lib/api/beAdapters.ts) — 순수 함수           |
| FE 모델 (화면이 쓰는 타입)                                          | [`src/lib/api/types.ts`](../src/lib/api/types.ts)                                 |
| 호출 함수                                                           | `address.ts` · `parcel.ts` · `review.ts` (`src/lib/api/`)                         |
| 오류 바디 `{ error: { code, message, details? } }` → `ApiError`     | [`src/lib/api/base.ts`](../src/lib/api/base.ts) `toApiError` / `classify`         |
| 어댑터 입력으로 쓰는 BE 응답 원문                                   | `src/lib/api/__fixtures__/*.json` (실행 중인 BE 에서 받은 그대로, Prettier 제외)  |
| 검증                                                                | `src/lib/api/beAdapters.test.ts` · `base.test.ts` · `review.test.ts` (`npm test`) |

## 호출 대응

| 화면               | BE 공개 API                                                               | FE 함수                                                                           |
| ------------------ | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 주소 검색 자동완성 | `GET /v1/address/search?q=`                                               | `searchAddress()` — 404 ADDRESS_NOT_FOUND 는 빈 결과                              |
| 이용화면 필지      | `GET /v1/parcels/{pnu}`                                                   | `resolveSite()` — 404 PARCEL_NOT_FOUND 는 '대기'                                  |
| 지도 클릭 → 필지   | `GET /v1/parcels/at?lng=&lat=`                                            | `getParcelAt()` — 404 는 null                                                     |
| 법규 검토          | `POST /v1/reviews` → 202 → `GET /v1/reviews/{id}` 폴링 (`Retry-After`)    | `getRuleReview()` — 60초 상한, failed 는 SERVER                                   |
| 배치안             | `POST /v1/reviews/{id}/parking-simulations` (`include_unverified`)        | `getParkingSimulation()`                                                          |
| 기본 지도 배경     | `GET /v1/map/config` → `<img>` 로 `GET /v1/map/tiles/{layer}/{z}/{x}/{y}` | `loadTileChoice()` (`src/lib/map/tileConfig.ts`) — 실패·`tiles: null` 은 OSM·Esri |

## 연동 전 대응표와 달라진 점

- **상태 3값**: `REVIEWABLE / CONDITIONAL / MANUAL_CHECK` 를 그대로 FE 모델로 쓰고 라벨만 `ko.json` 에서 (검토 가능 / 조건부 검토 / 별도 확인 필요).
- **근거**: 항목마다 `basis[]` 트리 전체(법률 → 시행령 → 조례 → 고시)를 보여 줍니다. 대응표의 "L3 우선 1건" 축약은 쓰지 않습니다.
- **배치안 레이어**: BE 의 `layers` 는 `{ stalls, aisles: FeatureCollection, gate, gate_opening, usable_area: Feature }` 묶음이라
  어댑터가 FeatureCollection 하나로 펴고 종류를 `properties.layer` 에 적습니다 (`toLayoutLayers`). 지도 어댑터(`setLayout`)는 이 모양만 받습니다.
- **지도 클릭**: `GET /v1/parcels/at` 가 필지 전체를 주므로 `getParcelAt()` 이 바로 `Parcel` 을 돌려줍니다 (재조회 없음).
- **시범 지역 밖 필지**: `parcel.jurisdiction.supported === false` 면 FE 가 `POST /v1/reviews` 를 보내지 않고 안내만 보여 줍니다
  (BE 도 빈 결과 + `REGION_NOT_SUPPORTED` 만 주므로 결과는 같습니다).
- **FE 전용 항목 삭제**: 목업 엔진의 '도로 접도'·'진출입 제한' 자동 항목, 도로 폭·접도 길이·도로 중심선, 토지 특성(형상·지세·이용상황),
  공시지가 기준연도는 BE 응답에 없어 화면에서 뺐습니다. 접도는 `road_side`(도로접면) 문구만 표시합니다.
- **501 NOT_READY 제거**: BE 에는 없는 상태입니다. 503 은 `error.code` 로 `SERVICE_BUSY → BUSY`, `UPSTREAM_UNAVAILABLE → UPSTREAM` 을 구분합니다.
