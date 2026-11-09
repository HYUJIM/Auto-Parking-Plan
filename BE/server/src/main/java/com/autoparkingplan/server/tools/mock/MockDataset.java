package com.autoparkingplan.server.tools.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * mock 서버 예시 필지 데이터셋 (data/mock/parcels.json) — 파이썬판 {@code mockserver/dataset.py} 의 이식.
 *
 * <p>모든 레코드는 is_example=true 인 가상 필지다. 법정동코드·법정동명만 실제 값이고 지번(9001~9201)·경계·도로명('…예시로')·건물·지정
 * 내용은 지어낸 것이다. 레코드는 파일 순서대로 {@link JsonNode} 그대로 들고 있고(파이썬 dict), 경계는 JTS 도형(shapely 에 해당)으로 바꿔 둔다.
 * 읽기 전용이라 스레드 안전하다.
 */
public final class MockDataset {
    private static final GeometryFactory GF = new GeometryFactory();

    /** 파이썬 {@code str.isdigit()} 가 참이지만 {@code isdecimal()} 은 거짓인 코드포인트 구간 (Unicode 15.0, 파이썬 3.12 에서 추출). */
    private static final int[] DIGIT_NOT_DECIMAL = {
        0xB2, 0xB3, 0xB9, 0xB9, 0x1369, 0x1371, 0x19DA, 0x19DA, 0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089,
        0x2460, 0x2468, 0x2474, 0x247C, 0x2488, 0x2490, 0x24EA, 0x24EA, 0x24F5, 0x24FD, 0x24FF, 0x24FF, 0x2776, 0x277E,
        0x2780, 0x2788, 0x278A, 0x2792, 0x10A40, 0x10A43, 0x10E60, 0x10E68, 0x11052, 0x1105A, 0x1F100, 0x1F10A,
    };

    /** 예시 필지 하나 (파이썬 {@code MockParcel}): 원본 레코드(dict) + 경계 도형. */
    public static final class Parcel {
        private final JsonNode record;
        private final Geometry geom;
        private final int index;

        Parcel(JsonNode record, Geometry geom, int index) {
            this.record = record;
            this.geom = geom;
            this.index = index;
        }

        /** 원본 레코드 (parcels.json 의 항목 그대로). */
        public JsonNode record() {
            return record;
        }

        /** 경계 (EPSG:4326, [경도, 위도]). */
        public Geometry geom() {
            return geom;
        }

        /** 파일 안의 위치 (0부터; 파이썬 {@code ds.parcels.index(p)}). */
        public int index() {
            return index;
        }

        public String id() {
            return text(record, "id");
        }

        public String pnu() {
            return text(record, "pnu");
        }

        public String roadAddress() {
            return text(record, "road_address");
        }

        public String jibunAddress() {
            return text(record, "jibun_address");
        }

        /** 파이썬 {@code record.get("behavior") or {}}: 특수 동작 (land_use / land_characteristics). */
        public JsonNode behavior() {
            JsonNode b = record.get("behavior");
            return truthy(b) ? b : Json.object();
        }

        /** 파이썬 {@code p.behavior.get(key)}: 없으면 null. */
        public String behavior(String key) {
            JsonNode b = behavior();
            JsonNode v = b.isObject() ? b.get(key) : null;
            return v == null || v.isNull() ? null : pyStr(v);
        }

        /** MVP 지원 지역(대구 수성구, 시군구코드 27260)인지. */
        public boolean isSuseong() {
            return pnu().startsWith("27260");
        }

