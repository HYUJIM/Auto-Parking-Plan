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
| 기존 건물 샘플 (BE 구현 전 계약대로 손으로 쓴 것)                   | `src/lib/api/__fixtures__/simulation_buildings_split.json`                        |
| 검증                                                                | `src/lib/api/beAdapters.test.ts` · `base.test.ts` · `review.test.ts` (`npm test`) |

## 호출 대응

| 화면               | BE 공개 API                                                                              | FE 함수                                                                           |
| ------------------ | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 주소 검색 자동완성 | `GET /v1/address/search?q=`                                                              | `searchAddress()` — 404 ADDRESS_NOT_FOUND 는 빈 결과                              |
| 이용화면 필지      | `GET /v1/parcels/{pnu}`                                                                  | `resolveSite()` — 404 PARCEL_NOT_FOUND 는 '대기'                                  |
| 지도 클릭 → 필지   | `GET /v1/parcels/at?lng=&lat=`                                                           | `getParcelAt()` — 404 는 null                                                     |
| 법규 검토          | `POST /v1/reviews` → 202 → `GET /v1/reviews/{id}` 폴링 (`Retry-After`)                   | `getRuleReview()` — 60초 상한, failed 는 SERVER                                   |
| 배치안             | `POST /v1/reviews/{id}/parking-simulations` (`include_unverified`, `existing_buildings`) | `getParkingSimulation()` · 탭 안 기억은 `simulationFor()`                         |
| 기본 지도 배경     | `GET /v1/map/config` → `<img>` 로 `GET /v1/map/tiles/{layer}/{z}/{x}/{y}`                | `loadTileChoice()` (`src/lib/map/tileConfig.ts`) — 실패·`tiles: null` 은 OSM·Esri |

## 연동 전 대응표와 달라진 점

- **상태 3값**: `REVIEWABLE / CONDITIONAL / MANUAL_CHECK` 를 그대로 FE 모델로 쓰고 라벨만 `ko.json` 에서 (검토 가능 / 조건부 검토 / 별도 확인 필요).
- **근거**: 항목마다 `basis[]` 트리 전체(법률 → 시행령 → 조례 → 고시)를 보여 줍니다. 대응표의 "L3 우선 1건" 축약은 쓰지 않습니다.
- **배치안 레이어**: BE 의 `layers` 는 `{ stalls, aisles: FeatureCollection, gate, gate_opening, usable_area: Feature }` 묶음이라
  어댑터가 FeatureCollection 하나로 펴고 종류를 `properties.layer` 에 적습니다 (`toLayoutLayers`). 지도 어댑터(`setLayout`)는 이 모양만 받습니다.
- **지도 클릭**: `GET /v1/parcels/at` 가 필지 전체를 주므로 `getParcelAt()` 이 바로 `Parcel` 을 돌려줍니다 (재조회 없음).
- **시범 지역 밖 필지**: `parcel.jurisdiction.supported === false` 면 FE 가 `POST /v1/reviews` 를 보내지 않고 안내만 보여 줍니다
  (BE 도 빈 결과 + `REGION_NOT_SUPPORTED` 만 주므로 결과는 같습니다).
- **FE 전용 항목 삭제**: 목업 엔진의 '도로 접도'·'진출입 제한' 자동 항목, 도로 폭·접도 길이·도로 중심선,
  공시지가 기준연도는 BE 응답에 없어 화면에서 뺐습니다. 접도는 `road_side`(도로접면) 문구만 표시합니다.
- **토지 특성(형상·지세·이용상황)**: BE 필지 응답의 `land_shape`·`terrain_height`·`land_use_status`(브이월드 토지특성
  `getLandCharacteristics` 의 `tpgrphFrmCodeNm`·`tpgrphHgCodeNm`·`ladUseSittnNm`)를 `toParcel` 이 `Parcel.landShape`·`terrainHeight`·`landUseStatus` 로 옮깁니다.
  '지정되지않음'·'-'·빈 값은 BE 가 `null` 로 바꿔 보내고(`road_side` 와 같은 규칙), FE 는 `null`·빈 문자열·키 없음(예전 캐시·예전 BE)을 `undefined` 로만 바꿀 뿐 값을 거르지 않습니다.
  BE 가 다시 조회하다 토지특성만 실패하면 세 값은 이전 값을 유지하지만 `road_side` 는 `null` 이 될 수 있습니다 (`BE/README.md` 필지 응답 설명).
- **501 NOT_READY 제거**: BE 에는 없는 상태입니다. 503 은 `error.code` 로 `SERVICE_BUSY → BUSY`, `UPSTREAM_UNAVAILABLE → UPSTREAM` 을 구분합니다.

