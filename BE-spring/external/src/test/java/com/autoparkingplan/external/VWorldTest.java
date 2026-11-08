package com.autoparkingplan.external;

import static com.autoparkingplan.external.Fixtures.jsonResponse;
import static com.autoparkingplan.external.Fixtures.load;
import static com.autoparkingplan.external.Fixtures.mockHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 파이썬 {@code tests/external/test_vworld.py}. */
class VWorldTest {
    private static final String SECRET = "SECRET-VWORLD-KEY-5678";
    private static final String PNU = "2726010100190010004";

    private static VWorldClient client(MockTransport http) {
        return client(http, null, Http.DEFAULT_TIMEOUT);
    }

    private static VWorldClient client(MockTransport http, String domain, double timeout) {
        return new VWorldClient(SECRET, domain, "https://vw.example", "https://ned.example", timeout, http);
    }

    // ---------------------------------------------------------------- 연속지적도

    /** test_parse_cadastral_ok. */
    @Test
    void testParseCadastralOk() {
        List<CadastralParcel> parcels = VWorld.parseCadastralResponse(load("synthetic_vworld_cadastral_ok.json"));
        assertThat(parcels).hasSize(1);
        CadastralParcel parcel = parcels.get(0);
        assertThat(parcel.pnu()).isEqualTo(PNU);
        assertThat(parcel.jibun()).isEqualTo("9001-4대");
        assertThat(parcel.address()).isEqualTo("대구광역시 수성구 범어동 9001-4");
        assertThat(parcel.officialLandPrice()).isEqualTo(3850000L);
        assertThat(parcel.source()).isEqualTo("vworld:LP_PA_CBND_BUBUN");
        assertThat(parcel.fetchedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(parcel.geometry().get("type").stringValue()).isEqualTo("MultiPolygon");
        JsonNode first = parcel.geometry().get("coordinates").get(0).get(0).get(0);
        double lng = first.get(0).doubleValue();
        double lat = first.get(1).doubleValue();
        assertThat(lng).isStrictlyBetween(124.0, 132.0);
        assertThat(lat).isStrictlyBetween(33.0, 39.0);
        assertThat(parcel.raw().get("bonbun").stringValue()).isEqualTo("9001");
    }

    /** test_normalize_polygon_closes_ring_and_wraps. */
    @Test
    void testNormalizePolygonClosesRingAndWraps() {
        ObjectNode g = VWorld.normalizeMultipolygon(
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": [[[128.1, 35.1, 12.0], [128.2, 35.1], [128.2, 35.2]]]}"));
        assertThat(g).isEqualTo(Json.parse(
                "{\"type\": \"MultiPolygon\", \"coordinates\": [[[[128.1, 35.1], [128.2, 35.1], [128.2, 35.2], [128.1, 35.1]]]]}"));
    }

    /** test_normalize_swaps_lat_lng_order. */
    @Test
    void testNormalizeSwapsLatLngOrder() {
        ObjectNode g = VWorld.normalizeMultipolygon(Json.parse(
                "{\"type\": \"MultiPolygon\", \"coordinates\": [[[[35.1, 128.1], [35.1, 128.2], [35.2, 128.2], [35.1, 128.1]]]]}"));
        assertThat(g.get("coordinates").get(0).get(0).get(0)).isEqualTo(Json.parse("[128.1, 35.1]"));
    }

