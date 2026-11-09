package com.autoparkingplan.parking.geom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;

import com.autoparkingplan.parking.geom.buffer.BufferOp;

/**
 * 파이썬판이 쓰는 shapely 2.2 (GEOS 3.14.1) 함수들의 JTS 대응.
 *
 * <p>JTS 와 GEOS 는 같은 뿌리지만 결과(정점 순서·좌표 끝자리)가 다른 곳이 있어, 파이썬판과 같은 배치를 내려면
 * GEOS 쪽 동작을 따라야 한다:
 * <ul>
 *   <li>버퍼: GEOS 판을 이식한 {@link BufferOp} (상수·단순화·마이터 계산이 JTS 와 다르다)</li>
 *   <li>중첩 연산: GEOS {@code HeuristicOverlay} 처럼 {@link OverlayNGRobust} 를 직접 쓴다 (JTS 기본은 옛 overlay)</li>
 *   <li>여러 다각형 합집합: GEOS {@code CascadedPolygonUnion} 의 STR 트리 잎 순서로 이분 합집합</li>
 *   <li>선형 참조(interpolate/project): 길이를 {@code sqrt(dx*dx+dy*dy)} 로 (JTS 는 hypot)</li>
 *   <li>make_valid: GEOS "linework" 방식 이식, 최대 내접원: GEOS 3.14 (정확해 최적화 포함)</li>
 * </ul>
 */
public final class Shapely {
    /** shapely 와 같은 부동소수 정밀도 팩토리. */
    public static final GeometryFactory FACTORY = new GeometryFactory();

    /** shapely buffer 의 기본 quad_segs. */
    public static final int QUAD_SEGS = 16;

    private Shapely() {
    }

    // ── 버퍼 ──────────────────────────────────────────────────────────

    /** {@code geom.buffer(d)} (둥근 모서리, quad_segs=16). */
    public static Geometry buffer(Geometry g, double d) {
        return buffer(g, d, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 5.0);
    }

    /** {@code geom.buffer(d, join_style="mitre", mitre_limit=limit)}. */
    public static Geometry bufferMitre(Geometry g, double d, double mitreLimit) {
        return buffer(g, d, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, mitreLimit);
    }

    /** {@code geom.buffer(d, cap_style=cap, join_style=join, mitre_limit=limit)} (quad_segs=16). */
    public static Geometry buffer(Geometry g, double d, int capStyle, int joinStyle, double mitreLimit) {
        BufferParameters bp = new BufferParameters(QUAD_SEGS, capStyle, joinStyle, mitreLimit);
        return BufferOp.bufferOp(g, d, bp);
    }

    // ── 중첩 연산 (GEOS HeuristicOverlay) ─────────────────────────────

