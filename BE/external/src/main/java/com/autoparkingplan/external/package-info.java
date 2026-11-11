/**
 * 외부 공공 API 클라이언트 (도로명주소·브이월드·건축HUB). 파이썬 {@code app.external} 이식판.
 *
 * <p>클라이언트가 돌려주는 공통 자료형 (계약, 파이썬 {@code app.external.types}):
 * <ul>
 *   <li>None(null) = 미확인. 규제 없음·0·허용으로 해석하지 않는다.</li>
 *   <li>모든 결과는 출처(source)와 조회 시각(fetched_at, UTC 오프셋이 붙은 {@link java.time.OffsetDateTime})을 가진다.</li>
 *   <li>좌표는 항상 EPSG:4326 [경도, 위도] GeoJSON.</li>
 * </ul>
 *
 * <p>구성: {@link com.autoparkingplan.external.JusoClient} (도로명주소 검색),
 * {@link com.autoparkingplan.external.VWorldClient} (연속지적도·토지이용계획·토지특성·토지임야),
 * {@link com.autoparkingplan.external.VWorldTileClient} (배경지도 타일 WMTS — 이것만 JSON 이 아니라 이미지를 돌려준다),
 * {@link com.autoparkingplan.external.BuildingHubClient} (건축물대장 표제부), {@link com.autoparkingplan.external.Pnu},
 * {@link com.autoparkingplan.external.ZoningCodes}, 공통 HTTP 처리 {@link com.autoparkingplan.external.HttpJsonClient}·
 * {@link com.autoparkingplan.external.Http}.
 */
package com.autoparkingplan.external;
