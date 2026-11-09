package com.autoparkingplan.server.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 입력·출력 정화: 링크는 http(s) 만, GeoJSON 경계는 모양·좌표 범위·꼭짓점 수를 확인한다 (파이썬 app.security.sanitize). */
public final class Sanitize {
    /** 대한민국 영역을 넉넉히 감싸는 경위도 범위 (EPSG:4326): minx, miny, maxx, maxy. */
    public static final double[] KOREA_BOUNDS = {124.0, 33.0, 132.0, 39.0};
    public static final int MAX_VERTICES = 50_000;

    private Sanitize() {
    }

    /**
     * 화면에서 링크(a href)로 쓰일 값. http/https 이고 호스트가 있을 때만 그대로(앞뒤 공백 제거), 아니면 null.
     * javascript:, data:, vbscript:, file:, 상대경로, 제어문자(줄바꿈 등)가 섞인 값은 버린다 → 저장형 XSS 차단.
     */
    public static String safeUrl(Object url) {
        if (!(url instanceof String s)) {
            return url instanceof JsonNode n && n.isString() ? safeUrl(n.stringValue()) : null;
        }
        String u = Py.strip(s);
        if (u.isEmpty() || Py.len(u) > 2048) {
            return null;
        }
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return null;
            }
        }
        int colon = u.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = u.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = hostname(u.substring(colon + 1));
        return host == null || host.isEmpty() ? null : u;
    }

    /** urlsplit 의 hostname: '//' 뒤 권한 부분에서 사용자 정보·포트를 뺀 것. */
    private static String hostname(String afterScheme) {
        if (!afterScheme.startsWith("//")) {
            return null;
        }
        String rest = afterScheme.substring(2);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0) {
                end = Math.min(end, i);
            }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        String hostport = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostport.startsWith("[")) {
            int close = hostport.indexOf(']');
            return close > 1 ? hostport.substring(1, close) : null;
        }
        int c = hostport.indexOf(':');
        String host = c >= 0 ? hostport.substring(0, c) : hostport;
        try {
            if (!host.isEmpty()) {
                new URI("http", host, null, null); // 해석할 수 없는 호스트 거르기
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    public static class GeometryError extends IllegalArgumentException {
        public GeometryError(String message) {
            super(message);
        }
    }

    private static int countCoords(JsonNode coords, int depth, int[] n) {
        if (depth > 4) { // MultiPolygon 도 4단계면 끝난다 → 깊은 중첩으로 재귀 한도를 노리는 입력 차단
            throw new GeometryError("coordinates 중첩이 너무 깊습니다");
        }
        if (coords != null && coords.isArray() && !coords.isEmpty() && coords.get(0).isNumber()) {
            checkPoint(coords, n);
            return n[0];
        }
        if (coords == null || !coords.isArray()) {
            throw new GeometryError("coordinates 형식이 올바르지 않습니다");
        }
        for (JsonNode c : coords.values()) {
            countCoords(c, depth + 1, n);
        }
        return n[0];
    }

    private static void checkPoint(JsonNode pt, int[] n) {
        n[0]++;
        if (n[0] > MAX_VERTICES) {
            throw new GeometryError(String.format("꼭짓점이 너무 많습니다 (최대 %,d개)", MAX_VERTICES));
        }
        if (pt.size() < 2 || !finiteNumber(pt.get(0)) || !finiteNumber(pt.get(1))) {
            throw new GeometryError("좌표는 유한한 [경도, 위도] 숫자여야 합니다");
        }
        double x = pt.get(0).doubleValue();
        double y = pt.get(1).doubleValue();
        if (!(KOREA_BOUNDS[0] <= x && x <= KOREA_BOUNDS[2] && KOREA_BOUNDS[1] <= y && y <= KOREA_BOUNDS[3])) {
            throw new GeometryError("좌표가 대한민국 경위도 범위(EPSG:4326)를 벗어났습니다");
        }
    }

    private static boolean finiteNumber(JsonNode v) {
        return v != null && v.isNumber() && Double.isFinite(v.doubleValue());
    }

    /** 관리자가 등록하는 L4 구역 경계. Polygon/MultiPolygon, 유한한 경위도, 대한민국 범위, 꼭짓점 상한, 유효한 도형. */
    public static ObjectNode validateZoneGeometry(JsonNode geom) {
        String type = geom != null && geom.isObject() ? Json.text(geom, "type") : null;
        if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
            throw new GeometryError("geometry 는 GeoJSON Polygon 또는 MultiPolygon 이어야 합니다");
        }
        for (Iterator<String> it = geom.propertyNames().iterator(); it.hasNext(); ) {
            if (!Set.of("type", "coordinates").contains(it.next())) {
                throw new GeometryError("geometry 에는 type, coordinates 만 둘 수 있습니다");
            }
        }
        int n = countCoords(geom.get("coordinates"), 0, new int[] {0});
        if (n == 0) {
            throw new GeometryError("좌표가 없습니다");
        }
        Geometry g;
        try {
            g = toGeometry(type, geom.get("coordinates"));
        } catch (RuntimeException e) { // shapely 는 형식 오류마다 다른 예외를 던진다
            throw new GeometryError("geometry 를 해석할 수 없습니다");
        }
        if (g.isEmpty() || !g.isValid() || g.getArea() <= 0) {
            throw new GeometryError("유효한 면(자기 교차 없음, 넓이 > 0)이 아닙니다");
        }
        ObjectNode out = Json.object();
        out.put("type", type);
        out.set("coordinates", geom.get("coordinates"));
        return out;
    }

    private static final GeometryFactory GF = new GeometryFactory();

    /** shapely.geometry.shape 와 같이: 고리는 닫히지 않았으면 닫고, 고리마다 점 4개 이상 필요. */
    public static Geometry toGeometry(String type, JsonNode coords) {
        if ("Polygon".equals(type)) {
            return polygon(coords);
        }
        List<Polygon> polys = new ArrayList<>();
        for (JsonNode p : coords.values()) {
            polys.add(polygon(p));
        }
        return GF.createMultiPolygon(polys.toArray(Polygon[]::new));
    }

    private static Polygon polygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            return GF.createPolygon();
        }
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return GF.createPolygon(shell, holes);
    }

    private static LinearRing ring(JsonNode pts) {
        List<Coordinate> cs = new ArrayList<>();
        for (JsonNode p : pts.values()) {
            cs.add(new Coordinate(p.get(0).doubleValue(), p.get(1).doubleValue()));
        }
        if (!cs.isEmpty() && !cs.getFirst().equals2D(cs.getLast())) {
            cs.add(new Coordinate(cs.getFirst()));
        }
        if (cs.size() < 4) {
            throw new IllegalArgumentException("A linearring requires at least 4 coordinates");
        }
        return GF.createLinearRing(cs.toArray(Coordinate[]::new));
    }
}