    public static Geometry intersection(Geometry a, Geometry b) {
        return OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION);
    }

    public static Geometry difference(Geometry a, Geometry b) {
        return OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE);
    }

    /** {@code shapely.union(a, b)}: 봉투가 겹치지 않는 단일 요소끼리는 노딩 없이 묶는다 (GEOS isCombinable). */
    public static Geometry union(Geometry a, Geometry b) {
        if (isCombinable(a, b)) {
            List<Geometry> v = new ArrayList<>();
            extractElements(a, v);
            extractElements(b, v);
            return a.getFactory().buildGeometry(v);
        }
        return OverlayNGRobust.overlay(a, b, OverlayNG.UNION);
    }

    private static boolean hasSingleNonEmptyElement(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return false;
        }
        if (!(geom instanceof GeometryCollection)) {
            return true;
        }
        boolean found = false;
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            if (hasSingleNonEmptyElement(geom.getGeometryN(i))) {
                if (found) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    private static boolean isCombinable(Geometry g0, Geometry g1) {
        if (g0.isEmpty() && g1.isEmpty()) {
            return false;
        }
        if (g0.getEnvelopeInternal().intersects(g1.getEnvelopeInternal())) {
            return false;
        }
        return hasSingleNonEmptyElement(g0) && hasSingleNonEmptyElement(g1);
    }

    private static void extractElements(Geometry g, List<Geometry> v) {
        if (g instanceof GeometryCollection) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                extractElements(g.getGeometryN(i), v);
            }
        } else if (!g.isEmpty()) {
            v.add(g.copy());
        }
    }

    /** {@code shapely.union_all(geoms)} = GEOS {@code UnaryUnionOp}. */
    public static Geometry unionAll(List<? extends Geometry> geoms) {
        GeometryFactory f = geoms.isEmpty() ? FACTORY : geoms.get(0).getFactory();
        List<Polygon> polygons = new ArrayList<>();
        List<LineString> lines = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        for (Geometry g : geoms) {
            extract(g, polygons, lines, points);
        }
        Geometry unionPoints = null;
        if (!points.isEmpty()) {
            unionPoints = OverlayNGRobust.overlay(f.buildGeometry(points), f.createGeometryCollection(), OverlayNG.UNION);
        }
        Geometry unionLines = null;
        if (!lines.isEmpty()) {
            unionLines = OverlayNGRobust.overlay(f.buildGeometry(lines), f.createGeometryCollection(), OverlayNG.UNION);
        }
        Geometry unionPolygons = polygons.isEmpty() ? null : cascadedPolygonUnion(polygons);
        Geometry unionLA;
        if (unionLines == null) {
            unionLA = unionPolygons;
        } else if (unionPolygons == null) {
            unionLA = unionLines;
        } else {
            unionLA = union(unionLines, unionPolygons);
        }
        Geometry ret;
        if (unionPoints == null) {
            ret = unionLA;
        } else if (unionLA == null) {
            ret = unionPoints;
        } else {
            ret = union(unionPoints, unionLA);
        }
        return ret == null ? f.createGeometryCollection() : ret;
    }

    private static void extract(Geometry g, List<Polygon> polys, List<LineString> lines, List<Point> points) {
        if (g instanceof Polygon p) {
            polys.add(p);
        } else if (g instanceof LineString l) {
            lines.add(l);
        } else if (g instanceof Point p) {
            points.add(p);
        } else if (g instanceof GeometryCollection) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                extract(g.getGeometryN(i), polys, lines, points);
            }
        }
    }

    /**
     * GEOS {@code CascadedPolygonUnion::Union}: STR 트리(노드 용량 10)의 잎 순서로 정렬한 뒤 이분 합집합.
     * 입력이 없으면 null.
     */
    public static Geometry cascadedPolygonUnion(List<Polygon> inputPolys) {
        if (inputPolys.isEmpty()) {
            return null;
        }
        List<Geometry> geoms = strLeafOrder(new ArrayList<>(inputPolys), 10);
        return binaryUnion(geoms, 0, geoms.size());
    }

    /** GEOS {@code TemplateSTRtree::build} 첫 단계(잎 정렬) 결과 순서. 정렬 키는 봉투 x·y 중심의 두 배(min+max). */
    static List<Geometry> strLeafOrder(List<Geometry> nodes, int nodeCapacity) {
        int number = nodes.size();
        if (number <= 1) {
            return nodes;
        }
        int numSlices = (int) Math.ceil(Math.sqrt(Math.ceil((double) number / (double) nodeCapacity)));
        int nodesPerSlice = (int) Math.ceil((double) number / (double) numSlices);
        nodes.sort(Comparator.comparingDouble(g -> centreX2(g.getEnvelopeInternal())));
        List<Geometry> out = new ArrayList<>(number);
        int start = 0;
        for (int j = 0; j < numSlices; j++) {
            int remaining = number - start;
            int inSlice = Math.min(remaining, nodesPerSlice);
            List<Geometry> slice = new ArrayList<>(nodes.subList(start, start + inSlice));
            slice.sort(Comparator.comparingDouble(g -> centreY2(g.getEnvelopeInternal())));
            out.addAll(slice);
            start += inSlice;
        }
        return out;
    }

    private static double centreX2(Envelope e) {
        return e.getMinX() + e.getMaxX();
    }

    private static double centreY2(Envelope e) {
        return e.getMinY() + e.getMaxY();
    }

    private static Geometry binaryUnion(List<Geometry> geoms, int start, int end) {
        if (end - start == 0) {
            return null;
        }
        if (end - start == 1) {
            return geoms.get(start).copy();
        }
        if (end - start == 2) {
            return unionActual(geoms.get(start), geoms.get(start + 1));
        }
        int mid = (end + start) / 2;
        Geometry g0 = binaryUnion(geoms, start, mid);
        Geometry g1 = binaryUnion(geoms, mid, end);
        if (g0 == null) {
            return g1;
        }
        if (g1 == null) {
            return g0;
        }
        return unionActual(g0, g1);
    }

    private static Geometry unionActual(Geometry g0, Geometry g1) {
        Geometry ug = OverlayNGRobust.overlay(g0, g1, OverlayNG.UNION);
        return restrictToPolygons(ug);
    }

    private static Geometry restrictToPolygons(Geometry g) {
        if (g instanceof Polygon || g instanceof MultiPolygon) {
            return g;
        }
        GeometryFactory f = g.getFactory();
        if (g instanceof GeometryCollection) {
            List<Polygon> polys = new ArrayList<>();
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry c = g.getGeometryN(i);
                if (c instanceof Polygon p) {
                    polys.add(p);
                } else if (c instanceof MultiPolygon mp) {
                    for (int k = 0; k < mp.getNumGeometries(); k++) {
                        polys.add((Polygon) mp.getGeometryN(k));
                    }
                }
            }
            return f.createMultiPolygon(polys.toArray(new Polygon[0]));
        }
        return f.createPolygon();
    }

    // ── 정리 ──────────────────────────────────────────────────────────

    public static Geometry makeValid(Geometry g) {
        return MakeValid.makeValid(g);
    }

    public static Geometry removeRepeatedPoints(Geometry g, double tolerance) {
        return RepeatedPointRemover.removeRepeatedPoints(g, tolerance);
    }

    /**
     * shapely {@code orient(polygon, sign=1.0)} = GEOS {@code Polygon::orientRings(exteriorCW=false)}:
     * 외곽은 반시계, 구멍은 시계 (평평한 링은 {@code isCCW=false} 로 보아 뒤집힌다).
     */
    public static Polygon orient(Polygon p) {
        if (p.isEmpty()) {
            return (Polygon) p.copy();
        }
        LinearRing shell = orientRing(p.getExteriorRing(), false);
        LinearRing[] holes = new LinearRing[p.getNumInteriorRing()];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = orientRing(p.getInteriorRingN(i), true);
        }
        return p.getFactory().createPolygon(shell, holes);
    }

    private static LinearRing orientRing(LinearRing ring, boolean isCW) {
        GeometryFactory f = ring.getFactory();
        if (ring.isEmpty()) {
            return (LinearRing) ring.copy();
        }
        Coordinate[] c = copyCoords(ring.getCoordinates());
        if (Orientation.isCCW(c) == isCW) {
            reverse(c);
        }
        return f.createLinearRing(c);
    }

    // ── 측정·구성 ─────────────────────────────────────────────────────

    /** {@code minimum_rotated_rectangle} (GEOS 3.12+ 최소 면적 사각형). */
    public static Geometry minimumRotatedRectangle(Geometry g) {
        return MinimumRotatedRectangle.of(g);
    }

    /** {@code shapely.maximum_inscribed_circle(poly, tolerance)} 의 반지름 선분. */
    public static LineString maximumInscribedCircle(Geometry g, double tolerance) {
        return new MaximumInscribedCircle(g, tolerance).getRadiusLine();
    }

    /** {@code shapely.ops.nearest_points(a, b)}. */
    public static Coordinate[] nearestPoints(Geometry a, Geometry b) {
        return DistanceOp.nearestPoints(a, b);
    }

    /** {@code shapely.ops.linemerge(g)} (GEOS LineMerge + buildGeometry). */
    @SuppressWarnings("unchecked")
    public static Geometry lineMerge(Geometry g) {
        LineMerger m = new LineMerger();
        m.add(g);
        List<Geometry> merged = new ArrayList<>((java.util.Collection<Geometry>) m.getMergedLineStrings());
        return g.getFactory().buildGeometry(merged);
    }

    // ── 선형 참조 (GEOS linearref, 단일 LineString) ────────────────────

    /** 선 길이 (GEOS {@code Length::ofLine}). */
    public static double length(Coordinate[] pts) {
        double len = 0.0;
        for (int i = 1; i < pts.length; i++) {
            len += GeosMath.distance(pts[i], pts[i - 1]);
        }
        return len;
    }

    /** {@code line.interpolate(d)} = GEOS {@code LengthIndexedLine::extractPoint(d)}. */
    public static Coordinate interpolate(LineString line, double length) {
        Coordinate[] pts = line.getCoordinates();
        if (pts.length == 0) {
            return null;
        }
        double forwardLength = length;
        if (length < 0.0) {
            forwardLength = length(pts) + length;
        }
        // getLocationForward
        if (forwardLength <= 0.0) {
            return pts[0].copy();
        }
        double totalLength = 0.0;
        int n = pts.length;
        for (int i = 0; i < n; i++) {
            if (i == n - 1) {
                if (totalLength == forwardLength) {
                    return pts[n - 1].copy();
                }
            } else {
                Coordinate p0 = pts[i];
                Coordinate p1 = pts[i + 1];
                double segLen = GeosMath.distance(p1, p0);
                if (totalLength + segLen > forwardLength) {
                    double frac = (forwardLength - totalLength) / segLen;
                    return pointAlongSegmentByFraction(p0, p1, frac);
                }
                totalLength += segLen;
            }
        }
        return pts[n - 1].copy();
    }

    private static Coordinate pointAlongSegmentByFraction(Coordinate p0, Coordinate p1, double frac) {
        if (frac <= 0.0) {
            return p0.copy();
        }
        if (frac >= 1.0) {
            return p1.copy();
        }
        double x = (p1.x - p0.x) * frac + p0.x;
        double y = (p1.y - p0.y) * frac + p0.y;
        return new Coordinate(x, y);
    }

    /** {@code line.project(point)} = GEOS {@code LengthIndexOfPoint::indexOf}. */
    public static double project(LineString line, Coordinate inputPt) {
        Coordinate[] pts = line.getCoordinates();
        double minIndex = -1.0;
        double minDistance = Double.POSITIVE_INFINITY;
        double ptMeasure = minIndex;
        double segmentStartMeasure = 0.0;
        for (int i = 0; i < pts.length - 1; i++) {
            Coordinate p0 = pts[i];
            Coordinate p1 = pts[i + 1];
            double segDistance = GeosMath.pointToSegment(inputPt, p0, p1);
            double segLen = GeosMath.distance(p0, p1);
            double projFactor = GeosMath.projectionFactor(p0, p1, inputPt);
            double segMeasureToPt;
            if (projFactor <= 0.0) {
                segMeasureToPt = segmentStartMeasure;
            } else if (projFactor <= 1.0) {
                segMeasureToPt = segmentStartMeasure + projFactor * segLen;
            } else {
                segMeasureToPt = segmentStartMeasure + segLen;
            }
            if (segDistance < minDistance && segMeasureToPt > minIndex) {
                ptMeasure = segMeasureToPt;
                minDistance = segDistance;
            }
            segmentStartMeasure += segLen;
        }
        return ptMeasure;
    }

    /**
     * {@code shapely.ops.substring(line, start, end)} (shapely 2.2 파이썬 구현 그대로).
     * 시작과 끝이 같은 위치면 Point.
     */
    public static Geometry substring(LineString geom, double startDist, double endDist) {
        GeometryFactory f = geom.getFactory();
        double len = geom.getLength();
        if (startDist == endDist) {
            return f.createPoint(interpolate(geom, startDist));
        } else if (startDist >= len && endDist >= len) {
            return f.createPoint(interpolate(geom, len));
        } else if (-startDist >= len && -endDist >= len) {
            return f.createPoint(interpolate(geom, 0));
        }
        if (startDist < 0 && 0 < endDist && Math.abs(startDist) + endDist == len) {
            return f.createPoint(interpolate(geom, endDist));
        } else if (endDist < 0 && 0 < startDist && Math.abs(endDist) + startDist == len) {
            return f.createPoint(interpolate(geom, startDist));
        }
        Coordinate startPoint = interpolate(geom, startDist);
        Coordinate endPoint = interpolate(geom, endDist);
        if (startDist < 0) {
            startDist = len + startDist;
        }
        if (endDist < 0) {
            endDist = len + endDist;
        }
        boolean reverse = startDist > endDist;
        if (reverse) {
            double t = startDist;
            startDist = endDist;
            endDist = t;
        }
        startDist = Math.max(startDist, 0);
        List<Coordinate> vertexList = new ArrayList<>();
        vertexList.add(reverse ? endPoint : startPoint);
        Coordinate[] coords = geom.getCoordinates();
        double currentDistance = 0;
        for (int i = 0; i < coords.length - 1; i++) {
            Coordinate p1 = coords[i];
            Coordinate p2 = coords[i + 1];
            if (startDist < currentDistance && currentDistance < endDist) {
                vertexList.add(p1.copy());
            } else if (currentDistance >= endDist) {
                break;
            }
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            currentDistance += Math.sqrt(dx * dx + dy * dy);
        }
        if (reverse) {
            vertexList.add(startPoint);
            java.util.Collections.reverse(vertexList);
        } else {
            vertexList.add(endPoint);
        }
        return f.createLineString(vertexList.toArray(new Coordinate[0]));
    }

    // ── 좌표 변환 ─────────────────────────────────────────────────────

    /** 좌표 하나를 바꾸는 함수 (shapely.transform 의 numpy 함수 대응). */
    @FunctionalInterface
    public interface CoordFn {
        /** (x, y) → 새 좌표를 out[0], out[1] 에 쓴다. */
        void apply(double x, double y, double[] out);
    }

    /** {@code shapely.transform(geom, fn)}: 구조는 그대로, 좌표만 바꾼다 (2D). */
    public static <T extends Geometry> T transform(T g, CoordFn fn) {
        @SuppressWarnings("unchecked")
        T copy = (T) g.copy();
        double[] out = new double[2];
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                fn.apply(seq.getX(i), seq.getY(i), out);
                seq.setOrdinate(i, 0, out[0]);
                seq.setOrdinate(i, 1, out[1]);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        copy.geometryChanged();
        return copy;
    }

    /** {@code shapely.affinity.affine_transform(geom, [a, b, d, e, xoff, yoff])}: x' = a·x + b·y + xoff. */
    public static <T extends Geometry> T affineTransform(T g, double a, double b, double d, double e, double xoff, double yoff) {
        return transform(g, (x, y, out) -> {
            out[0] = a * x + b * y + xoff;
            out[1] = d * x + e * y + yoff;
        });
    }

    /** {@code shapely.affinity.translate(geom, dx, dy)}. */
    public static <T extends Geometry> T translate(T g, double dx, double dy) {
        return affineTransform(g, 1.0, 0.0, 0.0, 1.0, dx, dy);
    }

    // ── 생성 ──────────────────────────────────────────────────────────

    public static Polygon polygon(double[][] xy) {
        Coordinate[] c = new Coordinate[xy.length];
        for (int i = 0; i < xy.length; i++) {
            c[i] = new Coordinate(xy[i][0], xy[i][1]);
        }
        return FACTORY.createPolygon(closeRing(c));
    }

    public static LineString lineString(Coordinate... pts) {
        return FACTORY.createLineString(copyCoords(pts));
    }

    public static Point point(double x, double y) {
        return FACTORY.createPoint(new Coordinate(x, y));
    }

    /** shapely 처럼 열린 링은 첫 점을 붙여 닫는다. */
    public static Coordinate[] closeRing(Coordinate[] c) {
        if (c.length > 0 && !c[0].equals2D(c[c.length - 1])) {
            Coordinate[] r = new Coordinate[c.length + 1];
            System.arraycopy(c, 0, r, 0, c.length);
            r[c.length] = c[0].copy();
            return r;
        }
        return c;
    }

    static Coordinate[] copyCoords(Coordinate[] c) {
        Coordinate[] r = new Coordinate[c.length];
        for (int i = 0; i < c.length; i++) {
            r[i] = new Coordinate(c[i].x, c[i].y);
        }
        return r;
    }

    private static void reverse(Coordinate[] c) {
        for (int i = 0, j = c.length - 1; i < j; i++, j--) {
            Coordinate t = c[i];
            c[i] = c[j];
            c[j] = t;
        }
    }
}
