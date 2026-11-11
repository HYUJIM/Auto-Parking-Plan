package com.autoparkingplan.server.config;

import com.autoparkingplan.external.VWorldTileApi;

/** 배경지도 타일 클라이언트 묶음. 브이월드 키가 설정돼 있을 때만 들어 있다 (없으면 null) — {@link ExternalClients} 와 같은 방식. */
public record MapTiles(VWorldTileApi vworld) {
}
