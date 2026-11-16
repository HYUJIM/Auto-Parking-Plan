package com.autoparkingplan.external;

import java.util.List;
import java.util.Optional;

/**
 * 필지 조회 기능 (필지 서비스가 쓰는 {@link VWorldClient} 의 메서드). 파이썬판은 덕 타이핑으로 가짜 객체를 끼워 넣었으므로
 * 자바판은 이 인터페이스로 바꿔 끼운다 (테스트용 가짜 구현).
 */
public interface VWorldApi {
    /** PNU 로 연속지적도 필지(경계·지번·주소·공시지가) 조회. 없으면 empty. */
    Optional<CadastralParcel> getParcel(String pnu);

    /** 좌표(EPSG:4326 경도·위도)가 속한 필지. 없으면 empty. */
    Optional<CadastralParcel> getParcelAt(double lng, double lat);

    /** 토지이용계획 속성(용도지역·지구·구역 등). [] = 조회는 성공했으나 지정 없음. 실패는 UpstreamError. */
    List<LandUseDesignation> getLandUse(String pnu);

    /** 토지임야 + 토지특성을 합친 결과(면적·지목·도로접면·토지형상·지형높이·토지이용상황·공시지가). 둘 다 자료 없음이면 empty. */
    Optional<LandCharacteristics> getLandCharacteristics(String pnu);

    /**
     * 경계 상자(EPSG:4326)와 겹치는 건물 윤곽선 ('도로명주소 건물'). [] = 조회는 성공했으나 건물 없음. 실패는 UpstreamError.
     * 건물 정보를 주지 않는 구현(시험용 가짜 등)은 기본값 [] 을 쓴다.
     */
    default List<BuildingFootprint> getBuildingFootprints(double minLng, double minLat, double maxLng, double maxLat) {
        return List.of();
    }
}
