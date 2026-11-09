package com.autoparkingplan.external;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 브이월드(공간정보 오픈플랫폼)의 상수·응답 해석 (파이썬 {@code app.external.vworld} 의 모듈 함수):
 * 연속지적도, 토지이용계획 속성, 토지특성, 토지임야. 클라이언트는 {@link VWorldClient}.
 *
 * <pre>
 * 1) 연속지적도 — 2D 데이터 API 2.0
 *    GET https://api.vworld.kr/req/data?service=data&amp;version=2.0&amp;request=GetFeature&amp;data=LP_PA_CBND_BUBUN
 *        &amp;key=..&amp;domain=..&amp;format=json&amp;errorFormat=json&amp;crs=EPSG:4326&amp;geometry=true&amp;attribute=true
 *        &amp;attrFilter=pnu:=:{pnu}   (pnu 는 '단일검색=Y' 속성이라 geomFilter 없이 attrFilter 만으로 조회 가능)
 *        &amp;geomFilter=POINT(x y)    (좌표 조회)
 *    속성: pnu, jibun('2-1대'), bonbun, bubun, ag_geom, addr('서울특별시 종로구 청운동 1234-5'),
 *          gosi_year, gosi_month, jiga(개별공시지가)
 *    응답: response.service{name,version,operation,time}, response.status(OK|NOT_FOUND|ERROR),
 *          response.record{total,current}, response.page{total,current,size}, response.result(json 이면 geojson),
 *          오류 시 response.error{level,code,text}
 *    출처: https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=cadastral (POST apiVer=2)
 *    [미확인] result 아래 GeoJSON 이 'featureCollection' 키에 들어 있다는 점은 공식 표에 없다(관행상 그렇게 온다).
 *             이 모듈은 result.featureCollection 과 result 자체가 FeatureCollection 인 경우를 모두 받는다.
 *    [미확인] crs=EPSG:4326 일 때 좌표 순서가 [경도, 위도]라는 점은 표에 명시되어 있지 않다. 한국 범위를 기준으로
 *             [위도, 경도]로 보이면 뒤집는다.
 *
 * 2) 토지이용계획속성조회 — 국가중점데이터 API
 *    GET https://api.vworld.kr/ned/data/getLandUseAttr?pnu=..&amp;key=..&amp;domain=..&amp;format=json&amp;numOfRows=..&amp;pageNo=..
 *        (선택: cnflcAt 저촉여부코드, prposAreaDstrcCodeNm 용도지역지구명)
 *    출력: pnu, ldCode, ldCodeNm, regstrSeCode, regstrSeCodeNm, mnnmSlno, manageNo, cnflcAt, cnflcAtNm,
 *          prposAreaDstrcCode, prposAreaDstrcCodeNm, registDt, lastUpdtDt
 *    출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=51
 *    * 근거 법령명 필드는 없다 → LandUseDesignation.basis_law = None.
 *    * 같은 코드가 도면번호(manageNo)별로 여러 줄 오므로 (코드, 명칭) 기준으로 합치고 가장 강한 저촉여부를 남긴다.
 *
 * 3) 토지특성속성조회 — GET https://api.vworld.kr/ned/data/getLandCharacteristics?pnu=..&amp;stdrYear=..(선택)&amp;...
 *    출력: pnu, ldCode, ldCodeNm, regstrSeCode, regstrSeCodeNm, mnnmSlno, ladSn, stdrYear, stdrMt, lndcgrCode,
 *          lndcgrCodeNm(지목명), lndpclAr(토지면적 ㎡), prposArea1/Nm, prposArea2/Nm, ladUseSittn/Nm, tpgrphHgCode/Nm,
 *          tpgrphFrmCode/Nm, roadSideCode, roadSideCodeNm(도로접면), pblntfPclnd(공시지가 원/㎡), lastUpdtDt
 *    출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=18
 *    * 기준연도별로 여러 줄이 오므로 가장 최근 (stdrYear, stdrMt) 를 쓴다.
 *
 * 4) 토지임야목록조회 — GET https://api.vworld.kr/ned/data/ladfrlList?pnu=..&amp;format=json&amp;numOfRows=..&amp;pageNo=..
 *    출력: pnu, ldCode, ldCodeNm, mnnmSlno, regstrSeCode, regstrSeCodeNm, lndcgrCode, lndcgrCodeNm, lndpclAr(면적 ㎡),
 *          posesnSeCode/Nm, cnrsPsnCo, ladFrtlSc/Nm, lastUpdtDt, numOfRows, pageNo, totalCount
 *    출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=128
 *
 * 국가중점데이터 API 의 JSON 봉투(공식 표에 없음, [미확인]·실응답 관찰 기반):
 *    성공  {"landUses": {"field": [...], "pageNo", "numOfRows", "totalCount", "resultCode": "", "resultMsg": ""}}
 *          {"landCharacteristicss": {"field": [...], ...}}        (키 철자 's' 두 개가 실제 응답)
 *          {"ladfrlVOList": {"ladfrlVOList": [...], "error", "message", "numOfRows", "pageNo", "totalCount"}}
 *    결과없음 {"response": {"pageNo", "resultCode": "", "totalCount": "0", "numOfRows", "resultMsg": ""}} 형태도 관찰됨
 *    오류  {"landUses": {"resultCode": "INVALID_KEY", "resultMsg": "등록되지 않은 인증키입니다."}}   (2026-11-06 실호출 확인)
 *          {"landCharacteristicss": {"resultCode": "INVALID_KEY", ...}}                         (실호출 확인)
 *          {"ladfrlVOList": {"error": "INVALID_KEY", "message": "..."}}                          (실호출 확인)
 *          {"response": {"status": "ERROR", "error": {"level", "code", "text"}}}                 (문서형 오류 봉투)
 *    그래서 봉투의 첫 번째 값(객체)에서 resultCode/error 와 목록(field/ladfrlVOList)을 찾는 방식으로 읽는다.
 *
 * 오류코드(레퍼런스 공통 표): PARAM_REQUIRED, INVALID_TYPE, INVALID_RANGE, URL_TYPE, INVALID_KEY, INCORRECT_KEY
 *    → retriable=false / UNAVAILABLE_KEY, OVER_REQUEST_LIMIT, SYSTEM_ERROR, UNKNOWN_ERROR → retriable=true.
 * </pre>
 *
 * <p>scrub 인자는 원천 오류 문구에서 인증키를 가리는 함수다 (파이썬 {@code Scrub = Callable[[str], str]}, 기본은 그대로).
 */
