package com.autoparkingplan.parking;

import static com.autoparkingplan.parking.ParkingTestSupport.ALL_ANGLES;
import static com.autoparkingplan.parking.ParkingTestSupport.IRREGULAR_HEX;
import static com.autoparkingplan.parking.ParkingTestSupport.PENTAGON;
import static com.autoparkingplan.parking.ParkingTestSupport.X0;
import static com.autoparkingplan.parking.ParkingTestSupport.Y0;
import static com.autoparkingplan.parking.ParkingTestSupport.assertLayoutValid;
import static com.autoparkingplan.parking.ParkingTestSupport.box;
import static com.autoparkingplan.parking.ParkingTestSupport.localPointLnglat;
import static com.autoparkingplan.parking.ParkingTestSupport.localToGeojson;
import static com.autoparkingplan.parking.ParkingTestSupport.makeDims;
import static com.autoparkingplan.parking.ParkingTestSupport.rect;
import static com.autoparkingplan.parking.ParkingTestSupport.scaledToArea;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Geometry;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 입력 처리·제약 사유·출입구·대안 선택·결정성·성능. 파이썬 {@code tests/parking/test_engine_behaviour.py}. */
class EngineBehaviourTest {
    static final ParkingDimensions DIMS = makeDims();

    static String dump(SimulationResult res) {
        return PyJson.dumpsSorted(res.toJson());
    }

    static ParkingEngine.Detailed run(JsonNode site, ParkingDimensions dims, SimulationParams params) {
        ParkingEngine.Detailed d = ParkingEngine.simulateDetailed(site, dims, params);
        assertThat(d.result().alternatives()).hasSameSizeAs(d.geoms());
        return d;
    }

