package com.autoparkingplan.parking;

import static com.autoparkingplan.parking.ParkingTestSupport.X0;
import static com.autoparkingplan.parking.ParkingTestSupport.Y0;
import static com.autoparkingplan.parking.ParkingTestSupport.box;
import static com.autoparkingplan.parking.ParkingTestSupport.localToGeojson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 파이썬 {@code tests/parking/test_geo.py}. */
class GeoTest {

    /** test_transformers_cached_and_roundtrip */
    @Test
    void transformersCachedAndRoundtrip() throws Exception {
        assertThat(Geo.getTransformer(Geo.WGS84, Geo.UTMK)).isSameAs(Geo.getTransformer(Geo.WGS84, Geo.UTMK));
        double[] xy = Geo.lnglatToUtmk(128.63, 35.85);
        // UTM-K 대구 부근 대략값 (false easting 1,000,000 / northing 2,000,000)
        assertThat(xy[0]).isBetween(1_000_000.0, 1_200_000.0);
        assertThat(xy[1]).isBetween(1_700_000.0, 1_800_000.0);
        double[] ll = Geo.utmkToLnglat(xy[0], xy[1]);
        assertThat(Math.abs(ll[0] - 128.63)).isLessThan(1e-9);
        assertThat(Math.abs(ll[1] - 35.85)).isLessThan(1e-9);
        // 자바 추가: 캐시된 변환기를 여러 스레드가 함께 써도 같은 값 (불변 객체)
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<double[]>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                fs.add(pool.submit(() -> Geo.lnglatToUtmk(128.63, 35.85)));
            }
            for (Future<double[]> f : fs) {
                assertThat(f.get()).containsExactly(xy[0], xy[1]);
            }
        } finally {
            pool.shutdown();
        }
    }

    /** 자바 추가: pyproj(PROJ 9.8) 와 같은 값 (generate.py 가 기록한 대구 부근 기준점). */
    @Test
    void transformerMatchesPyprojReferencePoint() {
        double[] xy = Geo.lnglatToUtmk(128.63, 35.85);
        // pyproj: Transformer.from_crs('EPSG:4326','EPSG:5179',always_xy=True).transform(128.63, 35.85)
        assertThat(xy[0]).isEqualTo(1102038.8246450843);
        assertThat(Math.abs(xy[1] - 1762085.7995629343)).isLessThan(1e-8);
    }

    /** test_geometry_roundtrip_and_area */
    @Test
    void geometryRoundtripAndArea() {
        Polygon sq = box(X0, Y0, X0 + 50, Y0 + 60);
        Polygon wgs = Geo.toWgs84(sq);
        Envelope b = wgs.getEnvelopeInternal();
        assertThat(b.getMinX()).isBetween(128.0, 129.0);
        assertThat(b.getMinY()).isBetween(35.0, 36.0);
        Polygon back = Geo.toUtmk(wgs);
        assertThat(Math.abs(back.getArea() - 3000.0)).isLessThan(1e-6);
        assertThat(Math.abs(Geo.areaM2(wgs) - 3000.0)).isLessThan(1e-6);
    }

    /** test_geojson_to_shape_variants */
    @Test
    void geojsonToShapeVariants() {
        ObjectNode g = localToGeojson(box(0, 0, 10, 10));
        assertThat(Geo.geojsonToShape(g) instanceof Polygon).isTrue();
        ObjectNode feat = Json.object();
        feat.put("type", "Feature");
        feat.set("geometry", g);
        feat.set("properties", Json.object());
        assertThat(Geo.geojsonToShape(feat) instanceof Polygon).isTrue();
        ObjectNode feat2 = Json.object();
        feat2.put("type", "Feature");
        feat2.set("geometry", localToGeojson(box(20, 0, 30, 10)));
        feat2.set("properties", Json.object());
        ObjectNode fc = Json.object();
        fc.put("type", "FeatureCollection");
        fc.putArray("features").add(feat).add(feat2);
        assertThat(Geo.geojsonToShape(fc) instanceof MultiPolygon).isTrue();
        ObjectNode mp = localToGeojson(ParkingTestSupport.multi(box(0, 0, 1, 1), box(3, 3, 4, 4)));
        assertThat(Geo.geojsonToShape(mp) instanceof MultiPolygon).isTrue();
        List<JsonNode> bads = List.of(
                Json.parse("{\"type\": \"Point\", \"coordinates\": [1, 2]}"),
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": \"x\"}"),
                Json.parse("[]"),
                Json.parse("{\"type\": \"FeatureCollection\", \"features\": []}"));
        for (JsonNode bad : bads) {
            assertThatThrownBy(() -> Geo.geojsonToShape(bad)).isInstanceOf(GeoJSONError.class);
        }
    }

    /** test_shape_to_geojson_rounding_and_orientation */
    @Test
    void shapeToGeojsonRoundingAndOrientation() {
        Polygon cw = Shapely.polygon(new double[][] {{128.123456789, 35.1}, {128.123456789, 35.2}, {128.2, 35.2},
                {128.2, 35.1}}); // 시계 방향
        ObjectNode gj = Geo.shapeToGeojson(cw);
        JsonNode ring = gj.get("coordinates").get(0);
        assertThat(ring.get(0)).isEqualTo(ring.get(ring.size() - 1));
        assertThat(ring.get(0).get(0).doubleValue()).isEqualTo(128.1234568);
        Coordinate[] cs = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            cs[i] = new Coordinate(ring.get(i).get(0).doubleValue(), ring.get(i).get(1).doubleValue());
        }
        assertThat(Orientation.isCCW(cs)).as("RFC 7946: 외곽 반시계").isTrue();
        assertThat(Geo.shapeToGeojson(Shapely.point(1.123456789, 2.0)))
                .isEqualTo(Json.parse("{\"type\": \"Point\", \"coordinates\": [1.1234568, 2.0]}"));
        ObjectNode props = Json.object();
        props.put("a", 1);
        ObjectNode f = Geo.feature(Shapely.point(1, 2), props);
        assertThat(f.get("type").stringValue()).isEqualTo("Feature");
        assertThat(f.get("properties")).isEqualTo(Json.parse("{\"a\": 1}"));
        ArrayNode feats = (ArrayNode) Geo.featureCollection(List.of(f)).get("features");
        assertThat(feats.size()).isEqualTo(1);
        assertThat(feats.get(0)).isEqualTo(f);
    }

    /** test_bounds_check_detects_swapped_coordinates */
    @Test
    void boundsCheckDetectsSwappedCoordinates() {
        Polygon swapped = Shapely.polygon(new double[][] {{35.85, 128.63}, {35.86, 128.63}, {35.86, 128.64}});
        assertThat(Geo.lnglatBoundsOk(swapped)).isFalse();
        assertThat(Geo.lnglatBoundsOk(Geo.geojsonToShape(localToGeojson(box(0, 0, 5, 5))))).isTrue();
    }

    /** test_largest_polygon */
    @Test
    void largestPolygon() {
        MultiPolygon mp = ParkingTestSupport.multi(box(0, 0, 1, 1), box(5, 5, 8, 8));
        assertThat(Geo.largestPolygon(mp).getArea()).isCloseTo(9.0, org.assertj.core.data.Offset.offset(1e-9));
        Geometry pt = Shapely.point(0, 0);
        assertThat(Geo.largestPolygon(pt) == null).isTrue();
    }
}
