/*
 * Copyright (c) 2016 Vivid Solutions.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Modified for AutoParkingPlan: logic follows GEOS 3.14.1 (src/operation/buffer/OffsetSegmentGenerator.cpp),
 * which shapely 2.2 uses, so that buffer output matches the Python implementation.
 */
package com.autoparkingplan.parking.geom.buffer;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.CGAlgorithmsDD;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.Intersection;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.Position;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.buffer.BufferParameters;

import com.autoparkingplan.parking.geom.GeosMath;

/**
 * 오프셋 곡선의 선분·모서리 생성 (GEOS 3.14.1 판과 같은 상수·분기).
 *
 * <p>JTS 1.20 과 다른 점: 바깥 꺾임 근접 판정 계수(1e-3, JTS .05), 곡선 정점 최소 간격 계수(1e-4, JTS 1e-6),
 * 마이터 교점을 double-double 로 계산, 거리 계산은 {@code sqrt(dx*dx+dy*dy)}, 제한 마이터는 바깥 이등분선 방향 투영.
 */
class OffsetSegmentGenerator {
    private static final double OFFSET_SEGMENT_SEPARATION_FACTOR = 1.0E-3;
    private static final double INSIDE_TURN_VERTEX_SNAP_DISTANCE_FACTOR = 1.0E-3;
    private static final double CURVE_VERTEX_SNAP_DISTANCE_FACTOR = 1.0E-4;
    private static final int MAX_CLOSING_SEG_LEN_FACTOR = 80;

    private double filletAngleQuantum;
    private int closingSegLengthFactor = 1;
    private OffsetSegmentString segList;
    private double distance = 0.0;
    private final PrecisionModel precisionModel;
    private final BufferParameters bufParams;
    private final LineIntersector li;

    private Coordinate s0 = new Coordinate();
    private Coordinate s1 = new Coordinate();
    private Coordinate s2 = new Coordinate();
    private final LineSegment seg0 = new LineSegment();
    private final LineSegment seg1 = new LineSegment();
    private final LineSegment offset0 = new LineSegment();
    private final LineSegment offset1 = new LineSegment();
    private int side = 0;
    private boolean hasNarrowConcaveAngle = false;

    OffsetSegmentGenerator(PrecisionModel precisionModel, BufferParameters bufParams, double distance) {
        this.precisionModel = precisionModel;
        this.bufParams = bufParams;
        li = new RobustLineIntersector();
        int quadSegs = bufParams.getQuadrantSegments();
        if (quadSegs < 1) {
            quadSegs = 1;
        }
        filletAngleQuantum = Angle.PI_OVER_2 / quadSegs;
        if (bufParams.getQuadrantSegments() >= 8 && bufParams.getJoinStyle() == BufferParameters.JOIN_ROUND) {
            closingSegLengthFactor = MAX_CLOSING_SEG_LEN_FACTOR;
        }
        init(distance);
    }

    boolean hasNarrowConcaveAngle() {
        return hasNarrowConcaveAngle;
    }

    private void init(double newDistance) {
        distance = newDistance;
        segList = new OffsetSegmentString();
        segList.setPrecisionModel(precisionModel);
        segList.setMinimumVertexDistance(distance * CURVE_VERTEX_SNAP_DISTANCE_FACTOR);
    }

    void initSideSegments(Coordinate nS1, Coordinate nS2, int nSide) {
        s1 = nS1;
        s2 = nS2;
        side = nSide;
        seg1.setCoordinates(s1, s2);
        computeOffsetSegment(seg1, side, distance, offset1);
    }

    /** GEOS: 좌표를 꺼낼 때 링을 닫는다. */
    Coordinate[] getCoordinates() {
        return segList.getCoordinates();
    }

    void closeRing() {
        segList.closeRing();
    }

    void addSegments(Coordinate[] pt, boolean isForward) {
        segList.addPts(pt, isForward);
    }

    void addFirstSegment() {
        segList.addPt(offset1.p0);
    }

    void addLastSegment() {
        segList.addPt(offset1.p1);
    }

    void addNextSegment(Coordinate p, boolean addStartPoint) {
        // do nothing if points are equal
        if (s2.equals2D(p)) {
            return;
        }
        s0 = s1;
        s1 = s2;
        s2 = p;
        seg0.setCoordinates(s0, s1);
        computeOffsetSegment(seg0, side, distance, offset0);
        seg1.setCoordinates(s1, s2);
        computeOffsetSegment(seg1, side, distance, offset1);

        int orientation = Orientation.index(s0, s1, s2);
        boolean outsideTurn = (orientation == Orientation.CLOCKWISE && side == Position.LEFT)
                || (orientation == Orientation.COUNTERCLOCKWISE && side == Position.RIGHT);
        if (orientation == 0) {
            addCollinear(addStartPoint);
        } else if (outsideTurn) {
            addOutsideTurn(orientation, addStartPoint);
        } else {
            addInsideTurn(orientation, addStartPoint);
        }
    }

