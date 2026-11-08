package com.autoparkingplan.parking;

import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * 대안 하나의 내부 기하 (EPSG:5179, 검증·디버깅용). {@code result.alternatives} 와 같은 순서. 파이썬 {@code engine.LayoutGeometry}.
 */
public record LayoutGeometry(
        String altCode,
        String parkingAngle,
        double orientationDeg,
        Polygon site,
        Polygon usable,
        List<Polygon> stalls,
        List<LineString> accessEdges,
        List<Aisle> aisles,
        Polygon aisleUnion,
        Point gatePoint,
        Geometry gateOpening,
        double aisleWidthM,
        double drivewayWidthM,
        double stallWidthM,
        double stallLengthM,
        double thetaDeg,
        double rowDepthM,
        double pitchM) {

    /**
     * 차로 다각형 하나 (파이썬 튜플 {@code (kind, polygon, width_m)}).
     *
     * @param kind    aisle | spine | driveway
     * @param polygon 다각형 (EPSG:5179)
     * @param widthM  폭
     */
    public record Aisle(String kind, Polygon polygon, double widthM) {
    }
}
