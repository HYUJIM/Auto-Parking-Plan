package com.autoparkingplan.external;

import java.util.List;

/**
 * 건축물대장 조회 기능 (필지 서비스가 쓰는 {@link BuildingHubClient} 의 메서드). 파이썬판은 덕 타이핑으로 가짜 객체를
 * 끼워 넣었으므로 자바판은 이 인터페이스로 바꿔 끼운다 (테스트용 가짜 구현).
 */
public interface BuildingHubApi {
    /** PNU 의 건축물대장 표제부(동별 1건). [] = 건축물 없음(또는 대장 미등재). */
    List<BuildingInfo> getBuildings(String pnu);
}
