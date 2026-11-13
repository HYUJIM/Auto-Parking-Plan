package com.autoparkingplan.external;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 도로명주소 검색 API(행정안전부 주소기반산업지원서비스)의 상수·응답 해석 (파이썬 {@code app.external.juso} 의 모듈 함수).
 * 클라이언트는 {@link JusoClient}.
 *
 * <pre>
 * 요청: GET https://business.juso.go.kr/addrlink/addrLinkApi.do
 *       confmKey(필수), currentPage(필수), countPerPage(필수), keyword(필수), resultType(json/xml, 기본 xml),
 *       hstryYn·firstSort·addInfoYn(선택, 미사용)
 * 응답: {"results": {"common": {"totalCount", "currentPage", "countPerPage", "errorCode", "errorMessage"},
 *                    "juso": [ {roadAddr, roadAddrPart1, roadAddrPart2, jibunAddr, engAddr, zipNo, admCd, rnMgtSn,
 *                               bdMgtSn, detBdNmList, bdNm, bdKdcd, siNm, sggNm, emdNm, liNm, rn, udrtYn, buldMnnm,
 *                               buldSlno, mtYn, lnbrMnnm, lnbrSlno, emdNo, ...}, ... ] | null}}
 *       오류도 HTTP 200 + common.errorCode 로 온다(2026-11-06 실호출로 E0001/E0005/E0006/E0013 확인).
 * 출처: business.juso.go.kr 개발자센터 '도로명주소 검색 API' 개발가이드 (SPA 경로 /jst/jstRoadNmAddrApiSearch),
 *       요청변수·출력결과·오류코드 표.
 * </pre>
 *
 * <p>확인 메모
 * <ul>
 *   <li>공식 출력결과 표에는 지번본번이 'InbrMnnm'(대문자 I), lnbrSlno 설명이 '읍면동일련번호'로 오기되어 있다.
 *       실제 응답 필드명은 lnbrMnnm / lnbrSlno(지번 부번)이므로 둘 다 읽는다. buldMnnm·lnbrMnnm 은 표에 Number 로
 *       적혀 있으나 JSON 에서는 문자열로 오는 경우가 있어 둘 다 허용한다.</li>
 *   <li>admCd(표기: 행정구역코드)는 10자리 법정동코드이며 PNU 앞 10자리로 쓴다.</li>
 *   <li>countPerPage 상한은 가이드에 명시되어 있지 않아 검사하지 않는다.</li>
 *   <li>이 API 는 좌표를 주지 않는다(좌표는 별도 '좌표제공 API'·별도 승인키). 후보 좌표가 필요하면 PNU 로
 *       브이월드 연속지적도를 조회해 경계 중심점을 쓴다.</li>
 * </ul>
 *
 * <p>오류코드 처리 (가이드 오류코드 표)
 * <pre>
 *   0      정상 → 결과(없으면 [])
 *   -999   시스템에러 → UpstreamError(retriable=true)
 *   E0001  승인되지 않은 KEY / E0014 개발승인키 기간 만료 → UpstreamError(retriable=false)
 *   E0005 검색어 없음, E0006 주소를 상세히(시도명만), E0008 한 글자, E0009 숫자만, E0010 너무 김,
 *   E0011 긴 숫자, E0012 특수문자+숫자만, E0013 SQL 예약어·특수문자, E0015 검색 범위 초과(9천 건)
 *          → JusoQueryError(UpstreamError 하위, retriable=false). API 계층은 이것을 사용자 입력 문제
 *            (ADDRESS_NOT_FOUND 계열)로 바꿔 보여주는 것이 맞다.
 * </pre>
 *
 * <p>검색어는 보내기 전에 {@link JusoKeyword} 가 먼저 검사한다. 허용 문자 밖의 글자나 SQL 예약어가 있으면 상위 API 를
 * 부르지 않는다.
 */
public final class Juso {
    private static final Logger log = LoggerFactory.getLogger(Juso.class);

