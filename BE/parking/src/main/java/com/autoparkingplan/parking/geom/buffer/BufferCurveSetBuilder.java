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
 * Modified for AutoParkingPlan: logic follows GEOS 3.14.1 (src/operation/buffer/BufferCurveSetBuilder.cpp).
 */
package com.autoparkingplan.parking.geom.buffer;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateArrays;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Position;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.Triangle;
import org.locationtech.jts.geomgraph.Label;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.SegmentString;
import org.locationtech.jts.operation.buffer.BufferParameters;

import com.autoparkingplan.parking.geom.GeosMath;
import com.autoparkingplan.parking.geom.RepeatedPointRemover;

/** 입력 기하의 버퍼 곡선(라벨 붙은 선분열) 생성 (GEOS 판). */
class BufferCurveSetBuilder {
    private static final int MAX_INVERTED_RING_SIZE = 9;
    private static final int INVERTED_CURVE_VERTEX_FACTOR = 4;
    private static final double NEARNESS_FACTOR = 0.99;

    private final Geometry inputGeom;
    private final double distance;
    private final OffsetCurveBuilder curveBuilder;
    private final List<SegmentString> curveList = new ArrayList<>();
    private boolean isInvertOrientation = false;

    BufferCurveSetBuilder(Geometry inputGeom, double distance, PrecisionModel precisionModel, BufferParameters bufParams) {
        this.inputGeom = inputGeom;
        this.distance = distance;
        this.curveBuilder = new OffsetCurveBuilder(precisionModel, bufParams);
    }

    void setInvertOrientation(boolean isInvertOrientation) {
        this.isInvertOrientation = isInvertOrientation;
    }

    List<SegmentString> getCurves() {
        add(inputGeom);
        return curveList;
    }

    private void addCurves(List<Coordinate[]> lineList, int leftLoc, int rightLoc) {
        for (Coordinate[] coords : lineList) {
            addCurve(coords, leftLoc, rightLoc);
        }
    }

    private void addCurve(Coordinate[] coord, int leftLoc, int rightLoc) {
        if (coord == null || coord.length < 2) {
            return;
        }
        SegmentString e = new NodedSegmentString(coord, new Label(0, Location.BOUNDARY, leftLoc, rightLoc));
        curveList.add(e);
    }

