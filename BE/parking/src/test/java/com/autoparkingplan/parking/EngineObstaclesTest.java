package com.autoparkingplan.parking;

import static com.autoparkingplan.parking.ParkingTestSupport.ALL_ANGLES;
import static com.autoparkingplan.parking.ParkingTestSupport.assertLayoutValid;
import static com.autoparkingplan.parking.ParkingTestSupport.box;
import static com.autoparkingplan.parking.ParkingTestSupport.localToGeojson;
import static com.autoparkingplan.parking.ParkingTestSupport.makeDims;
import static com.autoparkingplan.parking.ParkingTestSupport.rect;
import static com.autoparkingplan.parking.ParkingTestSupport.toLocal;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.parking.geom.Shapely;

import tools.jackson.databind.JsonNode;

/** 기존 건물(장애물)을 피하는 배치 (자바판 추가). 좌표는 기준점 기준 지역 좌표(m). */
class EngineObstaclesTest {
    static final ParkingDimensions DIMS = makeDims();
    static final double CLEARANCE = 1.0;
    /** 출력 좌표는 위경도 소수 7자리(약 1cm)로 반올림되므로 지역 좌표로 되돌린 검사는 이만큼 허용한다. */
    static final double ROUND_M = 0.02;
    static final double TOL_M2 = 0.05;

    static SimulationParams params() {
        return SimulationParams.defaults().withAngles(new ArrayList<>(ALL_ANGLES));
    }

    static ParkingEngine.Obstacles obstacles(Geometry... local) {
        List<JsonNode> g = new ArrayList<>();
        for (Geometry x : local) {
            g.add(localToGeojson(x));
        }
        return new ParkingEngine.Obstacles(g, CLEARANCE);
    }

    static Geometry local(JsonNode geojson) {
        return toLocal(Geo.toUtmk(Geo.geojsonToShape(geojson)));
    }

    static List<Geometry> layer(Alternative a, String name) {
        List<Geometry> out = new ArrayList<>();
        for (JsonNode f : a.layers().get(name).get("features")) {
            out.add(local(f.get("geometry")));
        }
        return out;
    }

    /** 구획은 건물 둘레 띄울 거리 밖, 차로·진입로는 건물 밖, 구획 수 = 레이어 수 = metrics. */
    static void assertAvoids(Alternative a, Geometry building) {
        List<Geometry> stalls = layer(a, "stalls");
        assertThat(stalls).hasSize(a.stallCount());
        assertThat(a.metrics().get("stall_count").intValue()).isEqualTo(a.stallCount());
        for (Geometry s : stalls) {
            assertThat(s.distance(building)).as("구획이 건물 띄울 거리 안").isGreaterThanOrEqualTo(CLEARANCE - ROUND_M);
        }
        for (Geometry x : layer(a, "aisles")) {
            assertThat(Shapely.intersection(x, building).getArea()).as("차로·진입로가 건물과 겹침").isLessThan(TOL_M2);
        }
    }

