package com.autoparkingplan.server.config;

import com.autoparkingplan.external.BuildingHubApi;
import com.autoparkingplan.external.JusoApi;
import com.autoparkingplan.external.VWorldApi;

/** 설정된 키가 있는 외부 API 클라이언트만 담는다 (없으면 null). 파이썬 app.container.build_external_clients. */
public record ExternalClients(JusoApi juso, VWorldApi vworld, BuildingHubApi building) {
}
