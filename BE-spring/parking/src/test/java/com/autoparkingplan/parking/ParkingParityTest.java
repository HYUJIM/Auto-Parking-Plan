package com.autoparkingplan.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬판과의 결과 대조 (교차 구현 동등성).
 *
 * <p>{@code src/test/resources/parity/generate.py} 가 파이썬 엔진(BE/app/parking/engine.py)으로 만든
 * {@code parity_cases.json.gz} 의 입력(대지 GeoJSON·치수·매개변수)을 그대로 자바 엔진에 넣고 결과 전체
 * ({@code dataclasses.asdict(result)} 와 같은 구조)를 비교한다.
 *
 * <p>비교 규칙
 * <ul>
 *   <li>문자열·불리언·정수(구획 수 등)·null·배열 길이·객체 키 순서: 정확히 같아야 한다</li>
 *   <li>GeoJSON 좌표(경위도, 소수 7자리 반올림): 차이 ≤ 1.5e-7° (반올림 경계에서 끝자리 1 차이 허용, 약 1.5 cm)</li>
 *   <li>그 밖의 실수(면적·효율·방향 등, 소수 2자리 반올림): 차이 ≤ 0.0101 (끝자리 1 차이 허용)</li>
 * </ul>
 * 허용오차의 근거: 자바와 파이썬은 같은 알고리즘·같은 연산 순서를 쓰지만 sin·cos·atan2·hypot 같은 수학 함수의
 * 마지막 비트가 플랫폼 라이브러리(macOS libm 대 Java StrictMath)마다 다르다. 이 차이(1e-9 m 미만)가 반올림 경계에
 * 걸린 값에서만 마지막 자리 1 차이로 드러난다. 배치(구획 수·형식·방향·출입구 선택)는 정확히 같아야 한다.
 */
class ParkingParityTest {
    static final double COORD_TOL = 1.5e-7;
    static final double VALUE_TOL = 0.0101;

    private static final Map<String, String> MISMATCHES = new LinkedHashMap<>();
    /** 투영 고정 대조의 실수 비교 통계: [같음, 허용오차 안]. */
    private static final int[] PINNED_STATS = new int[2];
    private static int casesRun;