public final class VWorld {
    public static final String SERVICE = "vworld";
    public static final String VWORLD_BASE_URL = "https://api.vworld.kr";
    public static final String DATA_PATH = "/req/data";
    public static final String LAND_USE_PATH = "/ned/data/getLandUseAttr";
    public static final String LAND_CHAR_PATH = "/ned/data/getLandCharacteristics";
    public static final String LADFRL_PATH = "/ned/data/ladfrlList";
    public static final String CADASTRAL_DATASET = "LP_PA_CBND_BUBUN";

    public static final String SOURCE_CADASTRAL = "vworld:" + CADASTRAL_DATASET;
    public static final String SOURCE_LAND_USE = "vworld:getLandUseAttr";
    public static final String SOURCE_LAND_CHAR = "vworld:getLandCharacteristics";
    public static final String SOURCE_LADFRL = "vworld:ladfrlList";

    public static final Set<String> AUTH_ERROR_CODES = Set.of("INVALID_KEY", "INCORRECT_KEY");
    public static final Set<String> PARAM_ERROR_CODES = Set.of("PARAM_REQUIRED", "INVALID_TYPE", "INVALID_RANGE", "URL_TYPE");
    private static final Set<String> OK_RESULT_CODES = Set.of("", "OK", "0", "00", "INFO-000", "NORMAL_SERVICE");
    private static final Set<String> NO_ROAD_SIDE = Set.of("지정되지않음", "-");

    private static final Map<String, Integer> CONFLICT_RANK = Map.of(
            Conflict.INCLUDED, 3, Conflict.PARTIALLY_INCLUDED, 2, Conflict.ADJACENT, 1, Conflict.UNKNOWN, 0);

    private VWorld() {
    }

    private static UnaryOperator<String> orIdentity(UnaryOperator<String> scrub) {
        return scrub != null ? scrub : UnaryOperator.identity();
    }

