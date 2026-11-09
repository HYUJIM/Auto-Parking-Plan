package com.autoparkingplan.external;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공공데이터포털 국토교통부_건축HUB_건축물대장정보 서비스 — 표제부 조회(선택 정보)의 상수·응답 해석
 * (파이썬 {@code app.external.building_hub} 의 모듈 함수). 클라이언트는 {@link BuildingHubClient}.
 *
 * <pre>
 * 요청: GET https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo
 *       serviceKey(필수), sigunguCd(필수, 시군구코드 5), bjdongCd(필수, 법정동코드 5),
 *       platGbCd(대지구분코드 0:대지 1:산 2:블록), bun(번 4), ji(지 4), startDate, endDate,
 *       _type(xml|json, 생략 시 xml), numOfRows, pageNo
 * 응답: response.header{resultCode, resultMsg}, response.body{items{item}, numOfRows, pageNo, totalCount}
 *       item 주요 필드: mainPurpsCdNm(주용도코드명), totArea(연면적 ㎡), archArea(건축면적 ㎡), platArea(대지면적 ㎡),
 *       useAprDay(사용승인일 YYYYMMDD), bldNm, dongNm, mainAtchGbCdNm(주부속구분), platPlc, newPlatPlc ...
 * 출처: https://www.data.go.kr/data/15134735/openapi.do (페이지에 포함된 OpenAPI(Swagger) 명세, 2026-11-06 확인)
 *
 * 인증 오류(2026-11-06 실호출 확인): HTTP 403 +
 *       {"OpenAPI_ServiceResponse": {"cmmMsgHeader": {"errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
 *                                                     "returnAuthMsg": "...", "returnReasonCode": "30"}}}
 * 게이트웨이 오류코드(같은 페이지 '오픈API 에러코드 안내'): 01 APPLICATION_ERROR, 04 HTTP_ERROR, 05 SERVICETIMEOUT_ERROR,
 *       10 INVALID_REQUEST_PARAMETER_ERROR, 12 NO_OPENAPI_SERVICE_ERROR, 20 SERVICE_KEY_IS_NULL/PERMISSION_DENIED/
 *       SERVICE_ACCESS_DENIED_ERROR, 22·23 호출량 초과, 29 BLACKLIST_IP_ACCESS_ERROR, 30 키 미등록, 31 키 기한 만료
 *
 * [미확인] JSON 응답에서 items 가 비었을 때의 모양(빈 문자열/누락), item 이 1건일 때 객체로 오는지 배열로 오는지,
 *          숫자 필드가 숫자/문자열 중 무엇으로 오는지는 명세에 없다 → 모두 허용한다.
 * [미확인] header.resultCode 정상값 '00' 은 공공데이터포털 관례이며 이 서비스 명세에는 값 목록이 없다.
 *
 * serviceKey: 공공데이터포털이 주는 'Encoding' 키(%2B 등 포함)를 그대로 넣으면 이중 인코딩되므로 '%' 가 있으면
 *             한 번 디코딩해서 보낸다.
 * </pre>
 */
public final class BuildingHub {
    public static final String SERVICE = "building_hub";
    public static final String BUILDING_HUB_BASE_URL = "https://apis.data.go.kr";
    public static final String TITLE_INFO_PATH = "/1613000/BldRgstHubService/getBrTitleInfo";
    public static final String SOURCE = "data.go.kr:BldRgstHubService/getBrTitleInfo";

