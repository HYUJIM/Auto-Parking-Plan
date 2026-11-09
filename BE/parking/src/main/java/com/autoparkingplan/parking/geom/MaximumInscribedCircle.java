package com.autoparkingplan.parking.geom;

import java.util.PriorityQueue;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateArrays;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;

/**
 * GEOS 3.14.1 {@code algorithm/construct/MaximumInscribedCircle} (+ {@code ExactMaxInscribedCircle}) 의 자바 이식.
 *
 * <p>shapely {@code maximum_inscribed_circle(poly, tolerance)} 와 같은 결과를 낸다. JTS 1.20 판에는 삼각형·볼록 사각형의
 * 정확해(ExactMaxInscribedCircle) 최적화가 없어 직사각형 대지에서 값이 달라진다.
 */
public final class MaximumInscribedCircle {
    private final Geometry inputGeom;
    private final double tolerance;
    private final IndexedFacetDistance indexedDistance;
    private final IndexedPointInAreaLocator ptLocator;
    private final GeometryFactory factory;
    private Coordinate centerPt;
    private Coordinate radiusPt;
    private boolean done;

    public MaximumInscribedCircle(Geometry polygonal, double tolerance) {
        if (!(polygonal instanceof Polygon || polygonal instanceof MultiPolygon)) {
            throw new IllegalArgumentException("Input must be a Polygon or MultiPolygon");
        }
        if (polygonal.isEmpty()) {
            throw new IllegalArgumentException("Empty input is not supported");
        }
        this.inputGeom = polygonal;
        this.tolerance = tolerance;
        this.indexedDistance = new IndexedFacetDistance(polygonal.getBoundary());
        this.ptLocator = new IndexedPointInAreaLocator(polygonal);
        this.factory = polygonal.getFactory();
    }

    /** shapely {@code maximum_inscribed_circle}: [중심, 경계 최근접점] 선분. */
    public LineString getRadiusLine() {
        compute();
        return factory.createLineString(new Coordinate[] {centerPt.copy(), radiusPt.copy()});
    }

    private static final class Cell {
        static final double SQRT2 = 1.4142135623730951;
        final double x;
        final double y;
        final double hSize;
        final double distance;
        final double maxDist;

        Cell(double x, double y, double hSize, double distanceToBoundary) {
            this.x = x;
            this.y = y;
            this.hSize = hSize;
            this.distance = distanceToBoundary;
            this.maxDist = distanceToBoundary + hSize * SQRT2;
        }
    }

    private static int computeMaximumIterations(Geometry geom, double toleranceDist) {
        double diam = diameter(geom.getEnvelopeInternal());
        double ncells = diam / toleranceDist;
        int factor = (int) StrictMath.log(ncells);
        if (factor < 1) {
            factor = 1;
        }
        return 2000 + 2000 * factor;
    }

    private static double diameter(Envelope env) {
        if (env.isNull()) {
            return 0.0;
        }
        double w = env.getWidth();
        double h = env.getHeight();
        return Math.sqrt(w * w + h * h);
    }

    private double distanceToBoundary(double x, double y) {
        return distanceToBoundary(factory.createPoint(new Coordinate(x, y)));
    }

    private double distanceToBoundary(Point pt) {
        double dist = indexedDistance.distance(pt);
        boolean isOutside = Location.EXTERIOR == ptLocator.locate(pt.getCoordinate());
        return isOutside ? -dist : dist;
    }

    private void compute() {
        if (done) {
            return;
        }
        if (inputGeom.getArea() == 0.0) {
            Coordinate c = inputGeom.getCoordinate();
            centerPt = c;
            radiusPt = c;
            done = true;
            return;
        }
        if (isExactSupported(inputGeom)) {
            Coordinate[] cr = computeExactRadius((Polygon) inputGeom);
            centerPt = cr[0];
            radiusPt = cr[1];
            done = true;
            return;
        }
        if (tolerance <= 0) {
            throw new IllegalArgumentException("Tolerance must be positive");
        }
        computeApproximation();
    }

    private void computeApproximation() {
        // std::priority_queue<Cell>: 가장 큰 maxDist 가 먼저
        PriorityQueue<Cell> cellQueue = new PriorityQueue<>((a, b) -> Double.compare(b.maxDist, a.maxDist));
        Envelope env = inputGeom.getEnvelopeInternal();
        double cellSize = Math.max(env.getWidth(), env.getHeight());
        double hSide = cellSize / 2.0;
        if (cellSize != 0) {
            Coordinate c = env.centre();
            cellQueue.add(new Cell(c.x, c.y, hSide, distanceToBoundary(c.x, c.y)));
        }
        Point ip = inputGeom.getInteriorPoint();
        Cell farthestCell = new Cell(ip.getX(), ip.getY(), 0, distanceToBoundary(ip));
        int maxIter = computeMaximumIterations(inputGeom, tolerance);
        int iterationCount = 0;
        while (!cellQueue.isEmpty() && iterationCount < maxIter) {
            Cell cell = cellQueue.poll();
            iterationCount++;
            if (cell.maxDist < farthestCell.distance) {
                break;
            }
            if (cell.distance > farthestCell.distance) {
                farthestCell = cell;
            }
            double potentialIncrease = cell.maxDist - farthestCell.distance;
            if (potentialIncrease > tolerance) {
                double h2 = cell.hSize / 2;
                cellQueue.add(new Cell(cell.x - h2, cell.y - h2, h2, distanceToBoundary(cell.x - h2, cell.y - h2)));
                cellQueue.add(new Cell(cell.x + h2, cell.y - h2, h2, distanceToBoundary(cell.x + h2, cell.y - h2)));
                cellQueue.add(new Cell(cell.x - h2, cell.y + h2, h2, distanceToBoundary(cell.x - h2, cell.y + h2)));
                cellQueue.add(new Cell(cell.x + h2, cell.y + h2, h2, distanceToBoundary(cell.x + h2, cell.y + h2)));
            }
        }
        centerPt = new Coordinate(farthestCell.x, farthestCell.y);
        Coordinate[] nearest = indexedDistance.nearestPoints(factory.createPoint(centerPt));
        radiusPt = nearest[0].copy();
        done = true;
    }

