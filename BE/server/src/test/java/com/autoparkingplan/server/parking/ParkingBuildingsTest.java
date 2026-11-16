package com.autoparkingplan.server.parking;

import static com.autoparkingplan.server.support.World.PNU_R2G;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;

import com.autoparkingplan.external.BuildingFootprint;
import com.autoparkingplan.parking.Geo;
import com.autoparkingplan.parking.ParkingEngine;
import com.autoparkingplan.server.review.ReviewService;
import com.autoparkingplan.server.support.StubVWorld;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 기존 건물을 피하는 배치 (자바판 추가): 건물 조회 → 대지 위 건물 고르기 → 유지·철거 가정 → 응답 블록. */
class ParkingBuildingsTest {
    @TempDir
    Path tmp;
    TestApp app;
    StubVWorld vworld;

    /** PNU_R2G 의 대지는 square(128.63, 35.85) = 경도 0.0004 × 위도 0.0003 (약 36m × 33m). */
    static final BuildingFootprint CORNER = new BuildingFootprint("B-1", "예시주택", 2, StubVWorld.square(128.63025, 35.8502, 0.0002, 0.00015));
    /** 이웃 필지 건물이 경계를 0.9m 쯤 넘어온 것 — 대지 위 건물로 보지 않는다. */
    static final BuildingFootprint NEIGHBOR = new BuildingFootprint("B-2", null, null, StubVWorld.square(128.63039, 35.85, 0.0003, 0.0003));

    @BeforeEach
    void world() {
        vworld = World.exampleVWorld();
        app = World.app(tmp, vworld, null).web(false).start();
        World.load(app);
    }

    @AfterEach
    void close() {
        app.close();
        TestApp.dropDb(app.dbName);
    }

    ObjectNode simulate(UUID rid, String mode) {
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, true, mode);
        return app.tx(em -> app.bean(ParkingBridge.class).prepare(em, rid, params)).finish();
    }

    static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.stringValue()));
        return out;
    }

    @Test
    void keepAvoidsBuildingsAndIgnoreAssumesDemolition() {
        vworld.footprints.add(CORNER);
        vworld.footprints.add(NEIGHBOR);
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);

        ObjectNode keep = simulate(rid, "keep");
        assertThat(keep.get("simulated").booleanValue()).isTrue();
        assertThat(keep.get("params").get("existing_buildings").stringValue()).isEqualTo("keep");
        JsonNode eb = keep.get("existing_buildings");
        assertThat(eb.get("mode").stringValue()).isEqualTo("keep");
        assertThat(eb.get("status").stringValue()).isEqualTo("applied");
        assertThat(eb.get("count").intValue()).isEqualTo(1); // 이웃 건물은 빠진다
        assertThat(eb.get("area_m2").doubleValue()).isBetween(100.0, 250.0);
        assertThat(eb.get("clearance_m").doubleValue()).isEqualTo(ParkingBridge.BUILDING_CLEARANCE_M);
        JsonNode f = eb.get("features").get("features").get(0);
        assertThat(f.get("properties").get("id").stringValue()).isEqualTo("B-1");
        assertThat(f.get("properties").get("floors").intValue()).isEqualTo(2);
        assertThat(texts(keep.get("constraint_reasons"))).contains(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED);
        // 구획이 건물(윤곽선)과 겹치지 않는다
        Geometry building = Geo.toUtmk(Geo.geojsonToShape(CORNER.geometry()));
        JsonNode alt = keep.get("result").get("alternatives").get(0);
        for (JsonNode s : alt.get("layers").get("stalls").get("features")) {
            assertThat(Geo.toUtmk(Geo.geojsonToShape(s.get("geometry"))).intersection(building).getArea()).isLessThan(0.05);
        }

        ObjectNode ignore = simulate(rid, "ignore");
        JsonNode eb2 = ignore.get("existing_buildings");
        assertThat(eb2.get("status").stringValue()).isEqualTo("ignored");
        assertThat(eb2.get("count").intValue()).isEqualTo(1);
        assertThat(texts(ignore.get("constraint_reasons"))).doesNotContain(ParkingEngine.EXISTING_BUILDINGS_EXCLUDED);
        assertThat(ignore.get("params").get("existing_buildings").stringValue()).isEqualTo("ignore");
        assertThat(ignore.get("result").get("alternatives").get(0).get("stall_count").intValue())
                .isGreaterThan(alt.get("stall_count").intValue());
        // 같은 필지는 한 번만 조회한다 (캐시)
        assertThat(vworld.buildingCalls.get()).isEqualTo(1);
    }

    @Test
    void noBuildingOnSiteLeavesTheLayoutUnchanged() {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        ObjectNode none = simulate(rid, "keep");
        assertThat(none.get("existing_buildings").get("status").stringValue()).isEqualTo("none");
        assertThat(none.get("existing_buildings").get("count").intValue()).isZero();
        assertThat(none.get("existing_buildings").get("features").get("features")).isEmpty();
        ObjectNode ignore = simulate(rid, "ignore");
        assertThat(none.get("result")).isEqualTo(ignore.get("result"));
        assertThat(none.get("constraint_reasons")).isEqualTo(ignore.get("constraint_reasons"));
    }

    @Test
    void lookupFailureStillSimulatesAndSaysSo() {
        vworld.buildingError = true;
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        ObjectNode out = simulate(rid, "keep");
        assertThat(out.get("simulated").booleanValue()).isTrue();
        assertThat(out.get("existing_buildings").get("status").stringValue()).isEqualTo("unavailable");
        assertThat(out.get("existing_buildings").get("features").isNull()).isTrue();
        assertThat(texts(out.get("constraint_reasons"))).contains(ParkingBridge.EXISTING_BUILDINGS_UNAVAILABLE);
        // 실패는 기억하지 않는다: 다음 요청이 다시 조회한다
        vworld.buildingError = false;
        vworld.footprints.add(CORNER);
        ObjectNode again = simulate(rid, "keep");
        assertThat(again.get("existing_buildings").get("status").stringValue()).isEqualTo("applied");
        assertThat(vworld.buildingCalls.get()).isEqualTo(2);
    }

    @Test
    void withoutVWorldThereIsNoBuildingBlock() {
        UUID rid = app.bean(ReviewService.class).submit(PNU_R2G, null, "owner", null);
        ParkingBridge.Request params = new ParkingBridge.Request(null, "general", 0.5, null, 1, 3, true);
        ObjectNode out = app.tx(em -> new ParkingBridge().prepare(em, rid, params)).finish();
        assertThat(out.get("simulated").booleanValue()).isTrue();
        assertThat(out.has("existing_buildings")).isFalse();
        assertThat(params.existingBuildings()).isEqualTo("keep");
    }

    @Test
    void cacheExpiresAfterTtl() {
        long[] now = {0L};
        BuildingFootprints bf = new BuildingFootprints(vworld, () -> now[0]);
        double[] box = {128.63, 35.85, 128.6304, 35.8503};
        assertThat(bf.lookup(PNU_R2G, box).ok()).isTrue();
        assertThat(bf.lookup(PNU_R2G, box).ok()).isTrue();
        assertThat(vworld.buildingCalls.get()).isEqualTo(1);
        now[0] = BuildingFootprints.TTL.toNanos() + 1;
        bf.lookup(PNU_R2G, box);
        assertThat(vworld.buildingCalls.get()).isEqualTo(2);
        assertThat(new BuildingFootprints(null).enabled()).isFalse();
        assertThat(new BuildingFootprints(null).lookup(PNU_R2G, box).ok()).isFalse();
    }
}
