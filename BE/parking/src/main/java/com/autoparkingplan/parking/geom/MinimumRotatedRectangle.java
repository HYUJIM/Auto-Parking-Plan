package com.autoparkingplan.parking.geom;

import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * GEOS 3.14.1 {@code algorithm/MinimumAreaRectangle} 의 자바 이식 (shapely {@code minimum_rotated_rectangle}).
 *
 * <p>JTS 판과 알고리즘은 같지만 선분 길이를 GEOS 처럼 {@code sqrt} 로 계산한다. 직사각형 대지처럼 여러 변이 거의 같은
 * 넓이를 주는 경우 끝자리 차이로 시작 변(→ 결과 꼭짓점 순서)이 달라지기 때문이다.
 */
public final class MinimumRotatedRectangle {
    private MinimumRotatedRectangle() {
    }

    public static Geometry of(Geometry inputGeom) {
        GeometryFactory f = inputGeom.getFactory();
        if (inputGeom.isEmpty()) {
            return f.createPolygon();
        }
        Geometry convexGeom = new ConvexHull(inputGeom).getConvexHull();
        Coordinate[] convexHullPts;
        if (convexGeom instanceof Polygon p) {
            convexHullPts = p.getExteriorRing().getCoordinates();
        } else if (convexGeom instanceof LineString l) {
            convexHullPts = l.getCoordinates();
        } else if (convexGeom instanceof Point p) {
            convexHullPts = p.getCoordinates();
        } else {
            throw new IllegalArgumentException("computeConvex called with unsupported geometry type");
        }
        return switch (convexHullPts.length) {
            case 1 -> f.createPoint(convexHullPts[0].copy());
            case 2, 3 -> computeMaximumLine(convexHullPts, f);
            default -> computeConvexRing(convexHullPts, f);
        };
    }

    private static Polygon computeConvexRing(Coordinate[] ring, GeometryFactory f) {
        double minRectangleArea = Double.MAX_VALUE;
        int minRectangleBaseIndex = -1;
        int minRectangleDiamIndex = -1;
        int minRectangleLeftIndex = -1;
        int minRectangleRightIndex = -1;
        int diameterIndex = 1;
        int leftSideIndex = 1;
        int rightSideIndex = -1;
        LineSegment segBase = new LineSegment();
        LineSegment segDiam = new LineSegment();
        for (int i = 0; i < ring.length - 1; i++) {
            segBase.p0 = ring[i];
            segBase.p1 = ring[i + 1];
            diameterIndex = findFurthestVertex(ring, segBase, diameterIndex, 0);
            Coordinate diamPt = ring[diameterIndex];
            Coordinate diamBasePt = segBase.project(diamPt);
            segDiam.p0 = diamBasePt;
            segDiam.p1 = diamPt;
            leftSideIndex = findFurthestVertex(ring, segDiam, leftSideIndex, 1);
            if (i == 0) {
                rightSideIndex = diameterIndex;
            }
            rightSideIndex = findFurthestVertex(ring, segDiam, rightSideIndex, -1);
            double rectWidth = distancePerpendicular(segDiam, ring[leftSideIndex])
                    + distancePerpendicular(segDiam, ring[rightSideIndex]);
            double rectArea = GeosMath.distance(segDiam.p0, segDiam.p1) * rectWidth;
            if (rectArea < minRectangleArea) {
                minRectangleArea = rectArea;
                minRectangleBaseIndex = i;
                minRectangleDiamIndex = diameterIndex;
                minRectangleLeftIndex = leftSideIndex;
                minRectangleRightIndex = rightSideIndex;
            }
        }
        return createFromSidePts(
                ring[minRectangleBaseIndex], ring[minRectangleBaseIndex + 1],
                ring[minRectangleDiamIndex], ring[minRectangleLeftIndex], ring[minRectangleRightIndex], f);
    }

    private static int findFurthestVertex(Coordinate[] pts, LineSegment baseSeg, int startIndex, int orient) {
        double maxDistance = orientedDistance(baseSeg, pts[startIndex], orient);
        double nextDistance = maxDistance;
        int maxIndex = startIndex;
        int nextIndex = maxIndex;
        while (isFurtherOrEqual(nextDistance, maxDistance, orient)) {
            maxDistance = nextDistance;
            maxIndex = nextIndex;
            nextIndex = getNextIndex(pts, maxIndex);
            if (nextIndex == startIndex) {
                break;
            }
            nextDistance = orientedDistance(baseSeg, pts[nextIndex], orient);
        }
        return maxIndex;
    }