    static UpstreamError errorFromCode(Object code, Object text, String endpoint, UnaryOperator<String> scrub) {
        String c0 = Http.cleanStr(code);
        String c = (c0 != null ? c0 : "UNKNOWN_ERROR").toUpperCase(java.util.Locale.ROOT);
        String t0 = Http.cleanStr(text);
        String t = orIdentity(scrub).apply(t0 != null ? t0 : "");
        if (AUTH_ERROR_CODES.contains(c)) {
            return new UpstreamError(SERVICE, endpoint + " 인증 오류(" + c + "): " + t, false);
        }
        if (PARAM_ERROR_CODES.contains(c)) {
            return new UpstreamError(SERVICE, endpoint + " 요청 파라미터 오류(" + c + "): " + t, false);
        }
        return new UpstreamError(SERVICE, endpoint + " 오류(" + c + "): " + t, true);
    }

    // --------------------------------------------------------------------------------------------
    // 기하
    // --------------------------------------------------------------------------------------------
    private static boolean looksLatLng(double x, double y) {
        return 33.0 <= x && x <= 39.5 && 124.0 <= y && y <= 132.5;
    }

    /**
     * GeoJSON Polygon/MultiPolygon → MultiPolygon(EPSG:4326, [경도, 위도]). Z 값은 버리고 고리는 닫는다.
     *
     * @return 쓸 수 있는 고리가 없거나 형식이 다르면 null
     */
    public static ObjectNode normalizeMultipolygon(JsonNode geometry) {
        if (geometry == null || !geometry.isObject()) {
            return null;
        }
        JsonNode gtype = geometry.get("type");
        JsonNode coords = geometry.get("coordinates");
        List<JsonNode> polygons = new ArrayList<>();
        if (gtype != null && gtype.isString() && gtype.stringValue().equals("Polygon")) {
            polygons.add(coords); // [coords] 는 언제나 목록
        } else if (gtype != null && gtype.isString() && gtype.stringValue().equals("MultiPolygon")) {
            if (coords == null || !coords.isArray()) {
                return null;
            }
            coords.forEach(polygons::add);
        } else {
            return null;
        }
        List<List<List<double[]>>> out = new ArrayList<>();
        for (JsonNode poly : polygons) {
            if (poly == null || !poly.isArray()) {
                continue;
            }
            List<List<double[]>> rings = new ArrayList<>();
            for (JsonNode ring : poly) {
                List<double[]> pts = ringPoints(ring);
                if (pts == null) {
                    continue; // 파이썬: TypeError/ValueError → 이 고리는 버림
                }
                pts.removeIf(p -> !Double.isFinite(p[0]) || !Double.isFinite(p[1]));
                if (pts.size() >= 3) {
                    double[] first = pts.getFirst();
                    double[] last = pts.getLast();
                    if (first[0] != last[0] || first[1] != last[1]) {
                        pts.add(new double[] {first[0], first[1]});
                    }
                }
                if (pts.size() >= 4) {
                    rings.add(pts);
                }
            }
            if (!rings.isEmpty()) {
                out.add(rings);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        double[] p0 = out.getFirst().getFirst().getFirst();
        boolean swap = looksLatLng(p0[0], p0[1]);
        ArrayNode coordsOut = Json.array();
        for (List<List<double[]>> poly : out) {
            ArrayNode polyNode = coordsOut.addArray();
            for (List<double[]> ring : poly) {
                ArrayNode ringNode = polyNode.addArray();
                for (double[] p : ring) {
                    ArrayNode pt = ringNode.addArray();
                    if (swap) {
                        pt.add(p[1]).add(p[0]);
                    } else {
                        pt.add(p[0]).add(p[1]);
                    }
                }
            }
        }
        ObjectNode g = Json.object();
        g.put("type", "MultiPolygon");
        g.set("coordinates", coordsOut);
        return g;
    }

    /**
     * 파이썬 {@code [[float(p[0]), float(p[1])] for p in ring if isinstance(p, list | tuple) and len(p) >= 2]}.
     * float() 가 실패(TypeError/ValueError)하거나 ring 이 순회할 수 없는 값이면 null.
     */
    private static List<double[]> ringPoints(JsonNode ring) {
        List<double[]> pts = new ArrayList<>();
        if (ring == null || ring.isNull() || ring.isNumber() || ring.isBoolean()) {
            return null; // 순회 불가 → TypeError
        }
        if (!ring.isArray()) {
            return pts; // 문자열·객체: 순회는 되지만 목록 원소가 없다
        }
        for (JsonNode p : ring) {
            if (p.isArray() && p.size() >= 2) {
                Double x = pyFloatOf(p.get(0));
                Double y = pyFloatOf(p.get(1));
                if (x == null || y == null) {
                    return null;
                }
                pts.add(new double[] {x, y});
            }
        }
        return pts;
    }

    /** 파이썬 {@code float(v)} (JSON 값). TypeError/ValueError 이면 null. */
    private static Double pyFloatOf(JsonNode v) {
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? 1.0 : 0.0;
        }
        if (v.isString()) {
            return PyStr.parseFloat(v.stringValue());
        }
        return null;
    }

    // --------------------------------------------------------------------------------------------
    // 파서 (네트워크 없이 단위 시험 가능)
    // --------------------------------------------------------------------------------------------
    private static CadastralParcel featureToParcel(JsonNode feature, OffsetDateTime fetchedAt) {
        JsonNode props = Juso.objectOrEmpty(feature.get("properties"));
        String pnu = Http.cleanStr(props.get("pnu"));
        if (pnu == null) {
            return null;
        }
        return new CadastralParcel(
                pnu,
                Http.cleanStr(props.get("jibun")),
                Http.cleanStr(props.get("addr")),
                normalizeMultipolygon(feature.get("geometry")),
                Http.toPositiveInt(props.get("jiga")),
                SOURCE_CADASTRAL,
                fetchedAt,
                (ObjectNode) props.deepCopy());
    }

    /**
     * Data API(GetFeature, LP_PA_CBND_BUBUN) JSON → 필지 목록. NOT_FOUND → [].
     *
     * @param fetchedAt null 이면 지금
     * @param scrub     null 이면 그대로
     */
    public static List<CadastralParcel> parseCadastralResponse(JsonNode payload, OffsetDateTime fetchedAt,
            UnaryOperator<String> scrub) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        JsonNode resp = payload != null && payload.isObject() ? payload.get("response") : null;
        if (resp == null || !resp.isObject()) {
            throw new UpstreamError(SERVICE, "연속지적도 응답 형식이 예상과 다릅니다(response 없음)", true);
        }
        String status = upper(PyStr.str(resp.has("status") ? resp.get("status") : (Object) ""));
        if (status.equals("NOT_FOUND")) {
            return List.of();
        }
        if (status.equals("ERROR")) {
            JsonNode err = Juso.objectOrEmpty(resp.get("error"));
            throw errorFromCode(err.get("code"), err.get("text"), CADASTRAL_DATASET, scrub);
        }
        if (!status.equals("OK")) {
            throw new UpstreamError(SERVICE,
                    "연속지적도 응답 status 를 알 수 없습니다(" + (status.isEmpty() ? "없음" : status) + ")", true);
        }
        JsonNode result = resp.get("result");
        JsonNode fc = null;
        if (result != null && result.isObject()) {
            fc = result.get("featureCollection");
            if (PyStr.isNone(fc)) {
                fc = null;
                JsonNode type = result.get("type");
                boolean isFc = type != null && type.isString() && type.stringValue().equals("FeatureCollection");
                if (isFc || result.has("features")) {
                    fc = result;
                }
            }
        }
        JsonNode features = fc != null && fc.isObject() ? fc.get("features") : null;
        List<CadastralParcel> parcels = new ArrayList<>();
        if (features != null && features.isArray()) {
            for (JsonNode feat : features) {
                if (feat.isObject()) {
                    CadastralParcel parcel = featureToParcel(feat, at);
                    if (parcel != null) {
                        parcels.add(parcel);
                    }
                }
            }
        }
        return parcels;
    }

