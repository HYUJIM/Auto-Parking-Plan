package com.autoparkingplan.parking.geom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ByteOrderValues;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.operation.buffer.BufferParameters;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * 원래 파이썬 테스트 없음 — 엔진이 쓰는 shapely 2.2(GEOS 3.14.1) 기하 연산과 자바 {@link Shapely} 결과를 정점 좌표·순서까지
 * (WKB 바이트 단위로) 비교한다. 기대값은 {@code src/test/resources/parity/generate.py} 가 만든 {@code geos_ops.json.gz}.
 *
 * <p>대상: 음·양 버퍼(마이터·둥근), 선 버퍼(납작 끝·마이터 2), 선∩면, 다각형 합집합(STR 순서), 최대 내접원, 최소회전사각형,
 * 링 방향, 선형 참조(interpolate·substring·project), 최근접점, 반복점 제거, 무게중심, 면적, make_valid.
 */
class GeosCompatTest {
    private static final WKBReader READER = new WKBReader(Shapely.FACTORY);
    private static final WKBWriter WRITER = new WKBWriter(2, ByteOrderValues.LITTLE_ENDIAN);

    static JsonNode fixture() {
        try (InputStream raw = GeosCompatTest.class.getResourceAsStream("/parity/geos_ops.json.gz")) {
            return Json.parse(new GZIPInputStream(raw));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Geometry g(JsonNode hex) {
        try {
            return READER.read(WKBReader.hexToBytes(hex.stringValue()));
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(Geometry geom) {
        return WKBWriter.toHex(WRITER.write(geom)).toUpperCase();
    }

    static String hexPoint(Coordinate c) {
        return hex(Shapely.FACTORY.createPoint(c));
    }

    @TestFactory
    Stream<DynamicTest> operationsMatchShapely() {
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode c : fixture().get("cases").values()) {
            int idx = i++;
            tests.add(DynamicTest.dynamicTest("case " + idx, () -> {
                Polygon p = (Polygon) g(c.get("poly"));
                Map<String, String> actual = new LinkedHashMap<>();
                actual.put("neg_buffer_0.5_mitre10", hex(Shapely.bufferMitre(p, -0.5, 10.0)));
                actual.put("neg_buffer_1.0_mitre10", hex(Shapely.bufferMitre(p, -1.0, 10.0)));
                actual.put("buffer_1e-6_round", hex(Shapely.buffer(p, 1e-6)));
                actual.put("buffer_1e-6_mitre", hex(Shapely.bufferMitre(p, 1e-6, 5.0)));
                actual.put("buffer_1e-7_mitre", hex(Shapely.bufferMitre(p, 1e-7, 5.0)));
                List<Geometry> rects = new ArrayList<>();
                c.get("rects").values().forEach(r -> rects.add(g(r)));
                Geometry union = Shapely.unionAll(rects);
                actual.put("union_all", hex(union));
                LineString line = (LineString) g(c.get("line"));
                double w = c.get("width").doubleValue();
                actual.put("line_buffer_flat_mitre2",
                        hex(Shapely.buffer(line, w / 2, BufferParameters.CAP_FLAT, BufferParameters.JOIN_MITRE, 2.0)));
                actual.put("line_intersection", hex(Shapely.intersection(line, p)));
                actual.put("mic_0.01", hex(Shapely.maximumInscribedCircle(p, 0.01)));
                actual.put("mrr", hex(Shapely.minimumRotatedRectangle(p)));
                actual.put("orient", hex(Shapely.orient(p)));
                actual.put("orient_rotated", hex(Shapely.orient((Polygon) g(c.get("rotated")))));
                LineString ring2 = (LineString) g(c.get("ring2"));
                double d = c.get("d").doubleValue();
                actual.put("interpolate", hexPoint(Shapely.interpolate(ring2, d)));
                actual.put("substring", hex(Shapely.substring(ring2, d, d + w)));
                actual.put("remove_repeated", hex(Shapely.removeRepeatedPoints(p, 1e-9)));
                actual.put("centroid", hex(p.getCentroid()));
                for (Map.Entry<String, String> e : actual.entrySet()) {
                    assertThat(e.getValue()).as(e.getKey()).isEqualTo(c.get(e.getKey()).stringValue().toUpperCase());
                }
                Point t = (Point) g(c.get("target"));
                LineString ext = Shapely.FACTORY.createLineString(p.getExteriorRing().getCoordinates());
                assertThat(Shapely.project(ext, t.getCoordinate())).as("project").isEqualTo(c.get("project").doubleValue());
                Coordinate[] np = Shapely.nearestPoints(ext, union);
                assertThat(hexPoint(np[0])).as("nearest[0]").isEqualTo(c.get("nearest_ext_union").get(0).stringValue().toUpperCase());
                assertThat(hexPoint(np[1])).as("nearest[1]").isEqualTo(c.get("nearest_ext_union").get(1).stringValue().toUpperCase());
                assertThat(p.getArea()).as("area").isEqualTo(c.get("area").doubleValue());
            }));
        }
        int k = 0;
        for (JsonNode c : fixture().get("invalid").values()) {
            int idx = k++;
            tests.add(DynamicTest.dynamicTest("make_valid " + idx, () ->
                    assertThat(hex(Shapely.makeValid(g(c.get("poly"))))).isEqualTo(c.get("make_valid").stringValue().toUpperCase())));
        }
        return tests.stream();
    }
}