    private static boolean isFurtherOrEqual(double d1, double d2, int orient) {
        return switch (orient) {
            case 0 -> Math.abs(d1) >= Math.abs(d2);
            case 1 -> d1 >= d2;
            case -1 -> d1 <= d2;
            default -> throw new IllegalArgumentException("Invalid orientation index");
        };
    }

    private static double orientedDistance(LineSegment seg, Coordinate p, int orient) {
        double dist;
        if (seg.p0.equals2D(seg.p1)) {
            dist = GeosMath.distance(seg.p0, p);
        } else {
            dist = distancePerpendicular(seg, p);
            if (Orientation.index(seg.p0, seg.p1, p) < 0) {
                dist = -dist;
            }
        }
        if (orient == 0) {
            return Math.abs(dist);
        }
        return dist;
    }

    private static double distancePerpendicular(LineSegment seg, Coordinate p) {
        Coordinate a = seg.p0;
        Coordinate b = seg.p1;
        if (a.equals2D(b)) {
            return GeosMath.distance(a, p);
        }
        double s = ((a.y - p.y) * (b.x - a.x) - (a.x - p.x) * (b.y - a.y))
                / ((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y));
        return Math.abs(s) * Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y));
    }

    private static int getNextIndex(Coordinate[] ring, int index) {
        index = index < 0 ? 0 : index + 1;
        if (index >= ring.length - 1) {
            index = 0;
        }
        return index;
    }

    private static LineString computeMaximumLine(Coordinate[] pts, GeometryFactory f) {
        Coordinate ptMinX = null;
        Coordinate ptMaxX = null;
        Coordinate ptMinY = null;
        Coordinate ptMaxY = null;
        for (Coordinate p : pts) {
            if (ptMinX == null || p.x < ptMinX.x) {
                ptMinX = p;
            }
            if (ptMaxX == null || p.x > ptMaxX.x) {
                ptMaxX = p;
            }
            if (ptMinY == null || p.y < ptMinY.y) {
                ptMinY = p;
            }
            if (ptMaxY == null || p.y > ptMaxY.y) {
                ptMaxY = p;
            }
        }
        Coordinate p0 = ptMinX;
        Coordinate p1 = ptMaxX;
        if (p0.x == p1.x) {
            p0 = ptMinY;
            p1 = ptMaxY;
        }
        return f.createLineString(new Coordinate[] {p0.copy(), p1.copy()});
    }

    /** GEOS {@code Rectangle::createFromSidePts}. */
    private static Polygon createFromSidePts(Coordinate baseRightPt, Coordinate baseLeftPt, Coordinate oppositePt,
            Coordinate leftSidePt, Coordinate rightSidePt, GeometryFactory factory) {
        double dx = baseLeftPt.x - baseRightPt.x;
        double dy = baseLeftPt.y - baseRightPt.y;
        double baseC = computeLineEquationC(dx, dy, baseRightPt);
        double oppC = computeLineEquationC(dx, dy, oppositePt);
        double leftC = computeLineEquationC(-dy, dx, leftSidePt);
        double rightC = computeLineEquationC(-dy, dx, rightSidePt);
        LineSegment baseLine = createLineForStandardEquation(-dy, dx, baseC);
        LineSegment oppLine = createLineForStandardEquation(-dy, dx, oppC);
        LineSegment leftLine = createLineForStandardEquation(-dx, -dy, leftC);
        LineSegment rightLine = createLineForStandardEquation(-dx, -dy, rightC);
        Coordinate p0 = rightSidePt.equals2D(baseRightPt) ? baseRightPt.copy() : baseLine.lineIntersection(rightLine);
        Coordinate p1 = leftSidePt.equals2D(baseLeftPt) ? baseLeftPt.copy() : baseLine.lineIntersection(leftLine);
        Coordinate p2 = leftSidePt.equals2D(oppositePt) ? oppositePt.copy() : oppLine.lineIntersection(leftLine);
        Coordinate p3 = rightSidePt.equals2D(oppositePt) ? oppositePt.copy() : oppLine.lineIntersection(rightLine);
        return factory.createPolygon(new Coordinate[] {p0, p1, p2, p3, p0.copy()});
    }

    private static double computeLineEquationC(double a, double b, Coordinate p) {
        return a * p.y - b * p.x;
    }

    private static LineSegment createLineForStandardEquation(double a, double b, double c) {
        Coordinate p0;
        Coordinate p1;
        if (Math.abs(b) > Math.abs(a)) {
            p0 = new Coordinate(0.0, c / b);
            p1 = new Coordinate(1.0, c / b - a / b);
        } else {
            p0 = new Coordinate(c / a, 0.0);
            p1 = new Coordinate(c / a - b / a, 1.0);
        }
        return new LineSegment(p0, p1);
    }
}