    public static final String JUSO_BASE_URL = "https://business.juso.go.kr";
    public static final String JUSO_SEARCH_PATH = "/addrlink/addrLinkApi.do";
    public static final String SOURCE = "juso:addrLinkApi";
    public static final String SERVICE = "juso";

    public static final Set<String> AUTH_ERROR_CODES = Set.of("E0001", "E0014");
    public static final Set<String> QUERY_ERROR_CODES =
            Set.of("E0005", "E0006", "E0008", "E0009", "E0010", "E0011", "E0012", "E0013", "E0015");
    public static final Set<String> SYSTEM_ERROR_CODES = Set.of("-999");

    /** 사용자에게 보여줄 문구 (가이드 오류코드 표의 메시지). */
    public static final Map<String, String> QUERY_ERROR_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("E0005", "검색어가 입력되지 않았습니다.");
        m.put("E0006", "주소를 상세히 입력해 주시기 바랍니다.");
        m.put("E0008", "검색어는 두글자 이상 입력되어야 합니다.");
        m.put("E0009", "검색어는 문자와 숫자 같이 입력되어야 합니다.");
        m.put("E0010", "검색어가 너무 깁니다. (한글40자, 영문,숫자 80자 이하)");
        m.put("E0011", "검색어에 너무 긴 숫자가 포함되어 있습니다. (숫자10자 이하)");
        m.put("E0012", "특수문자+숫자만으로는 검색이 불가능 합니다.");
        m.put("E0013", "SQL 예약어 또는 특수문자( %,=,>,<,[,] )는 검색이 불가능 합니다.");
        m.put("E0015", "검색 범위를 초과하였습니다.");
        QUERY_ERROR_MESSAGES = Collections.unmodifiableMap(m);
    }

    /** 검색 한 페이지 결과: (후보 목록, 전체 건수 totalCount). */
    public record SearchPage(List<AddressCandidate> candidates, long total) {
        public SearchPage {
            candidates = List.copyOf(candidates);
        }
    }

    private Juso() {
    }

    private static AddressCandidate recordToCandidate(JsonNode rec, OffsetDateTime fetchedAt) {
        String admCd = Http.cleanStr(rec.get("admCd"));
        String mtYn = Http.cleanStr(rec.get("mtYn"));
        if (mtYn == null) {
            mtYn = "0";
        }
        // rec.get("lnbrMnnm", rec.get("InbrMnnm")): 키가 있으면 값이 null 이어도 그 값을 쓴다
        JsonNode main = rec.has("lnbrMnnm") ? rec.get("lnbrMnnm") : rec.get("InbrMnnm");
        JsonNode sub = rec.has("lnbrSlno") ? rec.get("lnbrSlno") : null;
        Object mainArg = PyStr.isNone(main) ? "" : main;
        Object subArg = PyStr.isNone(sub) ? (Object) 0 : sub;
        String pnu;
        try {
            pnu = Pnu.buildPnu(admCd == null ? "" : admCd, mtYn, mainArg, subArg);
        } catch (IllegalArgumentException exc) {
            log.warn("[juso] PNU 를 만들 수 없는 결과를 건너뜀: {}", exc.getMessage());
            return null;
        }
        if (Py.slice(pnu, 11, 15).equals("0000")) {
            log.warn("[juso] 지번 본번이 없는 결과를 건너뜀 (admCd={})", admCd);
            return null;
        }

        String road = Http.cleanStr(rec.get("roadAddr"));
        if (road == null) {
            String part1 = Http.cleanStr(rec.get("roadAddrPart1"));
            String part2 = Http.cleanStr(rec.get("roadAddrPart2"));
            road = part1 != null && part2 != null ? Py.strip(part1 + " " + part2) : part1;
        }
        String sido = Http.cleanStr(rec.get("siNm"));
        if (sido == null) {
            sido = "";
        }
        String sigungu = Http.cleanStr(rec.get("sggNm"));
        String emd = Http.cleanStr(rec.get("emdNm"));
        String jibun = Http.cleanStr(rec.get("jibunAddr"));
        if (jibun == null) {
            String li = Http.cleanStr(rec.get("liNm"));
            String lot = Pnu.formatJibun(Long.parseLong(Py.slice(pnu, 11, 15)), Long.parseLong(Py.slice(pnu, 15, 19)),
                    Py.slice(pnu, 10, 11).equals("2"));
            List<String> parts = new ArrayList<>();
            for (String x : new String[] {sido, sigungu, emd, li, lot}) {
                if (x != null && !x.isEmpty()) {
                    parts.add(x);
                }
            }
            jibun = String.join(" ", parts);
        }
        return new AddressCandidate(
                road,
                jibun,
                pnu,
                Py.slice(pnu, 0, 10),
                sido,
                sigungu,
                emd,
                Http.cleanStr(rec.get("zipNo")),
                Http.cleanStr(rec.get("bdNm")),
                SOURCE,
                fetchedAt);
    }

    /**
     * 검색 API JSON 응답 → (후보 목록, totalCount). 오류코드는 클래스 설명의 표대로 예외로 바꾼다.
     *
     * @param fetchedAt null 이면 지금
     * @throws JusoQueryError 검색어 오류
     * @throws UpstreamError  인증·시스템·알 수 없는 오류, 응답 형식 오류
     */
    public static SearchPage parseSearchResponse(JsonNode payload, OffsetDateTime fetchedAt) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        if (payload == null || !payload.isObject() || payload.get("results") == null || !payload.get("results").isObject()) {
            throw new UpstreamError(SERVICE, "응답 형식이 예상과 다릅니다(results 없음)", true);
        }
        JsonNode results = payload.get("results");
        JsonNode common = objectOrEmpty(results.get("common"));
        String code = Py.strip(PyStr.str(common.has("errorCode") ? common.get("errorCode") : (Object) ""));
        String message = Py.strip(PyStr.str(common.has("errorMessage") ? common.get("errorMessage") : (Object) ""));
        if (!code.equals("0") && !code.isEmpty()) {
            if (AUTH_ERROR_CODES.contains(code)) {
                throw new UpstreamError(SERVICE, "인증 오류(" + code + "): " + message, false);
            }
            if (QUERY_ERROR_CODES.contains(code)) {
                throw new JusoQueryError(code, !message.isEmpty() ? message : QUERY_ERROR_MESSAGES.getOrDefault(code, ""));
            }
            if (SYSTEM_ERROR_CODES.contains(code)) {
                throw new UpstreamError(SERVICE, "시스템 오류(" + code + "): " + message, true);
            }
            throw new UpstreamError(SERVICE, "알 수 없는 오류코드(" + code + "): " + message, false);
        }

        long total;
        String totalStr = Py.strip(PyStr.str(common.has("totalCount") ? common.get("totalCount") : (Object) "0"));
        if (totalStr.isEmpty()) {
            total = 0;
        } else {
            BigInteger t = PyStr.parseInt(totalStr);
            total = t == null || t.bitLength() > 63 ? 0 : t.longValue();
        }
        JsonNode records = results.get("juso");
        List<JsonNode> list = new ArrayList<>();
        if (records != null && records.isObject()) {
            list.add(records);
        } else if (records != null && records.isArray()) {
            records.forEach(list::add);
        }
        List<AddressCandidate> candidates = new ArrayList<>();
        for (JsonNode rec : list) {
            if (rec.isObject()) {
                AddressCandidate cand = recordToCandidate(rec, at);
                if (cand != null) {
                    candidates.add(cand);
                }
            }
        }
        return new SearchPage(candidates, total);
    }

    public static SearchPage parseSearchResponse(JsonNode payload) {
        return parseSearchResponse(payload, null);
    }

    /** 파이썬 {@code d.get(k) or {}}: 객체가 아니면 빈 객체로 본다. */
    static JsonNode objectOrEmpty(JsonNode n) {
        return n != null && n.isObject() ? n : EMPTY;
    }

    private static final ObjectNode EMPTY = Json.object();
}
