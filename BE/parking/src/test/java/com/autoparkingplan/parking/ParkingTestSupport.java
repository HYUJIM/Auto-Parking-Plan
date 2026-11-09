package com.autoparkingplan.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
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
 * 주차 배치 테스트 공용: 합성 대지(대구 부근)와 배치 불변식 검사. 파이썬 {@code tests/parking/_helpers.py}.
 *
 * <p>주의: 아래 치수는 '테스트 입력'일 뿐이다. 엔진은 법정 치수를 ParkingDimensions 로만 받는다.
 */
final class ParkingTestSupport {
    // 대구 부근 기준점 (EPSG:5179 로 환산해 m 단위로 대지를 만든 뒤 EPSG:4326 으로 되돌린다)
    static final double ORIGIN_LNG = 128.63;
    static final double ORIGIN_LAT = 35.85;
    static final double X0;
    static final double Y0;

    static {
        double[] xy = Geo.lnglatToUtmk(ORIGIN_LNG, ORIGIN_LAT);
        X0 = xy[0];
        Y0 = xy[1];
    }

    static final double TOL = 1e-6; // m, EPSG:5179 내부 기하 허용오차
    static final List<String> ALL_ANGLES = List.of("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0");
    static final Map<String, Double> THETA = Map.of("PERPENDICULAR_90", 90.0, "ANGLE_60", 60.0, "ANGLE_45", 45.0,
            "PARALLEL_0", 0.0);

    private ParkingTestSupport() {
    }

    static Map<String, Map<String, Double>> defaultAisles() {
        Map<String, Map<String, Double>> a = new LinkedHashMap<>();
        a.put("PERPENDICULAR_90", widths(6.0, 6.0));
        a.put("ANGLE_60", widths(5.5, 4.5));
        a.put("ANGLE_45", widths(5.0, 3.5));
        a.put("PARALLEL_0", widths(5.0, 3.3));
        return a;
    }