## 기존 건물 (`existing_buildings`)

BE 는 필지와 겹치는 건물 외곽선(브이월드 '도로명주소 건물' 레이어)을 받아, 기본으로 건물 + 이격(`clearance_m`, 1.0 m)을 주차구획·차로·진입로를 놓는 영역에서 뺍니다.

**요청** — `POST /v1/reviews/{id}/parking-simulations` 바디에 `"existing_buildings": "keep" | "ignore"` (생략 = `keep`, 그 밖의 값은 422).
FE 는 철거 가정일 때만 `"ignore"` 를 보내고 기본 요청은 지금처럼 키 없이 보냅니다 (생략이 곧 `keep` 이라 같은 계산이고, 이 키를 모르는 BE 에도 기본 요청이 그대로 통함).
`simulationFor()` 는 검토 id · 참고 배치 여부 · `keep`/`ignore` 마다 따로 기억합니다.

**응답** — `simulated: true` 일 때만 붙습니다 (그 밖에는 없거나 `null`). `params.existing_buildings` 에도 요청 값이 메아리로 옵니다.

```json
"existing_buildings": {
  "mode": "keep",
  "status": "applied",
  "count": 1,
  "area_m2": 98.6,
  "clearance_m": 1.0,
  "source": "브이월드 도로명주소 건물",
  "features": { "type": "FeatureCollection", "features": [
    { "type": "Feature", "geometry": { "type": "Polygon", "coordinates": [] }, "properties": { "id": "…", "name": null, "floors": 2 } }
  ] }
}
```

| BE                        | FE (`ParkingSimulation.existingBuildings`, `toExistingBuildings`)                                                                                       |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mode` keep · ignore      | `mode` — 다른 값이면 `params.existing_buildings` → `status` 로 추정                                                                                     |
| `status`                  | `applied`(keep · 1동 이상) · `ignored`(ignore · 1동 이상) · `none`(건물 없음) · `unavailable`(조회 실패) — 모르는 값은 `unavailable`(외곽선 없음 · 0동) |
| `count`                   | `count` — 숫자가 아니면 받은 외곽선 수                                                                                                                  |
| `area_m2` · `clearance_m` | `areaM2` · `clearanceM` — 유한한 수가 아니면 `null`                                                                                                     |
| `features` (EPSG:4326)    | `footprints[]` — Polygon · MultiPolygon 이고 좌표 모양이 맞는 것만 (`{ id, name, floors, geometry }`)                                                   |
| 없음 · `simulated: false` | `null` (예전 응답과 같게 화면에 아무것도 더하지 않음)                                                                                                   |

화면: 주차 배치 결과 카드의 지도 아래 안내 한 줄 · '기존 건물 유지 / 철거 가정' 전환(`count > 0` 이고 `applied`·`ignored` 일 때) ·
두 지도(현황 부지 · 주차 배치 결과)의 건물 외곽선과 범례 · DXF 의 `EXISTING_BUILDING` 계층과 처리 방식 안내 줄. 판단은 `src/lib/report/existingBuildings.ts`.

**새 `constraint_reasons`** (결과 전체와 배치안마다) — 한글은 `ko.json` 의 `site.layout.constraintReasons`

| 코드                             | 뜻                                                             |
| -------------------------------- | -------------------------------------------------------------- |
| `EXISTING_BUILDINGS_EXCLUDED`    | 기존 건물(+ 이격)을 배치 영역에서 뺐음                         |
| `EXISTING_BUILDINGS_UNAVAILABLE` | 건물 조회 실패 — 건물 없이 계산                                |
| `SITE_SPLIT_BY_BUILDINGS`        | 건물로 대지가 나뉘어 두 곳 이상에 배치, 영역마다 출입구 하나씩 |

지도 아래 안내 문장이 이미 말한 코드는 이유 목록에서 되풀이하지 않습니다.

**건물로 나뉜 대지의 레이어** (`SITE_SPLIT_BY_BUILDINGS`) — `gate` 는 여전히 Feature 하나지만 geometry 가 `MultiPoint`(영역마다 한 점),
`gate_opening` 은 `MultiLineString`, `usable_area` 는 `MultiPolygon` 입니다. 나뉘지 않으면 지금처럼 Point · LineString · Polygon.
`metrics` 에는 출입구 수가 없어 FE 는 도형에서 셉니다 — 어댑터(`toLayoutLayers`)가 `MultiPoint`·`MultiLineString` 을 조각마다
`Point`·`LineString` feature 하나로 나누고(`properties.part` = 0, 1, …) `gateInfo` 가 출입구 feature 수를 셉니다 ('출입구 2개소').
지도·DXF 는 출입구 점마다 표시를 하나씩 그립니다.