    private void addCollinear(boolean addStartPoint) {
        li.computeIntersection(s0, s1, s1, s2);
        int numInt = li.getIntersectionNum();
        if (numInt >= 2) {
            if (bufParams.getJoinStyle() == BufferParameters.JOIN_BEVEL
                    || bufParams.getJoinStyle() == BufferParameters.JOIN_MITRE) {
                if (addStartPoint) {
                    segList.addPt(offset0.p1);
                }
                segList.addPt(offset1.p0);
            } else {
                addDirectedFillet(s1, offset0.p1, offset1.p0, Orientation.CLOCKWISE, distance);
            }
        }
    }

    private void addOutsideTurn(int orientation, boolean addStartPoint) {
        if (GeosMath.distance(offset0.p1, offset1.p0) < distance * OFFSET_SEGMENT_SEPARATION_FACTOR) {
            segList.addPt(offset0.p1);
            return;
        }
        if (bufParams.getJoinStyle() == BufferParameters.JOIN_MITRE) {
            addMitreJoin(s1, offset0, offset1, distance);
        } else if (bufParams.getJoinStyle() == BufferParameters.JOIN_BEVEL) {
            addBevelJoin(offset0, offset1);
        } else {
            if (addStartPoint) {
                segList.addPt(offset0.p1);
            }
            addDirectedFillet(s1, offset0.p1, offset1.p0, orientation, distance);
            segList.addPt(offset1.p0);
        }
    }

    private void addInsideTurn(int orientation, boolean addStartPoint) {
        li.computeIntersection(offset0.p0, offset0.p1, offset1.p0, offset1.p1);
        if (li.hasIntersection()) {
            segList.addPt(li.getIntersection(0));
            return;
        }
        hasNarrowConcaveAngle = true;
        if (GeosMath.distance(offset0.p1, offset1.p0) < distance * INSIDE_TURN_VERTEX_SNAP_DISTANCE_FACTOR) {
            segList.addPt(offset0.p1);
        } else {
            segList.addPt(offset0.p1);
            if (closingSegLengthFactor > 0) {
                Coordinate mid0 = new Coordinate(
                        (closingSegLengthFactor * offset0.p1.x + s1.x) / (closingSegLengthFactor + 1),
                        (closingSegLengthFactor * offset0.p1.y + s1.y) / (closingSegLengthFactor + 1));
                segList.addPt(mid0);
                Coordinate mid1 = new Coordinate(
                        (closingSegLengthFactor * offset1.p0.x + s1.x) / (closingSegLengthFactor + 1),
                        (closingSegLengthFactor * offset1.p0.y + s1.y) / (closingSegLengthFactor + 1));
                segList.addPt(mid1);
            } else {
                segList.addPt(s1);
            }
            segList.addPt(offset1.p0);
        }
    }

    static void computeOffsetSegment(LineSegment seg, int side, double distance, LineSegment offset) {
        int sideSign = side == Position.LEFT ? 1 : -1;
        double dx = seg.p1.x - seg.p0.x;
        double dy = seg.p1.y - seg.p0.y;
        double len = Math.sqrt(dx * dx + dy * dy);
        double ux = sideSign * distance * dx / len;
        double uy = sideSign * distance * dy / len;
        offset.p0.x = seg.p0.x - uy;
        offset.p0.y = seg.p0.y + ux;
        offset.p1.x = seg.p1.x - uy;
        offset.p1.y = seg.p1.y + ux;
    }

    void addLineEndCap(Coordinate p0, Coordinate p1) {
        LineSegment seg = new LineSegment(p0, p1);
        LineSegment offsetL = new LineSegment();
        computeOffsetSegment(seg, Position.LEFT, distance, offsetL);
        LineSegment offsetR = new LineSegment();
        computeOffsetSegment(seg, Position.RIGHT, distance, offsetR);
        double dx = p1.x - p0.x;
        double dy = p1.y - p0.y;
        double angle = StrictMath.atan2(dy, dx);
        switch (bufParams.getEndCapStyle()) {
            case BufferParameters.CAP_ROUND -> {
                segList.addPt(offsetL.p1);
                addDirectedFillet(p1, angle + Angle.PI_OVER_2, angle - Angle.PI_OVER_2, Orientation.CLOCKWISE, distance);
                segList.addPt(offsetR.p1);
            }
            case BufferParameters.CAP_FLAT -> {
                segList.addPt(offsetL.p1);
                segList.addPt(offsetR.p1);
            }
            case BufferParameters.CAP_SQUARE -> {
                double[] sc = GeosMath.sinCosSnap(angle);
                double sx = Math.abs(distance) * sc[1];
                double sy = Math.abs(distance) * sc[0];
                segList.addPt(new Coordinate(offsetL.p1.x + sx, offsetL.p1.y + sy));
                segList.addPt(new Coordinate(offsetR.p1.x + sx, offsetR.p1.y + sy));
            }
            default -> {
            }
        }
    }