    static Map<String, Double> widths(double one, double two) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("one_gate", one);
        m.put("two_plus_gates", two);
        return m;
    }

    /** 테스트 입력 치수 (법규 값 아님). */
    static ParkingDimensions makeDims() {
        Map<String, StallSize> np = new LinkedHashMap<>();
        np.put("general", new StallSize(2.5, 5.0));
        np.put("extended", new StallSize(2.6, 5.2));
        Map<String, StallSize> par = new LinkedHashMap<>();
        par.put("general", new StallSize(2.0, 6.0));
        return new ParkingDimensions(np, par, defaultAisles(), 3.5, new ArrayList<>(List.of("TEST-RULE")));
    }

    // ── 좌표 ──────────────────────────────────────────────────────────

    /** shapely {@code mapping(geom)}: 좌표를 그대로 (반올림·방향 정리 없음). */
    static ObjectNode mapping(Geometry g) {
        ObjectNode o = Json.object();
        if (g instanceof Polygon p) {
            o.put("type", "Polygon");
            o.set("coordinates", polygonCoords(p));
        } else if (g instanceof MultiPolygon mp) {
            o.put("type", "MultiPolygon");
            ArrayNode a = o.putArray("coordinates");
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                a.add(polygonCoords((Polygon) mp.getGeometryN(i)));
            }
        } else {
            throw new IllegalArgumentException(g.getGeometryType());
        }
        return o;
    }

    private static ArrayNode polygonCoords(Polygon p) {
        ArrayNode rings = Json.array();
        rings.add(ringCoords(p.getExteriorRing()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            rings.add(ringCoords(p.getInteriorRingN(i)));
        }
        return rings;
    }

    private static ArrayNode ringCoords(LinearRing r) {
        ArrayNode a = Json.array();
        for (Coordinate c : r.getCoordinates()) {
            a.addArray().add(c.x).add(c.y);
        }
        return a;
    }

    /** 기준점 기준 m 좌표 다각형 → EPSG:4326 GeoJSON geometry. */
    static ObjectNode localToGeojson(Geometry geomLocal) {
        return mapping(Geo.toWgs84(Shapely.translate(geomLocal, X0, Y0)));
    }

    static List<Double> localPointLnglat(double x, double y) {
        double[] ll = Geo.utmkToLnglat(X0 + x, Y0 + y);
        List<Double> out = new ArrayList<>();
        out.add(ll[0]);
        out.add(ll[1]);
        return out;
    }

    static <T extends Geometry> T toLocal(T geomAbs) {
        return Shapely.translate(geomAbs, -X0, -Y0);
    }

    // ── 합성 대지 (m) ─────────────────────────────────────────────────

    /** shapely {@code box(minx, miny, maxx, maxy)} (반시계, (maxx, miny) 부터). */
    static Polygon box(double minx, double miny, double maxx, double maxy) {
        return Shapely.polygon(new double[][] {{maxx, miny}, {maxx, maxy}, {minx, maxy}, {minx, miny}});
    }

    static Polygon rect(double w, double h) {
        return box(0, 0, w, h);
    }

    /** shapely {@code affinity.rotate(geom, deg, origin=(0, 0))}. */
    static <T extends Geometry> T rotate(T g, double deg, double ox, double oy) {
        double angle = deg * Math.PI / 180.0;
        double cosp = StrictMath.cos(angle);
        double sinp = StrictMath.sin(angle);
        if (Math.abs(cosp) < 2.5e-16) {
            cosp = 0.0;
        }
        if (Math.abs(sinp) < 2.5e-16) {
            sinp = 0.0;
        }
        return Shapely.affineTransform(g, cosp, -sinp, sinp, cosp, ox - ox * cosp + oy * sinp, oy - ox * sinp - oy * cosp);
    }

    static Polygon rotatedRect(double w, double h, double deg) {
        return rotate(box(0, 0, w, h), deg, 0, 0);
    }

    static final Polygon L_SHAPE = Shapely.polygon(new double[][] {{0, 0}, {50, 0}, {50, 20}, {20, 20}, {20, 45}, {0, 45}});
    static final Polygon PENTAGON = Shapely.polygon(new double[][] {{0, 0}, {60, -5}, {70, 30}, {30, 55}, {-5, 35}});
    static final Polygon IRREGULAR_HEX = Shapely.polygon(
            new double[][] {{0, 0}, {62, -4}, {70, 25}, {48, 52}, {12, 47}, {-6, 20}});

    static Polygon scaledToArea(Polygon poly, double area) {
        double f = Math.sqrt(area / poly.getArea());
        return Shapely.affineTransform(poly, f, 0.0, 0.0, f, 0.0, 0.0);
    }

    // ── 불변식 ────────────────────────────────────────────────────────

    private static Coordinate[] corners(Polygon poly) {
        Coordinate[] all = poly.getExteriorRing().getCoordinates();
        Coordinate[] pts = new Coordinate[all.length - 1];
        System.arraycopy(all, 0, pts, 0, pts.length);
        assertThat(pts).as("stall must have 4 corners, got " + pts.length).hasSize(4);
        return pts;
    }

    static double dist(Coordinate a, Coordinate b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static int nearestIndex(Coordinate[] pts, Coordinate e) {
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (dist(pts[i], e) < dist(pts[best], e)) {
                best = i;
            }
        }
        return best;
    }

    static void checkStallShape(Polygon stall, LineString edge, String angle, double w, double l) {
        Coordinate[] pts = corners(stall);
        Coordinate e0 = edge.getCoordinateN(0);
        Coordinate e1 = edge.getCoordinateN(1);
        int[] idx = {nearestIndex(pts, e0), nearestIndex(pts, e1)};
        assertThat(dist(pts[idx[0]], e0) < TOL && dist(pts[idx[1]], e1) < TOL).as("access edge must be a stall edge").isTrue();
        assertThat(Math.floorMod(idx[1] - idx[0], 4)).as("access edge endpoints must be adjacent corners").isIn(1, 3);
        double a = edge.getLength();
        // 접근변에 인접한 다른 변
        int j = idx[0];
        int k = (j + 1) % 4 == idx[1] ? Math.floorMod(j - 1, 4) : (j + 1) % 4;
        double[] side = {pts[k].x - pts[j].x, pts[k].y - pts[j].y};
        double[] acc = {e1.x - e0.x, e1.y - e0.y};
        double b = Math.hypot(side[0], side[1]);
        double cosang = Math.abs(acc[0] * side[0] + acc[1] * side[1]) / (a * b);
        double alpha = Math.toDegrees(Math.acos(Math.min(1.0, cosang)));
        // 평행사변형 (마주 보는 변 길이 같음)
        double[] lens = new double[4];
        for (int i = 0; i < 4; i++) {
            lens[i] = dist(pts[i], pts[(i + 1) % 4]);
        }
        assertThat(Math.abs(lens[0] - lens[2]) < TOL && Math.abs(lens[1] - lens[3]) < TOL).isTrue();
        double theta = THETA.get(angle);
        if ("PERPENDICULAR_90".equals(angle)) {
            assertThat(Math.abs(alpha - 90) < 1e-6 && Math.abs(a - w) < TOL && Math.abs(b - l) < TOL).isTrue();
            assertThat(a < b).as("짧은 변이 접근변").isTrue();
        } else if ("PARALLEL_0".equals(angle)) {
            assertThat(Math.abs(alpha - 90) < 1e-6 && Math.abs(a - l) < TOL && Math.abs(b - w) < TOL).isTrue();
            assertThat(a > b).as("긴 변이 접근변").isTrue();
        } else {
            double r = Math.toRadians(theta);
            assertThat(Math.abs(alpha - theta)).isLessThan(1e-6);
            assertThat(Math.abs(a - w / Math.sin(r))).isLessThan(TOL); // 차로 방향 간격 = W / sinθ
            assertThat(Math.abs(a * Math.sin(r) - w)).isLessThan(TOL); // 측변 사이 수직거리 = 법정 폭
            assertThat(Math.abs(b * Math.sin(r) - (l * Math.sin(r) + w * Math.cos(r)))).isLessThan(TOL); // 모듈 깊이
            assertThat(b - w / Math.tan(r)).isGreaterThanOrEqualTo(l - TOL); // 측변 = L + W·cotθ → W×L 직사각형을 품음
            assertThat(a < b).isTrue();
        }
    }

    private static Geometry bufferMitre(Geometry g, double d) {
        return Shapely.bufferMitre(g, d, 5.0);
    }

    /** 반환된 대안 하나의 모든 불변식. */
    static void assertLayoutValid(LayoutGeometry g, Alternative alt, ParkingDimensions dims, SimulationParams params) {
        String angle = alt.parkingAngle();
        assertThat(g.parkingAngle()).isEqualTo(angle);
        assertThat(g.stalls()).hasSize(alt.stallCount());
        assertThat(alt.stallCount()).isPositive();
        StallSize size = ("PARALLEL_0".equals(angle) ? dims.stallParallel() : dims.stallNonParallel()).get(params.stallType());
        double w = size.widthM();
        double l = size.lengthM();
        String key = params.gateCount() == 1 ? "one_gate" : "two_plus_gates";
        double reqAisle = dims.aisleWidth().get(angle).get(key);
        assertThat(g.stallWidthM() == w && g.stallLengthM() == l && g.aisleWidthM() == reqAisle).isTrue();

        // 면적 계산 정밀도를 위해 기준점 기준 지역 좌표(m)로 옮겨 검사 (EPSG:5179 와 같은 축척)
        Polygon site = toLocal(g.site());
        Polygon usable = toLocal(g.usable());
        List<Polygon> stalls = new ArrayList<>();
        g.stalls().forEach(s -> stalls.add(toLocal(s)));
        List<LineString> edges = new ArrayList<>();
        g.accessEdges().forEach(e -> edges.add(toLocal(e)));
        List<LayoutGeometry.Aisle> aisles = new ArrayList<>();
        g.aisles().forEach(a -> aisles.add(new LayoutGeometry.Aisle(a.kind(), toLocal(a.polygon()), a.widthM())));
        Polygon union = toLocal(g.aisleUnion());
        Point gp = toLocal(g.gatePoint());
        Geometry opening = toLocal(g.gateOpening());

        Geometry usableTol = bufferMitre(usable, TOL);
        assertThat(g.aisleUnion().getGeometryType()).as("aisle network must be ONE connected polygon").isEqualTo("Polygon");
        List<Polygon> aislePolys = new ArrayList<>();
        aisles.forEach(a -> aislePolys.add(a.polygon()));
        assertThat(Math.abs(union.getArea() - Shapely.unionAll(aislePolys).getArea())).isLessThan(1e-6);
        assertThat(bufferMitre(site, TOL).covers(union)).as("aisles must stay inside the site").isTrue();

        for (int i = 0; i < stalls.size(); i++) {
            Polygon stall = stalls.get(i);
            LineString edge = edges.get(i);
            // 1) 사용 가능 영역 안
            assertThat(usableTol.covers(stall)).as("stall %d outside usable area", i).isTrue();
            // 2) 구획끼리 겹치지 않음
            for (int j = 0; j < stalls.size(); j++) {
                if (j != i && stall.getEnvelopeInternal().intersects(stalls.get(j).getEnvelopeInternal())) {
                    assertThat(Shapely.intersection(stall, stalls.get(j)).getArea()).as("stalls %d,%d overlap", i, j)
                            .isLessThan(TOL);
                }
            }
            // 3) 차로와 겹치지 않음
            assertThat(Shapely.intersection(stall, union).getArea()).as("stall %d overlaps aisle", i).isLessThan(TOL);
            // 4) 접근변이 차로 경계에 전부 닿음
            assertThat(Shapely.buffer(union, TOL).covers(edge)).as("stall %d access edge not on aisle boundary", i).isTrue();
            // 5) 법정 치수 그대로 (축소 없음)
            checkStallShape(stall, edge, angle, w, l);
        }

        // 6) 차로 폭
        for (LayoutGeometry.Aisle a : aisles) {
            assertThat(a.widthM()).isGreaterThanOrEqualTo(reqAisle - TOL);
            if ("aisle".equals(a.kind()) || "spine".equals(a.kind())) {
                Polygon mrr = (Polygon) Shapely.minimumRotatedRectangle(a.polygon());
                Coordinate[] xy = mrr.getExteriorRing().getCoordinates();
                double s0 = dist(xy[0], xy[1]);
                double s1 = dist(xy[1], xy[2]);
                assertThat(Math.abs(a.polygon().getArea() - mrr.getArea())).as(a.kind() + " must be a rectangle")
                        .isLessThan(1e-6);
                assertThat(Math.min(s0, s1)).as(a.kind() + " narrower than required").isGreaterThanOrEqualTo(reqAisle - TOL);
            } else {
                assertThat(a.kind()).isEqualTo("driveway");
                if (dims.gateWidthMinM() != null && dims.gateWidthMinM() != 0.0) {
                    assertThat(a.widthM()).isGreaterThanOrEqualTo(dims.gateWidthMinM() - TOL);
                }
            }
        }
        // 출입구 바로 안쪽에도 차로 폭(지름) 원이 들어감 = 진입로 폭이 모자라지 않음
        // (곡선·꺾인 경계에서 출입구 구간의 안쪽 볼록 허용치 0.3 m 만큼 여유)
        Geometry core = Shapely.buffer(union, -(reqAisle / 2 - 1e-3));
        assertThat(core.isEmpty()).isFalse();
        assertThat(core.distance(gp)).as("driveway narrower than aisle at the gate").isLessThanOrEqualTo(reqAisle / 2 + 0.35);

        // 7) 출입구: 경계 위의 점 + 개구부, 차로망이 닿음
        assertThat(site.getExteriorRing().distance(gp)).as("gate must be on the site boundary").isLessThan(TOL);
        assertThat(union.distance(gp)).as("aisle network must touch the gate").isLessThan(TOL);
        assertThat(Shapely.buffer(site.getExteriorRing(), TOL).covers(opening)).as("gate opening must lie on the boundary")
                .isTrue();
        assertThat(Shapely.buffer(union, 1e-5).covers(opening)).as("aisle network must reach the full gate opening").isTrue();
        if (dims.gateWidthMinM() != null && dims.gateWidthMinM() != 0.0) {
            assertThat(opening.getLength()).isGreaterThanOrEqualTo(dims.gateWidthMinM() - TOL);
        }

        assertOutputLayers(alt, g, w, l, reqAisle, params);
    }

    /** shapely {@code shape(geojson)} (테스트용: Point·LineString·Polygon·MultiPolygon). */
    static Geometry shape(JsonNode geojson) {
        String t = geojson.get("type").stringValue();
        JsonNode c = geojson.get("coordinates");
        return switch (t) {
            case "Point" -> Shapely.point(c.get(0).doubleValue(), c.get(1).doubleValue());
            case "LineString" -> {
                List<Coordinate> pts = new ArrayList<>();
                c.values().forEach(p -> pts.add(new Coordinate(p.get(0).doubleValue(), p.get(1).doubleValue())));
                yield Shapely.lineString(pts.toArray(new Coordinate[0]));
            }
            default -> Geo.geojsonToShape(geojson);
        };
    }

    static void assertOutputLayers(Alternative alt, LayoutGeometry g, double w, double l, double reqAisle,
                                   SimulationParams params) {
        ObjectNode layers = alt.layers();
        assertThat(Set.copyOf(layers.propertyNames())).contains("stalls", "aisles", "gate", "usable_area");
        JsonNode feats = layers.get("stalls").get("features");
        assertThat(layers.get("stalls").get("type").stringValue()).isEqualTo("FeatureCollection");
        assertThat(feats.size()).isEqualTo(alt.stallCount());
        int i = 0;
        for (JsonNode f : feats.values()) {
            JsonNode p = f.get("properties");
            assertThat(p.get("id").stringValue()).isEqualTo(String.format("P-%03d", i + 1));
            assertThat(p.get("stall_type").stringValue()).isEqualTo(params.stallType());
            assertThat(p.get("angle").stringValue()).isEqualTo(alt.parkingAngle());
            assertThat(p.get("width_m").doubleValue()).isEqualTo(Py.round(w, 2));
            assertThat(p.get("length_m").doubleValue()).isEqualTo(Py.round(l, 2));
            JsonNode ring = f.get("geometry").get("coordinates").get(0);
            assertThat(f.get("geometry").get("type").stringValue()).isEqualTo("Polygon");
            assertThat(ring.get(0)).isEqualTo(ring.get(ring.size() - 1));
            Coordinate[] cs = new Coordinate[ring.size()];
            int k = 0;
            for (JsonNode c : ring.values()) {
                double lng = c.get(0).doubleValue();
                double lat = c.get(1).doubleValue();
                assertThat(Py.round(lng, 7)).isEqualTo(lng);
                assertThat(Py.round(lat, 7)).isEqualTo(lat);
                assertThat(124 < lng && lng < 132 && 33 < lat && lat < 39).as("[lng, lat] 순서").isTrue();
                cs[k++] = new Coordinate(lng, lat);
            }
            assertThat(Orientation.isCCW(cs)).as("RFC 7946").isTrue();
            i++;
        }
        // 4326 출력 ↔ 내부 기하 (반올림 1e-7° ≈ 1cm)
        for (int j = 0; j < Math.min(5, feats.size()); j++) {
            Geometry back = Geo.toUtmk(shape(feats.get(j).get("geometry")));
            Polygon s = g.stalls().get(j);
            assertThat(back.getCentroid().distance(s.getCentroid())).isLessThan(0.05);
            assertThat(Math.abs(back.getArea() - s.getArea())).isLessThan(0.2);
        }
        JsonNode aisles = layers.get("aisles").get("features");
        assertThat(aisles.size()).isPositive();
        List<Geometry> nets = new ArrayList<>();
        for (JsonNode a : aisles.values()) {
            assertThat(a.get("properties").get("width_m").doubleValue()).isGreaterThanOrEqualTo(reqAisle);
            nets.add(Shapely.buffer(Geo.toUtmk(shape(a.get("geometry"))), 0.02));
        }
        Geometry net = Shapely.unionAll(nets);
        assertThat(net.getGeometryType()).isEqualTo("Polygon");
        JsonNode gate = layers.get("gate");
        assertThat(gate.get("geometry").get("type").stringValue()).isEqualTo("Point");
        assertThat(net.distance(Geo.toUtmk(shape(gate.get("geometry"))))).isLessThan(0.05);
        assertThat(gate.get("properties").get("width_m").doubleValue()).isGreaterThanOrEqualTo(reqAisle);
        assertThat(layers.get("usable_area").get("geometry").get("type").stringValue()).isEqualTo("Polygon");
        ObjectNode m = alt.metrics();
        for (String key : List.of("site_area_m2", "usable_area_m2", "parking_area_m2", "stall_area_m2", "aisle_area_m2",
                "efficiency_percent", "area_per_stall_m2", "aisle_width_m", "stall_width_m", "stall_length_m",
                "orientation_deg")) {
            assertThat(m.has(key) && m.get(key).isNumber()).as(key).isTrue();
            assertThat(Py.round(m.get(key).doubleValue(), 2)).as(key).isEqualTo(m.get(key).doubleValue());
        }
        assertThat(m.get("aisle_width_m").doubleValue()).isEqualTo(Py.round(reqAisle, 2));
        assertThat(m.get("stall_width_m").doubleValue()).isEqualTo(Py.round(w, 2));
        assertThat(m.get("stall_length_m").doubleValue()).isEqualTo(Py.round(l, 2));
        assertThat(Math.abs(m.get("stall_area_m2").doubleValue() - alt.stallCount() * w * l)).isLessThan(0.01);
        assertThat(Math.abs(m.get("efficiency_percent").doubleValue()
                - m.get("stall_area_m2").doubleValue() / m.get("usable_area_m2").doubleValue() * 100)).isLessThan(0.02);
        assertThat(Math.abs(m.get("area_per_stall_m2").doubleValue()
                - m.get("usable_area_m2").doubleValue() / alt.stallCount())).isLessThan(0.02);
        assertThat(Math.abs(m.get("parking_area_m2").doubleValue()
                - (m.get("stall_area_m2").doubleValue() + m.get("aisle_area_m2").doubleValue()))).isLessThan(0.02);
        assertThat(m.get("orientation_deg").doubleValue()).isEqualTo(alt.orientationDeg());
        assertThat(alt.orientationDeg()).isBetween(0.0, 180.0).isLessThan(180.0);
    }

    /** 구획 하나가 최소 '구획 면적 + 차로 절반(양측 배치)' 을 차지한다는 거친 상한. */
    static int roughUpperBound(double usableArea, String angle, ParkingDimensions dims, String stallType, String gateKey) {
        StallSize size = ("PARALLEL_0".equals(angle) ? dims.stallParallel() : dims.stallNonParallel()).get(stallType);
        double a = dims.aisleWidth().get(angle).get(gateKey);
        double w = size.widthM();
        double l = size.lengthM();
        double t = Math.toRadians(THETA.get(angle));
        double pitch;
        double depth;
        if ("PARALLEL_0".equals(angle)) {
            pitch = l;
            depth = w;
        } else if ("PERPENDICULAR_90".equals(angle)) {
            pitch = w;
            depth = l;
        } else {
            pitch = w / Math.sin(t);
            depth = l * Math.sin(t) + w * Math.cos(t);
        }
        return (int) (usableArea / (pitch * (depth + a / 2)));
    }

    static int roughUpperBound(double usableArea, String angle, ParkingDimensions dims) {
        return roughUpperBound(usableArea, angle, dims, "general", "one_gate");
    }

    static Point pointOf(double x, double y) {
        return Shapely.point(X0 + x, Y0 + y);
    }

    /** shapely {@code MultiPolygon([...])}. */
    static MultiPolygon multi(Polygon... ps) {
        return Shapely.FACTORY.createMultiPolygon(ps);
    }
}
