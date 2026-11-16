package com.autoparkingplan.external;

import tools.jackson.databind.node.ObjectNode;

/**
 * 건물 윤곽선 하나 — 브이월드 2D 데이터 API '도로명주소 건물'(LT_C_SPBD).
 *
 * <p>도로명주소가 붙은 건물만 들어 있다 (주소 없는 부속 건물·가설물은 빠질 수 있다). 건물관리번호 앞자리는 건물을 처음 등록할 때의
 * 필지번호라서 합필·분할 뒤의 필지번호와 다를 수 있다 — 필지와의 관계는 번호가 아니라 경계가 겹치는지로 판단한다.
 *
 * @param id       건물관리번호 (bd_mgt_sn). 없으면 null
 * @param name     건물명 (buld_nm, 동 이름이 있으면 덧붙임). 없으면 null
 * @param floors   지상 층수 (gro_flo_co). 없거나 0 이하이면 null
 * @param geometry 윤곽선 MultiPolygon (EPSG:4326, [경도, 위도], 고리 닫힘)
 */
public record BuildingFootprint(String id, String name, Integer floors, ObjectNode geometry) {
}
