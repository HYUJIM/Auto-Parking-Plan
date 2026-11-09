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
 * Modified for AutoParkingPlan: logic follows GEOS 3.14.1 (src/operation/buffer/OffsetCurveBuilder.cpp).
 * Single-sided buffers are not supported (never used by the parking engine).
 */
package com.autoparkingplan.parking.geom.buffer;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Position;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.buffer.BufferParameters;

/** 선·링의 버퍼 곡선 생성 (GEOS 판: 단순화 허용오차 = 거리 / 100). */
class OffsetCurveBuilder {
    private static final double SIMPLIFY_FACTOR = 100.0;

    private double distance = 0.0;
    private final PrecisionModel precisionModel;
    private final BufferParameters bufParams;

    OffsetCurveBuilder(PrecisionModel precisionModel, BufferParameters bufParams) {
        this.precisionModel = precisionModel;
        this.bufParams = bufParams;
        if (bufParams.isSingleSided()) {
            throw new UnsupportedOperationException("single-sided buffer is not supported");
        }
    }

    BufferParameters getBufferParameters() {
        return bufParams;
    }

    /** GEOS {@code getLineCurve(inputPts, distance, lineList)}. */
    void getLineCurve(Coordinate[] inputPts, double nDistance, List<Coordinate[]> lineList) {
        distance = nDistance;
        if (isLineOffsetEmpty(distance)) {
            return;
        }
        double posDistance = Math.abs(distance);
        OffsetSegmentGenerator segGen = new OffsetSegmentGenerator(precisionModel, bufParams, posDistance);
        if (inputPts.length <= 1) {
            computePointCurve(inputPts[0], segGen);
        } else {
            computeLineBufferCurve(inputPts, segGen);
        }
        lineList.add(segGen.getCoordinates());
    }

    boolean isLineOffsetEmpty(double pDistance) {
        if (pDistance == 0.0) {
            return true;
        }
        return pDistance < 0.0 && !bufParams.isSingleSided();
    }

    /** GEOS {@code getRingCurve(inputPts, side, distance, lineList)}. */
    void getRingCurve(Coordinate[] inputPts, int side, double nDistance, List<Coordinate[]> lineList) {
        distance = nDistance;
        if (distance == 0.0) {
            Coordinate[] copy = new Coordinate[inputPts.length];
            for (int i = 0; i < inputPts.length; i++) {
                copy[i] = inputPts[i].copy();
            }
            lineList.add(copy);
            return;
        }
        if (inputPts.length <= 2) {
            getLineCurve(inputPts, distance, lineList);
            return;
        }
        OffsetSegmentGenerator segGen = new OffsetSegmentGenerator(precisionModel, bufParams, Math.abs(distance));
        computeRingBufferCurve(inputPts, side, segGen);
        lineList.add(segGen.getCoordinates());
    }

    private void computePointCurve(Coordinate pt, OffsetSegmentGenerator segGen) {
        switch (bufParams.getEndCapStyle()) {
            case BufferParameters.CAP_ROUND -> segGen.createCircle(pt, distance);
            case BufferParameters.CAP_SQUARE -> segGen.createSquare(pt, distance);
            default -> {
                // otherwise curve is empty (e.g. for a butt cap)
            }
        }
    }

    private static double simplifyTolerance(double bufDistance) {
        return bufDistance / SIMPLIFY_FACTOR;
    }

    private void computeLineBufferCurve(Coordinate[] inputPts, OffsetSegmentGenerator segGen) {
        double distTol = simplifyTolerance(distance);

        Coordinate[] simp1 = BufferInputLineSimplifier.simplify(inputPts, distTol);
        int n1 = simp1.length - 1;
        segGen.initSideSegments(simp1[0], simp1[1], Position.LEFT);
        for (int i = 2; i <= n1; ++i) {
            segGen.addNextSegment(simp1[i], true);
        }
        segGen.addLastSegment();
        segGen.addLineEndCap(simp1[n1 - 1], simp1[n1]);

        Coordinate[] simp2 = BufferInputLineSimplifier.simplify(inputPts, -distTol);
        int n2 = simp2.length - 1;
        segGen.initSideSegments(simp2[n2], simp2[n2 - 1], Position.LEFT);
        for (int i = n2 - 1; i > 0; --i) {
            segGen.addNextSegment(simp2[i - 1], true);
        }
        segGen.addLastSegment();
        segGen.addLineEndCap(simp2[1], simp2[0]);
        segGen.closeRing();
    }

    private void computeRingBufferCurve(Coordinate[] inputPts, int side, OffsetSegmentGenerator segGen) {
        double distTol = simplifyTolerance(distance);
        if (side == Position.RIGHT) {
            distTol = -distTol;
        }
        Coordinate[] simp = BufferInputLineSimplifier.simplify(inputPts, distTol);
        int n = simp.length - 1;
        segGen.initSideSegments(simp[n - 1], simp[0], side);
        for (int i = 1; i <= n; i++) {
            boolean addStartPoint = i != 1;
            segGen.addNextSegment(simp[i], addStartPoint);
        }
        segGen.closeRing();
    }
}