    // ── ExactMaxInscribedCircle ─────────────────────────────────────────

    private static boolean isExactSupported(Geometry geom) {
        if (!(geom instanceof Polygon polygon)) {
            return false;
        }
        if (polygon.getNumInteriorRing() > 0) {
            return false;
        }
        if (polygon.getNumPoints() == 4) {
            return true;
        }
        return polygon.getNumPoints() == 5 && isConvex(polygon.getExteriorRing().getCoordinates());
    }

    private static Coordinate[] computeExactRadius(Polygon polygon) {
        Coordinate[] ring = polygon.getExteriorRing().getCoordinates();
        if (ring.length == 4) {
            Coordinate center = GeosMath.triangleInCentre(ring[0], ring[1], ring[2]);
            LineSegment seg = new LineSegment(ring[0], ring[1]);
            Coordinate radius = seg.project(center);
            return new Coordinate[] {center, radius};
        }
        return computeConvexQuadrilateral(ring);
    }

    private static Coordinate[] computeConvexQuadrilateral(Coordinate[] ring) {
        Coordinate[] ringCW = ring;
        if (Orientation.isCCW(ring)) {
            ringCW = ring.clone();
            CoordinateArrays.reverse(ringCW);
        }
        Envelope env = new Envelope();
        for (Coordinate c : ringCW) {
            env.expandToInclude(c);
        }
        double diamWithTolerance = 2 * diameter(env);
        LineSegment[] bisector = new LineSegment[4];
        for (int i = 0; i < 4; i++) {
            bisector[i] = computeConvexBisector(ringCW, i, diamWithTolerance);
        }
        double maxDist = -1;
        Coordinate center = new Coordinate();
        Coordinate radius = new Coordinate();
        for (int i = 0; i < 4; i++) {
            LineSegment b1 = bisector[i];
            LineSegment b2 = bisector[(i + 1) % 4];
            Coordinate nodePt = b1.intersection(b2);
            if (nodePt == null) {
                continue;
            }
            if (!isPointInConvexRing(ringCW, nodePt)) {
                continue;
            }
            Coordinate r = nearestEdgePt(ringCW, nodePt);
            double dist = GeosMath.distance(nodePt, r);
            if (maxDist < 0 || dist > maxDist) {
                center = nodePt;
                radius = r;
                maxDist = dist;
            }
        }
        return new Coordinate[] {center, radius};
    }

    private static Coordinate nearestEdgePt(Coordinate[] ring, Coordinate pt) {
        Coordinate nearestPt = new Coordinate();
        double minDist = -1;
        for (int i = 0; i < ring.length - 1; i++) {
            LineSegment edge = new LineSegment(ring[i], ring[i + 1]);
            Coordinate r = edge.closestPoint(pt);
            double dist = GeosMath.distance(pt, r);
            if (minDist < 0 || dist < minDist) {
                minDist = dist;
                nearestPt = r;
            }
        }
        return nearestPt;
    }

    private static LineSegment computeConvexBisector(Coordinate[] pts, int index, double len) {
        Coordinate basePt = pts[index];
        int iPrev = index == 0 ? pts.length - 2 : index - 1;
        int iNext = index >= pts.length ? 0 : index + 1;
        Coordinate pPrev = pts[iPrev];
        Coordinate pNext = pts[iNext];
        if (Orientation.COUNTERCLOCKWISE == Orientation.index(pPrev, basePt, pNext)) {
            throw new IllegalStateException("Input is not convex");
        }
        double angDel = Angle.angleBetweenOriented(pPrev, basePt, pNext);
        double bisectAng = Angle.normalize(Angle.angle(basePt, pPrev) + angDel / 2);
        double[] sc = GeosMath.sinCosSnap(bisectAng);
        Coordinate endPt = new Coordinate(basePt.x + len * sc[1], basePt.y + len * sc[0]);
        return new LineSegment(basePt.x, basePt.y, endPt.x, endPt.y);
    }

    private static boolean isConvex(Coordinate[] ring) {
        int n = ring.length;
        if (n < 4) {
            return false;
        }
        int ringOrient = 0;
        for (int i = 0; i < n - 1; i++) {
            int i1 = (i + 1) % (n - 1);
            int i2 = (i + 2) % (n - 1);
            int orient = Orientation.index(ring[i], ring[i1], ring[i2]);
            if (orient == Orientation.COLLINEAR) {
                continue;
            }
            if (ringOrient == 0) {
                ringOrient = orient;
            } else if (orient != ringOrient) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPointInConvexRing(Coordinate[] ringCW, Coordinate p) {
        for (int i = 0; i < ringCW.length - 1; i++) {
            int orient = Orientation.index(ringCW[i], ringCW[i + 1], p);
            if (orient == Orientation.COUNTERCLOCKWISE) {
                return false;
            }
        }
        return true;
    }
}
