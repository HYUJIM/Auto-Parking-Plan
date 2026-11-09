package com.autoparkingplan.external;

import static com.autoparkingplan.external.Fixtures.jsonResponse;
import static com.autoparkingplan.external.Fixtures.load;
import static com.autoparkingplan.external.Fixtures.mockHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

/** 파이썬 {@code tests/external/test_building_hub.py}. */
class BuildingHubTest {
    private static final String SECRET = "SECRET-HUB-KEY+abc/def==";
    private static final String PNU = "2726010100190010004";

    private static BuildingHubClient hub(MockTransport http) {
        return new BuildingHubClient(SECRET, null, Http.DEFAULT_TIMEOUT, http);
    }

    /** test_parse_ok_list. */
    @Test
    void testParseOkList() {
        List<BuildingInfo> items = BuildingHub.parseTitleInfoResponse(load("synthetic_bldhub_ok.json"));
        assertThat(items).hasSize(2);
        BuildingInfo a = items.get(0);
        BuildingInfo b = items.get(1);
        assertThat(Arrays.asList(a.mainUse(), a.grossFloorArea(), a.buildingArea(), a.approvalDate()))
                .containsExactly("단독주택", 182.4, 98.6, "1994-05-12");
        assertThat(a.source()).isEqualTo("data.go.kr:BldRgstHubService/getBrTitleInfo");
        assertThat(a.fetchedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        // 0·빈 값은 미확인(null)
        assertThat(Arrays.asList(b.grossFloorArea(), b.buildingArea(), b.approvalDate())).containsExactly(null, 35.5, null);
        assertThat(b.raw().get("mainAtchGbCdNm").stringValue()).isEqualTo("부속건축물");
    }

    /** test_parse_single_item_object_and_empty. */
    @Test
    void testParseSingleItemObjectAndEmpty() {
        List<BuildingInfo> items = BuildingHub.parseTitleInfoResponse(load("synthetic_bldhub_single.json"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).grossFloorArea()).isEqualTo(612.3);
        assertThat(items.get(0).approvalDate()).isEqualTo("2003-08-20");
        assertThat(BuildingHub.parseTitleInfoResponse(load("synthetic_bldhub_empty.json"))).isEmpty();
        assertThat(BuildingHub.parseTitleInfoResponse(
                Json.parse("{\"response\": {\"header\": {\"resultCode\": \"03\", \"resultMsg\": \"NODATA_ERROR\"}}}"))).isEmpty();
    }

    /** test_parse_gateway_error. */
    @Test
    void testParseGatewayError() {
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> BuildingHub.parseTitleInfoResponse(load("observed_bldhub_invalid_key_http403.json"), 403));
        assertThat(e.retriable()).isFalse();
        assertThat(e.statusCode()).isEqualTo(403);
        assertThat(e.message()).isEqualTo("공공데이터포털 오류(30 SERVICE_KEY_IS_NOT_REGISTERED_ERROR)");
    }

    /** test_get_buildings_params. */
    @Test
    void testGetBuildingsParams() {
        MockTransport rec = mockHttp(load("synthetic_bldhub_ok.json"));
        BuildingHubClient hub = new BuildingHubClient(SECRET, "https://hub.example", Http.DEFAULT_TIMEOUT, rec);
        assertThat(hub.getBuildings(PNU)).hasSize(2);
        HttpRequest req = rec.requests().get(0);
        assertThat(List.of(req.uri().getHost(), req.uri().getPath()))
                .containsExactly("hub.example", "/1613000/BldRgstHubService/getBrTitleInfo");
        HttpRequest p = rec.lastRequest();
        assertThat(List.of(p.queryParam("sigunguCd"), p.queryParam("bjdongCd"), p.queryParam("platGbCd"), p.queryParam("bun"),
                p.queryParam("ji"))).containsExactly("27260", "10100", "0", "9001", "0004");
        assertThat(p.queryParam("_type")).isEqualTo("json");
        assertThat(p.queryParam("serviceKey")).isEqualTo(SECRET);
    }

    /** test_get_buildings_mountain_lot. */
    @Test
    void testGetBuildingsMountainLot() {
        MockTransport rec = mockHttp(load("synthetic_bldhub_empty.json"));
        assertThat(hub(rec).getBuildings("2726011400200120000")).isEmpty();
        assertThat(rec.lastRequest().queryParam("platGbCd")).isEqualTo("1");
    }

    /** test_encoded_service_key_is_decoded_once. */
    @Test
    void testEncodedServiceKeyIsDecodedOnce() {
        MockTransport rec = mockHttp(load("synthetic_bldhub_empty.json"));
        new BuildingHubClient("abc%2Bdef%3D%3D", null, Http.DEFAULT_TIMEOUT, rec).getBuildings(PNU);
        assertThat(rec.lastRequest().queryParam("serviceKey")).isEqualTo("abc+def==");
    }

    /** test_invalid_key_http_403. */
    @Test
    void testInvalidKeyHttp403() {
        MockTransport rec = mockHttp(jsonResponse(load("observed_bldhub_invalid_key_http403.json"), 403));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> hub(rec).getBuildings(PNU));
        assertThat(e.statusCode()).isEqualTo(403);
        assertThat(e.retriable()).isFalse();
        assertThat(e.getMessage()).doesNotContain(SECRET);
        assertThat(rec.requests()).hasSize(1);
    }

    /** test_xml_gateway_error. */
    @Test
    void testXmlGatewayError() {
        String xml = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                + "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>"
                + "<returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>";
        MockTransport rec = mockHttp(HttpResponse.of(200, "text/xml", xml.getBytes(StandardCharsets.UTF_8)));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> hub(rec).getBuildings(PNU));
        assertThat(e.retriable()).isTrue();
        assertThat(e.message()).contains("22");
        assertThat(e.message()).isEqualTo("공공데이터포털 오류(22 SERVICE ERROR)");
    }

    /** test_timeout_and_5xx. */
    @Test
    void testTimeoutAnd5xx() {
        MockTransport rec = mockHttp(new HttpTimeoutException("slow"));
        assertThatThrownBy(() -> hub(rec).getBuildings(PNU)).isInstanceOf(UpstreamError.class);
        assertThat(rec.requests()).hasSize(2);
        MockTransport rec2 = mockHttp(jsonResponse("{}", 500));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> hub(rec2).getBuildings(PNU));
        assertThat(e.statusCode()).isEqualTo(500);
        assertThat(rec2.requests()).hasSize(2);
    }

    /** test_bad_pnu. */
    @Test
    void testBadPnu() {
        try (BuildingHubClient hub = new BuildingHubClient(SECRET)) {
            assertThatThrownBy(() -> hub.getBuildings("abc")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
