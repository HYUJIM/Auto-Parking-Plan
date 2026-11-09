package com.autoparkingplan.parking.geom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import com.autoparkingplan.parking.geom.polygonize.Polygonizer;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.util.UniqueCoordinateArrayFilter;

/**
 * GEOS 3.14.1 {@code operation/valid/MakeValid} ("linework" 방식, shapely {@code make_valid} 기본값)와
 * {@code operation/polygonize/BuildArea} 의 자바 이식.
 *
 * <p>JTS 의 {@code GeometryFixer} 는 다른 알고리즘(GEOS 의 "structure" 방식)이라 결과 부분의 순서·모양이 다를 수 있다.
 */
public final class MakeValid {
    private MakeValid() {
    }

    public static Geometry makeValid(Geometry geom) {
        if (new IsValidOp(geom).getValidationError() == null) {
            return geom.copy();
        }
        if (geom instanceof LinearRing || geom instanceof LineString) {
            return makeValidLine((LineString) geom);
        }
        if (geom instanceof MultiLineString mls) {
            return makeValidMultiLine(mls);
        }
        if (geom instanceof Polygon || geom instanceof MultiPolygon) {
            return makeValidPoly(geom);
        }
        if (geom instanceof GeometryCollection coll) {
            Geometry[] valid = new Geometry[coll.getNumGeometries()];
            for (int i = 0; i < valid.length; i++) {
                valid[i] = makeValid(coll.getGeometryN(i));
            }
            return coll.getFactory().createGeometryCollection(valid);
        }
        throw new UnsupportedOperationException(geom.getGeometryType());
    }

    private static Geometry nodeLineWithFirstCoordinate(Geometry geom) {
        if (geom.isEmpty()) {
            return null;
        }
        Point point;
        if (geom instanceof LineString line) {
            point = line.getPointN(0);
        } else {
            point = ((LineString) geom.getGeometryN(0)).getPointN(0);
        }
        return OverlayNGRobust.overlay(geom, point, OverlayNG.UNION);
    }

    private static Geometry makeValidLine(LineString line) {
        return nodeLineWithFirstCoordinate(line);
    }

    private static Geometry makeValidMultiLine(MultiLineString mls) {
        GeometryFactory f = mls.getFactory();
        List<Geometry> points = new ArrayList<>();
        List<Geometry> lines = new ArrayList<>();
        for (int i = 0; i < mls.getNumGeometries(); i++) {
            Geometry valid = makeValidLine((LineString) mls.getGeometryN(i));
            if (valid == null || valid.isEmpty()) {
                continue;
            }
            if (valid instanceof Point) {
                points.add(valid);
            } else if (valid instanceof LineString) {
                lines.add(valid);
            } else if (valid instanceof MultiLineString mlsValid) {
                for (int j = 0; j < mlsValid.getNumGeometries(); j++) {
                    lines.add(mlsValid.getGeometryN(j).copy());
                }
            } else {
                throw new UnsupportedOperationException();
            }
        }
        Geometry pointsRet = points.isEmpty() ? null
                : points.size() > 1 ? f.createMultiPoint(points.toArray(new Point[0])) : points.get(0);
        Geometry linesRet = lines.isEmpty() ? null
                : lines.size() > 1 ? f.createMultiLineString(lines.toArray(new LineString[0])) : lines.get(0);
        if (pointsRet != null && linesRet != null) {
            return f.createGeometryCollection(new Geometry[] {pointsRet, linesRet});
        }
        return pointsRet != null ? pointsRet : linesRet;
    }

    private static Geometry extractUniquePoints(Geometry geom) {
        UniqueCoordinateArrayFilter filter = new UniqueCoordinateArrayFilter();
        geom.apply(filter);
        Coordinate[] coords = filter.getCoordinates();
        GeometryFactory f = geom.getFactory();
        Point[] pts = new Point[coords.length];
        for (int i = 0; i < coords.length; i++) {
            pts[i] = f.createPoint(coords[i].copy());
        }
        return f.createMultiPoint(pts);
    }

