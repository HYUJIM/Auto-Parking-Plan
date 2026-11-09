package com.autoparkingplan.parking;

import static com.autoparkingplan.parking.ParkingTestSupport.ALL_ANGLES;
import static com.autoparkingplan.parking.ParkingTestSupport.L_SHAPE;
import static com.autoparkingplan.parking.ParkingTestSupport.PENTAGON;
import static com.autoparkingplan.parking.ParkingTestSupport.assertLayoutValid;
import static com.autoparkingplan.parking.ParkingTestSupport.box;
import static com.autoparkingplan.parking.ParkingTestSupport.localToGeojson;
import static com.autoparkingplan.parking.ParkingTestSupport.makeDims;
import static com.autoparkingplan.parking.ParkingTestSupport.rect;
import static com.autoparkingplan.parking.ParkingTestSupport.rotatedRect;
import static com.autoparkingplan.parking.ParkingTestSupport.roughUpperBound;
import static com.autoparkingplan.parking.ParkingTestSupport.toLocal;
import static com.autoparkingplan.parking.ParkingTestSupport.widths;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.parking.geom.Shapely;

/** 합성 대지별 배치 결과: 모든 대안의 불변식 + 대수의 타당성. 파이썬 {@code tests/parking/test_engine_layouts.py}. */
class EngineLayoutsTest {
    static final ParkingDimensions DIMS = makeDims();

    static final Map<String, Geometry> SITES = new LinkedHashMap<>();

    static {
        SITES.put("rect_20x40", rect(20, 40));
        SITES.put("rect_50x60", rect(50, 60));
        SITES.put("rotated_33", rotatedRect(30, 45, 33));
        SITES.put("l_shape", L_SHAPE);
        SITES.put("pentagon", PENTAGON);
        SITES.put("strip_8x60", rect(8, 60));
    }

    record Run(SimulationResult res, List<LayoutGeometry> geoms, SimulationParams params) {
    }

    static Run run(Geometry siteLocal, ParkingDimensions dims, SimulationParams params) {
        ParkingEngine.Detailed d = ParkingEngine.simulateDetailed(localToGeojson(siteLocal), dims, params);
        assertThat(d.result().alternatives()).hasSameSizeAs(d.geoms());
        return new Run(d.result(), d.geoms(), params);
    }

    static Run run(Geometry siteLocal, SimulationParams params) {
        return run(siteLocal, DIMS, params);
    }

    /** (대지, 각도) → 결과. 각도 하나씩 따로 돌린다 (파이썬 module 범위 fixture {@code per_angle_results}). */
    static final Map<String, Run> PER_ANGLE = new LinkedHashMap<>();

    @BeforeAll
    static void perAngleResults() {
        if (!PER_ANGLE.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Geometry> e : SITES.entrySet()) {
            for (String angle : ALL_ANGLES) {
                PER_ANGLE.put(e.getKey() + "/" + angle,
                        run(e.getValue(), SimulationParams.defaults().withAngles(List.of(angle)).withMaxAlternatives(3)));
            }
        }
    }

    static Run perAngle(String name, String angle) {
        return PER_ANGLE.get(name + "/" + angle);
    }

    static Stream<Arguments> siteAngles() {
        return SITES.keySet().stream().flatMap(n -> ALL_ANGLES.stream().map(a -> Arguments.of(n, a)));
    }

