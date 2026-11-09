package com.autoparkingplan.server.tools.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.parking.Geo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 점검에 쓰는 기준 데이터: 모의 서버 예시 필지 요약과 조례 원문에서 독립적으로 읽은 건폐율·용적률. */
final class Fixtures {
    private Fixtures() {
    }

    /**
     * 파이썬 {@code mockserver.load_parcels()} — 예시 필지 JSON({@code data/mock/parcels.json})의 통합 시험용 요약.
     * (모의 서버 구현에 의존하지 않고 파일을 직접 읽는다.)
     */
    static List<ObjectNode> loadParcels(Path fixtures) {
        JsonNode doc = Json.parse(readBytes(fixtures));
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode r : PyStr.item(doc, "parcels").values()) {
            Geometry geom = Geo.geojsonToShape(PyStr.item(r, "geometry"));
            String pnu = PyStr.item(r, "pnu").stringValue();
            ObjectNode s = Json.object();
            s.set("id", PyStr.item(r, "id"));
            s.put("pnu", pnu);
            s.set("is_example", r.has("is_example") ? r.get("is_example") : Json.nodes().booleanNode(true));
            s.set("road_address", PyStr.item(r, "road_address"));
            s.set("jibun_address", PyStr.item(r, "jibun_address"));
            s.set("sido", PyStr.item(r, "sido"));
            s.set("sigungu", PyStr.item(r, "sigungu"));
            s.set("emd", PyStr.item(r, "emd"));
            s.set("zone", PyStr.item(r, "zone"));
            JsonNode land = PyStr.item(r, "land");
            s.set("area_m2", PyStr.item(land, "area_m2"));
            s.set("land_category", PyStr.item(land, "category"));
            ArrayNode des = s.putArray("designations");
            for (JsonNode d : PyStr.item(r, "designations").values()) {
                des.add(PyStr.item(d, "name"));
            }
            s.put("has_buildings", PyStr.truthy(PyStr.item(r, "buildings")));
            s.put("supported_region", pnu.startsWith("27260"));
            JsonNode beh = r.get("behavior");
            s.set("behavior", PyStr.truthy(beh) ? beh.deepCopy() : Json.object());
            JsonNode tags = r.get("tags");
            s.set("tags", PyStr.truthy(tags) ? tags.deepCopy() : Json.array());
            Point c = geom.getCentroid();
            s.putArray("centroid").add(Py.round(c.getX(), 7)).add(Py.round(c.getY(), 7));
            out.add(s);
        }
        return out;
    }

    /** 용도지역별 기대값 (조례 건폐율 %, 용적률 %, 용적률 단서 '다만' 유무). */
    record Ratio(double bcr, double far, boolean farHasException) {
    }

    private static final Pattern ITEM = Pattern.compile("^\\s*\\d+\\.\\s*(\\S+지역)\\s*:\\s*(.+)$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BREAK = Pattern.compile("^\\s*[①-⑳]|^\\s*\\d+\\.", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PERCENT = Pattern.compile("(\\d+)천(\\d{3})?퍼센트|(\\d+)퍼센트", Pattern.UNICODE_CHARACTER_CLASS);

    /** 파이썬 {@code expected_ratios()}: 대구 도시계획 조례 제75조(건폐율)·제80조(용적률) 원문 번들에서 읽는다 (엔진 경로와 별개). */
    static Map<String, Ratio> expectedRatios(Path legalSources) {
        Path bundle = legalSources.resolve("daegu.ord.urban_planning__2026-05-11.json");
        Map<String, String> bcr = table(bundle, "제75조");
        Map<String, String> far = table(bundle, "제80조");
        Map<String, Ratio> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bcr.entrySet()) {
            String z = e.getKey();
            if (!far.containsKey(z)) {
                continue;
            }
            String f = far.get(z);
            out.put(z, new Ratio(num(before(e.getValue(), "(다만")), num(before(f, "(다만")), f.contains("다만")));
        }
        return out;
    }

    /** 조문 본문의 "N. OOO지역 : 비율" 줄을 용도지역 → 비율 문구로 (다음 줄로 이어지는 단서 '(다만, …)' 포함). */
    private static Map<String, String> table(Path bundle, String art) {
        JsonNode b = Json.parse(readBytes(bundle));
        String text = null;
        for (JsonNode a : PyStr.item(b, "articles").values()) {
            if (PyStr.eq(a.get("label"), art)) {
                text = PyStr.item(a, "text").stringValue();
                break;
            }
        }
        if (text == null) {
            throw new NoSuchElementException("조문이 없습니다: " + art + " (" + bundle.getFileName() + ")");
        }
        Map<String, String> out = new LinkedHashMap<>();
        String cur = null;
        for (String line : text.split("\n", -1)) {
            Matcher m = ITEM.matcher(line);
            if (m.find()) {
                cur = m.group(1);
                out.put(cur, m.group(2));
            } else if (BREAK.matcher(line).find()) {
                cur = null;
            } else if (cur != null) { // 다음 줄로 이어지는 단서 '(다만, …)'
                out.put(cur, out.get(cur) + "\n" + line);
            }
        }
        return out;
    }

    /** "1천 300퍼센트 이하" → 1300.0, "60퍼센트 이하" → 60.0. */
    private static double num(String raw) {
        String s = raw.replace(" ", "");
        Matcher m = PERCENT.matcher(s);
        if (!m.find()) {
            throw new IllegalStateException("비율을 읽을 수 없습니다: " + raw);
        }
        if (m.group(1) != null) {
            return (double) (Long.parseLong(m.group(1)) * 1000 + (m.group(2) != null ? Long.parseLong(m.group(2)) : 0));
        }
        return Double.parseDouble(m.group(3));
    }

    /** {@code s.split(sep)[0]}. */
    private static String before(String s, String sep) {
        int i = s.indexOf(sep);
        return i < 0 ? s : s.substring(0, i);
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
