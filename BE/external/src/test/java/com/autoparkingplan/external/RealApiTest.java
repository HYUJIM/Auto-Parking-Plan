package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 외부 API 연기(smoke) 시험 (파이썬 {@code tests/external/test_real_api.py}). 환경변수에 키가 있을 때만 실행된다.
 *
 * <pre>
 *   JUSO_API_KEY             도로명주소 검색 API 승인키
 *   VWORLD_API_KEY           브이월드 인증키 (VWORLD_DOMAIN: 키 발급 시 등록한 서비스 URL, 선택)
 *   BUILDING_HUB_SERVICE_KEY 공공데이터포털 서비스키 (선택)
 * </pre>
 * 예: {@code JUSO_API_KEY=... VWORLD_API_KEY=... ./gradlew :external:test --tests '*RealApiTest'}
 */
@Tag("real_api")
class RealApiTest {
    // 수성구 범어동 일대(범어네거리 근처) 좌표. 어떤 필지가 나오든 응답 모양만 확인한다.
    private static final double LNG = 128.6237;
    private static final double LAT = 35.8590;

    /** test_juso_real_search. */
    @Test
    @EnabledIfEnvironmentVariable(named = "JUSO_API_KEY", matches = ".+")
    void testJusoRealSearch() {
        try (JusoClient juso = new JusoClient(System.getenv("JUSO_API_KEY"), null, 5.0)) {
            List<AddressCandidate> result = juso.search("대구광역시 수성구 달구벌대로");
            assertThat(result).as("검색 결과가 비어 있음").isNotEmpty();
            for (AddressCandidate cand : result) {
                assertThat(Pnu.isValidPnu(cand.pnu())).isTrue();
                assertThat(cand.pnu()).startsWith("27260");
                assertThat(cand.fetchedAt()).isNotNull();
            }
        }
    }

    /** test_vworld_real_lookup. */
    @Test
    @EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
    void testVworldRealLookup() {
        try (VWorldClient vw = new VWorldClient(System.getenv("VWORLD_API_KEY"), System.getenv("VWORLD_DOMAIN"), 5.0)) {
            CadastralParcel parcel = vw.getParcelAt(LNG, LAT).orElseThrow();
            assertThat(Pnu.isValidPnu(parcel.pnu())).isTrue();
            assertThat(parcel.geometry()).isNotNull();
            assertThat(parcel.geometry().get("type").stringValue()).isEqualTo("MultiPolygon");
            CadastralParcel again = vw.getParcel(parcel.pnu()).orElseThrow();
            assertThat(again.pnu()).isEqualTo(parcel.pnu());
            List<LandUseDesignation> designations = vw.getLandUse(parcel.pnu());
            assertThat(designations.stream().anyMatch(d -> d.kind().equals("zoning")) || designations.isEmpty()).isTrue();
            Optional<LandCharacteristics> lc = vw.getLandCharacteristics(parcel.pnu());
            assertThat(lc.isEmpty() || lc.get().pnu().equals(parcel.pnu())).isTrue();
        }
    }

    /** test_building_hub_real. */
    @Test
    @EnabledIfEnvironmentVariable(named = "BUILDING_HUB_SERVICE_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "VWORLD_API_KEY", matches = ".+")
    void testBuildingHubReal() {
        CadastralParcel parcel;
        try (VWorldClient vw = new VWorldClient(System.getenv("VWORLD_API_KEY"), System.getenv("VWORLD_DOMAIN"), 5.0)) {
            parcel = vw.getParcelAt(LNG, LAT).orElse(null);
        }
        assertThat(parcel).isNotNull();
        try (BuildingHubClient hub = new BuildingHubClient(System.getenv("BUILDING_HUB_SERVICE_KEY"), null, 5.0)) {
            List<BuildingInfo> buildings = hub.getBuildings(parcel.pnu());
            assertThat(buildings).isNotNull();
        }
    }
}
