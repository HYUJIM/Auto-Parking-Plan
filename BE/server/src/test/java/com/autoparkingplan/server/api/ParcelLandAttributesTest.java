package com.autoparkingplan.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.db.entity.Parcel;
import com.autoparkingplan.external.CadastralParcel;
import com.autoparkingplan.external.LandCharacteristics;
import com.autoparkingplan.external.LandUseDesignation;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorld;
import com.autoparkingplan.external.VWorldApi;
import com.autoparkingplan.external.VWorldClient;
import com.autoparkingplan.server.config.ExternalClients;
import com.autoparkingplan.server.support.Http;
import com.autoparkingplan.server.support.TestApp;
import com.autoparkingplan.server.support.World;
import com.autoparkingplan.server.tools.mock.MockDataset;
import com.autoparkingplan.server.tools.mock.MockServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공개 필지 API 의 토지형상·지형높이·토지이용상황({@code land_shape}·{@code terrain_height}·{@code land_use_status}) (자바판 추가).
 *
 * <p>모의 공공 API 서버({@link MockServer}, {@code data/mock/parcels.json})에 실제 브이월드 클라이언트를 붙여, 원천 필드
 * (tpgrphFrmCodeNm·tpgrphHgCodeNm·ladUseSittnNm) → 필지 캐시(source_meta) → 응답까지 확인한다.
 */
class ParcelLandAttributesTest {
    /** BE/data/mock/parcels.json (시험 작업 폴더는 server 모듈 폴더). */
    static final Path FIXTURES = Path.of("..", "data", "mock", "parcels.json");
    /** 모의 필지 S01 (범어동 9001, 정방형·평지·단독). */
    static final String S01 = "2726010100190010000";

    static MockServer mock;

    @TempDir
    Path tmp;
    TestApp app;
    Http c;
    SwitchableVWorld vworld;

    @BeforeAll
    static void startMock() {
        mock = MockServer.start("127.0.0.1", 0, FIXTURES, 0.2);
    }

    @AfterAll
    static void stopMock() {
        mock.close();
    }

    @BeforeEach
    void client() {
        String base = "http://127.0.0.1:" + mock.port();
        vworld = new SwitchableVWorld(new VWorldClient("mock-key", null, base, base, 5.0, null));
        app = TestApp.builder(tmp).external(new ExternalClients(null, vworld, null)).llms(World.fakeLlms("judge")).start();
        c = app.http();
    }

    @AfterEach
    void close() {
        app.close();
        vworld.inner.close();
        TestApp.dropDb(app.dbName);
    }

    /** 문자열 값 또는 null. 키가 아예 없으면 실패 (값을 모를 때도 키는 null 로 있어야 한다). */
    static String attr(JsonNode body, String key) {
        assertThat(body.has(key)).as("응답에 %s 키", key).isTrue();
        JsonNode v = body.get(key);
        return v.isNull() ? null : v.stringValue();
    }

    JsonNode parcel(String pnu) {
        Http.Response r = c.get("/v1/parcels/" + pnu).send();
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return r.json();
    }

    /** 모든 모의 필지: 응답의 세 값 = 모의 데이터의 land.form·height·land_use_status (도로접면도 함께). */
    @Test
    void parcelDetailExposesLandAttributesForEveryMockParcel() {
        for (MockDataset.Parcel p : mock.dataset().parcels()) {
            JsonNode land = p.record().get("land");
            JsonNode body = parcel(p.pnu());
            assertThat(attr(body, "land_shape")).as("%s 토지형상", p.id()).isEqualTo(land.get("form").stringValue());
            assertThat(attr(body, "terrain_height")).as("%s 지형높이", p.id()).isEqualTo(land.get("height").stringValue());
            assertThat(attr(body, "land_use_status")).as("%s 토지이용상황", p.id()).isEqualTo(land.get("land_use_status").stringValue());
            assertThat(attr(body, "road_side")).as("%s 도로접면", p.id()).isEqualTo(land.get("road_side").stringValue());
        }
        JsonNode s01 = parcel(S01);
        assertThat(attr(s01, "land_shape")).isEqualTo("정방형");
        assertThat(attr(s01, "terrain_height")).isEqualTo("평지");
        assertThat(attr(s01, "land_use_status")).isEqualTo("단독");
    }