    private static Geometry makeValidPoly(Geometry geom) {
        Geometry bound = geom.getBoundary();
        if (bound == null) {
            return null;
        }
        Geometry cutEdges = nodeLineWithFirstCoordinate(bound);
        if (cutEdges == null) {
            return null;
        }
        Geometry pi = extractUniquePoints(bound);
        Geometry po = extractUniquePoints(cutEdges);
        Geometry collapsePoints = OverlayNGRobust.overlay(pi, po, OverlayNG.DIFFERENCE);
        GeometryFactory factory = geom.getFactory();
        Geometry area = factory.createPolygon();
        while (cutEdges.getNumGeometries() > 0) {
            Geometry newArea = buildArea(cutEdges);
            if (newArea.isEmpty()) {
                break;
            }
            Geometry newAreaBound = newArea.getBoundary();
            area = OverlayNGRobust.overlay(area, newArea, OverlayNG.SYMDIFFERENCE);
            cutEdges = OverlayNGRobust.overlay(cutEdges, newAreaBound, OverlayNG.DIFFERENCE);
        }
        List<Geometry> vgeoms = new ArrayList<>(3);
        if (!area.isEmpty()) {
            vgeoms.add(area);
        }
        if (!cutEdges.isEmpty()) {
            vgeoms.add(cutEdges);
        }
        if (!collapsePoints.isEmpty()) {
            vgeoms.add(collapsePoints);
        }
        if (vgeoms.size() == 1) {
            return vgeoms.get(0);
        }
        return factory.createGeometryCollection(vgeoms.toArray(new Geometry[0]));
    }

    // ── BuildArea ─────────────────────────────────────────────────────

    private static final class Face {
        final Polygon poly;
        final double envarea;
        Face parent;

        Face(Polygon poly) {
            this.poly = poly;
            this.envarea = poly.getEnvelope().getArea();
        }

        int countParents() {
            int n = 0;
            Face f = this;
            while (f.parent != null) {
                n++;
                f = f.parent;
            }
            return n;
        }
    }

    private static boolean ringsEqualAnyDirection(LinearRing r1, LinearRing r2) {
        Coordinate[] cs1 = r1.getCoordinates();
        Coordinate[] cs2 = r2.getCoordinates();
        int npoints = cs1.length;
        if (npoints != cs2.length) {
            return false;
        }
        if (npoints == 0) {
            return true;
        }
        --npoints;
        Coordinate firstPoint = cs1[0];
        int offset = -1;
        for (int i = 0; i < cs2.length; i++) {
            if (cs2[i].equals2D(firstPoint)) {
                offset = i;
                break;
            }
        }
        if (offset < 0) {
            return false;
        }
        boolean equal = true;
        for (int i = 1; i < npoints; ++i) {
            int j = (i + offset) % npoints;
            if (!cs1[i].equals2D(cs2[j])) {
                equal = false;
                break;
            }
        }
        if (equal) {
            return true;
        }
        equal = true;
        for (int i = 1; i < npoints; ++i) {
            int j = i <= offset ? offset - i : npoints - (i - offset);
            if (!cs1[i].equals2D(cs2[j])) {
                equal = false;
                break;
            }
        }
        return equal;
    }

    @SuppressWarnings("unchecked")
    static Geometry buildArea(Geometry geom) {
        Polygonizer polygonizer = new Polygonizer();
        polygonizer.add(geom);
        Collection<Polygon> polys = polygonizer.getPolygons();
        GeometryFactory f = geom.getFactory();
        if (polys.isEmpty()) {
            return f.createGeometryCollection();
        }
        if (polys.size() == 1) {
            return polys.iterator().next();
        }
        List<Face> faces = new ArrayList<>();
        for (Polygon p : polys) {
            faces.add(new Face(p));
        }
        // std::sort(내림차순 envarea): 작은 입력에서는 안정 정렬과 같다
        faces.sort(Comparator.comparingDouble((Face x) -> x.envarea).reversed());
        int nfaces = faces.size();
        for (int i = 0; i < nfaces; ++i) {
            Face face = faces.get(i);
            int nholes = face.poly.getNumInteriorRing();
            for (int h = 0; h < nholes; h++) {
                LinearRing hole = face.poly.getInteriorRingN(h);
                for (int j = i + 1; j < nfaces; ++j) {
                    Face f2 = faces.get(j);
                    if (f2.parent != null) {
                        continue;
                    }
                    if (ringsEqualAnyDirection(f2.poly.getExteriorRing(), hole)) {
                        f2.parent = face;
                        break;
                    }
                }
            }
        }
        List<Polygon> even = new ArrayList<>();
        for (Face face : faces) {
            if (face.countParents() % 2 != 0) {
                continue;
            }
            even.add((Polygon) face.poly.copy());
        }
        Geometry shp = Shapely.cascadedPolygonUnion(even);
        return shp == null ? f.createMultiPolygon() : shp;
    }
}