    public static List<CadastralParcel> parseCadastralResponse(JsonNode payload) {
        return parseCadastralResponse(payload, null, null);
    }

    /** 국가중점데이터 API JSON 봉투에서 레코드 목록을 꺼낸다. 오류 봉투면 UpstreamError. */
    static List<ObjectNode> nedRecords(JsonNode payload, String endpoint, UnaryOperator<String> scrub) {
        if (payload == null || !payload.isObject() || payload.isEmpty()) {
            throw new UpstreamError(SERVICE, endpoint + " 응답 형식이 예상과 다릅니다", true);
        }
        JsonNode resp = payload.get("response");
        if (resp != null && resp.isObject() && resp.has("status")) {
            String status = upper(PyStr.str(resp.get("status")));
            if (status.equals("ERROR")) {
                JsonNode err = Juso.objectOrEmpty(resp.get("error"));
                throw errorFromCode(err.get("code"), err.get("text"), endpoint, scrub);
            }
            if (status.equals("NOT_FOUND")) {
                return List.of();
            }
        }
        JsonNode root = null;
        for (JsonNode v : payload.values()) {
            if (v.isObject()) {
                root = v;
                break;
            }
        }
        if (root == null) {
            throw new UpstreamError(SERVICE, endpoint + " 응답 형식이 예상과 다릅니다", true);
        }
        String code = Http.cleanStr(root.get("resultCode"));
        if (code == null) {
            code = Http.cleanStr(root.get("error"));
        }
        if (code != null && !OK_RESULT_CODES.contains(upper(code))) {
            JsonNode msg = root.get("resultMsg");
            throw errorFromCode(code, PyStr.truthy(msg) ? msg : root.get("message"), endpoint, scrub);
        }
        for (String key : new String[] {"field", "ladfrlVOList", "item", "items"}) {
            JsonNode val = root.get(key);
            if (val != null && val.isArray()) {
                return objects(val);
            }
            if (val != null && val.isObject()) {
                JsonNode inner = val.get("item");
                if (inner != null && inner.isArray()) {
                    return objects(inner);
                }
                return List.of((ObjectNode) val);
            }
        }
        Long total = Http.toPositiveInt(root.get("totalCount"));
        if (total != null && total != 0) {
            throw new UpstreamError(SERVICE, endpoint + " 응답에 목록이 없습니다(totalCount=" + total + ")", false);
        }
        return List.of();
    }