    // --- 입력 기하 -------------------------------------------------------------
    static Stream<JsonNode> badSites() {
        return Stream.of(
                Json.parse("{\"type\": \"Point\", \"coordinates\": [128.63, 35.85]}"),
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": []}"),
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": [[[128.63, 35.85], [128.63, 35.85], [128.63, 35.85], "
                        + "[128.63, 35.85]]]}"),
                // 위경도 뒤바뀜
                Json.parse("{\"type\": \"Polygon\", \"coordinates\": [[[35.85, 128.63], [35.851, 128.63], [35.851, 128.631], "
                        + "[35.85, 128.63]]]}"),
                Json.parse("{\"foo\": \"bar\"}"));
    }

    /** test_invalid_site_geometry (parametrize bad × 5) */
    @ParameterizedTest
    @MethodSource("badSites")
    void invalidSiteGeometry(JsonNode bad) {
        SimulationResult res = ParkingEngine.simulate(bad, DIMS, SimulationParams.defaults());
        assertThat(res.alternatives()).isEmpty();
        assertThat(res.constraintReasons()).containsExactly("SITE_GEOMETRY_INVALID");
        assertThat(res.siteAreaM2()).isNull();
        assertThat(res.crsInternal()).isEqualTo("EPSG:5179");
    }

    /** test_feature_input_and_default_params */
    @Test
    void featureInputAndDefaultParams() {
        ObjectNode feat = Json.object();
        feat.put("type", "Feature");
        feat.set("geometry", localToGeojson(rect(20, 40)));
        feat.putObject("properties").put("pnu", "x");
        SimulationResult res = ParkingEngine.simulate(feat, DIMS);
        assertThat(res.alternatives()).isNotEmpty();
        assertThat(Math.abs(res.siteAreaM2() - 800.0)).isLessThan(0.01);
    }

    /** test_self_intersecting_site_is_repaired_or_rejected — 나비넥타이 모양 → make_valid 로 두 삼각형 → 큰 쪽 사용 */
    @Test
    void selfIntersectingSiteIsRepairedOrRejected() {
        List<Double> p0 = localPointLnglat(0, 0);
        List<Double> p1 = localPointLnglat(60, 60);
        ObjectNode bow = Json.object();
        bow.put("type", "Polygon");
        ArrayNode ring = bow.putArray("coordinates").addArray();
        ring.addArray().add(p0.get(0)).add(p0.get(1));
        ring.addArray().add(p1.get(0)).add(p1.get(1));
        ring.addArray().add(p1.get(0)).add(p0.get(1));
        ring.addArray().add(p0.get(0)).add(p1.get(1));
        ring.addArray().add(p0.get(0)).add(p0.get(1));
        SimulationResult res = ParkingEngine.simulate(bow, DIMS, SimulationParams.defaults());
        assertThat(res.constraintReasons()).contains("SITE_GEOMETRY_REPAIRED").doesNotContain("SITE_GEOMETRY_INVALID");
    }

    // --- 치수 누락 -------------------------------------------------------------
    /** test_missing_dimension_for_one_angle */
    @Test
    void missingDimensionForOneAngle() {
        Map<String, Map<String, Double>> aisles = new LinkedHashMap<>(DIMS.aisleWidth());
        aisles.remove("ANGLE_45");
        ParkingDimensions dims = makeDims().withAisleWidth(aisles);
        SimulationParams params = SimulationParams.defaults().withMaxAlternatives(4);
        ParkingEngine.Detailed d = run(localToGeojson(rect(50, 60)), dims, params);
        SimulationResult res = d.result();
        assertThat(res.constraintReasons()).contains("DIMENSION_MISSING:ANGLE_45");
        assertThat(res.alternatives()).allMatch(a -> !a.parkingAngle().equals("ANGLE_45"));
        Set<String> angles = new HashSet<>();
        res.alternatives().forEach(a -> angles.add(a.parkingAngle()));
        assertThat(angles).containsExactlyInAnyOrder("PERPENDICULAR_90", "ANGLE_60", "PARALLEL_0");
        for (int i = 0; i < res.alternatives().size(); i++) {
            assertLayoutValid(d.geoms().get(i), res.alternatives().get(i), dims, params);
        }
    }

    /** test_missing_gate_key_and_stall_type */
    @Test
    void missingGateKeyAndStallType() {
        Map<String, Map<String, Double>> aisles = new LinkedHashMap<>();
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("one_gate", 6.0); // two_plus_gates 없음
        aisles.put("PERPENDICULAR_90", w);
        ParkingDimensions dims = makeDims().withAisleWidth(aisles);
        SimulationResult res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), dims,
                SimulationParams.defaults().withGateCount(2));
        assertThat(res.alternatives()).isEmpty();
        for (String a : ALL_ANGLES) {
            assertThat(res.constraintReasons()).contains("DIMENSION_MISSING:" + a);
        }
        assertThat(res.constraintReasons()).contains("NO_FEASIBLE_LAYOUT");

        res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS, SimulationParams.defaults().withStallType("disabled"));
        assertThat(res.alternatives()).isEmpty();
        assertThat(res.constraintReasons()).contains("NO_FEASIBLE_LAYOUT");
        for (String a : ALL_ANGLES) {
            assertThat(res.constraintReasons()).contains("DIMENSION_MISSING:" + a);
        }
    }

    /** test_empty_dimensions_and_bad_values */
    @Test
    void emptyDimensionsAndBadValues() {
        SimulationResult res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), ParkingDimensions.empty(),
                SimulationParams.defaults());
        assertThat(res.alternatives()).isEmpty();
        assertThat(res.constraintReasons()).contains("NO_FEASIBLE_LAYOUT");
        Map<String, StallSize> np = new LinkedHashMap<>();
        np.put("general", new StallSize(0.0, 5.0)); // 0 폭 = 없는 값
        ParkingDimensions dims = makeDims().withStallNonParallel(np);
        res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), dims,
                SimulationParams.defaults().withAngles(List.of("PERPENDICULAR_90")));
        assertThat(res.constraintReasons().subList(0, 1)).containsExactly("DIMENSION_MISSING:PERPENDICULAR_90");
    }

    /** test_missing_gate_width_uses_aisle_width */
    @Test
    void missingGateWidthUsesAisleWidth() {
        ParkingDimensions dims = makeDims().withGateWidthMinM(null);
        SimulationParams params = SimulationParams.defaults().withAngles(List.of("PARALLEL_0")).withGateCount(2);
        ParkingEngine.Detailed d = run(localToGeojson(rect(30, 40)), dims, params);
        LayoutGeometry g = d.geoms().get(0);
        assertThat(g.drivewayWidthM()).isEqualTo(dims.aisleWidth().get("PARALLEL_0").get("two_plus_gates"));
        assertLayoutValid(g, d.result().alternatives().get(0), dims, params);
    }

    // --- 출입구 ---------------------------------------------------------------
    /** test_gate_snapped_when_far — 대지(50×60) 남쪽 30 m 밖 */
    @Test
    void gateSnappedWhenFar() {
        SimulationParams params = SimulationParams.defaults().withGatePoint(localPointLnglat(25, -30));
        ParkingEngine.Detailed d = run(localToGeojson(rect(50, 60)), DIMS, params);
        assertThat(d.result().constraintReasons()).contains("GATE_SNAPPED");
        for (int i = 0; i < d.geoms().size(); i++) {
            Alternative alt = d.result().alternatives().get(i);
            LayoutGeometry g = d.geoms().get(i);
            assertThat(alt.constraintReasons()).contains("GATE_SNAPPED");
            assertThat(g.gatePoint().distance(Shapely.point(X0 + 25, Y0))).isLessThan(0.5); // 남쪽 변의 최근접점
            assertLayoutValid(g, alt, DIMS, params);
        }
    }

    /** test_gate_near_boundary_not_flagged — 동쪽 변에서 3 m 밖 */
    @Test
    void gateNearBoundaryNotFlagged() {
        SimulationParams params = SimulationParams.defaults().withGatePoint(localPointLnglat(53, 30));
        ParkingEngine.Detailed d = run(localToGeojson(rect(50, 60)), DIMS, params);
        assertThat(d.result().constraintReasons()).doesNotContain("GATE_SNAPPED");
        assertThat(d.result().alternatives()).isNotEmpty();
        for (int i = 0; i < d.geoms().size(); i++) {
            LayoutGeometry g = d.geoms().get(i);
            assertThat(g.gatePoint().distance(Shapely.point(X0 + 50, Y0 + 30))).isLessThan(g.drivewayWidthM());
            assertLayoutValid(g, d.result().alternatives().get(i), DIMS, params);
        }
    }

    /** test_gate_at_corner_is_moved_along_edge — 모서리 바로 옆 → 개구부가 변 안에 들도록 이동 */
    @Test
    void gateAtCornerIsMovedAlongEdge() {
        SimulationParams params = SimulationParams.defaults().withGatePoint(localPointLnglat(0.2, 0.1));
        ParkingEngine.Detailed d = run(localToGeojson(rect(50, 60)), DIMS, params);
        assertThat(d.result().alternatives()).isNotEmpty();
        assertThat(d.result().constraintReasons()).doesNotContain("GATE_SNAPPED");
        for (int i = 0; i < d.geoms().size(); i++) {
            assertLayoutValid(d.geoms().get(i), d.result().alternatives().get(i), DIMS, params);
        }
    }

    /** test_unreachable_gate_falls_back_to_auto_gate — 좁은 목(2 m)으로만 이어진 작은 땅 쪽에 출입구를 지정 → 자동 출입구 + 표시 */
    @Test
    void unreachableGateFallsBackToAutoGate() {
        Geometry dumbbell = Shapely.unionAll(List.of(box(0, 0, 40, 40), box(39, 19, 51, 21), box(50, 15, 60, 25)));
        SimulationParams params = SimulationParams.defaults().withGatePoint(localPointLnglat(60, 20));
        ParkingEngine.Detailed d = run(localToGeojson(dumbbell), DIMS, params);
        assertThat(d.result().alternatives()).isNotEmpty();
        assertThat(d.result().constraintReasons()).contains("GATE_UNREACHABLE");
        for (int i = 0; i < d.geoms().size(); i++) {
            Alternative alt = d.result().alternatives().get(i);
            LayoutGeometry g = d.geoms().get(i);
            assertThat(alt.constraintReasons()).contains("GATE_UNREACHABLE");
            assertThat(alt.constraintReasons()).contains("GATE_SNAPPED"); // 실제 출입구가 지정점에서 15 m 이상 떨어짐
            assertThat(g.gatePoint().getX() - X0).isLessThanOrEqualTo(40.5); // 큰 땅 쪽 경계
            assertLayoutValid(g, alt, DIMS, params);
        }
    }

    /** test_invalid_gate_point_falls_back_to_auto */
    @Test
    void invalidGatePointFallsBackToAuto() {
        SimulationResult res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS,
                SimulationParams.defaults().withGatePoint(Double.NaN, 35.0));
        assertThat(res.constraintReasons()).contains("GATE_POINT_INVALID");
        assertThat(res.alternatives()).isNotEmpty();
    }

    /** test_auto_gate_costs_few_stalls — 19 m 폭 → 양측 1모듈, 39 m / 2.5 = 15대 × 2열 */
    @Test
    void autoGateCostsFewStalls() {
        SimulationResult res = run(localToGeojson(rect(20, 40)), DIMS,
                SimulationParams.defaults().withAngles(List.of("PERPENDICULAR_90"))).result();
        assertThat(res.alternatives().get(0).stallCount()).isEqualTo(30);
    }

    // --- 대안 ------------------------------------------------------------------
    /** test_alternatives_ordering_and_recommendation */
    @Test
    void alternativesOrderingAndRecommendation() {
        List<Alternative> alts = ParkingEngine.simulate(localToGeojson(PENTAGON), DIMS,
                SimulationParams.defaults().withMaxAlternatives(3)).alternatives();
        assertThat(alts).extracting(Alternative::altCode).containsExactly("ALT_A", "ALT_B", "ALT_C");
        assertThat(alts.stream().map(Alternative::parkingAngle).distinct().count()).isEqualTo(3); // 서로 다른 형식 우선
        List<Integer> counts = alts.stream().map(Alternative::stallCount).toList();
        assertThat(counts).isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        assertThat(alts).extracting(Alternative::isRecommended).containsExactly(true, false, false);
    }

    /** test_alternatives_same_angle_use_different_orientations */
    @Test
    void alternativesSameAngleUseDifferentOrientations() {
        List<Alternative> alts = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS,
                SimulationParams.defaults().withAngles(List.of("PERPENDICULAR_90")).withMaxAlternatives(3)).alternatives();
        assertThat(alts.size()).isBetween(2, 3);
        for (int i = 0; i < alts.size(); i++) {
            for (int j = i + 1; j < alts.size(); j++) {
                double d = Math.abs(alts.get(i).orientationDeg() - alts.get(j).orientationDeg()) % 180;
                assertThat(Math.min(d, 180 - d)).isGreaterThanOrEqualTo(10);
            }
        }
        assertThat(alts.get(0).isRecommended()).isTrue();
        assertThat(alts.stream().filter(Alternative::isRecommended).count()).isEqualTo(1);
    }

    /** test_max_alternatives_respected */
    @Test
    void maxAlternativesRespected() {
        SimulationResult res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS,
                SimulationParams.defaults().withMaxAlternatives(1));
        assertThat(res.alternatives()).hasSize(1);
        assertThat(res.alternatives().get(0).parkingAngle()).isEqualTo("PERPENDICULAR_90");
        res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS, SimulationParams.defaults().withMaxAlternatives(10));
        assertThat(res.alternatives().size()).isGreaterThanOrEqualTo(4);
    }

    /** test_setback_changes_usable_area */
    @Test
    void setbackChangesUsableArea() {
        Alternative a = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS,
                SimulationParams.defaults().withSetbackM(0.0).withMaxAlternatives(1)).alternatives().get(0);
        Alternative b = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS,
                SimulationParams.defaults().withSetbackM(2.0).withMaxAlternatives(1)).alternatives().get(0);
        assertThat(Math.abs(a.metrics().get("usable_area_m2").doubleValue() - 3000)).isLessThan(0.01);
        assertThat(Math.abs(b.metrics().get("usable_area_m2").doubleValue() - 46 * 56)).isLessThan(0.01);
        assertThat(a.stallCount()).isGreaterThanOrEqualTo(b.stallCount());
    }

    /** test_setback_zero_still_valid */
    @Test
    void setbackZeroStillValid() {
        SimulationParams params = SimulationParams.defaults().withSetbackM(0.0);
        ParkingEngine.Detailed d = run(localToGeojson(PENTAGON), DIMS, params);
        assertThat(d.result().alternatives()).isNotEmpty();
        for (int i = 0; i < d.geoms().size(); i++) {
            assertLayoutValid(d.geoms().get(i), d.result().alternatives().get(i), DIMS, params);
        }
    }

    // --- 결정성·성능 -----------------------------------------------------------
    /** test_deterministic */
    @Test
    void deterministic() {
        ObjectNode site = localToGeojson(PENTAGON);
        SimulationParams params = SimulationParams.defaults().withGatePoint(localPointLnglat(70, 30));
        String a = dump(ParkingEngine.simulate(site, DIMS, params));
        String b = dump(ParkingEngine.simulate(site, DIMS, params));
        assertThat(a).isEqualTo(b);
        // 입력 객체를 바꾸지 않는다
        JsonNode site2 = Json.parse(Json.write(site));
        String before = Json.write(site2);
        ParkingEngine.simulate(site2, DIMS, params);
        assertThat(Json.write(site2)).isEqualTo(before);
    }

    /** test_result_is_json_serialisable */
    @Test
    void resultIsJsonSerialisable() {
        SimulationResult res = ParkingEngine.simulate(localToGeojson(rect(50, 60)), DIMS, SimulationParams.defaults());
        assertThat(Json.write(res)).contains("\"alternatives\"", "\"constraint_reasons\"", "\"site_area_m2\"",
                "\"crs_internal\"", "\"alt_code\"", "\"is_recommended\"", "\"stall_count\"");
        assertThat(PyJson.dumps(res.toJson())).isNotEmpty();
        // 레코드 직렬화와 toJson() 이 같은 JSON (키 이름·순서)
        assertThat(Json.parse(Json.write(res))).isEqualTo(res.toJson());
    }

    /** test_performance_irregular_3000m2 */
    @Test
    void performanceIrregular3000m2() {
        ObjectNode site = localToGeojson(scaledToArea(IRREGULAR_HEX, 3000.0));
        ParkingEngine.simulate(localToGeojson(rect(10, 10)), DIMS, SimulationParams.defaults()); // 변환기 초기화 제외
        long t0 = System.nanoTime();
        SimulationResult res = ParkingEngine.simulate(site, DIMS, SimulationParams.defaults());
        double dt = (System.nanoTime() - t0) / 1e9;
        assertThat(res.alternatives()).isNotEmpty();
        assertThat(dt).as("simulate took %.2fs (target < 2 s on a laptop)", dt).isLessThan(5.0);
    }
}
