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
 * Modified for AutoParkingPlan: logic follows GEOS 3.14.1 (src/operation/buffer/BufferInputLineSimplifier.cpp),
 * including its argument order in isShallowSampled and starting index 1 for rings.
 */
package com.autoparkingplan.parking.geom.buffer;

import java.util.ArrayList;
import java.util.List;

import com.autoparkingplan.parking.geom.GeosMath;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;

/** 버퍼 입력선의 얕은 오목부 단순화 (GEOS 판). */
final class BufferInputLineSimplifier {
    private static final int NUM_PTS_TO_CHECK = 10;

    private final Coordinate[] inputLine;
    private double distanceTol;
    private boolean[] isDeleted;
    private int angleOrientation = Orientation.COUNTERCLOCKWISE;

    private BufferInputLineSimplifier(Coordinate[] inputLine) {
        this.inputLine = inputLine;
    }

    static Coordinate[] simplify(Coordinate[] inputLine, double distanceTol) {
        return new BufferInputLineSimplifier(inputLine).simplify(distanceTol);
    }

    private Coordinate[] simplify(double nDistanceTol) {
        distanceTol = Math.abs(nDistanceTol);
        if (nDistanceTol < 0) {
            angleOrientation = Orientation.CLOCKWISE;
        }
        isDeleted = new boolean[inputLine.length];
        boolean isChanged;
        do {
            isChanged = deleteShallowConcavities();
        } while (isChanged);
        return collapseLine();
    }

    private boolean deleteShallowConcavities() {
        int index = 1;
        int midIndex = findNextNonDeletedIndex(index);
        int lastIndex = findNextNonDeletedIndex(midIndex);
        boolean isChanged = false;
        while (lastIndex < inputLine.length) {
            boolean isMiddleVertexDeleted = false;
            if (isDeletable(index, midIndex, lastIndex, distanceTol)) {
                isDeleted[midIndex] = true;
                isMiddleVertexDeleted = true;
                isChanged = true;
            }
            index = isMiddleVertexDeleted ? lastIndex : midIndex;
            midIndex = findNextNonDeletedIndex(index);
            lastIndex = findNextNonDeletedIndex(midIndex);
        }
        return isChanged;
    }

    private int findNextNonDeletedIndex(int index) {
        int next = index + 1;
        while (next < inputLine.length && isDeleted[next]) {
            next++;
        }
        return next;
    }

    private Coordinate[] collapseLine() {
        List<Coordinate> coordList = new ArrayList<>();
        for (int i = 0; i < inputLine.length; ++i) {
            if (!isDeleted[i]) {
                coordList.add(inputLine[i]);
            }
        }
        return coordList.toArray(new Coordinate[0]);
    }

    private boolean isDeletable(int i0, int i1, int i2, double pDistanceTol) {
        Coordinate p0 = inputLine[i0];
        Coordinate p1 = inputLine[i1];
        Coordinate p2 = inputLine[i2];
        if (!isConcave(p0, p1, p2)) {
            return false;
        }
        if (!isShallow(p0, p1, p2, pDistanceTol)) {
            return false;
        }
        // GEOS passes (p0, p1) here
        return isShallowSampled(p0, p1, i0, i2, pDistanceTol);
    }

    private boolean isShallowSampled(Coordinate p0, Coordinate p2, int i0, int i2, double pDistanceTol) {
        int inc = (i2 - i0) / NUM_PTS_TO_CHECK;
        if (inc <= 0) {
            inc = 1;
        }
        for (int i = i0; i < i2; i += inc) {
            // GEOS argument order: isShallow(p0, p2, inputLine[i]) -> distance of p2 to segment (p0, inputLine[i])
            if (!isShallow(p0, p2, inputLine[i], pDistanceTol)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isShallow(Coordinate p0, Coordinate p1, Coordinate p2, double pDistanceTol) {
        double dist = GeosMath.pointToSegment(p1, p0, p2);
        return dist < pDistanceTol;
    }

    private boolean isConcave(Coordinate p0, Coordinate p1, Coordinate p2) {
        int orientation = Orientation.index(p0, p1, p2);
        return orientation == angleOrientation;
    }
}