    static JsonNode fixture() {
        try (InputStream raw = ParkingParityTest.class.getResourceAsStream("/parity/parity_cases.json.gz")) {
            if (raw == null) {
                throw new IllegalStateException("parity fixture missing: run generate.py");
            }
            return Json.parse(new GZIPInputStream(raw));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ParkingDimensions dims(JsonNode d) {
        Map<String, StallSize> np = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : d.get("stall_non_parallel").properties()) {
            np.put(e.getKey(), new StallSize(e.getValue().get(0).doubleValue(), e.getValue().get(1).doubleValue()));
        }
        Map<String, StallSize> par = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : d.get("stall_parallel").properties()) {
            par.put(e.getKey(), new StallSize(e.getValue().get(0).doubleValue(), e.getValue().get(1).doubleValue()));
        }
        Map<String, Map<String, Double>> aisle = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : d.get("aisle_width").properties()) {
            Map<String, Double> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> w : e.getValue().properties()) {
                m.put(w.getKey(), w.getValue().doubleValue());
            }
            aisle.put(e.getKey(), m);
        }
        JsonNode g = d.get("gate_width_min_m");
        List<String> ids = new ArrayList<>();
        d.get("rule_ids").values().forEach(n -> ids.add(n.stringValue()));
        return new ParkingDimensions(np, par, aisle, Json.isNull(g) ? null : g.doubleValue(), ids);
    }

    static SimulationParams params(JsonNode p) {
        List<String> angles = null;
        if (!Json.isNull(p.get("angles"))) {
            angles = new ArrayList<>();
            for (JsonNode a : p.get("angles").values()) {
                angles.add(a.stringValue());
            }
        }
        List<Double> gp = null;
        if (!Json.isNull(p.get("gate_point"))) {
            gp = new ArrayList<>();
            for (JsonNode a : p.get("gate_point").values()) {
                gp.add(a.doubleValue());
            }
        }
        return new SimulationParams(angles, p.get("stall_type").stringValue(), p.get("setback_m").doubleValue(), gp,
                p.get("gate_count").intValue(), p.get("max_alternatives").intValue(),
                p.get("orientation_step_deg").doubleValue());
    }

    /** 사례 이름 → 차이 목록 (비어 있으면 같음). */
    static List<String> runCase(JsonNode c, int[] stats) {
        SimulationResult res = ParkingEngine.simulate(c.get("site"), dims(c.get("dims")), params(c.get("params")));
        List<String> diffs = new ArrayList<>();
        compare("$", c.get("expected"), res.toJson(), diffs, stats);
        return diffs;
    }

    /** 파이썬(pyproj)이 기록한 EPSG:4326 → 5179 값으로 정방향 변환을 고정한다. */
    static void pinForward(JsonNode c) {
        Map<List<Double>, double[]> table = new java.util.HashMap<>();
        for (JsonNode r : c.get("forward").values()) {
            table.put(List.of(r.get(0).doubleValue(), r.get(1).doubleValue()),
                    new double[] {r.get(2).doubleValue(), r.get(3).doubleValue()});
        }
        Geo.overrideTransformer(Geo.WGS84, Geo.UTMK, Geo.getTransformer(Geo.WGS84, Geo.UTMK).pinned(table));
    }

    /**
     * 원래 파이썬 테스트 없음 — 교차 구현 대조 (투영 고정): 입력 좌표의 EPSG:5179 변환값만 파이썬이 계산한 값으로 고정하고
     * 나머지(버퍼·탐색·출입구·출력 변환)는 자바가 계산한다. 엔진 이식이 같다면 모든 사례가 같아야 한다.
     */
    @TestFactory
    Stream<DynamicTest> parityWithPinnedProjection() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases").values()) {
            String name = c.get("name").stringValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                pinForward(c);
                List<String> diffs;
                try {
                    diffs = runCase(c, PINNED_STATS);
                } finally {
                    Geo.resetTransformers();
                }
                synchronized (MISMATCHES) {
                    casesRun++;
                    if (!diffs.isEmpty()) {
                        MISMATCHES.put("pinned " + name, diffs.size() + " diffs, first: " + diffs.get(0));
                    }
                }
                assertThat(diffs).as(name).isEmpty();
            }));
        }
        return tests.stream();
    }

    /**
     * 투영 결과가 플랫폼 수학 함수 차이로 달라져, 파이썬 결과 자체가 1e-9 m 수준 입력 변화에 반응하는 사례.
     * 투영을 고정하면 같아진다 ({@link #parityWithPinnedProjection}).
     * <ul>
     *   <li>random/20261106/3: 최소회전사각형 꼭짓점 계산(먼 기준점의 직선 교차)이 불안정해, 입력 끝자리 차이가
     *       방향 후보 각도 4e-5° 차이로 커지고 중복 제거(1e-6°) 결과가 달라진다 → 다른 방향 후보 집합</li>
     *   <li>dumbbell/unreachable_gate: 축에 평행한 대지 변이 띠 맞춤 허용오차(1e-9 m) 경계에 걸려, 같은 구획 수의
     *       phase 0.0/0.5 중 고르는 결과가 갈린다 (최소회전사각형 방향 후보도 다르다)</li>
     * </ul>
     */
    static final Set<String> PROJECTION_SENSITIVE = Set.of("random/20261106/3", "dumbbell/unreachable_gate");

    /**
     * 원래 파이썬 테스트 없음 — 교차 구현 대조 (자바 투영): 입력부터 출력까지 전부 자바로 계산해 파이썬 기록과 비교한다.
     * 알려진 투영 민감 사례({@link #PROJECTION_SENSITIVE}) 밖에서 차이가 나면 실패.
     */
    @Test
    void parityWithJavaProjection() {
        Map<String, String> natural = new LinkedHashMap<>();
        int[] stats = new int[2];
        int n = 0;
        for (JsonNode c : fixture().get("cases").values()) {
            String name = c.get("name").stringValue();
            List<String> diffs = runCase(c, stats);
            n++;
            if (!diffs.isEmpty()) {
                natural.put(name, diffs.size() + " diffs, first: " + diffs.get(0));
            }
        }
        System.out.printf("[parity] java-projection cases=%d mismatched=%d floats exact=%d within-tolerance=%d%n", n,
                natural.size(), stats[0], stats[1]);
        natural.forEach((k, v) -> System.out.println("[parity] java-projection MISMATCH " + k + ": " + v));
        assertThat(natural.keySet()).isSubsetOf(PROJECTION_SENSITIVE);
    }

    @AfterAll
    static void report() {
        System.out.printf("[parity] pinned-projection cases=%d mismatched=%d floats exact=%d within-tolerance=%d%n",
                casesRun, MISMATCHES.size(), PINNED_STATS[0], PINNED_STATS[1]);
        MISMATCHES.forEach((k, v) -> System.out.println("[parity] MISMATCH " + k + ": " + v));
    }

    static void compare(String path, JsonNode exp, JsonNode act, List<String> diffs, int[] stats) {
        if (diffs.size() > 20) {
            return;
        }
        if (Json.isNull(exp) || Json.isNull(act)) {
            if (Json.isNull(exp) != Json.isNull(act)) {
                diffs.add(path + ": expected " + exp + " got " + act);
            }
            return;
        }
        if (exp.isObject()) {
            if (!act.isObject()) {
                diffs.add(path + ": expected object got " + act.getNodeType());
                return;
            }
            List<String> ek = new ArrayList<>(exp.propertyNames());
            List<String> ak = new ArrayList<>(act.propertyNames());
            if (!ek.equals(ak)) {
                diffs.add(path + ": keys " + ek + " vs " + ak);
                return;
            }
            for (String k : ek) {
                compare(path + "." + k, exp.get(k), act.get(k), diffs, stats);
            }
            return;
        }
        if (exp.isArray()) {
            if (!act.isArray() || exp.size() != act.size()) {
                diffs.add(path + ": array size " + exp.size() + " vs " + (act.isArray() ? act.size() : act.getNodeType()));
                return;
            }
            Iterator<JsonNode> ei = exp.values().iterator();
            Iterator<JsonNode> ai = act.values().iterator();
            int i = 0;
            while (ei.hasNext()) {
                compare(path + "[" + i++ + "]", ei.next(), ai.next(), diffs, stats);
            }
            return;
        }
        if (exp.isNumber()) {
            if (!act.isNumber()) {
                diffs.add(path + ": expected number " + exp + " got " + act);
                return;
            }
            if (exp.isIntegralNumber() || act.isIntegralNumber()) {
                if (!(exp.isIntegralNumber() && act.isIntegralNumber()) || exp.longValue() != act.longValue()) {
                    diffs.add(path + ": " + exp + " vs " + act);
                }
                return;
            }
            double e = exp.doubleValue();
            double a = act.doubleValue();
            if (e == a || (Double.isNaN(e) && Double.isNaN(a))) {
                stats[0]++;
                return;
            }
            double tol = path.contains("coordinates") ? COORD_TOL : VALUE_TOL;
            if (Math.abs(e - a) <= tol) {
                stats[1]++;
                return;
            }
            diffs.add(path + ": " + e + " vs " + a);
            return;
        }
        if (!exp.equals(act)) {
            diffs.add(path + ": " + exp + " vs " + act);
        }
    }
}