    static Stream<JsonNode> rejectGeoms() {
        return Stream.of(null, Json.parse("{\"type\": \"Point\", \"coordinates\": [1, 2]}"),
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": [[[1, 2]]]}"));
    }

    /** test_normalize_rejects. */
    @ParameterizedTest
    @MethodSource("rejectGeoms")
    void testNormalizeRejects(JsonNode geom) {
        assertThat(VWorld.normalizeMultipolygon(geom)).isNull();
    }

    /** test_get_parcel_params_and_result. */
    @Test
    void testGetParcelParamsAndResult() {
        MockTransport rec = mockHttp(load("synthetic_vworld_cadastral_ok.json"));
        CadastralParcel parcel = client(rec, "https://app.example", Http.DEFAULT_TIMEOUT).getParcel(PNU).orElseThrow();
        assertThat(parcel.pnu()).isEqualTo(PNU);
        HttpRequest req = rec.requests().get(0);
        assertThat(List.of(req.uri().getHost(), req.uri().getPath())).containsExactly("vw.example", "/req/data");
        HttpRequest p = rec.lastRequest();
        assertThat(p.queryParam("service")).isEqualTo("data");
        assertThat(p.queryParam("request")).isEqualTo("GetFeature");
        assertThat(p.queryParam("data")).isEqualTo("LP_PA_CBND_BUBUN");
        assertThat(p.queryParam("attrFilter")).isEqualTo("pnu:=:" + PNU);
        assertThat(p.queryParam("crs")).isEqualTo("EPSG:4326");
        assertThat(p.queryParam("format")).isEqualTo("json");
        assertThat(p.queryParam("geometry")).isEqualTo("true");
        assertThat(p.queryParam("attribute")).isEqualTo("true");
        assertThat(p.queryParam("key")).isEqualTo(SECRET);
        assertThat(p.queryParam("domain")).isEqualTo("https://app.example");
    }

    /** test_get_parcel_at_uses_point_filter. */
    @Test
    void testGetParcelAtUsesPointFilter() {
        MockTransport rec = mockHttp(load("synthetic_vworld_cadastral_ok.json"));
        assertThat(client(rec).getParcelAt(128.6261, 35.8605).orElseThrow().pnu()).isEqualTo(PNU);
        assertThat(rec.lastRequest().queryParam("geomFilter")).isEqualTo("POINT(128.62610000 35.86050000)");
        assertThat(rec.lastRequest().queryParams()).doesNotContainKey("domain");
        assertThatThrownBy(() -> client(rec).getParcelAt(Double.NaN, 35.0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_get_parcel_not_found_is_none. */
    @Test
    void testGetParcelNotFoundIsNone() {
        assertThat(client(mockHttp(load("synthetic_vworld_not_found.json"))).getParcel(PNU)).isEmpty();
    }

    /** test_get_parcel_invalid_key. */
    @Test
    void testGetParcelInvalidKey() {
        MockTransport rec = mockHttp(load("observed_vworld_data_invalid_key.json"));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).getParcel(PNU));
        assertThat(e.retriable()).isFalse();
        assertThat(e.message()).contains("INVALID_KEY");
        assertThat(e.getMessage()).doesNotContain(SECRET);
        assertThat(rec.requests()).hasSize(1);
    }

    /** test_get_parcel_system_error_body_is_retriable. */
    @Test
    void testGetParcelSystemErrorBodyIsRetriable() {
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> client(mockHttp(load("synthetic_vworld_system_error.json"))).getParcel(PNU));
        assertThat(e.retriable()).isTrue();
    }

    /** test_error_text_echoing_key_is_scrubbed. */
    @Test
    void testErrorTextEchoingKeyIsScrubbed() {
        ObjectNode payload = (ObjectNode) Json.parse("{\"response\": {\"status\": \"ERROR\", \"error\": {\"level\": \"1\", "
                + "\"code\": \"INVALID_TYPE\", \"text\": \"입력한 파라미터 값 : <" + SECRET + ">\"}}}");
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(mockHttp(payload)).getParcel(PNU));
        assertThat(e.getMessage()).doesNotContain(SECRET);
    }

    /** test_invalid_pnu_rejected_before_call. */
    @Test
    void testInvalidPnuRejectedBeforeCall() {
        MockTransport rec = mockHttp(Json.object());
        assertThatThrownBy(() -> client(rec).getParcel("12345")).isInstanceOf(IllegalArgumentException.class);
        assertThat(rec.requests()).isEmpty();
    }

    // ---------------------------------------------------------------- 토지이용계획

    /** test_parse_land_use_kinds_dedup_and_conflicts. */
    @Test
    void testParseLandUseKindsDedupAndConflicts() {
        List<LandUseDesignation> items = VWorld.parseLandUseResponse(load("synthetic_ned_landuse_ok.json"), PNU);
        Map<String, LandUseDesignation> byCode =
                items.stream().collect(Collectors.toMap(LandUseDesignation::code, Function.identity()));
        assertThat(items).hasSize(8); // UQA122 두 줄이 하나로 합쳐짐
        LandUseDesignation z = byCode.get("UQA122");
        // 접함+포함 → 가장 강한 포함
        assertThat(List.of(z.kind(), z.name(), z.conflict())).containsExactly("zoning", "제2종일반주거지역", "included");
        assertThat(z.raw().get("_merged_records").intValue()).isEqualTo(2);
        assertThat(z.basisLaw()).isNull();
        assertThat(z.source()).isEqualTo("vworld:getLandUseAttr");
        assertThat(z.fetchedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(byCode.get("UQA01X").kind()).isEqualTo("other");
        assertThat(List.of(byCode.get("UQQ300").kind(), byCode.get("UQQ300").conflict())).containsExactly("zone", "included");
        assertThat(List.of(byCode.get("UQF110").kind(), byCode.get("UQF110").conflict()))
                .containsExactly("district", "partially_included");
        assertThat(byCode.get("UOA120").kind()).isEqualTo("other");
        assertThat(List.of(byCode.get("ZA0011").kind(), byCode.get("ZA0011").conflict())).containsExactly("other", "adjacent");
        assertThat(List.of(byCode.get("UQS121").name(), byCode.get("UQS121").conflict()))
                .containsExactly("소로2류(폭 8M~10M)", "unknown");
    }

    /** test_parse_land_use_filters_other_pnu. */
    @Test
    void testParseLandUseFiltersOtherPnu() {
        assertThat(VWorld.parseLandUseResponse(load("synthetic_ned_landuse_ok.json"), "2726010100190019999")).isEmpty();
    }

    /** test_parse_land_use_empty_envelope. */
    @Test
    void testParseLandUseEmptyEnvelope() {
        assertThat(VWorld.parseLandUseResponse(load("synthetic_ned_empty.json"))).isEmpty();
    }

    /** test_parse_land_use_errors. */
    @ParameterizedTest
    @CsvSource({"observed_ned_landuse_invalid_key.json,false", "synthetic_ned_error_envelope.json,true"})
    void testParseLandUseErrors(String fixture, boolean retriable) {
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> VWorld.parseLandUseResponse(load(fixture)));
        assertThat(e).isNotNull();
        assertThat(e.retriable()).isEqualTo(retriable);
    }

    /** test_get_land_use_params. */
    @Test
    void testGetLandUseParams() {
        MockTransport rec = mockHttp(load("synthetic_ned_landuse_ok.json"));
        List<LandUseDesignation> items = client(rec).getLandUse(PNU);
        assertThat(items).anyMatch(d -> d.kind().equals("zoning"));
        HttpRequest req = rec.requests().get(0);
        assertThat(List.of(req.uri().getHost(), req.uri().getPath())).containsExactly("ned.example", "/ned/data/getLandUseAttr");
        HttpRequest p = rec.lastRequest();
        assertThat(p.queryParam("pnu")).isEqualTo(PNU);
        assertThat(p.queryParam("format")).isEqualTo("json");
        assertThat(p.queryParam("key")).isEqualTo(SECRET);
        assertThat(p.queryParam("numOfRows")).isEqualTo("1000");
        assertThat(p.queryParam("pageNo")).isEqualTo("1");
    }

    /** test_get_land_use_http_500. */
    @Test
    void testGetLandUseHttp500() {
        MockTransport rec = mockHttp(jsonResponse("{\"landUses\": {\"resultCode\": \"SYSTEM_ERROR\"}}", 500));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).getLandUse(PNU));
        assertThat(e.statusCode()).isEqualTo(500);
        assertThat(e.retriable()).isTrue();
        assertThat(rec.requests()).hasSize(2);
    }

    /** test_get_land_use_timeout. */
    @Test
    void testGetLandUseTimeout() {
        MockTransport rec = mockHttp(new HttpTimeoutException("slow"));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec, null, 0.2).getLandUse(PNU));
        assertThat(e.retriable()).isTrue();
        assertThat(rec.requests()).hasSize(2);
    }

    /** test_get_land_use_http_4xx_with_error_body. */
    @Test
    void testGetLandUseHttp4xxWithErrorBody() {
        MockTransport rec = mockHttp(jsonResponse(load("observed_ned_landuse_invalid_key.json"), 401));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).getLandUse(PNU));
        assertThat(e.statusCode()).isEqualTo(401);
        assertThat(e.retriable()).isFalse();
    }

    // ---------------------------------------------------------------- 토지특성·토지임야

    /** test_parse_land_characteristics_latest_year. */
    @Test
    void testParseLandCharacteristicsLatestYear() {
        LandCharacteristics lc = VWorld.parseLandCharacteristicsResponse(load("synthetic_ned_landchar_ok.json"), PNU)
                .orElseThrow();
        assertThat(lc.raw().get("stdrYear").stringValue()).isEqualTo("2026");
        assertThat(lc.landAreaM2()).isCloseTo(624.1, within(624.1 * 1e-6));
        assertThat(lc.landCategory()).isEqualTo("대");
        assertThat(lc.roadSide()).isEqualTo("세로한면(가)");
        assertThat(lc.officialLandPrice()).isEqualTo(3850000L);
        assertThat(lc.source()).isEqualTo("vworld:getLandCharacteristics");
    }

    /** test_parse_land_characteristics_unspecified_values_become_none. */
    @Test
    void testParseLandCharacteristicsUnspecifiedValuesBecomeNone() {
        ObjectNode payload = load("synthetic_ned_landchar_ok.json");
        for (JsonNode row : payload.get("landCharacteristicss").get("field")) {
            ((ObjectNode) row).put("roadSideCodeNm", "지정되지않음").put("pblntfPclnd", "0").put("lndpclAr", "");
        }
        LandCharacteristics lc = VWorld.parseLandCharacteristicsResponse(payload).orElseThrow();
        assertThat(lc.roadSide()).isNull();
        assertThat(lc.officialLandPrice()).isNull();
        assertThat(lc.landAreaM2()).isNull();
    }

    /** test_parse_land_characteristics_empty_and_errors. */
    @Test
    void testParseLandCharacteristicsEmptyAndErrors() {
        assertThat(VWorld.parseLandCharacteristicsResponse(load("synthetic_ned_empty.json"))).isEmpty();
        assertThatThrownBy(() -> VWorld.parseLandCharacteristicsResponse(load("observed_ned_landchar_invalid_key.json")))
                .isInstanceOf(UpstreamError.class);
    }

    /** test_parse_ladfrl. */
    @Test
    void testParseLadfrl() {
        LandCharacteristics reg = VWorld.parseLadfrlResponse(load("synthetic_ned_ladfrl_ok.json"), PNU).orElseThrow();
        assertThat(reg.landAreaM2()).isEqualTo(625.0);
        assertThat(reg.landCategory()).isEqualTo("대");
        assertThat(reg.roadSide()).isNull();
        assertThat(reg.officialLandPrice()).isNull();
        assertThat(reg.source()).isEqualTo("vworld:ladfrlList");
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> VWorld.parseLadfrlResponse(load("observed_ned_ladfrl_invalid_key.json")));
        assertThat(e.retriable()).isFalse();
        JsonNode empty = Json.parse(
                "{\"ladfrlVOList\": {\"ladfrlVOList\": [], \"error\": \"\", \"message\": \"\", \"totalCount\": \"0\"}}");
        assertThat(VWorld.parseLadfrlResponse(empty)).isEmpty();
    }

    /** 파이썬 {@code _route(mapping)}: 요청 경로별 결과 (JSON·응답·예외). */
    private static MockTransport.Handler route(Map<String, Object> mapping) {
        return request -> {
            Object out = mapping.get(request.uri().getPath());
            if (out instanceof IOException e) {
                throw e;
            }
            return out instanceof HttpResponse r ? r : HttpResponse.json(200, out);
        };
    }

    /** test_get_land_characteristics_merges_sources. */
    @Test
    void testGetLandCharacteristicsMergesSources() {
        MockTransport http = mockHttp(route(Map.of(
                "/ned/data/ladfrlList", load("synthetic_ned_ladfrl_ok.json"),
                "/ned/data/getLandCharacteristics", load("synthetic_ned_landchar_ok.json"))));
        LandCharacteristics lc = client(http).getLandCharacteristics(PNU).orElseThrow();
        assertThat(lc.landAreaM2()).isEqualTo(625.0); // 토지대장(ladfrlList) 면적 우선
        assertThat(lc.officialLandPrice()).isEqualTo(3850000L);
        assertThat(lc.roadSide()).isEqualTo("세로한면(가)");
        assertThat(lc.source()).isEqualTo("vworld:ladfrlList+vworld:getLandCharacteristics");
        assertThat(lc.raw().get("_errors")).isEqualTo(Json.object());
        assertThat(lc.raw().get("ladfrlList").get("lndpclAr").stringValue()).isEqualTo("625.0");
    }

    /** test_get_land_characteristics_partial_when_one_fails. */
    @Test
    void testGetLandCharacteristicsPartialWhenOneFails() {
        MockTransport http = mockHttp(route(Map.of(
                "/ned/data/ladfrlList", load("synthetic_ned_ladfrl_ok.json"),
                "/ned/data/getLandCharacteristics", new HttpTimeoutException("slow"))));
        LandCharacteristics lc = client(http).getLandCharacteristics(PNU).orElseThrow();
        assertThat(lc.landAreaM2()).isEqualTo(625.0);
        assertThat(lc.landCategory()).isEqualTo("대");
        assertThat(lc.roadSide()).isNull();
        assertThat(lc.officialLandPrice()).isNull();
        assertThat(lc.raw().get("_errors").has("getLandCharacteristics")).isTrue();
        assertThat(lc.source()).isEqualTo("vworld:ladfrlList");
    }

    /** test_get_land_characteristics_both_fail. */
    @Test
    void testGetLandCharacteristicsBothFail() {
        UpstreamError e = catchThrowableOfType(UpstreamError.class,
                () -> client(mockHttp(jsonResponse("{}", 503))).getLandCharacteristics(PNU));
        assertThat(e.retriable()).isTrue();
        assertThat(e.statusCode()).isEqualTo(503);
    }

    /** test_get_land_characteristics_no_data. */
    @Test
    void testGetLandCharacteristicsNoData() {
        assertThat(client(mockHttp(load("synthetic_ned_empty.json"))).getLandCharacteristics(PNU)).isEmpty();
    }

    /** test_merge_helper_none. */
    @Test
    void testMergeHelperNone() {
        assertThat(VWorld.mergeLandCharacteristics(PNU, null, null)).isEmpty();
    }

    /** test_constructor_and_repr. */
    @Test
    void testConstructorAndRepr() {
        assertThatThrownBy(() -> new VWorldClient("")).isInstanceOf(IllegalArgumentException.class);
        try (VWorldClient c = new VWorldClient(SECRET)) {
            assertThat(c.toString()).doesNotContain(SECRET)
                    .isEqualTo("VWorldClient(base_url_data='https://api.vworld.kr', base_url_ned='https://api.vworld.kr')");
        }
    }
}
