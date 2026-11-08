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
 * Modified for AutoParkingPlan: logic follows GEOS 3.14.1 (include/geos/operation/buffer/OffsetSegmentString.h).
 */
package com.autoparkingplan.parking.geom.buffer;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.PrecisionModel;

import com.autoparkingplan.parking.geom.GeosMath;

/** 오프셋 곡선 정점 목록. GEOS 처럼 좌표를 꺼낼 때 링을 닫고, 근접 판정 거리는 sqrt 로 계산한다. */
class OffsetSegmentString {
    private final List<Coordinate> ptList = new ArrayList<>();
    private PrecisionModel precisionModel = null;
    private double minimumVertexDistance = 0.0;

    void setPrecisionModel(PrecisionModel precisionModel) {
        this.precisionModel = precisionModel;
    }

    void setMinimumVertexDistance(double minimumVertexDistance) {
        this.minimumVertexDistance = minimumVertexDistance;
    }

    void addPt(Coordinate pt) {
        Coordinate bufPt = new Coordinate(pt.x, pt.y);
        precisionModel.makePrecise(bufPt);
        if (isRedundant(bufPt)) {
            return;
        }
        ptList.add(bufPt);
    }

    void addPts(Coordinate[] pt, boolean isForward) {
        if (isForward) {
            for (Coordinate c : pt) {
                addPt(c);
            }
        } else {
            for (int i = pt.length - 1; i >= 0; i--) {
                addPt(pt[i]);
            }
        }
    }

    private boolean isRedundant(Coordinate pt) {
        if (ptList.isEmpty()) {
            return false;
        }
        Coordinate lastPt = ptList.get(ptList.size() - 1);
        return GeosMath.distance(pt, lastPt) < minimumVertexDistance;
    }

    void closeRing() {
        if (ptList.isEmpty()) {
            return;
        }
        Coordinate startPt = ptList.get(0);
        Coordinate lastPt = ptList.get(ptList.size() - 1);
        if (startPt.equals2D(lastPt)) {
            return;
        }
        ptList.add(new Coordinate(startPt.x, startPt.y));
    }

    Coordinate[] getCoordinates() {
        closeRing();
        return ptList.toArray(new Coordinate[0]);
    }
}