    private void add(Geometry g) {
        if (g.isEmpty()) {
            return;
        }
        if (g instanceof Polygon p) {
            addPolygon(p);
        } else if (g instanceof LineString l) {
            addLineString(l);
        } else if (g instanceof Point p) {
            addPoint(p);
        } else if (g instanceof GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                add(gc.getGeometryN(i));
            }
        } else {
            throw new UnsupportedOperationException(g.getClass().getName());
        }
    }

    private void addPoint(Point p) {
        if (distance <= 0.0) {
            return;
        }
        Coordinate[] coord = p.getCoordinates();
        if (coord.length >= 1 && !coord[0].isValid()) {
            return;
        }
        List<Coordinate[]> lineList = new ArrayList<>();
        curveBuilder.getLineCurve(coord, distance, lineList);
        addCurves(lineList, Location.EXTERIOR, Location.INTERIOR);
    }

    private void addLineString(LineString line) {
        if (curveBuilder.isLineOffsetEmpty(distance)) {
            return;
        }
        Coordinate[] coord = RepeatedPointRemover.removeRepeatedAndInvalidPoints(line.getCoordinates(), 0.0);
        if (coord.length > 0 && CoordinateArrays.isRing(coord) && !curveBuilder.getBufferParameters().isSingleSided()) {
            addLinearRingSides(coord, distance);
        } else {
            List<Coordinate[]> lineList = new ArrayList<>();
            curveBuilder.getLineCurve(coord, distance, lineList);
            addCurves(lineList, Location.EXTERIOR, Location.INTERIOR);
        }
    }

    private void addLinearRingSides(Coordinate[] coord, double pDistance) {
        Envelope env = new Envelope();
        for (Coordinate c : coord) {
            env.expandToInclude(c);
        }
        boolean isHoleComputed = !isRingFullyEroded(coord, env, true, distance);
        boolean isCCW = isRingCCW(coord);
        boolean isShellLeft = !isCCW;
        if (isShellLeft || isHoleComputed) {
            addRingSide(coord, pDistance, Position.LEFT, Location.EXTERIOR, Location.INTERIOR);
        }
        boolean isShellRight = isCCW;
        if (isShellRight || isHoleComputed) {
            addRingSide(coord, pDistance, Position.RIGHT, Location.INTERIOR, Location.EXTERIOR);
        }
    }

    private void addPolygon(Polygon p) {
        double offsetDistance = distance;
        int offsetSide = Position.LEFT;
        if (distance < 0.0) {
            offsetDistance = -distance;
            offsetSide = Position.RIGHT;
        }
        LinearRing shell = p.getExteriorRing();
        if (distance < 0.0 && isRingFullyEroded(shell, false, distance)) {
            return;
        }
        Coordinate[] shellCoord = RepeatedPointRemover.removeRepeatedAndInvalidPoints(shell.getCoordinates(), 0.0);
        if (shellCoord.length == 0) {
            throw new IllegalStateException("Shell empty after removing invalid points");
        }
        if (distance <= 0.0 && shellCoord.length < 3) {
            return;
        }
        addPolygonRingSide(shellCoord, offsetDistance, offsetSide, Location.EXTERIOR, Location.INTERIOR);
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            LinearRing hole = p.getInteriorRingN(i);
            if (distance > 0.0 && isRingFullyEroded(hole, true, distance)) {
                continue;
            }
            Coordinate[] holeCoord = RepeatedPointRemover.removeRepeatedAndInvalidPoints(hole.getCoordinates(), 0.0);
            addPolygonRingSide(holeCoord, offsetDistance, Position.opposite(offsetSide), Location.INTERIOR, Location.EXTERIOR);
        }
    }

    private void addPolygonRingSide(Coordinate[] coord, double offsetDistance, int side, int cwLeftLoc, int cwRightLoc) {
        if (offsetDistance == 0.0 && coord.length < LinearRing.MINIMUM_VALID_SIZE) {
            return;
        }
        int leftLoc = cwLeftLoc;
        int rightLoc = cwRightLoc;
        boolean isCCW = isRingCCW(coord);
        if (coord.length >= LinearRing.MINIMUM_VALID_SIZE && isCCW) {
            leftLoc = cwRightLoc;
            rightLoc = cwLeftLoc;
            side = Position.opposite(side);
        }
        addRingSide(coord, offsetDistance, side, leftLoc, rightLoc);
    }

    private void addRingSide(Coordinate[] coord, double offsetDistance, int side, int leftLoc, int rightLoc) {
        List<Coordinate[]> lineList = new ArrayList<>();
        curveBuilder.getRingCurve(coord, side, offsetDistance, lineList);
        if (!lineList.isEmpty()) {
            Coordinate[] curve = lineList.get(0);
            if (isRingCurveInverted(coord, offsetDistance, curve)) {
                return;
            }
        }
        addCurves(lineList, leftLoc, rightLoc);
    }

    private static boolean isRingCurveInverted(Coordinate[] inputRing, double dist, Coordinate[] curveRing) {
        if (dist == 0.0) {
            return false;
        }
        if (inputRing.length <= 3) {
            return false;
        }
        if (inputRing.length >= MAX_INVERTED_RING_SIZE) {
            return false;
        }
        if (curveRing.length > INVERTED_CURVE_VERTEX_FACTOR * inputRing.length) {
            return false;
        }
        return !hasPointOnBuffer(inputRing, dist, curveRing);
    }

    private static boolean hasPointOnBuffer(Coordinate[] inputRing, double dist, Coordinate[] curveRing) {
        double distTol = NEARNESS_FACTOR * Math.abs(dist);
        for (int i = 0; i < curveRing.length; i++) {
            Coordinate v = curveRing[i];
            double distVertex = GeosMath.pointToSegmentString(v, inputRing);
            if (distVertex > distTol) {
                return true;
            }
            int iNext = (i < curveRing.length - 1) ? i + 1 : 0;
            Coordinate vnext = curveRing[iNext];
            Coordinate midPt = LineSegment.midPoint(v, vnext);
            double distMid = GeosMath.pointToSegmentString(midPt, inputRing);
            if (distMid > distTol) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRingFullyEroded(LinearRing ring, boolean isHole, double bufferDistance) {
        return isRingFullyEroded(ring.getCoordinates(), ring.getEnvelopeInternal(), isHole, bufferDistance);
    }

    private static boolean isRingFullyEroded(Coordinate[] ringCoord, Envelope env, boolean isHole, double bufferDistance) {
        if (ringCoord.length < 4) {
            return true;
        }
        if (ringCoord.length == 4) {
            return isTriangleErodedCompletely(ringCoord, bufferDistance);
        }
        boolean isErodable = (isHole && bufferDistance > 0) || (!isHole && bufferDistance < 0);
        if (isErodable) {
            double envMinDimension = Math.min(env.getHeight(), env.getWidth());
            return 2 * Math.abs(bufferDistance) > envMinDimension;
        }
        return false;
    }

    private static boolean isTriangleErodedCompletely(Coordinate[] triangleCoord, double bufferDistance) {
        Triangle tri = new Triangle(triangleCoord[0], triangleCoord[1], triangleCoord[2]);
        Coordinate inCentre = GeosMath.triangleInCentre(tri.p0, tri.p1, tri.p2);
        double distToCentre = GeosMath.pointToSegment(inCentre, tri.p0, tri.p1);
        return distToCentre < Math.abs(bufferDistance);
    }

    private boolean isRingCCW(Coordinate[] coords) {
        boolean isCCW = Orientation.isCCWArea(coords);
        if (isInvertOrientation) {
            return !isCCW;
        }
        return isCCW;
    }
}
