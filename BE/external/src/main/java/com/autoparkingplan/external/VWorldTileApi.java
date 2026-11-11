package com.autoparkingplan.external;

import java.util.Optional;

/** 배경지도 타일 조회 기능 ({@link VWorldTileClient}). 서버 테스트는 이 인터페이스로 가짜 구현을 끼운다. */
public interface VWorldTileApi {
    /**
     * 타일 한 장. 원천에 그 타일이 없으면(서비스 제공영역 밖 등) empty.
     *
     * @throws IllegalArgumentException 줌·타일 번호가 범위 밖 (네트워크 호출 전)
     * @throws UpstreamError            원천 장애·시간 초과·인증키 오류
     */
    Optional<VWorldTiles.Tile> getTile(VWorldTiles.Layer layer, int z, int x, int y);
}