    @Test
    void withoutObstaclesTheResultIsUnchanged() {
        JsonNode site = localToGeojson(rect(40, 30));
        String plain = PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params()).toJson());
        assertThat(PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params(), null).toJson())).isEqualTo(plain);
        assertThat(PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params(), new ParkingEngine.Obstacles(List.of(), 1.0)).toJson()))
                .isEqualTo(plain);
        // 대지 밖 건물, 읽을 수 없는 도형은 무시한다
        ParkingEngine.Obstacles away = new ParkingEngine.Obstacles(List.of(localToGeojson(box(100, 100, 120, 120)),
                Json.parse("{\"type\": \"Point\", \"coordinates\": [128.63, 35.85]}"), Json.parse("{\"type\": \"Polygon\"}")), 1.0);
        assertThat(PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params(), away).toJson())).isEqualTo(plain);
    }

    @Test
    void cornerBuildingIsAvoided() {
        JsonNode site = localToGeojson(rect(40, 30));
        Polygon building = box(28, 18, 42, 32); // 모서리에 걸치고 대지 밖까지 나간 건물
        SimulationResult before = ParkingEngine.simulate(site, DIMS, params());
        ParkingEngine.Detailed d = ParkingEngine.simulateDetailed(site, DIMS, params(), obstacles(building));
        SimulationResult res = d.result();
        assertThat(res.alternatives()).isNotEmpty();
        assertThat(res.constraintReasons()).contains(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED)
                .doesNotContain(ParkingEngine.SITE_SPLIT_BY_BUILDINGS);
        assertThat(res.siteAreaM2()).isEqualTo(before.siteAreaM2()); // 대지 면적은 건물과 무관
        assertThat(res.alternatives().get(0).stallCount()).isLessThan(before.alternatives().get(0).stallCount());
        for (int i = 0; i < res.alternatives().size(); i++) {
            Alternative a = res.alternatives().get(i);
            assertThat(a.constraintReasons()).contains(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED);
            assertThat(a.layers().get("gate").get("geometry").get("type").stringValue()).isEqualTo("Point");
            assertAvoids(a, building);
            assertLayoutValid(d.geoms().get(i), a, DIMS, params());
        }
        assertThat(res.alternatives().get(0).isRecommended()).isTrue();
    }

    @Test
    void buildingAcrossTheSiteSplitsItIntoTwoLayouts() {
        JsonNode site = localToGeojson(rect(50, 60));
        Polygon building = box(-1, 30, 51, 38); // 대지를 가로지르는 건물 → 아래 30m · 위 22m 두 조각
        SimulationResult res = ParkingEngine.simulate(site, DIMS, params(), obstacles(building));
        assertThat(res.constraintReasons()).contains(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED, ParkingEngine.SITE_SPLIT_BY_BUILDINGS);
        Alternative a = res.alternatives().get(0);
        assertThat(a.constraintReasons()).contains(ParkingEngine.SITE_SPLIT_BY_BUILDINGS);
        JsonNode gate = a.layers().get("gate").get("geometry");
        assertThat(gate.get("type").stringValue()).isEqualTo("MultiPoint");
        assertThat(gate.get("coordinates")).hasSize(2);
        assertThat(a.layers().get("gate_opening").get("geometry").get("type").stringValue()).isEqualTo("MultiLineString");
        assertThat(a.layers().get("usable_area").get("geometry").get("type").stringValue()).isEqualTo("MultiPolygon");
        assertAvoids(a, building);
        // 두 조각 모두에 구획이 있다
        boolean below = false;
        boolean above = false;
        for (Geometry s : layer(a, "stalls")) {
            double y = s.getCentroid().getY();
            below |= y < 30;
            above |= y > 38;
        }
        assertThat(below && above).isTrue();
        // 진입로(조각마다 하나)가 건물을 지나지 않는다
        long driveways = 0;
        for (JsonNode f : a.layers().get("aisles").get("features")) {
            if ("driveway".equals(f.get("properties").get("kind").stringValue())) {
                driveways++;
                assertThat(Shapely.intersection(local(f.get("geometry")), building).getArea()).isLessThan(TOL_M2);
            }
        }
        assertThat(driveways).isEqualTo(2);
        // 지표: 가용 면적 = 두 조각 합, 효율 = 구획 면적 / 가용 면적
        double usable = a.metrics().get("usable_area_m2").doubleValue();
        assertThat(usable).isCloseTo(local(a.layers().get("usable_area").get("geometry")).getArea(), org.assertj.core.data.Offset.offset(0.5));
        assertThat(a.metrics().get("stall_area_m2").doubleValue()).isEqualTo(ParkingEngine.r2(a.stallCount() * 2.5 * 5.0));
    }

    @Test
    void buildingCoveringTheSiteLeavesNoLayout() {
        JsonNode site = localToGeojson(rect(30, 20));
        SimulationResult res = ParkingEngine.simulate(site, DIMS, params(), obstacles(box(-2, -2, 32, 22)));
        assertThat(res.alternatives()).isEmpty();
        assertThat(res.constraintReasons()).contains(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED, ParkingEngine.NO_FEASIBLE_LAYOUT);
    }

    @Test
    void sameInputGivesSameOutput() {
        JsonNode site = localToGeojson(rect(50, 60));
        ParkingEngine.Obstacles obs = obstacles(box(-1, 30, 51, 38), box(5, 5, 12, 12));
        String a = PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params(), obs).toJson());
        String b = PyJson.dumpsSorted(ParkingEngine.simulate(site, DIMS, params(), obs).toJson());
        assertThat(a).isEqualTo(b);
    }
}
