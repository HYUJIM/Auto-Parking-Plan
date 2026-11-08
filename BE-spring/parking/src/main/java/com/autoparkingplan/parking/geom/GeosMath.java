package com.autoparkingplan.parking.geom;

import org.locationtech.jts.geom.Coordinate;

/**
 * GEOS(파이썬 shapely 의 기하 엔진)와 같은 방식의 기본 수치 계산.
 *
 * <p>JTS 의 {@code Coordinate.distance} 는 {@code Math.hypot} 을 쓰지만 GEOS 는 {@code sqrt(dx*dx + dy*dy)} 를 쓴다.
 * 마지막 자리 차이가 길이 누적·투영 위치에 번지지 않도록 GEOS 와 같은 식을 쓴다.
 * 삼각함수는 플랫폼과 무관하게 같은 값을 내는 {@link StrictMath} 를 쓴다.
 */
public final class GeosMath {
    private GeosMath() {
    }

    /** GEOS {@code CoordinateXY::distance}. */
    public static double distance(Coordinate a, Coordinate b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** GEOS {@code Angle::sinCosSnap}: [sin, cos], 5e-16 미만은 0. */
    public static double[] sinCosSnap(double ang) {
        double s = StrictMath.sin(ang);
        double c = StrictMath.cos(ang);
        if (Math.abs(s) < 5e-16) {
            s = 0.0;
        }
        if (Math.abs(c) < 5e-16) {
            c = 0.0;
        }
        return new double[] {s, c};
    }

    /** GEOS {@code Distance::pointToSegment}. */
    public static double pointToSegment(Coordinate p, Coordinate a, Coordinate b) {
        if (a.x == b.x && a.y == b.y) {
            return distance(p, a);
        }
        double r = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y))
                / ((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y));
        if (r <= 0.0) {
            return distance(p, a);
        }
        if (r >= 1.0) {
            return distance(p, b);
        }
        double s = ((a.y - p.y) * (b.x - a.x) - (a.x - p.x) * (b.y - a.y))
                / ((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y));
        return Math.abs(s) * Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y));
    }

    /** GEOS {@code Distance::pointToSegmentString}. */
    public static double pointToSegmentString(Coordinate p, Coordinate[] seq) {
        if (seq.length == 0) {
            throw new IllegalArgumentException("Line array must contain at least one vertex");
        }
        double minDistance = distance(p, seq[0]);
        for (int i = 0; i < seq.length - 1; i++) {
            double dist = pointToSegment(p, seq[i], seq[i + 1]);
            if (dist < minDistance) {
                minDistance = dist;
            }
        }
        return minDistance;
    }

    /** GEOS {@code LineSegment::projectionFactor}. */
    public static double projectionFactor(Coordinate p0, Coordinate p1, Coordinate p) {
        if (p.equals2D(p0)) {
            return 0.0;
        }
        if (p.equals2D(p1)) {
            return 1.0;
        }
        if (p0.equals2D(p1)) {
            return 0.0;
        }
        double dx = p1.x - p0.x;
        double dy = p1.y - p0.y;
        double len2 = dx * dx + dy * dy;
        return ((p.x - p0.x) * dx + (p.y - p0.y) * dy) / len2;
    }

    /** GEOS {@code Triangle::inCentre} (변 길이는 sqrt). */
    public static Coordinate triangleInCentre(Coordinate a, Coordinate b, Coordinate c) {
        double len0 = distance(b, c);
        double len1 = distance(a, c);
        double len2 = distance(a, b);
        double circum = len0 + len1 + len2;
        double inCentreX = (len0 * a.x + len1 * b.x + len2 * c.x) / circum;
        double inCentreY = (len0 * a.y + len1 * b.y + len2 * c.y) / circum;
        return new Coordinate(inCentreX, inCentreY);
    }
}
