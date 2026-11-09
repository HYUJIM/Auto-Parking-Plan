package com.autoparkingplan.parking.geom;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * GEOS 3.14.1 {@code operation/valid/RepeatedPointRemover} 의 자바 이식
 * (shapely {@code remove_repeated_points} 와 버퍼 입력 정리에 쓰인다).
 */
public final class RepeatedPointRemover {
    private RepeatedPointRemover() {
    }

    /** {@code removeRepeatedPoints(seq, tolerance)}: 앞 점과 같거나 허용오차 안의 점을 뺀다. */
    public static Coordinate[] removeRepeatedPoints(Coordinate[] seq, double tolerance) {
        if (seq.length == 0) {
            return new Coordinate[0];
        }
        List<Coordinate> out = new ArrayList<>(seq.length);
        if (tolerance == 0.0) {
            // CoordinateSequence::add(seq, allowRepeated=false)
            Coordinate prev = null;
            for (Coordinate c : seq) {
                if (prev != null && prev.equals2D(c)) {
                    continue;
                }
                out.add(c.copy());
                prev = c;
            }
            return out.toArray(new Coordinate[0]);
        }
        double sqTolerance = tolerance * tolerance;
        Coordinate prev = null;
        for (Coordinate c : seq) {
            if (prev != null && (c.equals2D(prev) || distanceSquared(c, prev) <= sqTolerance)) {
                continue;
            }
            out.add(c.copy());
            prev = c;
        }
        return out.toArray(new Coordinate[0]);
    }

    /** {@code removeRepeatedAndInvalidPoints(seq, tolerance)}. */
    public static Coordinate[] removeRepeatedAndInvalidPoints(Coordinate[] seq, double tolerance) {
        if (seq.length == 0) {
            return new Coordinate[0];
        }
        return filterRepeatedInvalid(seq, tolerance).toArray(new Coordinate[0]);
    }

    private static List<Coordinate> filterRepeatedInvalid(Coordinate[] seq, double tolerance) {
        double sqTolerance = tolerance * tolerance;
        List<Coordinate> out = new ArrayList<>(seq.length);
        Coordinate prev = null;
        for (Coordinate c : seq) {
            boolean invalid = !c.isValid();
            if (prev == null && invalid) {
                continue;
            }
            if (prev != null && (invalid || c.equals2D(prev) || distanceSquared(c, prev) <= sqTolerance)) {
                continue;
            }
            out.add(c.copy());
            prev = c;
        }
        return out;
    }

    private static double distanceSquared(Coordinate a, Coordinate b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    /** GEOS {@code RepeatedPointCoordinateOperation::edit}. 결과가 없으면 null. */
    private static Coordinate[] edit(Coordinate[] coordinates, int minLength, double tolerance) {
        if (coordinates.length <= minLength) {
            return copy(coordinates);
        }
        List<Coordinate> filt = filterRepeatedInvalid(coordinates, tolerance);
        if (filt.isEmpty()) {
            return null;
        }
        Coordinate origEnd = coordinates[coordinates.length - 1];
        if (filt.size() < minLength) {
            filt.add(origEnd.copy());
        }
        Coordinate filtEnd = filt.get(filt.size() - 1);
        if (!origEnd.equals2D(filtEnd)) {
            if (distanceSquared(origEnd, filtEnd) <= tolerance * tolerance) {
                filt.remove(filt.size() - 1);
            }
            filt.add(origEnd.copy());
        }
        return filt.toArray(new Coordinate[0]);
    }

    private static Coordinate[] copy(Coordinate[] c) {
        Coordinate[] r = new Coordinate[c.length];
        for (int i = 0; i < c.length; i++) {
            r[i] = c[i].copy();
        }
        return r;
    }

    /** shapely {@code remove_repeated_points(geom, tolerance)} (GEOS {@code GeometryEditor} 와 같은 구조 보존). */
    public static Geometry removeRepeatedPoints(Geometry g, double tolerance) {
        GeometryFactory f = g.getFactory();
        if (g instanceof Point) {
            return g.copy();
        }
        if (g instanceof LinearRing r) {
            Coordinate[] c = edit(r.getCoordinates(), LinearRing.MINIMUM_VALID_SIZE, tolerance);
            return c == null ? f.createLinearRing() : f.createLinearRing(c);
        }
        if (g instanceof LineString l) {
            Coordinate[] c = edit(l.getCoordinates(), 2, tolerance);
            return c == null ? f.createLineString() : f.createLineString(c);
        }
        if (g instanceof Polygon p) {
            if (p.isEmpty()) {
                return p.copy();
            }
            LinearRing shell = (LinearRing) removeRepeatedPoints(p.getExteriorRing(), tolerance);
            if (shell.isEmpty()) {
                return f.createPolygon();
            }
            List<LinearRing> holes = new ArrayList<>();
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                LinearRing h = (LinearRing) removeRepeatedPoints(p.getInteriorRingN(i), tolerance);
                if (!h.isEmpty()) {
                    holes.add(h);
                }
            }
            return f.createPolygon(shell, holes.toArray(new LinearRing[0]));
        }
        if (g instanceof MultiPolygon mp) {
            List<Polygon> parts = new ArrayList<>();
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Geometry e = removeRepeatedPoints(mp.getGeometryN(i), tolerance);
                if (!e.isEmpty()) {
                    parts.add((Polygon) e);
                }
            }
            return f.createMultiPolygon(parts.toArray(new Polygon[0]));
        }
        if (g instanceof MultiLineString ml) {
            List<LineString> parts = new ArrayList<>();
            for (int i = 0; i < ml.getNumGeometries(); i++) {
                Geometry e = removeRepeatedPoints(ml.getGeometryN(i), tolerance);
                if (!e.isEmpty()) {
                    parts.add((LineString) e);
                }
            }
            return f.createMultiLineString(parts.toArray(new LineString[0]));
        }
        return g.copy();
    }
}