    /** 지도 클릭(GET /v1/parcels/at)도 같은 키를 준다. */
    @Test
    void parcelAtExposesLandAttributes() {
        JsonNode centroid = mock.dataset().byPnu(S01).summary().get("centroid");
        Http.Response r = c.get("/v1/parcels/at").query("lng", String.valueOf(centroid.get(0).doubleValue()))
                .query("lat", String.valueOf(centroid.get(1).doubleValue())).send();
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("pnu").stringValue()).isEqualTo(S01);
        assertThat(attr(body, "land_shape")).isEqualTo("정방형");
        assertThat(attr(body, "terrain_height")).isEqualTo("평지");
        assertThat(attr(body, "land_use_status")).isEqualTo("단독");
    }

    /** 이 키가 생기기 전에 저장된 캐시(source_meta 에 land_attributes 없음) → 세 값 null, 다시 조회되면 채워진다. */
    @Test
    void legacyCacheWithoutAttributesYieldsNullUntilRefetched() {
        parcel(S01);
        app.txRun(em -> {
            Parcel row = em.find(Parcel.class, S01);
            ObjectNode meta = (ObjectNode) row.getSourceMeta().deepCopy();
            assertThat(meta.get("land_attributes").get("land_shape").stringValue()).isEqualTo("정방형");
            meta.remove("land_attributes");
            row.setSourceMeta(meta);
        });
        JsonNode cached = parcel(S01);
        assertThat(attr(cached, "land_shape")).isNull();
        assertThat(attr(cached, "terrain_height")).isNull();
        assertThat(attr(cached, "land_use_status")).isNull();
        assertThat(cached.get("land_area_m2").doubleValue()).isEqualTo(624.1); // 나머지는 그대로

        app.txRun(em -> em.find(Parcel.class, S01).setExpiresAt(Instant.now().minusSeconds(1)));
        JsonNode refetched = parcel(S01);
        assertThat(attr(refetched, "land_shape")).isEqualTo("정방형");
        assertThat(attr(refetched, "terrain_height")).isEqualTo("평지");
        assertThat(attr(refetched, "land_use_status")).isEqualTo("단독");
    }

    /** 다시 조회할 때 토지임야·토지특성 조회가 모두 실패 → 면적·지목·도로접면처럼 이전 값을 유지하고 PARCEL_PARTIAL 안내. */
    @Test
    void landCharacteristicsOutageKeepsPreviousAttributes() {
        JsonNode body = assertKeptAfterRefetch(Mode.ALL_FAIL);
        assertThat(attr(body, "road_side")).isEqualTo("세로한면(가)");
    }

    /**
     * 다시 조회할 때 토지특성(getLandCharacteristics)만 실패하고 토지임야는 받음(부분 결과) → 세 값은 이전 값 유지.
     * 도로접면(road_side)은 파이썬판부터의 동작대로 null 로 덮인다 (BE/README.md 필지 응답 설명에 적어 둔 차이).
     */
    @Test
    void landCharacteristicsPartialKeepsPreviousAttributes() {
        JsonNode body = assertKeptAfterRefetch(Mode.CHR_ONLY_FAILS);
        assertThat(attr(body, "road_side")).isNull();
    }

    /**
     * 토지특성이 '지정되지않음'·'-'·빈 값을 주면(조회 실패가 아님) → 해석 단계에서 null, 캐시에는 JSON null 로 저장되고 응답 키는 null.
     * 이전 값이 있었어도 덮이며 PARCEL_PARTIAL 은 붙지 않는다.
     */
    @Test
    void unspecifiedLandCharacteristicsBecomeNullAndOverwritePrevious() {
        assertThat(attr(parcel(S01), "land_shape")).isEqualTo("정방형");
        app.txRun(em -> em.find(Parcel.class, S01).setExpiresAt(Instant.now().minusSeconds(1)));
        vworld.mode = Mode.UNSPECIFIED;
        JsonNode body = parcel(S01);
        assertThat(attr(body, "land_shape")).isNull();
        assertThat(attr(body, "terrain_height")).isNull();
        assertThat(attr(body, "land_use_status")).isNull();
        assertThat(attr(body, "road_side")).isNull();
        assertThat(body.get("land_area_m2").doubleValue()).isEqualTo(624.1);
        assertThat(noticeCodes(body)).doesNotContain("PARCEL_PARTIAL");
        app.txRun(em -> {
            JsonNode stored = em.find(Parcel.class, S01).getSourceMeta().get("land_attributes");
            for (String k : List.of("land_shape", "terrain_height", "land_use_status")) {
                assertThat(stored.has(k)).as("저장된 %s 키", k).isTrue();
                assertThat(stored.get(k).isNull()).as("저장된 %s 값", k).isTrue();
            }
        });
    }

    /** 처음 조회부터 토지특성만 실패 → 이전 값이 없으므로 세 값 null (키는 있음), 면적은 토지임야 값. */
    @Test
    void firstFetchWithoutLandCharacteristicsYieldsNull() {
        vworld.mode = Mode.CHR_ONLY_FAILS;
        JsonNode body = parcel(S01);
        assertThat(attr(body, "land_shape")).isNull();
        assertThat(attr(body, "terrain_height")).isNull();
        assertThat(attr(body, "land_use_status")).isNull();
        assertThat(body.get("land_area_m2").doubleValue()).isEqualTo(624.1);
        assertThat(noticeCodes(body)).contains("PARCEL_PARTIAL");
    }

    private JsonNode assertKeptAfterRefetch(Mode mode) {
        JsonNode first = parcel(S01);
        assertThat(attr(first, "land_shape")).isEqualTo("정방형");
        assertThat(attr(first, "road_side")).isEqualTo("세로한면(가)");
        app.txRun(em -> em.find(Parcel.class, S01).setExpiresAt(Instant.now().minusSeconds(1)));
        vworld.mode = mode;
        JsonNode body = parcel(S01);
        assertThat(attr(body, "land_shape")).isEqualTo("정방형");
        assertThat(attr(body, "terrain_height")).isEqualTo("평지");
        assertThat(attr(body, "land_use_status")).isEqualTo("단독");
        assertThat(noticeCodes(body)).contains("PARCEL_PARTIAL");
        return body;
    }

    static List<String> noticeCodes(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.get("data_notices").values().forEach(n -> codes.add(n.get("code").stringValue()));
        return codes;
    }

    /** 토지특성 조회 상태: 정상 / 토지임야·토지특성 모두 실패 / 토지특성만 실패(토지임야는 받음) / 토지특성이 값 없음 표기를 줌. */
    enum Mode {
        OK, ALL_FAIL, CHR_ONLY_FAILS, UNSPECIFIED
    }

    /**
     * 모의 서버가 준 실제 토지특성 레코드에서 도로접면·토지형상·지형높이·토지이용상황만 값 없음 표기로 바꿔,
     * 브이월드 응답 봉투 그대로 다시 해석한다 (해석 → 합치기 → 저장 → 응답 경로 전체를 거치게).
     */
    static Optional<LandCharacteristics> unspecifiedCharacteristics(VWorldClient inner, String pnu) {
        ObjectNode rec = inner.getLandCharAttr(pnu).orElseThrow().raw().deepCopy();
        rec.put("roadSideCodeNm", "지정되지않음").put("tpgrphFrmCodeNm", "지정되지않음").put("tpgrphHgCodeNm", "-").put("ladUseSittnNm", "  ");
        ObjectNode payload = Json.object();
        ObjectNode envelope = payload.putObject("landCharacteristicss");
        envelope.put("resultCode", "");
        envelope.putArray("field").add(rec);
        LandCharacteristics chr = VWorld.parseLandCharacteristicsResponse(payload, pnu).orElseThrow();
        return VWorld.mergeLandCharacteristics(pnu, chr, inner.getLandRegister(pnu).orElse(null), Map.of(), null);
    }

    /** 실제 클라이언트에 위임하되 토지특성 조회만 장애·값 없음 표기로 바꿀 수 있는 브이월드. */
    static final class SwitchableVWorld implements VWorldApi {
        final VWorldClient inner;
        volatile Mode mode = Mode.OK;

        SwitchableVWorld(VWorldClient inner) {
            this.inner = inner;
        }

        @Override
        public Optional<CadastralParcel> getParcel(String pnu) {
            return inner.getParcel(pnu);
        }

        @Override
        public Optional<CadastralParcel> getParcelAt(double lng, double lat) {
            return inner.getParcelAt(lng, lat);
        }

        @Override
        public List<LandUseDesignation> getLandUse(String pnu) {
            return inner.getLandUse(pnu);
        }

        @Override
        public Optional<LandCharacteristics> getLandCharacteristics(String pnu) {
            return switch (mode) {
                case OK -> inner.getLandCharacteristics(pnu);
                case ALL_FAIL -> throw new UpstreamError("vworld", "토지임야·토지특성 조회 모두 실패(시험)", true);
                // VWorldClient.getLandCharacteristics 가 토지특성만 실패했을 때 돌려주는 것과 같은 부분 결과
                case CHR_ONLY_FAILS -> VWorld.mergeLandCharacteristics(pnu, null, inner.getLandRegister(pnu).orElse(null),
                        Map.of("getLandCharacteristics", "시간 초과(시험)"), null);
                case UNSPECIFIED -> unspecifiedCharacteristics(inner, pnu);
            };
        }
    }
}