    private static final Set<String> OK_CODES = Set.of("00", "0", "000");
    private static final Set<String> NO_DATA_CODES = Set.of("03"); // 공공데이터포털 관례 NODATA_ERROR
    private static final Set<String> NON_RETRIABLE_GW_CODES = Set.of("10", "12", "20", "30", "31");
    // 파이썬 re 의 \d 는 유니코드 숫자(Nd)
    private static final Pattern DATE_RE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern XML_REASON_RE = Pattern.compile(
            "<returnReasonCode>" + PyStr.WS_CLASS + "*(\\d+)" + PyStr.WS_CLASS + "*</returnReasonCode>",
            Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern XML_ERRMSG_RE = Pattern.compile("<errMsg>" + PyStr.WS_CLASS + "*([^<]*)</errMsg>");

    private BuildingHub() {
    }

    /** 사용승인일 'YYYYMMDD'(또는 'YYYY-MM-DD') → 'YYYY-MM-DD'. 날짜가 아니면 null. */
    static String approvalDate(Object value) {
        String s = Http.cleanStr(value);
        if (s == null) {
            return null;
        }
        Matcher m = DATE_RE.matcher(s.replace("-", ""));
        if (!m.matches()) {
            return null;
        }
        String y = m.group(1);
        String mo = m.group(2);
        String d = m.group(3);
        int yi = PyStr.parseInt(y).intValue();
        int moi = PyStr.parseInt(mo).intValue();
        int di = PyStr.parseInt(d).intValue();
        if (yi < 1) { // 파이썬 datetime 은 1년부터
            return null;
        }
        try {
            LocalDate.of(yi, moi, di);
        } catch (DateTimeException e) {
            return null;
        }
        return y + "-" + mo + "-" + d;
    }

    static UpstreamError gatewayError(String reason, String errMsg, Integer statusCode) {
        String r = reason == null ? "" : com.autoparkingplan.common.Py.strip(reason);
        String msg = com.autoparkingplan.common.Py.strip(
                "공공데이터포털 오류(" + (r.isEmpty() ? "?" : r) + " " + (errMsg == null || errMsg.isEmpty() ? "" : errMsg) + ")");
        return new UpstreamError(SERVICE, msg, !NON_RETRIABLE_GW_CODES.contains(r), statusCode);
    }

    /**
     * getBrTitleInfo JSON → BuildingInfo 목록. 자료 없음 → [].
     *
     * @param fetchedAt  null 이면 지금
     * @param statusCode 원천 HTTP 상태코드 (오류에 싣는다, null 가능)
     */
    public static List<BuildingInfo> parseTitleInfoResponse(JsonNode payload, OffsetDateTime fetchedAt, Integer statusCode) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        if (payload == null || !payload.isObject()) {
            throw new UpstreamError(SERVICE, "응답 형식이 예상과 다릅니다", true, statusCode);
        }
        JsonNode gw = payload.get("OpenAPI_ServiceResponse");
        if (gw != null && gw.isObject()) {
            JsonNode hdr = Juso.objectOrEmpty(gw.get("cmmMsgHeader"));
            throw gatewayError(Http.cleanStr(hdr.get("returnReasonCode")), Http.cleanStr(hdr.get("errMsg")), statusCode);
        }
        JsonNode resp = payload.get("response");
        if (resp == null || !resp.isObject()) {
            throw new UpstreamError(SERVICE, "응답 형식이 예상과 다릅니다(response 없음)", true, statusCode);
        }
        JsonNode header = Juso.objectOrEmpty(resp.get("header"));
        String code = Http.cleanStr(header.get("resultCode"));
        if (code != null && !OK_CODES.contains(code)) {
            if (NO_DATA_CODES.contains(code)) {
                return List.of();
            }
            throw gatewayError(code, Http.cleanStr(header.get("resultMsg")), statusCode);
        }
        JsonNode body = resp.get("body");
        JsonNode items = body != null && body.isObject() ? body.get("items") : null;
        JsonNode records = items != null && items.isObject() ? items.get("item") : null;
        List<JsonNode> list = new ArrayList<>();
        if (records != null && records.isObject()) {
            list.add(records);
        } else if (records != null && records.isArray()) {
            records.forEach(list::add);
        } else {
            return List.of();
        }
        List<BuildingInfo> out = new ArrayList<>();
        for (JsonNode rec : list) {
            if (!rec.isObject()) {
                continue;
            }
            out.add(new BuildingInfo(
                    Http.cleanStr(rec.get("mainPurpsCdNm")),
                    Http.toPositiveFloat(rec.get("totArea")),
                    Http.toPositiveFloat(rec.get("archArea")),
                    approvalDate(rec.get("useAprDay")),
                    SOURCE,
                    at,
                    (ObjectNode) rec.deepCopy()));
        }
        return out;
    }

    public static List<BuildingInfo> parseTitleInfoResponse(JsonNode payload, Integer statusCode) {
        return parseTitleInfoResponse(payload, null, statusCode);
    }

    public static List<BuildingInfo> parseTitleInfoResponse(JsonNode payload) {
        return parseTitleInfoResponse(payload, null, null);
    }
}