        /**
         * 도로명주소 또는 지번주소에 검색어가 들어 있는지.
         *
         * <p>글자 토큰은 부분 문자열로, 숫자가 든 토큰(건물번호·지번)은 토큰 단위로 비교한다 ('범어예시로 12' 가 '범어예시로 120' 에 걸리지
         * 않도록). '9002' 는 '9002-3' 에도 걸린다.
         */
        public boolean matches(String keyword) {
            List<String> kwTokens = tokens(keyword);
            if (kwTokens.isEmpty()) {
                return false;
            }
            String[] hays = {roadAddress() + " (" + text(record, "emd") + ")", jibunAddress()};
            for (String hay : hays) {
                List<String> hayTokens = tokens(hay);
                boolean ok = true;
                for (String t : kwTokens) {
                    if (t.codePoints().anyMatch(MockDataset::isPyDigit)) {
                        boolean hit = false;
                        for (String h : hayTokens) {
                            if (h.equals(t) || h.startsWith(t + "-")) {
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) {
                            ok = false;
                            break;
                        }
                    } else if (!hay.contains(t)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return true;
                }
            }
            return false;
        }

        /** 통합 시험용 요약 ({@code /__mock__/parcels} 의 항목, 파이썬 {@code MockParcel.summary()}). */
        public ObjectNode summary() {
            JsonNode r = record;
            JsonNode land = field(r, "land");
            ObjectNode o = Json.object();
            o.set("id", field(r, "id"));
            o.set("pnu", field(r, "pnu"));
            o.set("is_example", r.has("is_example") ? r.get("is_example") : Json.nodes().booleanNode(true));
            o.set("road_address", field(r, "road_address"));
            o.set("jibun_address", field(r, "jibun_address"));
            o.set("sido", field(r, "sido"));
            o.set("sigungu", field(r, "sigungu"));
            o.set("emd", field(r, "emd"));
            o.set("zone", field(r, "zone"));
            o.set("area_m2", field(land, "area_m2"));
            o.set("land_category", field(land, "category"));
            ArrayNode names = o.putArray("designations");
            for (JsonNode d : field(r, "designations").values()) {
                names.add(field(d, "name"));
            }
            o.put("has_buildings", truthy(field(r, "buildings")));
            o.put("supported_region", isSuseong());
            o.set("behavior", behavior().deepCopy());
            JsonNode tags = r.get("tags");
            o.set("tags", truthy(tags) ? tags.deepCopy() : Json.array());
            Point c = geom.getCentroid();
            o.putArray("centroid").add(Py.round(c.getX(), 7)).add(Py.round(c.getY(), 7));
            return o;
        }
    }

    private final JsonNode meta;
    private final List<Parcel> parcels;
    private final Map<String, Parcel> byPnu = new HashMap<>();

    private MockDataset(JsonNode meta, List<Parcel> parcels) {
        this.meta = meta;
        this.parcels = List.copyOf(parcels);
        for (Parcel p : parcels) { // 파이썬 dict comprehension: 같은 PNU 가 겹치면 뒤의 것
            byPnu.put(p.pnu(), p);
        }
    }

    /** 예시 필지 JSON 파일 읽기. 파일이 없거나 형식이 다르면 {@link UncheckedIOException}/{@link IllegalArgumentException}. */
    public static MockDataset load(Path path) {
        JsonNode doc;
        try {
            doc = Json.parse(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode list = doc.get("parcels");
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("parcels 목록이 없습니다: " + path);
        }
        List<Parcel> parcels = new ArrayList<>();
        int i = 0;
        for (JsonNode rec : list.values()) {
            parcels.add(new Parcel(rec, toGeometry(field(rec, "geometry")), i++));
        }
        JsonNode meta = doc.get("_meta");
        return new MockDataset(truthy(meta) ? meta : Json.object(), parcels);
    }

    /** GeoJSON(Polygon·MultiPolygon) → JTS (shapely.geometry.shape 와 같이 고리를 닫는다). */
    static Geometry toGeometry(JsonNode geometry) {
        String type = Json.text(geometry, "type");
        if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
            throw new IllegalArgumentException("지원하지 않는 도형: " + type);
        }
        return Sanitize.toGeometry(type, geometry.get("coordinates"));
    }

    /** parcels.json 의 _meta (없으면 빈 객체). */
    public JsonNode meta() {
        return meta;
    }

    /** 예시 필지 (파일 순서). */
    public List<Parcel> parcels() {
        return parcels;
    }

    /** 원본 레코드 목록 (파일 순서). */
    public List<JsonNode> records() {
        List<JsonNode> out = new ArrayList<>(parcels.size());
        for (Parcel p : parcels) {
            out.add(p.record());
        }
        return out;
    }

    /** PNU 로 찾기. 없으면 null. */
    public Parcel byPnu(String pnu) {
        return byPnu.get(pnu);
    }

    /** PNU 가 주어진 앞부분으로 시작하는 필지 (파일 순서). */
    public List<Parcel> byPnuPrefix(String prefix) {
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : parcels) {
            if (p.pnu().startsWith(prefix)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 좌표(경도, 위도)를 덮는(covers) 필지. 경계 위면 여럿일 수 있다. */
    public List<Parcel> atPoint(double lng, double lat) {
        Point pt = GF.createPoint(new Coordinate(lng, lat));
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : parcels) {
            if (p.geom().covers(pt)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 도형과 교차하는 필지. */
    public List<Parcel> intersecting(Geometry geom) {
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : parcels) {
            if (p.geom().intersects(geom)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 주소 검색어에 걸리는 필지 ({@link Parcel#matches}). */
    public List<Parcel> search(String keyword) {
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : parcels) {
            if (p.matches(keyword)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 시도명·시군구명 집합 (검색어가 지역명만으로 되어 있는지 판단할 때). */
    public Set<String> regionNames() {
        Set<String> names = new HashSet<>();
        for (Parcel p : parcels) {
            names.add(text(p.record(), "sido"));
            names.add(text(p.record(), "sigungu"));
        }
        return names;
    }

    // ── 파이썬 흉내 도우미 (MockServer 와 공용) ──────────────────────────────

    /** 파이썬 {@code re.split(r"[\s,()]+", text)} 에서 빈 조각을 뺀 것 (공백은 파이썬 기준). */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Py.isSpace(cp) || cp == ',' || cp == '(' || cp == ')') {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.appendCodePoint(cp);
            }
        });
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 파이썬 {@code str.isdigit()} (코드포인트 하나) = Numeric_Type 이 Decimal 또는 Digit. */
    static boolean isPyDigit(int cp) {
        if (Character.isDigit(cp)) {
            return true;
        }
        for (int i = 0; i < DIGIT_NOT_DECIMAL.length; i += 2) {
            if (cp >= DIGIT_NOT_DECIMAL[i] && cp <= DIGIT_NOT_DECIMAL[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** 파이썬 {@code s.isdigit()}: 비어 있지 않고 모두 숫자. */
    static boolean isPyDigit(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(MockDataset::isPyDigit);
    }

    /** 파이썬 {@code bool(value)} (json.loads 결과 기준): None·False·0·빈 문자열·빈 목록·빈 dict 는 거짓. */
    static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return !v.isEmpty();
    }

    /** 파이썬 {@code str(value)} (json.loads 결과: str·int·float·bool·None). */
    static String pyStr(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "None";
        }
        if (v.isString()) {
            return v.stringValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "True" : "False";
        }
        if (v.isIntegralNumber()) {
            return v.bigIntegerValue().toString();
        }
        if (v.isNumber()) {
            return Py.floatRepr(v.doubleValue());
        }
        return v.toString();
    }

    /** 파이썬 {@code obj[key]}: 키가 없으면 KeyError. */
    static JsonNode field(JsonNode obj, String key) {
        JsonNode v = obj == null ? null : obj.get(key);
        if (v == null) {
            throw new IllegalStateException("KeyError: '" + key + "'");
        }
        return v;
    }

    /** 파이썬 {@code str(obj[key])}. */
    static String text(JsonNode obj, String key) {
        return pyStr(field(obj, key));
    }
}
