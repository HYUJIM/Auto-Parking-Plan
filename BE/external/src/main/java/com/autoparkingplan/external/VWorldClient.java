package com.autoparkingplan.external;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.autoparkingplan.common.http.HttpTransport;

import tools.jackson.databind.JsonNode;

/**
 * 브이월드 동기 클라이언트 (파이썬 {@code app.external.vworld.VWorldClient}). 상수·응답 해석은 {@link VWorld}.
 *
 * <p>baseUrlData / baseUrlNed 는 scheme+host(+port) 만 받는다(경로는 고정).
 * domain: 인증키 발급 시 등록한 서비스 URL. 키가 도메인에 묶여 있으면 넣어야 INCORRECT_KEY 를 피한다.
 * 스레드 안전 (필지 서비스가 토지이용계획·토지특성을 동시에 부른다).
 */
public final class VWorldClient implements VWorldApi, AutoCloseable {
    private final String apiKey;
    private final String domain;
    private final String baseUrlData;
    private final String baseUrlNed;
    private final HttpJsonClient http;

    /**
     * @param apiKey      인증키 (비어 있으면 IllegalArgumentException)
     * @param domain      null·빈 값이면 보내지 않는다
     * @param baseUrlData null 이면 {@link VWorld#VWORLD_BASE_URL}
     * @param baseUrlNed  null 이면 {@link VWorld#VWORLD_BASE_URL}
     * @param timeout     시간 제한(초, 0보다 커야 함)
     * @param transport   null 이면 기본 JDK 전송
     */
    public VWorldClient(String apiKey, String domain, String baseUrlData, String baseUrlNed, double timeout,
            HttpTransport transport) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("브이월드 인증키(api_key)가 비어 있습니다");
        }
        this.apiKey = apiKey;
        this.domain = domain;
        this.baseUrlData = baseUrlData != null ? baseUrlData : VWorld.VWORLD_BASE_URL;
        this.baseUrlNed = baseUrlNed != null ? baseUrlNed : VWorld.VWORLD_BASE_URL;
        this.http = new HttpJsonClient(VWorld.SERVICE, timeout, transport, List.of(apiKey));
    }

    public VWorldClient(String apiKey, String domain, String baseUrlData, String baseUrlNed, double timeout) {
        this(apiKey, domain, baseUrlData, baseUrlNed, timeout, null);
    }

    public VWorldClient(String apiKey, String domain, double timeout) {
        this(apiKey, domain, VWorld.VWORLD_BASE_URL, VWorld.VWORLD_BASE_URL, timeout, null);
    }

    public VWorldClient(String apiKey) {
        this(apiKey, null, VWorld.VWORLD_BASE_URL, VWorld.VWORLD_BASE_URL, Http.DEFAULT_TIMEOUT, null);
    }

    public String domain() {
        return domain;
    }

    public String baseUrlData() {
        return baseUrlData;
    }

    public String baseUrlNed() {
        return baseUrlNed;
    }

    /** 키 노출 방지. */
    @Override
    public String toString() {
        return "VWorldClient(base_url_data=" + PyStr.reprString(baseUrlData) + ", base_url_ned="
                + PyStr.reprString(baseUrlNed) + ")";
    }

    @Override
    public void close() {
        http.close();
    }

    // --- 내부 -------------------------------------------------------------------------------
    private Map<String, Object> auth() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", apiKey);
        if (domain != null && !domain.isEmpty()) {
            params.put("domain", domain);
        }
        return params;
    }

    private JsonNode call(String base, String path, Map<String, Object> params, String endpoint) {
        Map<String, Object> all = new LinkedHashMap<>(params);
        all.putAll(auth());
        HttpJsonClient.JsonResult r = http.getJson(Http.joinUrl(base, path), all, endpoint);
        int status = r.status();
        if (status >= 400) {
            // 4xx 라도 본문에 원천 오류코드가 있으면 그것을 쓰고, 없으면 HTTP 오류로 처리
            try {
                if (path.equals(VWorld.DATA_PATH)) {
                    VWorld.parseCadastralResponse(r.payload(), null, http::scrub);
                } else {
                    VWorld.nedRecords(r.payload(), endpoint, http::scrub);
                }
            } catch (UpstreamError exc) {
                exc.setStatusCode(status);
                throw exc;
            }
            throw new UpstreamError(VWorld.SERVICE, endpoint + " HTTP " + status + " 응답", false, status);
        }
        return r.payload();
    }

    private List<CadastralParcel> getFeatures(Map<String, Object> extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service", "data");
        params.put("version", "2.0");
        params.put("request", "GetFeature");
        params.put("data", VWorld.CADASTRAL_DATASET);
        params.put("format", "json");
        params.put("errorFormat", "json");
        params.put("crs", "EPSG:4326");
        params.put("geometry", "true");
        params.put("attribute", "true");
        params.put("size", 10);
        params.put("page", 1);
        params.putAll(extra);
        JsonNode payload = call(baseUrlData, VWorld.DATA_PATH, params, VWorld.CADASTRAL_DATASET);
        return VWorld.parseCadastralResponse(payload, Http.utcnow(), http::scrub);
    }

    private static Map<String, Object> nedParams(String pnu, int numOfRows) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pnu", pnu);
        params.put("format", "json");
        params.put("numOfRows", numOfRows);
        params.put("pageNo", 1);
        return params;
    }

    // --- 공개 메서드 ---------------------------------------------------------------------------

    /**
     * PNU 로 연속지적도 필지(경계·지번·주소·공시지가) 조회. 없으면 empty.
     *
     * @throws IllegalArgumentException PNU 형식 오류 (네트워크 호출 전)
     * @throws UpstreamError            조회 실패
     */
    @Override
    public Optional<CadastralParcel> getParcel(String pnu) {
        VWorld.requirePnu(pnu);
        List<CadastralParcel> parcels = getFeatures(Map.of("attrFilter", "pnu:=:" + pnu));
        List<CadastralParcel> exact = new ArrayList<>();
        for (CadastralParcel p : parcels) {
            if (p.pnu().equals(pnu)) {
                exact.add(p);
            }
        }
        if (!exact.isEmpty()) {
            return Optional.of(exact.getFirst());
        }
        return parcels.isEmpty() ? Optional.empty() : Optional.of(parcels.getFirst());
    }

    /**
     * 좌표(EPSG:4326 경도·위도)가 속한 필지. 없으면 empty. 경계 위라 여러 개면 첫 번째.
     *
     * @throws IllegalArgumentException 좌표가 유한하지 않거나 범위 밖
     */
    @Override
    public Optional<CadastralParcel> getParcelAt(double lng, double lat) {
        if (!(Double.isFinite(lng) && Double.isFinite(lat) && -180 <= lng && lng <= 180 && -90 <= lat && lat <= 90)) {
            throw new IllegalArgumentException(
                    "좌표가 올바르지 않습니다: lng=" + VWorld.floatRepr(lng) + ", lat=" + VWorld.floatRepr(lat));
        }
        List<CadastralParcel> parcels = getFeatures(Map.of("geomFilter", VWorld.formatPoint(lng, lat)));
        return parcels.isEmpty() ? Optional.empty() : Optional.of(parcels.getFirst());
    }

    /** 토지이용계획 속성(용도지역·지구·구역 등). [] = 조회는 성공했으나 지정 없음. 실패는 UpstreamError. */
    @Override
    public List<LandUseDesignation> getLandUse(String pnu) {
        VWorld.requirePnu(pnu);
        JsonNode payload = call(baseUrlNed, VWorld.LAND_USE_PATH, nedParams(pnu, 1000), "getLandUseAttr");
        return VWorld.parseLandUseResponse(payload, pnu, Http.utcnow(), http::scrub);
    }

    /** 토지특성속성조회(가장 최근 기준연도). 자료 없음 → empty. */
    public Optional<LandCharacteristics> getLandCharAttr(String pnu) {
        VWorld.requirePnu(pnu);
        JsonNode payload = call(baseUrlNed, VWorld.LAND_CHAR_PATH, nedParams(pnu, 1000), "getLandCharacteristics");
        return VWorld.parseLandCharacteristicsResponse(payload, pnu, Http.utcnow(), http::scrub);
    }

    /** 토지임야목록조회(현행 토지·임야대장의 면적·지목). 자료 없음 → empty. */
    public Optional<LandCharacteristics> getLandRegister(String pnu) {
        VWorld.requirePnu(pnu);
        JsonNode payload = call(baseUrlNed, VWorld.LADFRL_PATH, nedParams(pnu, 10), "ladfrlList");
        return VWorld.parseLadfrlResponse(payload, pnu, Http.utcnow(), http::scrub);
    }

    /**
     * 토지임야 + 토지특성을 합친 결과(면적·지목·도로접면·공시지가).
     *
     * <p>한쪽만 실패하면 나머지로 채우고 raw["_errors"] 에 실패 내용을 남긴다. 둘 다 실패하면 UpstreamError.
     * 둘 다 자료 없음이면 empty.
     */
    @Override
    public Optional<LandCharacteristics> getLandCharacteristics(String pnu) {
        VWorld.requirePnu(pnu);
        Map<String, UpstreamError> errors = new LinkedHashMap<>();
        LandCharacteristics chr = null;
        LandCharacteristics register = null;
        try {
            register = getLandRegister(pnu).orElse(null);
        } catch (UpstreamError exc) {
            errors.put("ladfrlList", exc);
        }
        try {
            chr = getLandCharAttr(pnu).orElse(null);
        } catch (UpstreamError exc) {
            errors.put("getLandCharacteristics", exc);
        }
        if (errors.size() == 2) {
            List<String> messages = new ArrayList<>();
            boolean retriable = false;
            Integer statusCode = null;
            for (UpstreamError e : errors.values()) {
                messages.add(e.message());
                retriable |= e.retriable();
                if (statusCode == null && e.statusCode() != null && e.statusCode() != 0) {
                    statusCode = e.statusCode();
                }
            }
            throw new UpstreamError(VWorld.SERVICE, "토지임야·토지특성 조회 모두 실패: " + String.join(" / ", messages), retriable,
                    statusCode);
        }
        Map<String, String> errorMessages = new LinkedHashMap<>();
        errors.forEach((k, v) -> errorMessages.put(k, v.message()));
        Optional<LandCharacteristics> merged =
                VWorld.mergeLandCharacteristics(pnu, chr, register, errorMessages, Http.utcnow());
        if (merged.isEmpty() && !errors.isEmpty()) {
            throw errors.values().iterator().next();
        }
        return merged;
    }
}
