package com.autoparkingplan.parking;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 주차 배치용 좌표·기하 유틸리티. 파이썬 {@code app/parking/geo.py}.
 *
 * <ul>
 *   <li>입출력 좌표: EPSG:4326 GeoJSON ([lng, lat])</li>
 *   <li>내부 계산 좌표: EPSG:5179 (UTM-K, m)</li>
 * </ul>
 *
 * <p>변환기는 (src, dst) 별로 한 번만 만들어 캐시한다 (불변 객체라 스레드 안전). I/O 없음, 순수 함수만 둔다.
 */
public final class Geo {
    public static final String WGS84 = "EPSG:4326";
    public static final String UTMK = "EPSG:5179";
    /** GeoJSON 출력 소수 자릿수 (약 1cm). */
    public static final int COORD_PRECISION = 7;

    private static final GeometryFactory FACTORY = Shapely.FACTORY;
    private static final Map<String, CoordinateTransformer> TRANSFORMERS = new ConcurrentHashMap<>();

    private Geo() {
    }

    // ── 좌표 변환 ─────────────────────────────────────────────────────

    /** (src, dst) 별로 한 번만 만드는 always_xy 변환기. */
    public static CoordinateTransformer getTransformer(String src, String dst) {
        return TRANSFORMERS.computeIfAbsent(src + "->" + dst, k -> CoordinateTransformer.create(src, dst));
    }

    /** 시험용: 변환기를 바꿔 끼운다 ({@link #resetTransformers()} 로 되돌림). */
    static void overrideTransformer(String src, String dst, CoordinateTransformer t) {
        TRANSFORMERS.put(src + "->" + dst, t);
    }

    static void resetTransformers() {
        TRANSFORMERS.clear();
    }

    private static <T extends Geometry> T transformGeom(T geom, CoordinateTransformer tr) {
        return Shapely.transform(geom, tr::transform);
    }

    /** EPSG:4326(lng, lat) 기하 → EPSG:5179(m). */
    public static <T extends Geometry> T toUtmk(T geom) {
        return transformGeom(geom, getTransformer(WGS84, UTMK));
    }

    /** EPSG:5179(m) 기하 → EPSG:4326(lng, lat). */
    public static <T extends Geometry> T toWgs84(T geom) {
        return transformGeom(geom, getTransformer(UTMK, WGS84));
    }

    /** [x, y] (EPSG:5179). */
    public static double[] lnglatToUtmk(double lng, double lat) {
        return getTransformer(WGS84, UTMK).transform(lng, lat);
    }

    /** [lng, lat]. */
    public static double[] utmkToLnglat(double x, double y) {
        return getTransformer(UTMK, WGS84).transform(x, y);
    }

    // ── GeoJSON ↔ 기하 ────────────────────────────────────────────────

    /** 기하에서 면(Polygon) 부분만 꺼낸다 (GeometryCollection 포함, 빈 것 제외). */
    public static List<Polygon> polygonParts(Geometry geom) {
        List<Polygon> out = new ArrayList<>();
        collectPolygons(geom, out);
        return out;
    }