    private void addMitreJoin(Coordinate cornerPt, LineSegment pOffset0, LineSegment pOffset1, double pDistance) {
        double mitreLimitDistance = bufParams.getMitreLimit() * pDistance;
        Coordinate intPt = CGAlgorithmsDD.intersection(pOffset0.p0, pOffset0.p1, pOffset1.p0, pOffset1.p1);
        if (intPt != null && GeosMath.distance(intPt, cornerPt) <= mitreLimitDistance) {
            segList.addPt(intPt);
            return;
        }
        double bevelDist = Distance.pointToSegment(cornerPt, pOffset0.p1, pOffset1.p0);
        if (bevelDist >= mitreLimitDistance) {
            addBevelJoin(pOffset0, pOffset1);
            return;
        }
        addLimitedMitreJoin(pOffset0, pOffset1, pDistance, mitreLimitDistance);
    }

    private void addLimitedMitreJoin(LineSegment pOffset0, LineSegment pOffset1, double pDistance, double pMitreLimitDistance) {
        Coordinate cornerPt = seg0.p1;
        double angInterior = Angle.angleBetweenOriented(seg0.p0, cornerPt, seg1.p1);
        double angInterior2 = angInterior / 2.0;
        double dir0 = Angle.angle(cornerPt, seg0.p0);
        double dirBisector = Angle.normalize(dir0 + angInterior2);
        double dirBisectorOut = Angle.normalize(dirBisector + Math.PI);
        Coordinate bevelMidPt = project(cornerPt, pMitreLimitDistance, dirBisectorOut);
        double dirBevel = Angle.normalize(dirBisectorOut + Angle.PI_OVER_2);
        Coordinate bevel0 = project(bevelMidPt, pDistance, dirBevel);
        Coordinate bevel1 = project(bevelMidPt, pDistance, dirBevel + Math.PI);
        Coordinate bevelInt0 = Intersection.lineSegment(pOffset0.p0, pOffset0.p1, bevel0, bevel1);
        Coordinate bevelInt1 = Intersection.lineSegment(pOffset1.p0, pOffset1.p1, bevel0, bevel1);
        if (bevelInt0 != null && bevelInt1 != null) {
            segList.addPt(bevelInt0);
            segList.addPt(bevelInt1);
            return;
        }
        addBevelJoin(pOffset0, pOffset1);
    }

    private static Coordinate project(Coordinate pt, double d, double dir) {
        double[] sc = GeosMath.sinCosSnap(dir);
        return new Coordinate(pt.x + d * sc[1], pt.y + d * sc[0]);
    }

    private void addBevelJoin(LineSegment pOffset0, LineSegment pOffset1) {
        segList.addPt(pOffset0.p1);
        segList.addPt(pOffset1.p0);
    }

    private void addDirectedFillet(Coordinate p, Coordinate p0, Coordinate p1, int direction, double radius) {
        double dx0 = p0.x - p.x;
        double dy0 = p0.y - p.y;
        double startAngle = StrictMath.atan2(dy0, dx0);
        double dx1 = p1.x - p.x;
        double dy1 = p1.y - p.y;
        double endAngle = StrictMath.atan2(dy1, dx1);
        if (direction == Orientation.CLOCKWISE) {
            if (startAngle <= endAngle) {
                startAngle += Angle.PI_TIMES_2;
            }
        } else {
            if (startAngle >= endAngle) {
                startAngle -= Angle.PI_TIMES_2;
            }
        }
        segList.addPt(p0);
        addDirectedFillet(p, startAngle, endAngle, direction, radius);
        segList.addPt(p1);
    }

    private void addDirectedFillet(Coordinate p, double startAngle, double endAngle, int direction, double radius) {
        int directionFactor = direction == Orientation.CLOCKWISE ? -1 : 1;
        double totalAngle = Math.abs(startAngle - endAngle);
        int nSegs = (int) (totalAngle / filletAngleQuantum + 0.5);
        if (nSegs < 1) {
            return;
        }
        double angleInc = totalAngle / nSegs;
        Coordinate pt = new Coordinate();
        for (int i = 0; i < nSegs; i++) {
            double[] sc = GeosMath.sinCosSnap(startAngle + directionFactor * i * angleInc);
            pt.x = p.x + radius * sc[1];
            pt.y = p.y + radius * sc[0];
            segList.addPt(pt);
        }
    }

    void createCircle(Coordinate p, double pDistance) {
        Coordinate pt = new Coordinate(p.x + pDistance, p.y);
        segList.addPt(pt);
        addDirectedFillet(p, 0.0, Angle.PI_TIMES_2, -1, pDistance);
        segList.closeRing();
    }

    void createSquare(Coordinate p, double pDistance) {
        segList.addPt(new Coordinate(p.x + pDistance, p.y + pDistance));
        segList.addPt(new Coordinate(p.x + pDistance, p.y - pDistance));
        segList.addPt(new Coordinate(p.x - pDistance, p.y - pDistance));
        segList.addPt(new Coordinate(p.x - pDistance, p.y + pDistance));
        segList.closeRing();
    }
}