    private static List<ObjectNode> objects(JsonNode array) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode r : array) {
            if (r.isObject()) {
                out.add((ObjectNode) r);
            }
        }
        return out;
    }

    private static String upper(String s) {
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    /** 병합 중인 지정 (파이썬은 dataclass 를 제자리에서 고쳤다). */
    private static final class Pending {
        final String kind;
        final String name;
        final String code;
        String conflict;
        ObjectNode raw;
        int count;

        Pending(String kind, String name, String code, String conflict, ObjectNode raw) {
            this.kind = kind;
            this.name = name;
            this.code = code;
            this.conflict = conflict;
            this.raw = raw;
            this.count = 1;
        }
    }

    /**
     * getLandUseAttr JSON → LandUseDesignation 목록(같은 코드·명칭은 합침, 원천 순서 유지).
     *
     * @param pnu       주어지면 다른 PNU 레코드는 버린다 (null = 거르지 않음)
     * @param fetchedAt null 이면 지금
     */
    public static List<LandUseDesignation> parseLandUseResponse(JsonNode payload, String pnu, OffsetDateTime fetchedAt,
            UnaryOperator<String> scrub) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        List<ObjectNode> records = nedRecords(payload, "getLandUseAttr", scrub);
        Map<List<String>, Pending> merged = new LinkedHashMap<>();
        for (ObjectNode rec : records) {
            String recPnu = Http.cleanStr(rec.get("pnu"));
            if (pnu != null && !pnu.isEmpty() && recPnu != null && !recPnu.equals(pnu)) {
                continue;
            }
            String code = Http.cleanStr(rec.get("prposAreaDstrcCode"));
            JsonNode rawName = rec.get("prposAreaDstrcCodeNm");
            String name = ZoningCodes.canonicalDesignationName(PyStr.truthy(rawName) ? PyStr.str(rawName) : null, code);
            if (name.isEmpty() && code == null) {
                continue;
            }
            String conflict = ZoningCodes.conflictFromSource(rec.get("cnflcAt"), rec.get("cnflcAtNm"));
            List<String> key = List.of(upper(code != null ? code : ""), name);
            Pending prev = merged.get(key);
            if (prev != null) {
                if (CONFLICT_RANK.get(conflict) > CONFLICT_RANK.get(prev.conflict)) {
                    prev.conflict = conflict;
                    ObjectNode raw = rec.deepCopy();
                    raw.put("_merged_records", prev.count + 1);
                    prev.raw = raw;
                } else {
                    prev.raw.put("_merged_records", prev.count + 1);
                }
                prev.count++;
                continue;
            }
            merged.put(key, new Pending(
                    ZoningCodes.classifyDesignation(name, code),
                    !name.isEmpty() ? name : (code != null ? code : ""),
                    code,
                    conflict,
                    rec.deepCopy()));
        }
        List<LandUseDesignation> out = new ArrayList<>();
        for (Pending p : merged.values()) {
            // basis_law: 원천에 근거 법령명 필드 없음
            out.add(new LandUseDesignation(p.kind, p.name, p.code, p.conflict, null, SOURCE_LAND_USE, at, p.raw));
        }
        return out;
    }

    public static List<LandUseDesignation> parseLandUseResponse(JsonNode payload, String pnu) {
        return parseLandUseResponse(payload, pnu, null, null);
    }

    public static List<LandUseDesignation> parseLandUseResponse(JsonNode payload) {
        return parseLandUseResponse(payload, null, null, null);
    }

    /** 파이썬 {@code int(str(v).strip())}, 실패하면 0. */
    private static BigInteger pyIntOrZero(JsonNode v) {
        BigInteger n = PyStr.parseInt(PyStr.str(v));
        return n != null ? n : BigInteger.ZERO;
    }

    /** 파이썬 {@code str(rec.get(k) or "")}. */
    private static String strOrEmpty(JsonNode v) {
        return PyStr.truthy(v) ? PyStr.str(v) : "";
    }

    /** 정렬 키 (stdrYear, stdrMt, lastUpdtDt) 비교. */
    private static int compareYearMonth(JsonNode a, JsonNode b) {
        int c = pyIntOrZero(a.get("stdrYear")).compareTo(pyIntOrZero(b.get("stdrYear")));
        if (c != 0) {
            return c;
        }
        c = pyIntOrZero(a.get("stdrMt")).compareTo(pyIntOrZero(b.get("stdrMt")));
        if (c != 0) {
            return c;
        }
        return PyJson.compareCodePoints(strOrEmpty(a.get("lastUpdtDt")), strOrEmpty(b.get("lastUpdtDt")));
    }

    /** 파이썬 {@code max(records, key=...)}: 같은 최댓값이 여럿이면 처음 것. */
    private static ObjectNode max(List<ObjectNode> records, java.util.Comparator<JsonNode> cmp) {
        Iterator<ObjectNode> it = records.iterator();
        ObjectNode best = it.next();
        while (it.hasNext()) {
            ObjectNode r = it.next();
            if (cmp.compare(r, best) > 0) {
                best = r;
            }
        }
        return best;
    }

    private static List<ObjectNode> filterPnu(List<ObjectNode> records, String pnu) {
        if (pnu == null || pnu.isEmpty()) {
            return records;
        }
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode r : records) {
            String p = Http.cleanStr(r.get("pnu"));
            if (p == null || p.equals(pnu)) {
                out.add(r);
            }
        }
        return out;
    }

    /** getLandCharacteristics JSON → 가장 최근 기준연도의 LandCharacteristics. 자료 없음 → empty. */
    public static Optional<LandCharacteristics> parseLandCharacteristicsResponse(JsonNode payload, String pnu,
            OffsetDateTime fetchedAt, UnaryOperator<String> scrub) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        List<ObjectNode> records = filterPnu(nedRecords(payload, "getLandCharacteristics", scrub), pnu);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode rec = max(records, VWorld::compareYearMonth);
        String roadSide = Http.cleanStr(rec.get("roadSideCodeNm"));
        if (roadSide != null && NO_ROAD_SIDE.contains(roadSide)) {
            roadSide = null;
        }
        String recPnu = Http.cleanStr(rec.get("pnu"));
        return Optional.of(new LandCharacteristics(
                recPnu != null ? recPnu : (pnu != null ? pnu : ""),
                Http.toPositiveFloat(rec.get("lndpclAr")),
                Http.cleanStr(rec.get("lndcgrCodeNm")),
                roadSide,
                Http.toPositiveInt(rec.get("pblntfPclnd")),
                SOURCE_LAND_CHAR,
                at,
                rec.deepCopy()));
    }

    public static Optional<LandCharacteristics> parseLandCharacteristicsResponse(JsonNode payload, String pnu) {
        return parseLandCharacteristicsResponse(payload, pnu, null, null);
    }

    public static Optional<LandCharacteristics> parseLandCharacteristicsResponse(JsonNode payload) {
        return parseLandCharacteristicsResponse(payload, null, null, null);
    }

    /** ladfrlList(토지임야목록) JSON → LandCharacteristics(면적·지목만). 자료 없음 → empty. */
    public static Optional<LandCharacteristics> parseLadfrlResponse(JsonNode payload, String pnu, OffsetDateTime fetchedAt,
            UnaryOperator<String> scrub) {
        OffsetDateTime at = fetchedAt != null ? fetchedAt : Http.utcnow();
        List<ObjectNode> records = filterPnu(nedRecords(payload, "ladfrlList", scrub), pnu);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode rec = max(records,
                (a, b) -> PyJson.compareCodePoints(strOrEmpty(a.get("lastUpdtDt")), strOrEmpty(b.get("lastUpdtDt"))));
        String recPnu = Http.cleanStr(rec.get("pnu"));
        return Optional.of(new LandCharacteristics(
                recPnu != null ? recPnu : (pnu != null ? pnu : ""),
                Http.toPositiveFloat(rec.get("lndpclAr")),
                Http.cleanStr(rec.get("lndcgrCodeNm")),
                null,
                null,
                SOURCE_LADFRL,
                at,
                rec.deepCopy()));
    }

    public static Optional<LandCharacteristics> parseLadfrlResponse(JsonNode payload, String pnu) {
        return parseLadfrlResponse(payload, pnu, null, null);
    }

    public static Optional<LandCharacteristics> parseLadfrlResponse(JsonNode payload) {
        return parseLadfrlResponse(payload, null, null, null);
    }

    /**
     * 토지임야(현행 토지대장: 면적·지목 우선) + 토지특성(도로접면·공시지가, 면적·지목 보조)을 합친다.
     *
     * <p>raw = {"ladfrlList": {...}|null, "getLandCharacteristics": {...}|null, "_errors": {endpoint: 메시지}}
     * 한쪽 조회가 실패했으면 raw["_errors"] 에 남는다(API 계층의 PARCEL_PARTIAL 판단용).
     *
     * @param chr      토지특성 (null = 없음)
     * @param register 토지임야 (null = 없음)
     * @param errors   실패한 조회 {endpoint: 메시지} (null = 없음)
     * @return 둘 다 없으면 empty
     */
    public static Optional<LandCharacteristics> mergeLandCharacteristics(String pnu, LandCharacteristics chr,
            LandCharacteristics register, Map<String, String> errors, OffsetDateTime fetchedAt) {
        if (chr == null && register == null) {
            return Optional.empty();
        }
        List<String> sources = new ArrayList<>();
        for (LandCharacteristics s : new LandCharacteristics[] {register, chr}) {
            if (s != null) {
                sources.add(s.source());
            }
        }
        Double area = register != null && register.landAreaM2() != null ? register.landAreaM2()
                : chr != null ? chr.landAreaM2() : null;
        String category = register != null && register.landCategory() != null ? register.landCategory()
                : chr != null ? chr.landCategory() : null;
        ObjectNode raw = Json.object();
        raw.set("ladfrlList", register != null ? register.raw() : Json.nodes().nullNode());
        raw.set("getLandCharacteristics", chr != null ? chr.raw() : Json.nodes().nullNode());
        ObjectNode errs = raw.putObject("_errors");
        if (errors != null) {
            errors.forEach(errs::put);
        }
        return Optional.of(new LandCharacteristics(
                pnu,
                area,
                category,
                chr != null ? chr.roadSide() : null,
                chr != null ? chr.officialLandPrice() : null,
                String.join("+", sources),
                fetchedAt != null ? fetchedAt : Http.utcnow(),
                raw));
    }

    public static Optional<LandCharacteristics> mergeLandCharacteristics(String pnu, LandCharacteristics chr,
            LandCharacteristics register) {
        return mergeLandCharacteristics(pnu, chr, register, null, null);
    }

    /** PNU 형식 검사 (VWorldClient 공개 메서드 공통). */
    static void requirePnu(String pnu) {
        if (!Pnu.isValidPnu(pnu)) {
            throw new IllegalArgumentException("PNU 는 19자리 숫자(필지구분 1·2)여야 합니다: " + PyStr.repr(pnu));
        }
    }

    static String formatPoint(double lng, double lat) {
        return "POINT(" + PyStr.formatFixed(lng, 8) + " " + PyStr.formatFixed(lat, 8) + ")";
    }

    static String floatRepr(double x) {
        return Py.floatRepr(x);
    }
}
