package com.autoparkingplan.parking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.valid.IsValidOp;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.parking.geom.GeosMath;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 지상 자주식 노외주차장 배치 시뮬레이션 엔진. 파이썬 {@code app/parking/engine.py}.
 *
 * <p>공개 API
 * <ul>
 *   <li>{@link #simulate(JsonNode, ParkingDimensions, SimulationParams)} → {@link SimulationResult}</li>
 *   <li>{@link #simulateDetailed(JsonNode, ParkingDimensions, SimulationParams)} → (결과, 대안별 내부 기하)</li>
 * </ul>
 *
 * <p>원칙
 * <ul>
 *   <li>법정 치수(구획 폭·길이, 차로 폭, 출입구 너비)는 전부 {@link ParkingDimensions} 로 받는다.
 *       엔진에는 법규 값을 두지 않는다. 필요한 치수가 없으면 그 형식은 배치하지 않고 {@code DIMENSION_MISSING:<angle>} 를 남긴다.</li>
 *   <li>순수 함수, I/O 없음, 결정적(같은 입력 → 같은 출력).</li>
 *   <li>내부 계산은 EPSG:5179(m). 출력 레이어는 EPSG:4326 GeoJSON(소수 7자리).</li>
 * </ul>
 *
 * <p>알고리즘 개요
 * <ol>
 *   <li>대지(MultiPolygon 이면 가장 큰 부분)를 setback 만큼 안쪽으로 줄여 사용 가능 영역 U 를 만든다.</li>
 *   <li>차로 방향 후보(0~180°를 orientation_step 간격 + 대지 긴 변·최소회전사각형 방향)마다
 *       U 를 회전해 차로가 x 축과 평행한 지역 좌표계를 만든다.</li>
 *   <li>주차 모듈(구획열 D + 차로 A + 구획열 D, 주기 2D+A)을 y 방향으로 쌓는다. 시작 위치(phase)를
 *       바꿔 가며 탐색한다. 열이 들어가지 않는 쪽은 비워지므로 편측(single-loaded) 배치도 자연히 나온다.</li>
 *   <li>각 띠(band)가 U 안에 완전히 들어가는 x 구간을 해석적으로 구한다 (경계선 투영 + 내부점 판정).
 *       경사주차 열은 전단(shear) 좌표계에서 계산해 평행사변형 구획이 정확히 U 안에 들도록 한다.</li>
 *   <li>차로가 여러 개면 수직 연결차로(spine)로 하나의 망으로 묶는다. 연결차로 위치도 탐색한다.</li>
 *   <li>탐색: 방향마다 모든 거친 phase(0.5 m)의 상한(연결차로 무시 구획 수)을 구하고, 상한 상위 방향만
 *       연결차로까지 정밀 평가한다(상한 &lt; 현재 최선이면 가지치기). 상위 phase 주변 미세 탐색(1/16 m),
 *       연결차로 위치 미세 조정(0.1 m) 후 방향별 최선안에 출입구를 붙인다.</li>
 *   <li>출입구: 지정점이 있으면 대지 경계 최근접점으로 스냅, 없으면 구획 손실이 가장 적은 경계점을 고른다.
 *       출입구에서 대지 경계에 수직으로 진입로(driveway)를 내어 차로망에 붙인다. 진입로와 겹치는 구획은 뺀다.</li>
 * </ol>
 *
 * <p>기하 연산은 파이썬판이 쓰는 shapely 2.2(GEOS 3.14.1)와 같은 결과를 내도록 {@link Shapely} 를 거친다.
 * numpy 배열 연산은 같은 연산 순서의 반복문으로 옮겼다 (numpy 는 원소별 연산을 합치지 않으므로 결과가 같다).
 */
public final class ParkingEngine {
    // ── 제약 사유 코드 ─────────────────────────────────────────────────
    public static final String SITE_GEOMETRY_INVALID = "SITE_GEOMETRY_INVALID";
    /** 부지 경계 꼭짓점 상한 — 배치 탐색 비용은 꼭짓점 수에 대략 제곱으로 늘어난다. 지적도 필지는 수백 점을 넘지 않으므로 넘으면 SITE_GEOMETRY_INVALID. */
    public static final int MAX_SITE_VERTICES = 20_000;
    /** 자기교차 등 → make_valid 로 보정해 진행. */
    public static final String SITE_GEOMETRY_REPAIRED = "SITE_GEOMETRY_REPAIRED";
    public static final String MULTIPART_SITE_LARGEST_USED = "MULTIPART_SITE_LARGEST_USED";
    /** setback 후 영역이 갈라짐. */
    public static final String USABLE_AREA_SPLIT_LARGEST_USED = "USABLE_AREA_SPLIT_LARGEST_USED";
    /** 실제 코드는 "DIMENSION_MISSING:&lt;angle&gt;". */
    public static final String DIMENSION_MISSING = "DIMENSION_MISSING";
    /** 상세 코드 "LIMIT_SITE_WIDTH_NARROW:&lt;angle&gt;" 도 함께. */
    public static final String LIMIT_SITE_WIDTH_NARROW = "LIMIT_SITE_WIDTH_NARROW";
    /** 상세 코드 "NO_FEASIBLE_LAYOUT:&lt;angle&gt;" 도 사용. */
    public static final String NO_FEASIBLE_LAYOUT = "NO_FEASIBLE_LAYOUT";
    public static final String GATE_SNAPPED = "GATE_SNAPPED";
    public static final String GATE_POINT_INVALID = "GATE_POINT_INVALID";
    public static final String GATE_UNREACHABLE = "GATE_UNREACHABLE";

    // ── 엔진 조정값 (법규 값 아님: 탐색·수치 허용오차) ───────────────────
    /** 지정 출입구가 경계에서 이보다 멀면 GATE_SNAPPED. */
    public static final double GATE_SNAP_WARN_M = 15.0;
    private static final List<String> ANGLE_ORDER = List.of("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0");
    /** 형식 정의상의 각도. */
    private static final Map<String, Double> THETA_DEG = Map.of(
            "PERPENDICULAR_90", 90.0, "ANGLE_60", 60.0, "ANGLE_45", 45.0, "PARALLEL_0", 0.0);

    private static final double EPS = 1e-9; // 띠 맞춤 허용오차 (m)
    private static final double LEN_TOL = 1e-9;
    private static final double Y_TOL = 1e-7;
    private static final double COARSE_PHASE_STEP_M = 0.5;
    private static final double[] REFINE_PHASE_OFFSETS = rangeExcludingZero(-4, 5, 0.0625);
    private static final int TOP_ORIENTATIONS = 12; // 상한 기준 상위 방향만 정밀 평가
    private static final int REFINE_SEEDS = 2; // 방향마다 미세 탐색할 상위 phase 수
    private static final int FINAL_PER_ANGLE = 3;
    private static final double MIN_ORIENTATION_GAP_DEG = 10.0;
    private static final double SECONDARY_MIN_RATIO = 0.5;
    private static final int SPINE_GRID = 8;
    private static final double[] SPINE_REFINE = rangeExcludingZero(-10, 11, 0.1);
    private static final int EDGE_CANDIDATES = 6;
    private static final double GATE_OVERSHOOT_M = 0.5;
    private static final double GATE_MAX_BULGE_M = 0.3;
    private static final double GATE_SEARCH_STEP_M = 0.5;
    private static final double GATE_DEPTH_STEP_M = 0.25;
    private static final double AREA_TOL = 1e-9;
    private static final double CONTAIN_TOL = 1e-7;
    private static final double NARROW_TOL_M = 0.025; // 최대 내접원 계산 허용오차(0.01m 반경)를 흡수

    private static final GeometryFactory GF = Shapely.FACTORY;

    private ParkingEngine() {
    }

    /** 파이썬 {@code tuple(i * step for i in range(a, b) if i != 0)}. */
    private static double[] rangeExcludingZero(int from, int to, double step) {
        double[] out = new double[to - from - 1];
        int k = 0;
        for (int i = from; i < to; i++) {
            if (i != 0) {
                out[k++] = i * step;
            }
        }
        return out;
    }

    // =====================================================================
    // 내부 자료형
    // =====================================================================

    private record Spec(String angle, double thetaDeg, double stallW, double stallL, double depth, double pitch,
                        double shear, double aisleW, double drivewayW, Double gateMin) {
        double period() {
            return 2.0 * depth + aisleW;
        }
    }

    /** 선분 배열 (n,4) 을 열별로. */
    private record Edges(double[] xa, double[] ya, double[] xb, double[] yb) {
        int size() {
            return xa.length;
        }
    }

    private static final class Frame {
        final int idx;
        final double phi;
        final double cos;
        final double sin;
        final Edges e0;
        final Edges e0t;
        final double xmin;
        final double xmax;
        final double ymin;
        final double ymax;
        final Map<Double, Edges[]> sheared = new HashMap<>();

        Frame(int idx, double phi, double cos, double sin, Edges e0, Edges e0t, double xmin, double xmax, double ymin,
              double ymax) {
            this.idx = idx;
            this.phi = phi;
            this.cos = cos;
            this.sin = sin;
            this.e0 = e0;
            this.e0t = e0t;
            this.xmin = xmin;
            this.xmax = xmax;
            this.ymin = ymin;
            this.ymax = ymax;
        }
    }

    private record Iv(double a, double b) {
    }

    private record Mod(double ya, List<Iv> aisleIv, List<Iv> lowIv, List<Iv> upIv) {
    }

    /** 배치 선택: single (mi, iv) 또는 spine (s, conn). */
    private record Choice(boolean spine, int mi, Iv iv, double s, List<Conn> conn) {
    }

    private record Conn(int mi, Iv iv) {
    }

    private record Plan(int count, int nAisles, int fidx, double phase, List<Mod> mods, Choice choice) {
    }

    /** _full_eval 결과 (구획 수, 차로 수, 선택). */
    private record Res(int count, int nAisles, Choice choice) {
    }

    private record Rect(String kind, double x0, double y0, double x1, double y1) {
    }

    private record Realized(List<double[][]> stalls, List<double[][]> edges, List<Rect> rects) {
    }

    private static final class Candidate {
        final Spec spec;
        final Frame fr;
        final Plan plan;
        final List<Polygon> stalls;
        final List<LineString> edges;
        final List<LayoutGeometry.Aisle> aisles;
        final Polygon network;
        final Point gatePoint;
        final Geometry gateOpening;
        final List<String> reasons;

        Candidate(Spec spec, Frame fr, Plan plan, List<Polygon> stalls, List<LineString> edges,
                  List<LayoutGeometry.Aisle> aisles, Polygon network, Point gatePoint, Geometry gateOpening,
                  List<String> reasons) {
            this.spec = spec;
            this.fr = fr;
            this.plan = plan;
            this.stalls = stalls;
            this.edges = edges;
            this.aisles = aisles;
            this.network = network;
            this.gatePoint = gatePoint;
            this.gateOpening = gateOpening;
            this.reasons = reasons;
        }

        int count() {
            return stalls.size();
        }

        /** 순 구획 면적 = 구획 수 × 법정 폭 × 길이 (경사주차 평행사변형의 삼각 여유는 제외). */
        double stallArea() {
            return stalls.size() * spec.stallW * spec.stallL;
        }

        /** 그려진 구획 다각형 면적 합 (경사주차는 평행사변형). */
        double stallFootprint() {
            List<Double> areas = new ArrayList<>(stalls.size());
            for (Polygon s : stalls) {
                areas.add(s.getArea());
            }
            return PyFloat.sum(areas);
        }

        double aisleArea() {
            return network.getArea();
        }
    }

    /** {@link #simulateDetailed} 의 결과: 시뮬레이션 결과 + 대안별 내부 기하(EPSG:5179). */
    public record Detailed(SimulationResult result, List<LayoutGeometry> geoms) {
    }

    // =====================================================================
    // 치수 해석
    // =====================================================================

    private static Double num(Double v) {
        if (v == null) {
            return null;
        }
        double f = v;
        return Double.isFinite(f) && f > 0 ? f : null;
    }

    private static double[] sizeOf(StallSize obj) {
        if (obj == null) {
            return null;
        }
        Double w = num(obj.widthM());
        Double l = num(obj.lengthM());
        if (w == null || l == null) {
            return null;
        }
        return new double[] {w, l};
    }

    private static Spec resolveSpec(String angle, ParkingDimensions dims, SimulationParams params) {
        if (angle == null || !THETA_DEG.containsKey(angle)) {
            return null;
        }
        Map<String, StallSize> table = "PARALLEL_0".equals(angle) ? dims.stallParallel() : dims.stallNonParallel();
        double[] size = sizeOf(table == null || params.stallType() == null ? null : table.get(params.stallType()));
        Map<String, Double> widths = dims.aisleWidth() == null ? null : dims.aisleWidth().get(angle);
        int gateCount = params.gateCount() == null || params.gateCount() == 0 ? 1 : params.gateCount();
        String key = gateCount <= 1 ? "one_gate" : "two_plus_gates";
        Double aw = widths == null ? null : num(widths.get(key));
        if (size == null || aw == null) {
            return null;
        }
        double w = size[0];
        double l = size[1];
        Double gateMin = num(dims.gateWidthMinM());
        double driveW = gateMin != null ? PyFloat.max(aw, gateMin) : aw;
        double theta = THETA_DEG.get(angle);
        double depth;
        double pitch;
        double k;
        if ("PARALLEL_0".equals(angle)) {
            depth = w;
            pitch = l;
            k = 0.0;
        } else if ("PERPENDICULAR_90".equals(angle)) {
            depth = l;
            pitch = w;
            k = 0.0;
        } else {
            double r = Math.toRadians(theta);
            double sr = StrictMath.sin(r);
            double cr = StrictMath.cos(r);
            depth = l * sr + w * cr;
            pitch = w / sr;
            k = cr / sr;
        }
        return new Spec(angle, theta, w, l, depth, pitch, k, aw, driveW, gateMin);
    }

    // =====================================================================
    // 띠 맞춤: 수평 띠 [y0,y1] 가 다각형 안에 완전히 들어가는 x 구간
    // =====================================================================

    /** 짝홀 규칙 점-다각형 판정 (모든 링의 선분 E 사용). */
    private static boolean pip(Edges e, double px, double py) {
        int cnt = 0;
        for (int j = 0; j < e.size(); j++) {
            double xa = e.xa[j];
            double ya = e.ya[j];
            double xb = e.xb[j];
            double yb = e.yb[j];
            boolean cond = (ya > py) != (yb > py);
            double dy = yb - ya;
            double xint = xa + (py - ya) * (xb - xa) / (dy == 0.0 ? 1.0 : dy);
            if (cond && px < xint) {
                cnt++;
            }
        }
        return cnt % 2 == 1;
    }

    /**
     * 각 띠 i 에 대해 {x : 선분 {x}×[y0_i,y1_i] ⊂ 다각형} 을 닫힌 구간 목록으로 돌려준다.
     *
     * <p>경계선 중 띠 내부(허용오차 EPS 안쪽)를 지나는 부분의 x 투영이 금지 구간이고,
     * 그 사이 틈 중 중앙점이 다각형 내부인 것만 허용 구간이다.
     */
    private static List<List<Iv>> fitIntervals(Edges e, double[] y0, double[] y1) {
        int m = y0.length;
        List<List<Iv>> out = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            out.add(new ArrayList<>());
        }
        if (m == 0) {
            return out;
        }
        int n = e.size();
        double[] eymin = new double[n];
        double[] eymax = new double[n];
        double[] inv = new double[n];
        boolean[] flat = new boolean[n];
        for (int j = 0; j < n; j++) {
            eymin[j] = Math.min(e.ya[j], e.yb[j]);
            eymax[j] = Math.max(e.ya[j], e.yb[j]);
            double dy = e.yb[j] - e.ya[j];
            flat[j] = Math.abs(dy) <= 1e-12;
            inv[j] = flat[j] ? 0.0 : (e.xb[j] - e.xa[j]) / dy;
        }
        // 띠별로: (a, b) 를 a 오름차순 (같으면 선분 순서) 으로 훑으며 틈을 찾는다
        List<Integer> gBand = new ArrayList<>();
        List<Double> gA = new ArrayList<>();
        List<Double> gB = new ArrayList<>();
        double[] av = new double[n];
        double[] bv = new double[n];
        Integer[] order = new Integer[n];
        for (int r = 0; r < m; r++) {
            double yy0 = y0[r] + EPS;
            double yy1 = y1[r] - EPS;
            int cntValid = 0;
            for (int j = 0; j < n; j++) {
                double lo = Math.max(eymin[j], yy0);
                double hi = Math.min(eymax[j], yy1);
                if (!(lo <= hi)) {
                    continue;
                }
                double a;
                double b;
                if (flat[j]) {
                    a = Math.min(e.xa[j], e.xb[j]);
                    b = Math.max(e.xa[j], e.xb[j]);
                } else {
                    double xl = e.xa[j] + (lo - e.ya[j]) * inv[j];
                    double xh = e.xa[j] + (hi - e.ya[j]) * inv[j];
                    a = Math.min(xl, xh);
                    b = Math.max(xl, xh);
                }
                av[cntValid] = a;
                bv[cntValid] = b;
                order[cntValid] = cntValid;
                cntValid++;
            }
            if (cntValid == 0) {
                continue;
            }
            Integer[] ord = Arrays.copyOf(order, cntValid);
            Arrays.sort(ord, (p, q) -> Double.compare(av[p], av[q])); // 안정 정렬 (np.lexsort)
            double runEnd = bv[ord[0]];
            for (int t = 1; t < cntValid; t++) {
                double ai = av[ord[t]];
                double bi = bv[ord[t]];
                if (ai > runEnd) {
                    gBand.add(r);
                    gA.add(runEnd);
                    gB.add(ai);
                }
                if (bi > runEnd) {
                    runEnd = bi;
                }
            }
        }
        for (int g = 0; g < gBand.size(); g++) {
            int r = gBand.get(g);
            double x0 = gA.get(g);
            double x1 = gB.get(g);
            if (pip(e, (x0 + x1) * 0.5, (y0[r] + y1[r]) * 0.5)) {
                out.get(r).add(new Iv(x0, x1));
            }
        }
        return out;
    }

    // =====================================================================
    // 좌표계·후보 방향
    // =====================================================================

    private static Edges edgesOf(List<double[][]> rings) {
        int n = 0;
        for (double[][] r : rings) {
            n += r.length - 1;
        }
        double[] xa = new double[n];
        double[] ya = new double[n];
        double[] xb = new double[n];
        double[] yb = new double[n];
        int k = 0;
        for (double[][] r : rings) {
            for (int i = 0; i < r.length - 1; i++) {
                xa[k] = r[i][0];
                ya[k] = r[i][1];
                xb[k] = r[i + 1][0];
                yb[k] = r[i + 1][1];
                k++;
            }
        }
        return new Edges(xa, ya, xb, yb);
    }

    /** -phi 회전 (차로 방향 → x 축). */
    private static double[][] rotNeg(double[][] xy, double c, double s) {
        double[][] out = new double[xy.length][2];
        for (int i = 0; i < xy.length; i++) {
            double x = xy[i][0];
            double y = xy[i][1];
            out[i][0] = x * c + y * s;
            out[i][1] = -x * s + y * c;
        }
        return out;
    }

    private static double angleGap(double a, double b) {
        double d = PyFloat.mod(Math.abs(a - b), 180.0);
        return PyFloat.min(d, 180.0 - d);
    }

    private static double[][] coordsOf(Coordinate[] cs) {
        double[][] xy = new double[cs.length][2];
        for (int i = 0; i < cs.length; i++) {
            xy[i][0] = cs[i].x;
            xy[i][1] = cs[i].y;
        }
        return xy;
    }

    /** 대지 변 방향(우선) + 균등 간격. 앞쪽일수록 동률 시 우선. */
    private static List<Double> orientationCandidates(Polygon usable, Polygon site, double step) {
        List<Double> raw = new ArrayList<>();
        for (Polygon poly : List.of(usable, site)) {
            double[][] xy = coordsOf(poly.getExteriorRing().getCoordinates());
            int ne = xy.length - 1;
            double[] dx = new double[ne];
            double[] dy = new double[ne];
            double[] lens = new double[ne];
            Integer[] idx = new Integer[ne];
            for (int i = 0; i < ne; i++) {
                dx[i] = xy[i + 1][0] - xy[i][0];
                dy[i] = xy[i + 1][1] - xy[i][1];
                lens[i] = StrictMath.hypot(dx[i], dy[i]);
                idx[i] = i;
            }
            Arrays.sort(idx, (p, q) -> Double.compare(-lens[p], -lens[q])); // np.argsort(-lens, kind="stable")
            for (int t = 0; t < Math.min(EDGE_CANDIDATES, ne); t++) {
                int i = idx[t];
                if (lens[i] < 1.0) {
                    continue;
                }
                double ang = PyFloat.mod(Math.toDegrees(StrictMath.atan2(dy[i], dx[i])), 180.0);
                raw.add(ang);
                raw.add(PyFloat.mod(ang + 90.0, 180.0));
            }
        }
        Geometry mrr = Shapely.minimumRotatedRectangle(usable);
        if (mrr instanceof Polygon mp) {
            double[][] xy = coordsOf(mp.getExteriorRing().getCoordinates());
            for (int i = 0; i < Math.min(2, xy.length - 1); i++) {
                double ddx = xy[i + 1][0] - xy[i][0];
                double ddy = xy[i + 1][1] - xy[i][1];
                if (StrictMath.hypot(ddx, ddy) > 1e-6) {
                    raw.add(PyFloat.mod(Math.toDegrees(StrictMath.atan2(ddy, ddx)), 180.0));
                }
            }
        }
        int n = Math.max(1, (int) Math.ceil(180.0 / step - 1e-9));
        for (int i = 0; i < n; i++) {
            if (i * step < 180.0 - 1e-9) {
                raw.add(i * step);
            }
        }
        List<Double> out = new ArrayList<>();
        for (double v0 : raw) {
            double v = Py.round(PyFloat.mod(v0, 180.0), 9);
            if (v >= 180.0) {
                v = 0.0;
            }
            boolean distinct = true;
            for (double o : out) {
                if (!(angleGap(v, o) > 1e-6)) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<Frame> buildFrames(List<double[][]> ringsLocal, List<Double> angles) {
        List<Frame> frames = new ArrayList<>(angles.size());
        for (int idx = 0; idx < angles.size(); idx++) {
            double phi = angles.get(idx);
            double r = Math.toRadians(phi);
            double c = StrictMath.cos(r);
            double s = StrictMath.sin(r);
            List<double[][]> rr = new ArrayList<>(ringsLocal.size());
            double xmin = Double.POSITIVE_INFINITY;
            double xmax = Double.NEGATIVE_INFINITY;
            double ymin = Double.POSITIVE_INFINITY;
            double ymax = Double.NEGATIVE_INFINITY;
            for (double[][] ring : ringsLocal) {
                double[][] rot = rotNeg(ring, c, s);
                rr.add(rot);
                for (double[] p : rot) {
                    xmin = Math.min(xmin, p[0]);
                    xmax = Math.max(xmax, p[0]);
                    ymin = Math.min(ymin, p[1]);
                    ymax = Math.max(ymax, p[1]);
                }
            }
            Edges e0 = edgesOf(rr);
            Edges e0t = new Edges(e0.ya, e0.xa, e0.yb, e0.xb);
            frames.add(new Frame(idx, phi, c, s, e0, e0t, xmin, xmax, ymin, ymax));
        }
        return frames;
    }

    /** (위 열용, 아래 열용) 전단 좌표계 경계. 위 열: x' = x - k·y, 아래 열: x' = x + k·y. */
    private static Edges[] sheared(Frame fr, double k) {
        Edges[] hit = fr.sheared.get(k);
        if (hit != null) {
            return hit;
        }
        Edges[] res;
        if (k == 0.0) {
            res = new Edges[] {fr.e0, fr.e0};
        } else {
            Edges e = fr.e0;
            int n = e.size();
            double[] uxa = new double[n];
            double[] uxb = new double[n];
            double[] dxa = new double[n];
            double[] dxb = new double[n];
            for (int j = 0; j < n; j++) {
                uxa[j] = e.xa[j] - k * e.ya[j];
                uxb[j] = e.xb[j] - k * e.yb[j];
                dxa[j] = e.xa[j] + k * e.ya[j];
                dxb[j] = e.xb[j] + k * e.yb[j];
            }
            res = new Edges[] {new Edges(uxa, e.ya, uxb, e.yb), new Edges(dxa, e.ya, dxb, e.yb)};
        }
        fr.sheared.put(k, res);
        return res;
    }

    // =====================================================================
    // 모듈 배치·계수
    // =====================================================================

    /** 정렬 + 같은 값 제거 (파이썬 {@code sorted(set(...))}). */
    private static List<Double> sortedUnique(List<Double> vals) {
        double[] a = new double[vals.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = vals.get(i);
        }
        Arrays.sort(a);
        List<Double> out = new ArrayList<>();
        for (double v : a) {
            if (out.isEmpty() || out.get(out.size() - 1) != v) {
                out.add(v == 0.0 ? 0.0 : v);
            }
        }
        return out;
    }

    private static List<Double> coarsePhases(Frame fr, Spec spec) {
        double p = spec.period();
        double d = spec.depth;
        double a = spec.aisleW;
        double h = fr.ymax - fr.ymin;
        List<Double> vals = new ArrayList<>(List.of(0.0, -d, h, h - d - a, h - d));
        int n = (int) Math.ceil(p / COARSE_PHASE_STEP_M);
        for (int i = 0; i < n; i++) {
            vals.add(i * COARSE_PHASE_STEP_M);
        }
        List<Double> r = new ArrayList<>(vals.size());
        for (double v : vals) {
            r.add(PyFloat.mod(Py.round(PyFloat.mod(v, p), 9), p));
        }
        return sortedUnique(r);
    }

    private record Meta(int pi, double ya, boolean hasLow, boolean hasUp) {
    }

    private static List<List<Mod>> modulesForPhases(Frame fr, Spec spec, List<Double> phases) {
        double d = spec.depth;
        double a = spec.aisleW;
        double p = spec.period();
        double k = spec.shear;
        Edges[] eud = sheared(fr, k);
        Edges eup = eud[0];
        Edges edn = eud[1];
        List<Meta> meta = new ArrayList<>();
        for (int pi = 0; pi < phases.size(); pi++) {
            double base0 = fr.ymin - p + phases.get(pi);
            int j = 0;
            while (true) {
                double b = base0 + j * p;
                double ya = b + d;
                if (ya >= fr.ymax) {
                    break;
                }
                if (ya >= fr.ymin - Y_TOL && ya + a <= fr.ymax + Y_TOL) {
                    boolean hasLow = b >= fr.ymin - Y_TOL;
                    boolean hasUp = b + 2 * d + a <= fr.ymax + Y_TOL;
                    if (hasLow || hasUp) {
                        meta.add(new Meta(pi, ya, hasLow, hasUp));
                    }
                }
                j++;
            }
        }
        List<List<Mod>> result = new ArrayList<>(phases.size());
        for (int i = 0; i < phases.size(); i++) {
            result.add(new ArrayList<>());
        }
        if (meta.isEmpty()) {
            return result;
        }
        int nm = meta.size();
        double[] yaArr = new double[nm];
        double[] yaTop = new double[nm];
        for (int i = 0; i < nm; i++) {
            yaArr[i] = meta.get(i).ya;
            yaTop[i] = yaArr[i] + a;
        }
        List<List<Iv>> aisleIvs = fitIntervals(fr.e0, yaArr, yaTop);
        List<Integer> lowSel = new ArrayList<>();
        List<Integer> upSel = new ArrayList<>();
        for (int i = 0; i < nm; i++) {
            if (meta.get(i).hasLow && !aisleIvs.get(i).isEmpty()) {
                lowSel.add(i);
            }
            if (meta.get(i).hasUp && !aisleIvs.get(i).isEmpty()) {
                upSel.add(i);
            }
        }
        Map<Integer, List<Iv>> lowMap = new HashMap<>();
        Map<Integer, List<Iv>> upMap = new HashMap<>();
        if (!lowSel.isEmpty()) {
            double[] ly0 = new double[lowSel.size()];
            double[] ly1 = new double[lowSel.size()];
            for (int t = 0; t < lowSel.size(); t++) {
                ly0[t] = yaArr[lowSel.get(t)] - d;
                ly1[t] = yaArr[lowSel.get(t)];
            }
            List<List<Iv>> lowIvs = fitIntervals(edn, ly0, ly1);
            for (int t = 0; t < lowSel.size(); t++) {
                lowMap.put(lowSel.get(t), lowIvs.get(t));
            }
        }
        if (!upSel.isEmpty()) {
            double[] uy0 = new double[upSel.size()];
            double[] uy1 = new double[upSel.size()];
            for (int t = 0; t < upSel.size(); t++) {
                uy0[t] = yaArr[upSel.get(t)] + a;
                uy1[t] = yaArr[upSel.get(t)] + a + d;
            }
            List<List<Iv>> upIvs = fitIntervals(eup, uy0, uy1);
            for (int t = 0; t < upSel.size(); t++) {
                upMap.put(upSel.get(t), upIvs.get(t));
            }
        }
        for (int i = 0; i < nm; i++) {
            if (aisleIvs.get(i).isEmpty()) {
                continue;
            }
            Meta mt = meta.get(i);
            double ya = mt.ya;
            List<Iv> loIv = lowMap.getOrDefault(i, List.of());
            List<Iv> upIv = upMap.getOrDefault(i, List.of());
            if (k != 0.0) {
                double sl = k * ya; // 아래 열: u = x' - k·y_acc (y_acc = ya)
                List<Iv> lo2 = new ArrayList<>(loIv.size());
                for (Iv iv : loIv) {
                    lo2.add(new Iv(iv.a - sl, iv.b - sl));
                }
                loIv = lo2;
                double su = k * (ya + a); // 위 열: u = x' + k·y_acc (y_acc = ya + A)
                List<Iv> up2 = new ArrayList<>(upIv.size());
                for (Iv iv : upIv) {
                    up2.add(new Iv(iv.a + su, iv.b + su));
                }
                upIv = up2;
            }
            if (!loIv.isEmpty() || !upIv.isEmpty()) {
                result.get(mt.pi).add(new Mod(ya, aisleIvs.get(i), loIv, upIv));
            }
        }
        return result;
    }

    private static int count(List<Iv> ivs, double lo, double hi, double p, Iv block) {
        int n = 0;
        for (Iv iv : ivs) {
            double a = iv.a;
            double b = iv.b;
            if (a < lo) {
                a = lo;
            }
            if (b > hi) {
                b = hi;
            }
            if (b - a + LEN_TOL < p) {
                continue;
            }
            if (block == null) {
                n += PyFloat.floorDivInt(b - a + LEN_TOL, p);
            } else {
                double bl = block.a;
                double bh = block.b;
                double e = b < bl ? b : bl;
                if (e - a + LEN_TOL >= p) {
                    n += PyFloat.floorDivInt(e - a + LEN_TOL, p);
                }
                double s = a > bh ? a : bh;
                if (b - s + LEN_TOL >= p) {
                    n += PyFloat.floorDivInt(b - s + LEN_TOL, p);
                }
            }
        }
        return n;
    }

    /** 구획 접근변 시작 u 좌표. 연결차로 왼쪽 조각은 오른쪽 정렬, 오른쪽 조각은 왼쪽 정렬, 그 외 가운데. */
    private static List<Double> positions(List<Iv> ivs, double lo, double hi, double p, Iv block) {
        List<double[]> pieces = new ArrayList<>(); // (a, b, side)
        for (Iv iv : ivs) {
            double a = PyFloat.max(iv.a, lo);
            double b = PyFloat.min(iv.b, hi);
            if (b - a + LEN_TOL < p) {
                continue;
            }
            if (block == null) {
                pieces.add(new double[] {a, b, 0});
            } else {
                double bl = block.a;
                double bh = block.b;
                double e = PyFloat.min(b, bl);
                if (e - a + LEN_TOL >= p) {
                    pieces.add(new double[] {a, e, -1});
                }
                double s = PyFloat.max(a, bh);
                if (b - s + LEN_TOL >= p) {
                    pieces.add(new double[] {s, b, 1});
                }
            }
        }
        List<Double> us = new ArrayList<>();
        for (double[] pc : pieces) {
            double a = pc[0];
            double b = pc[1];
            double side = pc[2];
            int n = PyFloat.floorDivInt(b - a + LEN_TOL, p);
            double slack = PyFloat.max(0.0, (b - a) - n * p);
            double start = side < 0 ? a + slack : (side > 0 ? a : a + slack * 0.5);
            for (int i = 0; i < n; i++) {
                us.add(start + i * p);
            }
        }
        return us;
    }

    private static int upperBound(List<Mod> mods, double p) {
        int total = 0;
        for (Mod m : mods) {
            for (Iv iv : m.aisleIv) {
                total += count(m.lowIv, iv.a, iv.b, p, null) + count(m.upIv, iv.a, iv.b, p, null);
            }
        }
        return total;
    }

    private static Iv containing(List<Iv> ivs, double a, double b) {
        for (Iv iv : ivs) {
            if (iv.a <= a + Y_TOL && iv.b >= b - Y_TOL) {
                return iv;
            }
        }
        return null;
    }

    private static List<Double> spineCandidates(Frame fr, Spec spec, List<Mod> mods, double[] extra) {
        double as = spec.aisleW;
        List<Double> cands = new ArrayList<>();
        for (Mod m : mods) {
            for (Iv iv : m.aisleIv) {
                if (iv.b - iv.a >= as - LEN_TOL) {
                    cands.add(iv.a);
                    cands.add(iv.b - as);
                }
            }
        }
        double lo = fr.xmin;
        double hi = fr.xmax - as;
        if (hi > lo) {
            // np.linspace(lo, hi, 8): i * step + lo, 끝값은 hi
            double step = (hi - lo) / (SPINE_GRID - 1);
            for (int i = 0; i < SPINE_GRID; i++) {
                cands.add(i == SPINE_GRID - 1 ? hi : i * step + lo);
            }
        }
        for (double x : extra) {
            cands.add(x);
        }
        return sortedUnique(cands);
    }

    private static boolean better(Res best, int c, int n) {
        return best == null || c > best.count || (c == best.count && n < best.nAisles);
    }

    /** 연결차로까지 고려한 최적 배치 선택. (구획 수, 차로 수, 선택) 또는 null. */
    private static Res fullEval(Frame fr, Spec spec, List<Mod> mods, double[] extraS) {
        double p = spec.pitch;
        double a = spec.aisleW;
        double d = spec.depth;
        double k = spec.shear;
        Res best = null;
        for (int mi = 0; mi < mods.size(); mi++) {
            Mod m = mods.get(mi);
            for (Iv iv : m.aisleIv) {
                int c = count(m.lowIv, iv.a, iv.b, p, null) + count(m.upIv, iv.a, iv.b, p, null);
                if (c > 0 && better(best, c, 1)) {
                    best = new Res(c, 1, new Choice(false, mi, iv, 0, List.of()));
                }
            }
        }
        if (mods.size() < 2) {
            return best;
        }
        List<Double> cands = spineCandidates(fr, spec, mods, extraS);
        double[] s0 = new double[cands.size()];
        double[] s1 = new double[cands.size()];
        for (int i = 0; i < s0.length; i++) {
            s0[i] = cands.get(i);
            s1[i] = s0[i] + a;
        }
        List<List<Iv>> jAll = fitIntervals(fr.e0t, s0, s1);
        for (int ci0 = 0; ci0 < cands.size(); ci0++) {
            double s = cands.get(ci0);
            Iv block = new Iv(s - d * k, s + a);
            for (Iv jy : jAll.get(ci0)) {
                double jy0 = jy.a;
                double jy1 = jy.b;
                if (jy1 - jy0 < 2 * a) {
                    continue;
                }
                List<Conn> conn = new ArrayList<>();
                for (int mi = 0; mi < mods.size(); mi++) {
                    Mod m = mods.get(mi);
                    if (m.ya >= jy0 - Y_TOL && m.ya + a <= jy1 + Y_TOL) {
                        Iv iv = containing(m.aisleIv, s, s + a);
                        if (iv != null) {
                            conn.add(new Conn(mi, iv));
                        }
                    }
                }
                if (conn.size() < 2) {
                    continue;
                }
                int last = conn.size() - 1;
                int c = 0;
                for (int ci = 0; ci < conn.size(); ci++) {
                    Conn cn = conn.get(ci);
                    Mod m = mods.get(cn.mi);
                    c += count(m.lowIv, cn.iv.a, cn.iv.b, p, ci == 0 ? null : block);
                    c += count(m.upIv, cn.iv.a, cn.iv.b, p, ci == last ? null : block);
                }
                if (c > 0 && better(best, c, conn.size())) {
                    best = new Res(c, conn.size(), new Choice(true, -1, null, s, List.copyOf(conn)));
                }
            }
        }
        return best;
    }

    // =====================================================================
    // 각도별 탐색
    // =====================================================================

    private static boolean betterPlan(int count, int nAisles, Plan plan) {
        return plan == null || count > plan.count || (count == plan.count && nAisles < plan.nAisles);
    }

    private static final Comparator<Plan> PLAN_ORDER = (x, y) -> {
        int c = Integer.compare(-x.count, -y.count);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(x.nAisles, y.nAisles);
        return c != 0 ? c : PyFloat.compare(x.phase, y.phase);
    };

    /** 상한(UB)이 높은 phase 부터 정밀 평가. UB 는 실제 구획 수의 상한이므로 floor 미만이면 건너뛴다. */
    private static List<Plan> evalPhases(Frame fr, Spec spec, List<Double> phases, List<List<Mod>> modsList, int floor,
                                         int keep) {
        int np = phases.size();
        int[] ubs = new int[np];
        for (int i = 0; i < np; i++) {
            ubs[i] = upperBound(modsList.get(i), spec.pitch);
        }
        Integer[] order = new Integer[np];
        for (int i = 0; i < np; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> {
            int c = Integer.compare(-ubs[x], -ubs[y]);
            return c != 0 ? c : PyFloat.compare(phases.get(x), phases.get(y));
        });
        List<Plan> found = new ArrayList<>();
        for (int i : order) {
            if (ubs[i] <= 0 || ubs[i] < floor) {
                break;
            }
            Res res = fullEval(fr, spec, modsList.get(i), new double[0]);
            if (res == null) {
                continue;
            }
            found.add(new Plan(res.count, res.nAisles, fr.idx, phases.get(i), modsList.get(i), res.choice));
            found.sort(PLAN_ORDER);
            while (found.size() > keep) {
                found.remove(found.size() - 1);
            }
            if (found.size() >= keep) {
                floor = Math.max(floor, found.get(found.size() - 1).count);
            }
        }
        return found;
    }

    private record Coarse(int negMx, int fidx, List<Double> phases, List<List<Mod>> modsList) {
    }

    /** 한 주차형식에 대해 방향별 최적 배치 (구획 수 내림차순). */
    private static List<Plan> searchAngle(List<Frame> frames, Spec spec) {
        double p = spec.period();
        List<Coarse> coarse = new ArrayList<>();
        for (Frame fr : frames) {
            List<Double> phases = coarsePhases(fr, spec);
            List<List<Mod>> modsList = modulesForPhases(fr, spec, phases);
            int mx = 0;
            for (List<Mod> m : modsList) {
                mx = Math.max(mx, upperBound(m, spec.pitch));
            }
            if (mx > 0) {
                coarse.add(new Coarse(-mx, fr.idx, phases, modsList));
            }
        }
        coarse.sort(Comparator.comparingInt(Coarse::negMx).thenComparingInt(Coarse::fidx));
        Map<Integer, Plan> best = new LinkedHashMap<>();
        for (Coarse co : coarse.subList(0, Math.min(TOP_ORIENTATIONS, coarse.size()))) {
            Frame fr = frames.get(co.fidx);
            List<Plan> top = evalPhases(fr, spec, co.phases, co.modsList, 0, REFINE_SEEDS);
            if (top.isEmpty()) {
                continue;
            }
            Plan plan = top.get(0);
            for (Plan seed : top) { // 상위 phase 주변 미세 탐색
                List<Double> refRaw = new ArrayList<>();
                for (double dd : REFINE_PHASE_OFFSETS) {
                    refRaw.add(PyFloat.mod(Py.round(PyFloat.mod(seed.phase + dd, p), 9), p));
                }
                List<Double> ref = sortedUnique(refRaw);
                for (Plan cand : evalPhases(fr, spec, ref, modulesForPhases(fr, spec, ref), plan.count, 1)) {
                    if (betterPlan(cand.count, cand.nAisles, plan)) {
                        plan = cand;
                    }
                }
            }
            if (plan.choice.spine) { // 연결차로 위치 미세 조정
                double s0 = plan.choice.s;
                double[] extra = new double[SPINE_REFINE.length];
                for (int i = 0; i < extra.length; i++) {
                    extra[i] = s0 + SPINE_REFINE[i];
                }
                Res res = fullEval(fr, spec, plan.mods, extra);
                if (res != null && betterPlan(res.count, res.nAisles, plan)) {
                    plan = new Plan(res.count, res.nAisles, co.fidx, plan.phase, plan.mods, res.choice);
                }
            }
            best.put(co.fidx, plan);
        }
        List<Plan> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingInt((Plan pl) -> -pl.count).thenComparingInt(pl -> pl.nAisles)
                .thenComparingInt(pl -> pl.fidx));
        return out;
    }

    // =====================================================================
    // 기하 생성 (지역 회전 좌표계)
    // =====================================================================

    /** 구획 좌표(각 (5,2)), 접근변 좌표(각 (2,2)), 차로 사각형. 배치가 비면 null. */
    private static Realized realize(Spec spec, Plan plan) {
        double p = spec.pitch;
        double a = spec.aisleW;
        double d = spec.depth;
        double k = spec.shear;
        List<Mod> mods = plan.mods;
        List<Conn> conn;
        Double s;
        if (!plan.choice.spine) {
            conn = List.of(new Conn(plan.choice.mi, plan.choice.iv));
            s = null;
        } else {
            s = plan.choice.s;
            conn = plan.choice.conn;
        }
        Iv block = s != null ? new Iv(s - d * k, s + a) : null;
        int last = conn.size() - 1;
        record Per(Mod m, Iv iv, List<Double> loUs, List<Double> upUs) {
        }
        List<Per> per = new ArrayList<>();
        for (int ci = 0; ci < conn.size(); ci++) {
            Conn cn = conn.get(ci);
            Mod m = mods.get(cn.mi);
            List<Double> loUs = positions(m.lowIv, cn.iv.a, cn.iv.b, p, (block == null || ci == 0) ? null : block);
            List<Double> upUs = positions(m.upIv, cn.iv.a, cn.iv.b, p, (block == null || ci == last) ? null : block);
            if (!loUs.isEmpty() || !upUs.isEmpty()) {
                per.add(new Per(m, cn.iv, loUs, upUs));
            }
        }
        if (per.isEmpty()) {
            return null;
        }
        boolean useSpine = s != null && per.size() >= 2;
        List<double[][]> stalls = new ArrayList<>();
        List<double[][]> edges = new ArrayList<>();
        List<Rect> rects = new ArrayList<>();
        double dk = d * k;
        for (Per pr : per) {
            double ya = pr.m.ya;
            double yt = ya + a;
            for (double u : pr.loUs) {
                stalls.add(new double[][] {{u, ya}, {u + p, ya}, {u + p + dk, ya - d}, {u + dk, ya - d}, {u, ya}});
                edges.add(new double[][] {{u, ya}, {u + p, ya}});
            }
            for (double u : pr.upUs) {
                stalls.add(new double[][] {{u, yt}, {u + p, yt}, {u + p + dk, yt + d}, {u + dk, yt + d}, {u, yt}});
                edges.add(new double[][] {{u, yt}, {u + p, yt}});
            }
            List<Double> us = new ArrayList<>(pr.loUs);
            us.addAll(pr.upUs);
            double x0 = us.get(0);
            double mx = us.get(0);
            for (double u : us) {
                x0 = PyFloat.min(x0, u);
                mx = PyFloat.max(mx, u);
            }
            double x1 = mx + p;
            if (useSpine) {
                double nx0 = PyFloat.min(x0, s);
                double nx1 = PyFloat.max(x1, s + a);
                x0 = nx0;
                x1 = nx1;
            }
            double fx0 = PyFloat.max(x0, pr.iv.a);
            double fx1 = PyFloat.min(x1, pr.iv.b);
            rects.add(new Rect("aisle", fx0, ya, fx1, yt));
        }
        if (useSpine) {
            rects.add(new Rect("spine", s, per.get(0).m.ya, s + a, per.get(per.size() - 1).m.ya + a));
        }
        return new Realized(stalls, edges, rects);
    }

    private static Polygon rect(double x0, double y0, double x1, double y1) {
        return Shapely.polygon(new double[][] {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}});
    }

    private static Polygon polygonOf(double[][] xy) {
        Coordinate[] c = new Coordinate[xy.length];
        for (int i = 0; i < xy.length; i++) {
            c[i] = new Coordinate(xy[i][0], xy[i][1]);
        }
        return GF.createPolygon(c);
    }

    private static LineString lineOf(double[]... xy) {
        Coordinate[] c = new Coordinate[xy.length];
        for (int i = 0; i < xy.length; i++) {
            c[i] = new Coordinate(xy[i][0], xy[i][1]);
        }
        return GF.createLineString(c);
    }

    // =====================================================================
    // 출입구
    // =====================================================================

    private record GateFrame(double[] g, double[] n, double bulge, double outward, double d0, double d1) {
    }

    /** 점수 (제거 구획 수, 진입로 면적(소수 4자리), 후보 순서). 작을수록 좋다. */
    private record Score(int removed, double area, int order) implements Comparable<Score> {
        @Override
        public int compareTo(Score o) {
            int c = Integer.compare(removed, o.removed);
            if (c != 0) {
                return c;
            }
            c = PyFloat.compare(area, o.area);
            return c != 0 ? c : Integer.compare(order, o.order);
        }
    }

    private record GateFit(double[] point, Geometry opening, Polygon connector, List<Integer> removed, Score score) {
    }

    private static final class GateBuilder {
        final Polygon site;
        final LineString ext;
        final double bigL;
        final double[][] ringXy;
        final LineString ring2;
        final double ring2Length;
        final double[] cum;
        final Polygon network;
        final PreparedGeometry netCover;
        final List<Polygon> stalls;
        final List<Envelope> stallEnvs;
        final Spec spec;
        final double w;
        final double minOpening;
        final double extLen;
        final double setback;
        final PreparedGeometry siteBuf;
        final double far;

        GateBuilder(Polygon siteL, Polygon network, List<Polygon> stallGeoms, Spec spec, double setback) {
            this.site = Shapely.orient(siteL);
            this.ext = GF.createLineString(site.getExteriorRing().getCoordinates());
            this.bigL = ext.getLength();
            double[][] xy = coordsOf(ext.getCoordinates());
            this.ringXy = xy;
            Coordinate[] r2 = new Coordinate[xy.length + xy.length - 1];
            for (int i = 0; i < xy.length; i++) {
                r2[i] = new Coordinate(xy[i][0], xy[i][1]);
            }
            for (int i = 1; i < xy.length; i++) {
                r2[xy.length + i - 1] = new Coordinate(xy[i][0], xy[i][1]);
            }
            this.ring2 = GF.createLineString(r2);
            this.ring2Length = ring2.getLength();
            this.cum = new double[xy.length];
            this.cum[0] = 0.0;
            for (int i = 1; i < xy.length; i++) {
                cum[i] = cum[i - 1] + StrictMath.hypot(xy[i][0] - xy[i - 1][0], xy[i][1] - xy[i - 1][1]);
            }
            this.network = network;
            this.netCover = PreparedGeometryFactory.prepare(Shapely.bufferMitre(network, 1e-6, 5.0));
            this.stalls = stallGeoms;
            this.stallEnvs = new ArrayList<>(stallGeoms.size());
            for (Polygon st : stallGeoms) {
                stallEnvs.add(st.getEnvelopeInternal());
            }
            this.spec = spec;
            this.w = spec.drivewayW;
            this.minOpening = spec.gateMin != null ? spec.gateMin : 0.95 * spec.drivewayW;
            this.extLen = spec.aisleW * 0.5;
            this.setback = setback;
            this.siteBuf = PreparedGeometryFactory.prepare(Shapely.buffer(site, 1e-6));
            Envelope env = site.getEnvelopeInternal();
            this.far = 2.0 * PyFloat.hypot(env.getMaxX() - env.getMinX(), env.getMaxY() - env.getMinY()) + 10.0;
        }

        private double[] interp(double d) {
            Coordinate c = Shapely.interpolate(ring2, d);
            return new double[] {c.x, c.y};
        }

        // -- 위치 ----------------------------------------------------------
        GateFrame frame(double d) {
            double wl = w;
            double ll = bigL;
            if (ring2Length < wl || ll < wl) {
                return null;
            }
            double dd = PyFloat.mod(d, ll);
            if (dd < wl / 2) {
                dd += ll;
            }
            double d0 = dd - wl / 2;
            double d1 = dd + wl / 2;
            double[] g = interp(dd);
            double[] p0 = interp(d0);
            double[] p1 = interp(d1);
            double tx = p1[0] - p0[0];
            double ty = p1[1] - p0[1];
            double nt = PyFloat.hypot(tx, ty);
            if (nt < 0.5 * wl) {
                return null;
            }
            tx = tx / nt;
            ty = ty / nt;
            double[] n = {-ty, tx}; // CCW 외곽 → 왼쪽이 내부
            Geometry span = Shapely.substring(ring2, d0, d1);
            double devMax = Double.NEGATIVE_INFINITY;
            double devMin = Double.POSITIVE_INFINITY;
            for (Coordinate c : span.getCoordinates()) {
                double dev = (c.x - g[0]) * n[0] + (c.y - g[1]) * n[1];
                devMax = Math.max(devMax, dev);
                devMin = Math.min(devMin, dev);
            }
            double bulge = PyFloat.max(0.0, devMax);
            double outward = PyFloat.max(0.0, -devMin);
            if (bulge > GATE_MAX_BULGE_M) {
                return null;
            }
            return new GateFrame(g, n, bulge, outward, d0, d1);
        }

        /** d 가 속한 변 안쪽으로 출입구 폭 전체가 들어가도록 옮긴 위치 (변이 짧으면 null). */
        Double edgeClamped(double d) {
            double dd = PyFloat.mod(d, bigL);
            // np.searchsorted(cum, dd, side="right") - 1
            int cntLe = 0;
            while (cntLe < cum.length && cum[cntLe] <= dd) {
                cntLe++;
            }
            int i = cntLe - 1;
            i = Math.min(Math.max(i, 0), cum.length - 2);
            double a = cum[i];
            double b = cum[i + 1];
            if (b - a < w) {
                return null;
            }
            return PyFloat.min(PyFloat.max(dd, a + w / 2), b - w / 2);
        }

        // -- 진입로 --------------------------------------------------------
        GateFit make(GateFrame fr, List<double[]> pathTail, int order) {
            double[] g = fr.g;
            double[] n = fr.n;
            double[] start = {g[0] - n[0] * (fr.outward + GATE_OVERSHOOT_M), g[1] - n[1] * (fr.outward + GATE_OVERSHOOT_M)};
            double[] coreStart = {g[0] + n[0] * (fr.bulge + 1e-3), g[1] + n[1] * (fr.bulge + 1e-3)};
            List<double[]> gpts = new ArrayList<>();
            gpts.add(start);
            gpts.addAll(pathTail);
            List<double[]> cpts = new ArrayList<>();
            cpts.add(coreStart);
            cpts.addAll(pathTail);
            Geometry geom = Shapely.buffer(lineOf(gpts.toArray(new double[0][])), w / 2, BufferParameters.CAP_FLAT,
                    BufferParameters.JOIN_MITRE, 2.0);
            Geometry core = Shapely.buffer(lineOf(cpts.toArray(new double[0][])), w / 2, BufferParameters.CAP_FLAT,
                    BufferParameters.JOIN_MITRE, 2.0);
            if (core.isEmpty() || !siteBuf.contains(core)) {
                return null;
            }
            Geometry clipped = Shapely.intersection(geom, site);
            List<Polygon> parts = new ArrayList<>();
            for (Polygon pg : Geo.polygonParts(clipped)) {
                if (pg.getArea() > 1e-6) {
                    parts.add(pg);
                }
            }
            if (parts.size() != 1) {
                return null;
            }
            Polygon c = parts.get(0);
            if (Shapely.intersection(c, network).getArea() <= 1e-6) {
                return null;
            }
            if (!"Polygon".equals(Shapely.union(network, c).getGeometryType())) {
                return null;
            }
            Geometry opening = opening(c, g, fr.d0, fr.d1);
            if (opening == null || opening.getLength() < minOpening - 1e-6) {
                return null;
            }
            List<Integer> removed = new ArrayList<>();
            Envelope cEnv = c.getEnvelopeInternal();
            PreparedGeometry cPrep = null;
            for (int i = 0; i < stalls.size(); i++) {
                if (!stallEnvs.get(i).intersects(cEnv)) {
                    continue;
                }
                if (cPrep == null) {
                    cPrep = PreparedGeometryFactory.prepare(c);
                }
                if (!cPrep.intersects(stalls.get(i))) {
                    continue;
                }
                if (Shapely.intersection(stalls.get(i), c).getArea() > AREA_TOL) {
                    removed.add(i);
                }
            }
            return new GateFit(g, opening, c, removed, new Score(removed.size(), Py.round(c.getArea(), 4), order));
        }

        /** 출입구 개구부 = 출입구 중심 ±w/2 의 대지 경계선 중 진입로에 닿는 부분. */
        Geometry opening(Polygon c, double[] g, double d0, double d1) {
            Geometry inter = Shapely.intersection(Shapely.substring(ring2, d0, d1), Shapely.bufferMitre(c, 1e-6, 5.0));
            List<Geometry> parts = new ArrayList<>();
            if (inter instanceof LineString || inter instanceof Point || inter instanceof Polygon) {
                parts.add(inter); // geoms 속성 없음 → [inter]
            } else {
                for (int i = 0; i < inter.getNumGeometries(); i++) {
                    parts.add(inter.getGeometryN(i));
                }
            }
            List<Geometry> lines = new ArrayList<>();
            for (Geometry pg : parts) {
                String t = pg.getGeometryType();
                if ("LineString".equals(t) || "MultiLineString".equals(t)) {
                    lines.add(pg);
                }
            }
            if (lines.isEmpty()) {
                return null;
            }
            Geometry merged;
            if (lines.size() > 1 || !"LineString".equals(lines.get(0).getGeometryType())) {
                merged = linemerge(Shapely.unionAll(lines));
            } else {
                merged = lines.get(0);
            }
            List<Geometry> cand = new ArrayList<>();
            if (merged instanceof LineString || merged instanceof Point || merged instanceof Polygon) {
                cand.add(merged);
            } else {
                for (int i = 0; i < merged.getNumGeometries(); i++) {
                    cand.add(merged.getGeometryN(i));
                }
            }
            Point gp = GF.createPoint(new Coordinate(g[0], g[1]));
            Geometry best = null;
            for (Geometry cg : cand) {
                if (cg.isEmpty()) {
                    continue; // shapely: 빈 기하와의 거리는 nan
                }
                if (cg.distance(gp) < 1e-6 && (best == null || cg.getLength() > best.getLength())) {
                    best = cg;
                }
            }
            return best;
        }

        List<GateFit> options(double d, int order) {
            GateFrame fr = frame(d);
            if (fr == null) {
                return List.of();
            }
            double[] g = fr.g;
            double[] n = fr.n;
            List<GateFit> out = new ArrayList<>();
            // (1) 경계에 수직으로 곧장 차로망까지
            LineString ray = lineOf(g, new double[] {g[0] + n[0] * far, g[1] + n[1] * far});
            Geometry hit = Shapely.intersection(ray, network);
            if (!hit.isEmpty()) {
                double mn = Double.POSITIVE_INFINITY;
                for (Coordinate c : hit.getCoordinates()) {
                    mn = Math.min(mn, (c.x - g[0]) * n[0] + (c.y - g[1]) * n[1]);
                }
                double h = PyFloat.max(0.0, mn);
                Double t = fullDepth(g, n, h);
                if (t != null) {
                    double tt = t + extLen;
                    GateFit f = make(fr, List.<double[]>of(new double[] {g[0] + n[0] * tt, g[1] + n[1] * tt}), order);
                    if (f != null) {
                        out.add(f);
                    }
                }
            }
            // (2) 경계에서 수직으로 들어온 뒤 가장 가까운 차로망 점으로 꺾기
            double r1 = PyFloat.max(fr.bulge + 1e-3, setback) + w / 2;
            double[] p1 = {g[0] + n[0] * r1, g[1] + n[1] * r1};
            Point p1pt = GF.createPoint(new Coordinate(p1[0], p1[1]));
            if (network.distance(p1pt) > 1e-6) {
                Coordinate qc = Shapely.nearestPoints(p1pt, network)[1];
                double[] q = {qc.x, qc.y};
                double vx = q[0] - p1[0];
                double vy = q[1] - p1[1];
                double dist = PyFloat.hypot(vx, vy);
                if (dist > 1e-6) {
                    double[] u = {vx / dist, vy / dist};
                    Double t = (u[0] * n[0] + u[1] * n[1]) > -0.2 ? fullDepth(q, u, 0.0) : null;
                    if (t != null) {
                        double tt = t + extLen;
                        GateFit f = make(fr, List.of(p1, new double[] {q[0] + u[0] * tt, q[1] + u[1] * tt}), order + 1);
                        if (f != null) {
                            out.add(f);
                        }
                    }
                }
            }
            return out;
        }

        /**
         * origin + dirv·t 에서 진행 방향에 수직인 차로 폭 단면이 차로망 안에 완전히 드는 최소 t.
         * 진입로가 차로망 모서리에 살짝만 걸쳐 통로가 차로 폭보다 좁아지는 것을 막는다.
         */
        Double fullDepth(double[] origin, double[] dirv, double t0) {
            double half = spec.aisleW / 2;
            double[] perp = {-dirv[1] * half, dirv[0] * half};
            double t = t0;
            double tMax = t0 + 2.0 * spec.aisleW + w;
            while (t <= tMax) {
                double[] c = {origin[0] + dirv[0] * t, origin[1] + dirv[1] * t};
                if (netCover.covers(lineOf(new double[] {c[0] - perp[0], c[1] - perp[1]},
                        new double[] {c[0] + perp[0], c[1] + perp[1]}))) {
                    return t;
                }
                t += GATE_DEPTH_STEP_M;
            }
            return null;
        }

        private static GateFit minScore(List<GateFit> opts) {
            GateFit best = null;
            for (GateFit f : opts) {
                if (best == null || f.score.compareTo(best.score) < 0) {
                    best = f;
                }
            }
            return best;
        }

        // -- 진입점 --------------------------------------------------------
        GateFit forTarget(double[] target) {
            Coordinate tc = new Coordinate(target[0], target[1]);
            double d0 = Shapely.project(ext, tc);
            List<Double> positions = new ArrayList<>();
            positions.add(d0);
            Double dc = edgeClamped(d0);
            if (dc != null) {
                positions.add(dc);
            }
            int steps = (int) Math.ceil(2 * w / GATE_SEARCH_STEP_M);
            for (int j = 1; j <= steps; j++) {
                positions.add(d0 + j * GATE_SEARCH_STEP_M);
                positions.add(d0 - j * GATE_SEARCH_STEP_M);
            }
            for (int oi = 0; oi < positions.size(); oi++) {
                List<GateFit> opts = options(positions.get(oi), 2 * oi);
                if (!opts.isEmpty()) {
                    return minScore(opts);
                }
            }
            // 근처에서 못 찾으면: 가장 가까운 '출입구 폭 이상의 변' 위로 이동
            double[] best = null;
            for (int i = 0; i < cum.length - 1; i++) {
                double a = cum[i];
                double b = cum[i + 1];
                if (b - a < w) {
                    continue;
                }
                LineString seg = lineOf(ringXy[i], ringXy[i + 1]);
                double proj = Shapely.project(seg, tc);
                double d = PyFloat.min(PyFloat.max(a + proj, a + w / 2), b - w / 2);
                Coordinate ip = Shapely.interpolate(ring2, d);
                double dist = GeosMath.distance(ip, tc);
                if (best == null || dist < best[0]) {
                    best = new double[] {dist, d};
                }
            }
            if (best != null) {
                List<GateFit> opts = options(best[1], 10_000);
                if (!opts.isEmpty()) {
                    return minScore(opts);
                }
            }
            return null;
        }

        GateFit auto(List<Rect> rects) {
            List<Double> raw = new ArrayList<>();
            Coordinate pe = Shapely.nearestPoints(ext, network)[0];
            raw.add(Shapely.project(ext, pe));
            for (Rect r : rects) {
                double[][][] rays;
                if ("aisle".equals(r.kind)) {
                    double ym = (r.y0 + r.y1) / 2;
                    rays = new double[][][] {{{r.x0, ym}, {-1.0, 0.0}}, {{r.x1, ym}, {1.0, 0.0}}};
                } else {
                    double xm = (r.x0 + r.x1) / 2;
                    rays = new double[][][] {{{xm, r.y0}, {0.0, -1.0}}, {{xm, r.y1}, {0.0, 1.0}}};
                }
                for (double[][] ray : rays) {
                    double[] o = ray[0];
                    double[] dv = ray[1];
                    LineString seg = lineOf(o, new double[] {o[0] + dv[0] * far, o[1] + dv[1] * far});
                    Geometry inter = Shapely.intersection(seg, ext);
                    Coordinate[] pts = inter.getCoordinates();
                    if (pts.length > 0) {
                        int bi = 0;
                        double bd = Double.POSITIVE_INFINITY;
                        for (int i = 0; i < pts.length; i++) {
                            double ddx = pts[i].x - o[0];
                            double ddy = pts[i].y - o[1];
                            double dd = ddx * ddx + ddy * ddy;
                            if (dd < bd) {
                                bd = dd;
                                bi = i;
                            }
                        }
                        raw.add(Shapely.project(ext, pts[bi]));
                    }
                }
            }
            for (int i = 0; i < cum.length - 1; i++) {
                double a = cum[i];
                double b = cum[i + 1];
                if (b - a < w) {
                    continue;
                }
                LineString seg = lineOf(ringXy[i], ringXy[i + 1]);
                Coordinate q = Shapely.nearestPoints(seg, network)[0];
                raw.add(a + Shapely.project(seg, q));
                raw.add((a + b) / 2);
            }
            List<Double> positions = new ArrayList<>();
            Set<Double> seen = new HashSet<>();
            for (double d : raw) {
                Double[] pair = {d, edgeClamped(d)};
                for (Double dd : pair) {
                    if (dd == null) {
                        continue;
                    }
                    double key = Py.round(PyFloat.mod(dd, bigL), 3);
                    if (key == 0.0) {
                        key = 0.0; // -0.0 과 0.0 은 같은 키
                    }
                    if (seen.contains(key)) {
                        continue;
                    }
                    seen.add(key);
                    positions.add(dd);
                }
            }
            GateFit best = null;
            for (int oi = 0; oi < positions.size(); oi++) {
                for (GateFit f : options(positions.get(oi), 2 * oi)) {
                    if (best == null || f.score.compareTo(best.score) < 0) {
                        best = f;
                    }
                }
            }
            return best;
        }
    }

    /** shapely {@code ops.linemerge(lines)}: MultiLineString·다중 기하만 받는다 (단일 LineString 이면 ValueError). */
    private static Geometry linemerge(Geometry lines) {
        Geometry source;
        if (lines instanceof MultiLineString) {
            source = lines;
        } else if (lines.getNumGeometries() > 1 || lines instanceof org.locationtech.jts.geom.GeometryCollection) {
            List<LineString> ls = new ArrayList<>();
            for (int i = 0; i < lines.getNumGeometries(); i++) {
                Geometry g = lines.getGeometryN(i);
                if (!(g instanceof LineString l)) {
                    throw new IllegalArgumentException("Cannot linemerge " + lines.getGeometryType());
                }
                ls.add(l);
            }
            source = GF.createMultiLineString(ls.toArray(new LineString[0]));
        } else {
            throw new IllegalArgumentException("Cannot linemerge " + lines);
        }
        return Shapely.lineMerge(source);
    }

    // =====================================================================
    // 후보 → 최종 기하
    // =====================================================================

    private static Shapely.CoordFn localToAbsFn(Frame fr, double[] origin) {
        double c = fr.cos;
        double s = fr.sin;
        double ox = origin[0];
        double oy = origin[1];
        return (x, y, out) -> {
            out[0] = ox + x * c - y * s;
            out[1] = oy + x * s + y * c;
        };
    }

    private static double[] absToLocal(Frame fr, double[] origin, double[] xy) {
        double[][] r = rotNeg(new double[][] {{xy[0] - origin[0], xy[1] - origin[1]}}, fr.cos, fr.sin);
        return r[0];
    }

    /** 배치안 → 출입구 연결 → EPSG:5179 기하. 실패하면 사유 문자열("EMPTY"|"NETWORK"|"GATE"). */
    private static Object buildCandidate(Spec spec, Frame fr, Plan plan, double[] origin, Polygon site,
                                         PreparedGeometry usablePrep, double setback, double[] gateTarget) {
        Realized real = realize(spec, plan);
        if (real == null) {
            return "EMPTY";
        }
        List<Polygon> stallsL = new ArrayList<>(real.stalls.size());
        for (double[][] st : real.stalls) {
            stallsL.add(polygonOf(st));
        }
        List<Polygon> rectPolys = new ArrayList<>(real.rects.size());
        for (Rect r : real.rects) {
            rectPolys.add(rect(r.x0, r.y0, r.x1, r.y1));
        }
        Geometry networkL = Shapely.unionAll(rectPolys);
        if (!"Polygon".equals(networkL.getGeometryType())) {
            return "NETWORK";
        }
        double c = fr.cos;
        double s = fr.sin;
        double ox = origin[0];
        double oy = origin[1];
        Polygon siteL = Shapely.affineTransform(site, c, s, -s, c, -(c * ox + s * oy), s * ox - c * oy);
        GateBuilder gb = new GateBuilder(siteL, (Polygon) networkL, stallsL, spec, setback);
        List<String> reasons = new ArrayList<>();
        GateFit gfit = gateTarget != null ? gb.forTarget(absToLocal(fr, origin, gateTarget)) : null;
        if (gfit == null) {
            if (gateTarget != null) {
                // 지정 출입구에서 차로망까지 진입로를 낼 수 없음 → 자동 출입구로 대체하고 표시
                reasons.add(GATE_UNREACHABLE);
            }
            gfit = gb.auto(real.rects);
        }
        if (gfit == null) {
            return "GATE";
        }
        Set<Integer> removed = new HashSet<>(gfit.removed);
        Shapely.CoordFn toAbs = localToAbsFn(fr, origin);
        List<Polygon> stallsAbs = new ArrayList<>();
        List<LineString> edgesAbs = new ArrayList<>();
        for (int i = 0; i < stallsL.size(); i++) {
            if (removed.contains(i)) {
                continue;
            }
            stallsAbs.add(Shapely.transform(stallsL.get(i), toAbs));
            edgesAbs.add(Shapely.transform(lineOf(real.edges.get(i)), toAbs));
        }
        List<LayoutGeometry.Aisle> aislesAbs = new ArrayList<>();
        for (int i = 0; i < real.rects.size(); i++) {
            aislesAbs.add(new LayoutGeometry.Aisle(real.rects.get(i).kind, Shapely.transform(rectPolys.get(i), toAbs),
                    spec.aisleW));
        }
        aislesAbs.add(new LayoutGeometry.Aisle("driveway", Shapely.transform(gfit.connector, toAbs), spec.drivewayW));
        // 안전망: 사용영역 밖 구획 제거 (정상이면 없음)
        List<Polygon> keptStalls = new ArrayList<>(stallsAbs.size());
        List<LineString> keptEdges = new ArrayList<>(edgesAbs.size());
        for (int i = 0; i < stallsAbs.size(); i++) {
            if (usablePrep.contains(stallsAbs.get(i))) {
                keptStalls.add(stallsAbs.get(i));
                keptEdges.add(edgesAbs.get(i));
            }
        }
        if (keptStalls.isEmpty()) {
            return "EMPTY";
        }
        List<Polygon> aislePolys = new ArrayList<>();
        for (LayoutGeometry.Aisle a : aislesAbs) {
            aislePolys.add(a.polygon());
        }
        Geometry network = Shapely.unionAll(aislePolys);
        if (!"Polygon".equals(network.getGeometryType())) {
            return "NETWORK";
        }
        Point gatePt = Shapely.transform(GF.createPoint(new Coordinate(gfit.point[0], gfit.point[1])), toAbs);
        Geometry opening = Shapely.transform(gfit.opening, toAbs);
        if (gateTarget != null
                && GeosMath.distance(gatePt.getX(), gatePt.getY(), gateTarget[0], gateTarget[1]) > GATE_SNAP_WARN_M) {
            reasons.add(GATE_SNAPPED);
        }
        return new Candidate(spec, fr, plan, keptStalls, keptEdges, aislesAbs, (Polygon) network, gatePt, opening,
                reasons);
    }

    private static final Comparator<Candidate> CAND_KEY = (x, y) -> {
        int c = Integer.compare(-x.count(), -y.count());
        if (c != 0) {
            return c;
        }
        c = PyFloat.compare(Py.round(x.aisleArea(), 6), Py.round(y.aisleArea(), 6));
        if (c != 0) {
            return c;
        }
        c = Integer.compare(ANGLE_ORDER.indexOf(x.spec.angle), ANGLE_ORDER.indexOf(y.spec.angle));
        return c != 0 ? c : Integer.compare(x.fr.idx, y.fr.idx);
    };

    // =====================================================================
    // 입력 준비
    // =====================================================================

    private record Prepared(double total, Polygon site) {
    }

    private static Prepared prepareSite(JsonNode siteGeojson, List<String> reasons) {
        Geometry g;
        try {
            g = Geo.geojsonToShape(siteGeojson);
        } catch (GeoJSONError e) {
            return null;
        }
        if (g.isEmpty() || !Geo.lnglatBoundsOk(g) || g.getNumPoints() > MAX_SITE_VERTICES) {
            return null;
        }
        Geometry gAbs;
        try {
            gAbs = Geo.toUtmk(g);
        } catch (RuntimeException e) {
            return null;
        }
        if (!Geo.allFinite(gAbs)) {
            return null;
        }
        if (!new IsValidOp(gAbs).isValid()) {
            List<Polygon> parts = new ArrayList<>();
            for (Polygon p : Geo.polygonParts(Shapely.makeValid(gAbs))) {
                if (p.getArea() > 1e-6) {
                    parts.add(p);
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            reasons.add(SITE_GEOMETRY_REPAIRED);
            gAbs = parts.size() == 1 ? parts.get(0) : GF.createMultiPolygon(parts.toArray(new Polygon[0]));
        }
        List<Polygon> parts = new ArrayList<>();
        for (Polygon p : Geo.polygonParts(gAbs)) {
            if (p.getArea() > 1e-6) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        List<Double> areas = new ArrayList<>(parts.size());
        for (Polygon p : parts) {
            areas.add(p.getArea());
        }
        double total = PyFloat.sum(areas);
        Polygon site = parts.get(0);
        for (Polygon p : parts.subList(1, parts.size())) {
            if (p.getArea() > site.getArea()) {
                site = p;
            }
        }
        if (parts.size() > 1) {
            reasons.add(MULTIPART_SITE_LARGEST_USED);
        }
        return new Prepared(total, Shapely.orient(site));
    }

    private static Polygon usableArea(Polygon site, double setback, List<String> reasons) {
        Geometry u = setback > 0 ? Shapely.bufferMitre(site, -setback, 10.0) : site;
        List<Polygon> parts = new ArrayList<>();
        for (Polygon p : Geo.polygonParts(u)) {
            if (p.getArea() > 1e-6) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() > 1) {
            reasons.add(USABLE_AREA_SPLIT_LARGEST_USED);
        }
        Polygon best = parts.get(0);
        for (Polygon p : parts.subList(1, parts.size())) {
            if (p.getArea() > best.getArea()) {
                best = p;
            }
        }
        best = (Polygon) Shapely.removeRepeatedPoints(best, 1e-9);
        return Shapely.orient(best);
    }

    private static double[] gateTarget(SimulationParams params, List<String> reasons) {
        List<Double> gp = params.gatePoint();
        if (gp == null) {
            return null;
        }
        if (gp.size() < 2 || gp.get(0) == null || gp.get(1) == null) {
            reasons.add(GATE_POINT_INVALID);
            return null;
        }
        double lng = gp.get(0);
        double lat = gp.get(1);
        if (!(Double.isFinite(lng) && Double.isFinite(lat) && -180 <= lng && lng <= 180 && -90 <= lat && lat <= 90)) {
            reasons.add(GATE_POINT_INVALID);
            return null;
        }
        double[] xy = Geo.lnglatToUtmk(lng, lat);
        if (!(Double.isFinite(xy[0]) && Double.isFinite(xy[1]))) {
            reasons.add(GATE_POINT_INVALID);
            return null;
        }
        return xy;
    }

    private static double inscribedWidth(Polygon poly) {
        try {
            LineString line = Shapely.maximumInscribedCircle(poly, 0.01);
            return 2.0 * line.getLength();
        } catch (RuntimeException e) {
            // 파이썬판의 구버전 shapely 대비 경로와 같은 이분 탐색
            Envelope b = poly.getEnvelopeInternal();
            double lo = 0.0;
            double hi = PyFloat.max(b.getMaxX() - b.getMinX(), b.getMaxY() - b.getMinY());
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) / 2;
                if (Shapely.buffer(poly, -mid).isEmpty()) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            return 2.0 * lo;
        }
    }

    private static void add(List<String> reasons, String code) {
        if (!reasons.contains(code)) {
            reasons.add(code);
        }
    }

    // =====================================================================
    // 출력 변환
    // =====================================================================

    /** 파이썬 {@code round(float(v) + 0.0, 2)}. */
    static double r2(double v) {
        return Py.round(v + 0.0, 2);
    }

    /** 방향(도)을 소수 2자리로, [0, 180) 범위 유지 (179.999 → 0.0). */
    static double ori(double phi) {
        double v = r2(phi);
        return v >= 180.0 ? 0.0 : v;
    }

    private static ObjectNode props() {
        return Json.object();
    }

    private static Alternative toAlternative(String code, Candidate cand, double siteArea, Polygon usable, String stallType,
                                             List<String> baseReasons) {
        Spec spec = cand.spec;
        List<ObjectNode> stallFeats = new ArrayList<>(cand.stalls.size());
        for (int i = 0; i < cand.stalls.size(); i++) {
            Polygon sp = Geo.toWgs84(cand.stalls.get(i));
            ObjectNode p = props();
            p.put("id", String.format("P-%03d", i + 1));
            p.put("stall_type", stallType);
            p.put("angle", spec.angle);
            p.put("width_m", r2(spec.stallW));
            p.put("length_m", r2(spec.stallL));
            stallFeats.add(Geo.feature(sp, p));
        }
        List<ObjectNode> aisleFeats = new ArrayList<>(cand.aisles.size());
        for (LayoutGeometry.Aisle a : cand.aisles) {
            ObjectNode p = props();
            p.put("kind", a.kind());
            p.put("width_m", r2(a.widthM()));
            aisleFeats.add(Geo.feature(Geo.toWgs84(a.polygon()), p));
        }
        double openingLen = cand.gateOpening.getLength();
        ObjectNode layers = Json.object();
        layers.set("stalls", Geo.featureCollection(stallFeats));
        layers.set("aisles", Geo.featureCollection(aisleFeats));
        ObjectNode gp = props();
        gp.put("width_m", r2(spec.drivewayW));
        layers.set("gate", Geo.feature(Geo.toWgs84(cand.gatePoint), gp));
        ObjectNode op = props();
        op.put("width_m", r2(openingLen));
        layers.set("gate_opening", Geo.feature(Geo.toWgs84(cand.gateOpening), op));
        ObjectNode up = props();
        up.put("area_m2", r2(usable.getArea()));
        layers.set("usable_area", Geo.feature(Geo.toWgs84(usable), up));

        double usableArea = usable.getArea();
        double stallArea = cand.stallArea();
        double aisleArea = cand.aisleArea();
        int n = cand.count();
        ObjectNode m = Json.object();
        m.put("site_area_m2", r2(siteArea));
        m.put("usable_area_m2", r2(usableArea));
        m.put("parking_area_m2", r2(stallArea + aisleArea));
        m.put("stall_area_m2", r2(stallArea));
        m.put("stall_footprint_m2", r2(cand.stallFootprint()));
        m.put("aisle_area_m2", r2(aisleArea));
        m.put("efficiency_percent", usableArea > 0 ? r2(stallArea / usableArea * 100.0) : 0.0);
        if (n != 0) {
            m.put("area_per_stall_m2", r2(usableArea / n));
        } else {
            m.putNull("area_per_stall_m2");
        }
        m.put("aisle_width_m", r2(spec.aisleW));
        m.put("stall_width_m", r2(spec.stallW));
        m.put("stall_length_m", r2(spec.stallL));
        m.put("orientation_deg", ori(cand.fr.phi));
        m.put("stall_count", n);
        m.put("gate_width_m", r2(spec.drivewayW));
        m.put("stall_row_depth_m", r2(spec.depth));
        m.put("stall_pitch_m", r2(spec.pitch));
        List<String> reasons = new ArrayList<>(baseReasons);
        for (String r : cand.reasons) {
            add(reasons, r);
        }
        return new Alternative(code, spec.angle, stallType, ori(cand.fr.phi), n, layers, m, reasons, false);
    }

    static String altCode(int i) {
        StringBuilder s = new StringBuilder();
        i += 1;
        while (i != 0) {
            int r = Math.floorMod(i - 1, 26);
            i = Math.floorDiv(i - 1, 26);
            s.insert(0, (char) ('A' + r));
        }
        return "ALT_" + s;
    }

    // =====================================================================
    // 공개 API
    // =====================================================================

    /** 대지 GeoJSON(EPSG:4326) 과 법정 치수로 주차 배치 대안을 만든다. params 가 null 이면 기본값. */
    public static SimulationResult simulate(JsonNode siteGeojson, ParkingDimensions dims, SimulationParams params) {
        return simulateDetailed(siteGeojson, dims, params).result();
    }

    public static SimulationResult simulate(JsonNode siteGeojson, ParkingDimensions dims) {
        return simulate(siteGeojson, dims, null);
    }

    /** {@link #simulate} 와 같고, 대안별 내부 기하(EPSG:5179)를 함께 돌려준다. */
    public static Detailed simulateDetailed(JsonNode siteGeojson, ParkingDimensions dims, SimulationParams params) {
        if (params == null) {
            params = SimulationParams.defaults();
        }
        if (dims == null) {
            dims = ParkingDimensions.empty();
        }
        List<String> reasons = new ArrayList<>();
        Prepared prepared = prepareSite(siteGeojson, reasons);
        if (prepared == null) {
            return new Detailed(new SimulationResult(new ArrayList<>(), new ArrayList<>(List.of(SITE_GEOMETRY_INVALID)),
                    null), new ArrayList<>());
        }
        double siteArea = prepared.total;
        Polygon site = prepared.site;
        double setback = PyFloat.max(0.0, params.setbackM() == null || params.setbackM() == 0.0 ? 0.0 : params.setbackM());
        Polygon usable = usableArea(site, setback, reasons);

        List<Spec> specs = new ArrayList<>();
        Set<String> seenAngles = new HashSet<>();
        for (String angle : params.angles() == null ? List.<String>of() : params.angles()) {
            if (seenAngles.contains(angle)) {
                continue;
            }
            seenAngles.add(angle);
            Spec spec = resolveSpec(angle, dims, params);
            if (spec == null) {
                add(reasons, DIMENSION_MISSING + ":" + (angle == null ? "None" : angle));
            } else {
                specs.add(spec);
            }
        }
        double[] gateTarget = gateTarget(params, reasons);

        if (usable == null) {
            if (!specs.isEmpty()) {
                add(reasons, LIMIT_SITE_WIDTH_NARROW);
                for (Spec spec : specs) {
                    add(reasons, LIMIT_SITE_WIDTH_NARROW + ":" + spec.angle);
                }
            }
            add(reasons, NO_FEASIBLE_LAYOUT);
            return new Detailed(new SimulationResult(new ArrayList<>(), reasons, r2(siteArea)), new ArrayList<>());
        }

        Point centroid = usable.getCentroid();
        double[] origin = {Py.round(centroid.getX(), 3), Py.round(centroid.getY(), 3)};
        List<double[][]> rings = new ArrayList<>();
        rings.add(minus(coordsOf(usable.getExteriorRing().getCoordinates()), origin));
        for (int i = 0; i < usable.getNumInteriorRing(); i++) {
            rings.add(minus(coordsOf(usable.getInteriorRingN(i).getCoordinates()), origin));
        }
        double step = params.orientationStepDeg() == null || params.orientationStepDeg() == 0.0
                ? 5.0 : params.orientationStepDeg();
        if (!Double.isFinite(step) || step <= 0) {
            step = 5.0;
        }
        step = PyFloat.min(PyFloat.max(step, 0.5), 90.0);
        List<Frame> frames = buildFrames(rings, orientationCandidates(usable, site, step));
        PreparedGeometry usablePrep = PreparedGeometryFactory.prepare(Shapely.bufferMitre(usable, CONTAIN_TOL, 5.0));

        Map<String, List<Candidate>> perAngle = new LinkedHashMap<>();
        double inscribed = !specs.isEmpty() ? inscribedWidth(usable) : 0.0;
        for (Spec spec : specs) {
            // 가장 넓은 곳의 폭조차 편측 모듈(구획열 + 차로) 깊이에 못 미치면 '좁은 대지'
            // (좁은 방향으로 모듈을 돌려 끝단에만 몇 대 두는 퇴화 배치는 만들지 않는다)
            if (inscribed + NARROW_TOL_M < spec.depth + spec.aisleW) {
                add(reasons, LIMIT_SITE_WIDTH_NARROW);
                add(reasons, LIMIT_SITE_WIDTH_NARROW + ":" + spec.angle);
                continue;
            }
            List<Plan> plans = searchAngle(frames, spec);
            List<Candidate> finals = new ArrayList<>();
            List<Double> tried = new ArrayList<>();
            for (Plan plan : plans) {
                Frame fr = frames.get(plan.fidx);
                boolean close = false;
                for (double t : tried) {
                    if (angleGap(fr.phi, t) < MIN_ORIENTATION_GAP_DEG) {
                        close = true;
                        break;
                    }
                }
                if (close) {
                    continue;
                }
                Object cand = buildCandidate(spec, fr, plan, origin, site, usablePrep, setback, gateTarget);
                if (cand instanceof String) {
                    continue;
                }
                tried.add(fr.phi);
                finals.add((Candidate) cand);
                if (finals.size() >= FINAL_PER_ANGLE) {
                    break;
                }
            }
            finals.sort(CAND_KEY);
            // 같은 형식의 다른 방향 대안은 최선의 절반 이상일 때만 (의미 없는 대안 제외)
            List<Candidate> kept = new ArrayList<>();
            for (int i = 0; i < finals.size(); i++) {
                if (i == 0 || finals.get(i).count() >= SECONDARY_MIN_RATIO * finals.get(0).count()) {
                    kept.add(finals.get(i));
                }
            }
            if (!kept.isEmpty()) {
                perAngle.put(spec.angle, kept);
            } else {
                add(reasons, NO_FEASIBLE_LAYOUT + ":" + spec.angle);
            }
        }

        // 대안 고르기: 각도 우선 다양화 → 같은 각도 내 다른 방향
        int maxAlt = Math.max(1, params.maxAlternatives() == null || params.maxAlternatives() == 0
                ? 1 : params.maxAlternatives());
        List<String> rankedAngles = new ArrayList<>(perAngle.keySet());
        rankedAngles.sort((x, y) -> CAND_KEY.compare(perAngle.get(x).get(0), perAngle.get(y).get(0)));
        List<Candidate> chosen = new ArrayList<>();
        int rank = 0;
        while (chosen.size() < maxAlt) {
            boolean added = false;
            for (String a : rankedAngles) {
                List<Candidate> lst = perAngle.get(a);
                if (rank < lst.size() && chosen.size() < maxAlt) {
                    chosen.add(lst.get(rank));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
            rank++;
        }
        chosen.sort(CAND_KEY);

        if (chosen.isEmpty()) {
            add(reasons, NO_FEASIBLE_LAYOUT);
        }
        List<String> baseReasons = new ArrayList<>();
        for (String r : reasons) {
            if (r.equals(MULTIPART_SITE_LARGEST_USED) || r.equals(USABLE_AREA_SPLIT_LARGEST_USED)
                    || r.equals(SITE_GEOMETRY_REPAIRED)) {
                baseReasons.add(r);
            }
        }
        List<Alternative> alternatives = new ArrayList<>();
        List<LayoutGeometry> geoms = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Candidate cand = chosen.get(i);
            String code = altCode(i);
            Alternative alt = toAlternative(code, cand, siteArea, usable, params.stallType(), baseReasons);
            alternatives.add(alt);
            for (String r : cand.reasons) {
                add(reasons, r);
            }
            Spec spec = cand.spec;
            geoms.add(new LayoutGeometry(code, spec.angle, cand.fr.phi, site, usable, cand.stalls, cand.edges,
                    cand.aisles, cand.network, cand.gatePoint, cand.gateOpening, spec.aisleW, spec.drivewayW,
                    spec.stallW, spec.stallL, spec.thetaDeg, spec.depth, spec.pitch));
        }
        if (!alternatives.isEmpty()) {
            alternatives.set(0, alternatives.get(0).withRecommended(true));
        }
        return new Detailed(new SimulationResult(alternatives, reasons, r2(siteArea)), geoms);
    }

    private static double[][] minus(double[][] xy, double[] origin) {
        double[][] out = new double[xy.length][2];
        for (int i = 0; i < xy.length; i++) {
            out[i][0] = xy[i][0] - origin[0];
            out[i][1] = xy[i][1] - origin[1];
        }
        return out;
    }
}