    private static void collectPolygons(Geometry geom, List<Polygon> out) {
        if (geom == null || geom.isEmpty()) {
            return;
        }
        if (geom instanceof Polygon p) {
            out.add(p);
        } else if (geom instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Polygon g = (Polygon) mp.getGeometryN(i);
                if (!g.isEmpty()) {
                    out.add(g);
                }
            }
        } else if (geom instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                collectPolygons(gc.getGeometryN(i), out);
            }
        }
    }

    /** 가장 넓은 Polygon (동률이면 앞의 것). 없으면 null. */
    public static Polygon largestPolygon(Geometry geom) {
        Polygon best = null;
        for (Polygon p : polygonParts(geom)) {
            if (best == null || p.getArea() > best.getArea()) {
                best = p;
            }
        }
        return best;
    }

    /**
     * GeoJSON Geometry / Feature / FeatureCollection → Polygon 또는 MultiPolygon. 좌표는 [lng, lat] 로 가정한다.
     *
     * @throws GeoJSONError 면이 아니거나 해석할 수 없는 GeoJSON
     */
    public static Geometry geojsonToShape(JsonNode obj) {
        if (obj == null || !obj.isObject()) {
            throw new GeoJSONError("GeoJSON object must be a dict");
        }
        JsonNode gtypeNode = obj.get("type");
        String gtype = gtypeNode != null && gtypeNode.isString() ? gtypeNode.stringValue() : null;
        if ("Feature".equals(gtype)) {
            JsonNode geometry = obj.get("geometry");
            if (geometry == null || !geometry.isObject()) {
                throw new GeoJSONError("Feature has no geometry");
            }
            return geojsonToShape(geometry);
        }
        if ("FeatureCollection".equals(gtype)) {
            List<Polygon> polys = new ArrayList<>();
            for (JsonNode feat : pyIterOrEmpty(obj.get("features"))) {
                polys.addAll(polygonParts(geojsonToShape(feat)));
            }
            if (polys.isEmpty()) {
                throw new GeoJSONError("FeatureCollection has no polygonal features");
            }
            return polys.size() == 1 ? polys.get(0) : FACTORY.createMultiPolygon(polys.toArray(new Polygon[0]));
        }
        if (!"Polygon".equals(gtype) && !"MultiPolygon".equals(gtype)) {
            throw new GeoJSONError("unsupported geometry type: " + pyRepr(gtypeNode));
        }
        try {
            return shape(obj, gtype);
        } catch (RuntimeException exc) {
            throw new GeoJSONError("invalid " + gtype + " coordinates: " + exc.getMessage(), exc);
        }
    }

    /** {@code obj.get("features") or []} 을 파이썬처럼 순회 (dict 는 키, 문자열은 글자). */
    private static List<JsonNode> pyIterOrEmpty(JsonNode v) {
        if (Json.isNull(v)) {
            return List.of();
        }
        if (v.isNumber() && v.doubleValue() == 0.0 || v.isBoolean() && !v.booleanValue()) {
            return List.of();
        }
        return pyIter(v);
    }

    /** 파이썬 {@code iter(x)}: list → 원소, dict → 키, str → 글자. 그 밖은 TypeError. */
    private static List<JsonNode> pyIter(JsonNode v) {
        List<JsonNode> out = new ArrayList<>();
        if (v.isArray()) {
            v.values().forEach(out::add);
        } else if (v.isObject()) {
            for (Iterator<String> it = v.propertyNames().iterator(); it.hasNext(); ) {
                out.add(Json.nodes().stringNode(it.next()));
            }
        } else if (v.isString()) {
            v.stringValue().codePoints().forEach(cp -> out.add(Json.nodes().stringNode(new String(Character.toChars(cp)))));
        } else {
            throw new IllegalArgumentException("'" + pyTypeName(v) + "' object is not iterable");
        }
        return out;
    }

    private static String pyTypeName(JsonNode v) {
        if (v.isIntegralNumber()) {
            return "int";
        }
        if (v.isNumber()) {
            return "float";
        }
        if (v.isBoolean()) {
            return "bool";
        }
        return v.isNull() ? "NoneType" : "object";
    }

    private static String pyRepr(JsonNode v) {
        if (Json.isNull(v)) {
            return "None";
        }
        return v.isString() ? "'" + v.stringValue() + "'" : v.toString();
    }

    /** shapely {@code shape(obj)} (Polygon/MultiPolygon). */
    private static Geometry shape(JsonNode ob, String gtype) {
        boolean hasCoords = ob.has("coordinates");
        JsonNode coords = ob.get("coordinates");
        if (hasCoords && isCoordinatesEmpty(coords)) {
            return "Polygon".equals(gtype) ? FACTORY.createPolygon() : FACTORY.createMultiPolygon();
        }
        if (!hasCoords) {
            throw new IllegalArgumentException("'coordinates'");
        }
        if ("Polygon".equals(gtype)) {
            List<JsonNode> rings = pySequence(coords);
            if (rings.isEmpty()) {
                throw new IllegalArgumentException("list index out of range");
            }
            return polygon(rings.get(0), rings.subList(1, rings.size()));
        }
        List<Polygon> subs = new ArrayList<>();
        for (JsonNode c : pyIter(coords)) {
            List<JsonNode> parts = pySequence(c);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("list index out of range");
            }
            subs.add(polygon(parts.get(0), parts.subList(1, parts.size())));
        }
        return FACTORY.createMultiPolygon(subs.toArray(new Polygon[0]));
    }

    /** 첨자 접근이 되는 시퀀스 (list·str). */
    private static List<JsonNode> pySequence(JsonNode v) {
        if (v.isArray() || v.isString()) {
            return pyIter(v);
        }
        throw new IllegalArgumentException("'" + pyTypeName(v) + "' object is not subscriptable");
    }

    private static boolean isCoordinatesEmpty(JsonNode c) {
        if (Json.isNull(c)) {
            return true;
        }
        if (c.isArray()) {
            if (c.isEmpty()) {
                return true;
            }
            for (JsonNode x : c.values()) {
                if (!isCoordinatesEmpty(x)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Polygon polygon(JsonNode shellNode, List<JsonNode> holeNodes) {
        LinearRing shell = linearRing(shellNode);
        LinearRing[] holes = new LinearRing[holeNodes.size()];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = linearRing(holeNodes.get(i));
        }
        if (shell.isEmpty() && holes.length == 0) {
            return FACTORY.createPolygon();
        }
        return FACTORY.createPolygon(shell, holes);
    }

    /** shapely {@code LinearRing(coords)}: float() 변환, 2·3차원, 닫히지 않았거나 3점이면 첫 점을 붙인다. */
    private static LinearRing linearRing(JsonNode coordsNode) {
        List<JsonNode> items = pyIter(coordsNode);
        if (items.isEmpty()) {
            return FACTORY.createLinearRing();
        }
        List<double[]> pts = new ArrayList<>(items.size());
        int dim = -1;
        for (JsonNode o : items) {
            List<JsonNode> comps = pyIter(o);
            double[] p = new double[comps.size()];
            for (int i = 0; i < p.length; i++) {
                p[i] = pyFloat(comps.get(i));
            }
            if (dim < 0) {
                dim = p.length;
            } else if (dim != p.length) {
                throw new IllegalArgumentException("setting an array element with a sequence. The requested array has an "
                        + "inhomogeneous shape after 1 dimensions.");
            }
            pts.add(p);
        }
        if (dim != 2 && dim != 3) {
            throw new IllegalArgumentException("The ordinate (last) dimension should be 2 or 3, got " + dim);
        }
        boolean closure = pts.size() == 3;
        if (!closure) {
            double[] first = pts.get(0);
            double[] last = pts.get(pts.size() - 1);
            for (int j = 0; j < dim; j++) {
                if (first[j] != last[j]) {
                    closure = true;
                    break;
                }
            }
        }
        int n = pts.size() + (closure ? 1 : 0);
        if (n < 4) {
            throw new IllegalArgumentException("A linearring requires at least 4 coordinates.");
        }
        Coordinate[] c = new Coordinate[n];
        for (int i = 0; i < pts.size(); i++) {
            c[i] = new Coordinate(pts.get(i)[0], pts.get(i)[1]);
        }
        if (closure) {
            c[n - 1] = new Coordinate(pts.get(0)[0], pts.get(0)[1]);
        }
        return FACTORY.createLinearRing(c);
    }

    /** 파이썬 {@code float(x)} (수·bool·숫자 문자열). */
    static double pyFloat(JsonNode v) {
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? 1.0 : 0.0;
        }
        if (v.isString()) {
            String s = Py.strip(v.stringValue()).toLowerCase(java.util.Locale.ROOT);
            String t = s.replace("_", "");
            String u = t.startsWith("+") || t.startsWith("-") ? t.substring(1) : t;
            double sign = t.startsWith("-") ? -1.0 : 1.0;
            if (u.equals("inf") || u.equals("infinity")) {
                return sign * Double.POSITIVE_INFINITY;
            }
            if (u.equals("nan")) {
                return Double.NaN;
            }
            if (!s.isEmpty() && !s.contains("__") && !s.startsWith("_") && !s.endsWith("_")
                    && t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([e][+-]?\\d+)?")) {
                return Double.parseDouble(t);
            }
            throw new IllegalArgumentException("could not convert string to float: '" + v.stringValue() + "'");
        }
        throw new IllegalArgumentException("float() argument must be a string or a real number, not '"
                + pyTypeName(v) + "'");
    }

    private static List<double[]> roundCoords(Coordinate[] coords, int precision) {
        List<double[]> out = new ArrayList<>();
        for (Coordinate c : coords) {
            double[] pt = {Py.round(c.x, precision), Py.round(c.y, precision)};
            if (!out.isEmpty()) {
                double[] last = out.get(out.size() - 1);
                if (last[0] == pt[0] && last[1] == pt[1]) {
                    continue; // 반올림으로 생긴 연속 중복점 제거
                }
            }
            out.add(pt);
        }
        return out;
    }

    private static List<double[]> ring(LinearRing ring, int precision) {
        List<double[]> pts = roundCoords(ring.getCoordinates(), precision);
        if (!pts.isEmpty()) {
            double[] first = pts.get(0);
            double[] last = pts.get(pts.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                pts.add(new double[] {first[0], first[1]});
            }
        }
        return pts;
    }

    private static ArrayNode coordArray(List<double[]> pts) {
        ArrayNode a = Json.array();
        for (double[] p : pts) {
            a.addArray().add(p[0]).add(p[1]);
        }
        return a;
    }

    private static ArrayNode polygonCoords(Polygon g, int precision) {
        Polygon p = Shapely.orient(g);
        ArrayNode rings = Json.array();
        rings.add(coordArray(ring(p.getExteriorRing(), precision)));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            rings.add(coordArray(ring(p.getInteriorRingN(i), precision)));
        }
        return rings;
    }

    /** 기하 → GeoJSON geometry (좌표 반올림, RFC 7946 링 방향). */
    public static ObjectNode shapeToGeojson(Geometry geom, int precision) {
        ObjectNode o = Json.object();
        if (geom instanceof Point p) {
            o.put("type", "Point");
            o.putArray("coordinates").add(Py.round(p.getX(), precision)).add(Py.round(p.getY(), precision));
        } else if (geom instanceof MultiPoint mp) {
            o.put("type", "MultiPoint");
            ArrayNode a = o.putArray("coordinates");
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Point p = (Point) mp.getGeometryN(i);
                a.addArray().add(Py.round(p.getX(), precision)).add(Py.round(p.getY(), precision));
            }
        } else if (geom instanceof LinearRing || geom instanceof LineString) {
            o.put("type", "LineString");
            o.set("coordinates", coordArray(roundCoords(geom.getCoordinates(), precision)));
        } else if (geom instanceof MultiLineString ml) {
            o.put("type", "MultiLineString");
            ArrayNode a = o.putArray("coordinates");
            for (int i = 0; i < ml.getNumGeometries(); i++) {
                a.add(coordArray(roundCoords(ml.getGeometryN(i).getCoordinates(), precision)));
            }
        } else if (geom instanceof Polygon p) {
            o.put("type", "Polygon");
            o.set("coordinates", polygonCoords(p, precision));
        } else if (geom instanceof MultiPolygon mp) {
            o.put("type", "MultiPolygon");
            ArrayNode a = o.putArray("coordinates");
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                a.add(polygonCoords((Polygon) mp.getGeometryN(i), precision));
            }
        } else if (geom instanceof GeometryCollection gc) {
            o.put("type", "GeometryCollection");
            ArrayNode a = o.putArray("geometries");
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                a.add(shapeToGeojson(gc.getGeometryN(i), precision));
            }
        } else {
            throw new GeoJSONError("unsupported geometry type: " + geom.getGeometryType());
        }
        return o;
    }

    public static ObjectNode shapeToGeojson(Geometry geom) {
        return shapeToGeojson(geom, COORD_PRECISION);
    }

    /** {"type": "Feature", "geometry": ..., "properties": {...}}. properties 는 복사한다 (null 이면 빈 객체). */
    public static ObjectNode feature(Geometry geom, ObjectNode properties, int precision) {
        ObjectNode f = Json.object();
        f.put("type", "Feature");
        f.set("geometry", shapeToGeojson(geom, precision));
        f.set("properties", properties == null ? Json.object() : properties.deepCopy());
        return f;
    }

    public static ObjectNode feature(Geometry geom, ObjectNode properties) {
        return feature(geom, properties, COORD_PRECISION);
    }

    public static ObjectNode featureCollection(List<ObjectNode> features) {
        ObjectNode fc = Json.object();
        fc.put("type", "FeatureCollection");
        ArrayNode a = fc.putArray("features");
        features.forEach(a::add);
        return fc;
    }

    // ── 면적·검사 ─────────────────────────────────────────────────────

    /** EPSG:4326 기하의 면적(㎡). EPSG:5179 로 투영해 계산한다. */
    public static double areaM2(Geometry geomWgs84) {
        return toUtmk(geomWgs84).getArea();
    }

    /** 경위도 범위(-180..180, -90..90)와 유한값 여부. */
    public static boolean lnglatBoundsOk(Geometry geom) {
        if (geom.isEmpty()) {
            return false;
        }
        Envelope e = geom.getEnvelopeInternal();
        double minx = e.getMinX();
        double miny = e.getMinY();
        double maxx = e.getMaxX();
        double maxy = e.getMaxY();
        if (!(Double.isFinite(minx) && Double.isFinite(miny) && Double.isFinite(maxx) && Double.isFinite(maxy))) {
            return false;
        }
        return -180.0 <= minx && minx <= maxx && maxx <= 180.0 && -90.0 <= miny && miny <= maxy && maxy <= 90.0;
    }

    /** 좌표가 하나 이상 있고 모두 유한값. */
    public static boolean allFinite(Geometry geom) {
        Coordinate[] cs = geom.getCoordinates();
        if (cs.length == 0) {
            return false;
        }
        for (Coordinate c : cs) {
            if (!Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                return false;
            }
        }
        return true;
    }
}
