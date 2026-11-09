package com.autoparkingplan.external;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;

import tools.jackson.databind.JsonNode;

/**
 * 건축HUB 건축물대장 표제부 동기 클라이언트 (파이썬 {@code app.external.building_hub.BuildingHubClient}).
 * 상수·응답 해석은 {@link BuildingHub}. baseUrl 은 scheme+host(+port) 만 받는다. 스레드 안전.
 */
public final class BuildingHubClient implements BuildingHubApi, AutoCloseable {
    private final String serviceKey;
    private final String baseUrl;
    private final HttpJsonClient http;

    /**
     * @param serviceKey 공공데이터포털 서비스키 (비어 있으면 IllegalArgumentException). '%' 가 있으면 한 번 디코딩한다
     *                   (파이썬 {@code urllib.parse.unquote}: '+' 는 그대로).
     * @param baseUrl    null 이면 {@link BuildingHub#BUILDING_HUB_BASE_URL}
     * @param timeout    시간 제한(초, 0보다 커야 함)
     * @param transport  null 이면 기본 JDK 전송
     */
    public BuildingHubClient(String serviceKey, String baseUrl, double timeout, HttpTransport transport) {
        if (serviceKey == null || serviceKey.isEmpty()) {
            throw new IllegalArgumentException("공공데이터포털 서비스키(service_key)가 비어 있습니다");
        }
        String key = serviceKey.contains("%") ? PyStr.unquote(serviceKey) : serviceKey;
        this.serviceKey = key;
        this.baseUrl = baseUrl != null ? baseUrl : BuildingHub.BUILDING_HUB_BASE_URL;
        this.http = new HttpJsonClient(BuildingHub.SERVICE, timeout, transport, List.of(key, serviceKey));
    }

    public BuildingHubClient(String serviceKey, String baseUrl, double timeout) {
        this(serviceKey, baseUrl, timeout, null);
    }

    public BuildingHubClient(String serviceKey) {
        this(serviceKey, BuildingHub.BUILDING_HUB_BASE_URL, Http.DEFAULT_TIMEOUT, null);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 키 노출 방지. */
    @Override
    public String toString() {
        return "BuildingHubClient(base_url=" + PyStr.reprString(baseUrl) + ")";
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * PNU 의 건축물대장 표제부(동별 1건). [] = 건축물 없음(또는 대장 미등재).
     *
     * @param maxRows numOfRows (파이썬 기본 100)
     * @throws IllegalArgumentException PNU 형식 오류 (네트워크 호출 전)
     * @throws UpstreamError            조회 실패 (게이트웨이 오류·HTTP 4xx·5xx·시간 초과)
     */
    public List<BuildingInfo> getBuildings(String pnu, int maxRows) {
        Pnu.ParsedPnu p = Pnu.parsePnu(pnu);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceKey", serviceKey);
        params.put("sigunguCd", p.sigunguCode());
        params.put("bjdongCd", p.bjdongCode());
        params.put("platGbCd", p.platGbCd());
        params.put("bun", p.bun());
        params.put("ji", p.ji());
        params.put("_type", "json");
        params.put("numOfRows", maxRows);
        params.put("pageNo", 1);
        HttpResponse resp = http.get(Http.joinUrl(baseUrl, BuildingHub.TITLE_INFO_PATH), params, "getBrTitleInfo");
        JsonNode payload;
        try {
            payload = http.decodeJson(resp, "getBrTitleInfo");
        } catch (UpstreamError exc) {
            // 게이트웨이는 _type=json 이어도 XML 오류를 줄 때가 있다
            String text = resp.text();
            Matcher reason = BuildingHub.XML_REASON_RE.matcher(text);
            if (reason.find()) {
                Matcher msg = BuildingHub.XML_ERRMSG_RE.matcher(text);
                throw BuildingHub.gatewayError(reason.group(1), msg.find() ? msg.group(1) : null, resp.status());
            }
            throw exc;
        }
        List<BuildingInfo> result = BuildingHub.parseTitleInfoResponse(payload, Http.utcnow(), resp.status());
        if (resp.status() >= 400) {
            throw new UpstreamError(BuildingHub.SERVICE, "getBrTitleInfo HTTP " + resp.status() + " 응답", false, resp.status());
        }
        return result;
    }

    @Override
    public List<BuildingInfo> getBuildings(String pnu) {
        return getBuildings(pnu, 100);
    }
}