    /** test_invariants_every_alternative (parametrize name × angle) */
    @ParameterizedTest
    @MethodSource("siteAngles")
    void invariantsEveryAlternative(String name, String angle) {
        Run r = perAngle(name, angle);
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            Alternative alt = r.res.alternatives().get(i);
            assertThat(alt.parkingAngle()).isEqualTo(angle);
            assertLayoutValid(r.geoms.get(i), alt, DIMS, r.params);
        }
    }

    static Stream<String> siteNames() {
        return SITES.keySet().stream();
    }

    /** test_invariants_all_angles_default_params (parametrize name) */
    @ParameterizedTest
    @MethodSource("siteNames")
    void invariantsAllAnglesDefaultParams(String name) {
        Run r = run(SITES.get(name), SimulationParams.defaults());
        assertThat(r.res.alternatives()).as(r.res.constraintReasons().toString()).isNotEmpty();
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            assertLayoutValid(r.geoms.get(i), r.res.alternatives().get(i), DIMS, r.params);
        }
    }

    /** test_rect_50x60_counts_are_sensible */
    @Test
    void rect50x60CountsAreSensible() {
        Alternative best = perAngle("rect_50x60", "PERPENDICULAR_90").res.alternatives().get(0);
        double usable = best.metrics().get("usable_area_m2").doubleValue();
        assertThat(Math.abs(usable - 49 * 59)).isLessThan(0.01);
        int ub = roughUpperBound(usable, "PERPENDICULAR_90", DIMS);
        assertThat(best.stallCount()).isGreaterThan(60);
        assertThat(best.stallCount()).isBetween((int) Math.ceil(0.8 * ub), ub);
        assertThat(best.metrics().get("efficiency_percent").doubleValue()).isBetween(45.0, 65.0);
        assertThat(best.metrics().get("area_per_stall_m2").doubleValue()).isBetween(20.0, 30.0);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String angle : ALL_ANGLES) {
            int n = perAngle("rect_50x60", angle).res.alternatives().get(0).stallCount();
            assertThat((double) n).isBetween(0.6 * roughUpperBound(usable, angle, DIMS), (double) roughUpperBound(usable, angle, DIMS));
            counts.put(angle, n);
        }
        // 직각주차가 가장 효율적
        assertThat(counts.get("PERPENDICULAR_90")).isEqualTo(counts.values().stream().max(Integer::compare).orElseThrow());
    }

    /** test_rect_20x40_counts_are_sensible */
    @Test
    void rect20x40CountsAreSensible() {
        Alternative best = perAngle("rect_20x40", "PERPENDICULAR_90").res.alternatives().get(0);
        // 19 m 폭 → 양측 직각 모듈(5+6+5) 1개, 39 m 길이 → 열당 15대
        assertThat(best.stallCount()).isGreaterThanOrEqualTo(28);
        assertThat(best.stallCount()).isLessThanOrEqualTo(
                roughUpperBound(best.metrics().get("usable_area_m2").doubleValue(), "PERPENDICULAR_90", DIMS));
        // 차로는 긴 변 방향(대략 남북)
        assertThat(Math.abs(best.orientationDeg() - 90)).isLessThan(2);
        for (String angle : ALL_ANGLES) {
            assertThat(perAngle("rect_20x40", angle).res.alternatives().get(0).stallCount()).isGreaterThan(10);
        }
    }

    /** test_rotation_invariance — 같은 직사각형을 33° 돌려도 대수는 (거의) 같고, 차로는 대지 변과 평행 */
    @Test
    void rotationInvariance() {
        SimulationParams p = SimulationParams.defaults().withAngles(List.of("PERPENDICULAR_90"));
        Alternative base = run(rect(30, 45), p).res.alternatives().get(0);
        Alternative rot = run(rotatedRect(30, 45, 33), p).res.alternatives().get(0);
        assertThat(Math.abs(base.stallCount() - rot.stallCount())).isLessThanOrEqualTo(1);
        double d = rot.orientationDeg() % 90;
        assertThat(Math.min(Math.abs(d - 33), Math.min(Math.abs(d - 33 + 90), Math.abs(d - 33 - 90)))).isLessThan(0.01);
    }

    /** test_l_shape_and_pentagon_use_area */
    @Test
    void lShapeAndPentagonUseArea() {
        for (String name : List.of("l_shape", "pentagon")) {
            Alternative best = perAngle(name, "PERPENDICULAR_90").res.alternatives().get(0);
            assertThat(best.metrics().get("efficiency_percent").doubleValue()).isGreaterThan(35);
            for (String angle : ALL_ANGLES) {
                assertThat(perAngle(name, angle).res.alternatives()).as(name + "/" + angle).isNotEmpty();
            }
        }
    }

    /** test_narrow_strip_only_parallel */
    @Test
    void narrowStripOnlyParallel() {
        Run r = run(rect(8, 60), SimulationParams.defaults());
        assertThat(r.res.constraintReasons()).contains("LIMIT_SITE_WIDTH_NARROW");
        for (String a : List.of("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45")) {
            assertThat(r.res.constraintReasons()).contains("LIMIT_SITE_WIDTH_NARROW:" + a);
        }
        assertThat(r.res.constraintReasons()).doesNotContain("LIMIT_SITE_WIDTH_NARROW:PARALLEL_0");
        Set<String> angles = new HashSet<>();
        r.res.alternatives().forEach(a -> angles.add(a.parkingAngle()));
        assertThat(angles).containsExactly("PARALLEL_0");
        Alternative best = r.res.alternatives().get(0);
        assertThat(best.isRecommended()).isTrue();
        assertThat(best.stallCount()).isGreaterThanOrEqualTo(8); // 59 m / 6 m → 9대 (편측)
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            assertLayoutValid(r.geoms.get(i), r.res.alternatives().get(i), DIMS, r.params);
        }
    }

    /** test_tiny_site_no_layout */
    @Test
    void tinySiteNoLayout() {
        Run r = run(rect(6, 6), SimulationParams.defaults());
        assertThat(r.res.alternatives()).isEmpty();
        assertThat(r.geoms).isEmpty();
        assertThat(r.res.constraintReasons()).contains("NO_FEASIBLE_LAYOUT", "LIMIT_SITE_WIDTH_NARROW");
        assertThat(Math.abs(r.res.siteAreaM2() - 36.0)).isLessThan(0.01);
    }

    /** test_multipolygon_uses_largest_part */
    @Test
    void multipolygonUsesLargestPart() {
        Polygon big = box(0, 0, 30, 40);
        Polygon small = box(50, 0, 60, 10);
        MultiPolygon mp = ParkingTestSupport.multi(big, small);
        Run r = run(mp, SimulationParams.defaults());
        assertThat(r.res.constraintReasons()).contains("MULTIPART_SITE_LARGEST_USED");
        assertThat(Math.abs(r.res.siteAreaM2() - 1300.0)).isLessThan(0.01); // 전체 면적
        assertThat(r.res.alternatives()).isNotEmpty();
        Geometry bigBuf = Shapely.buffer(big, 1e-6);
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            Alternative alt = r.res.alternatives().get(i);
            LayoutGeometry g = r.geoms.get(i);
            assertThat(alt.constraintReasons()).contains("MULTIPART_SITE_LARGEST_USED");
            assertThat(Math.abs(toLocal(g.site()).getArea() - big.getArea())).isLessThan(1e-6);
            assertThat(g.stalls()).allMatch(s -> toLocal(s).within(bigBuf));
            assertLayoutValid(g, alt, DIMS, r.params);
        }
    }

    /** test_custom_dimensions_are_honoured — 엔진에 치수가 박혀 있지 않음: 전혀 다른 치수를 넣으면 그대로 그려진다 */
    @Test
    void customDimensionsAreHonoured() {
        Map<String, StallSize> np = new LinkedHashMap<>();
        np.put("general", new StallSize(3.3, 5.6));
        Map<String, StallSize> par = new LinkedHashMap<>();
        par.put("general", new StallSize(2.3, 7.1));
        Map<String, Map<String, Double>> aisles = new LinkedHashMap<>();
        aisles.put("PERPENDICULAR_90", widths(7.3, 7.0));
        aisles.put("ANGLE_60", widths(6.1, 5.0));
        aisles.put("ANGLE_45", widths(5.7, 4.1));
        aisles.put("PARALLEL_0", widths(4.4, 3.1));
        ParkingDimensions dims = makeDims().withStallNonParallel(np).withStallParallel(par).withAisleWidth(aisles)
                .withGateWidthMinM(8.0);
        Run r = run(rect(50, 60), dims, SimulationParams.defaults().withMaxAlternatives(4));
        assertThat(r.res.alternatives()).hasSize(4);
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            Alternative alt = r.res.alternatives().get(i);
            LayoutGeometry g = r.geoms.get(i);
            assertLayoutValid(g, alt, dims, r.params);
            assertThat(g.drivewayWidthM()).isEqualTo(8.0); // max(차로 폭, 출입구 최소 너비)
            assertThat(alt.layers().get("gate").get("properties").get("width_m").doubleValue()).isEqualTo(8.0);
        }
    }

    /** test_stall_type_and_two_gates (parametrize stall_type) */
    @ParameterizedTest
    @ValueSource(strings = {"general", "extended"})
    void stallTypeAndTwoGates(String stallType) {
        Run r = run(rect(50, 60), SimulationParams.defaults().withStallType(stallType).withGateCount(2)
                .withMaxAlternatives(4));
        if ("extended".equals(stallType)) {
            assertThat(r.res.constraintReasons()).contains("DIMENSION_MISSING:PARALLEL_0");
            assertThat(r.res.alternatives()).allMatch(a -> !a.parkingAngle().equals("PARALLEL_0"));
        }
        for (int i = 0; i < r.res.alternatives().size(); i++) {
            Alternative alt = r.res.alternatives().get(i);
            assertThat(alt.stallType()).isEqualTo(stallType);
            assertThat(alt.metrics().get("aisle_width_m").doubleValue())
                    .isEqualTo(DIMS.aisleWidth().get(alt.parkingAngle()).get("two_plus_gates"));
            assertLayoutValid(r.geoms.get(i), alt, DIMS, r.params);
        }
    }
}
