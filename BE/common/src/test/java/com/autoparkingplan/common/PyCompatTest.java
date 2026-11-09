package com.autoparkingplan.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.autoparkingplan.common.http.Redact;

import tools.jackson.databind.JsonNode;

/** 기대값은 파이썬 3.12 에서 실제로 출력한 값 (이식 전 파이썬판). */
class PyCompatTest {

    @ParameterizedTest
    @CsvSource({
        "0.1,0.1", "1e-05,1e-05", "0.0001,0.0001", "1e16,1e+16", "1e15,1000000000000000.0",
        "123456789012345678.0,1.2345678901234568e+17", "1.5e300,1.5e+300", "-2.5e-7,-2.5e-07", "100.0,100.0", "3.0,3.0",
        "0.30000000000000004,0.30000000000000004", "2.675,2.675", "1e22,1e+22", "5e-324,5e-324", "-0.0,-0.0",
    })
    void floatReprMatchesPython(double x, String expected) {
        assertThat(Py.floatRepr(x)).isEqualTo(expected);
    }

    @Test
    void roundIsBankersOnExactBinaryValue() {
        assertThat(List.of(Py.round(2.675, 2), Py.round(0.125, 2), Py.round(0.375, 2), Py.round(2.5, 2), Py.round(-1.005, 2),
                Py.round(1234.5678, 2))).containsExactly(2.67, 0.12, 0.38, 2.5, -1.0, 1234.57);
        assertThat(List.of(Py.round(0.5), Py.round(1.5), Py.round(2.5), Py.round(-0.5), Py.round(-1.5)))
                .containsExactly(0L, 2L, 2L, 0L, -2L);
    }

    @ParameterizedTest
    @CsvSource({"3.0,3", "0.5,0.5", "1234567,1.23457e+06", "0.0001,0.0001", "0.00001,1e-05", "150.0,150", "2.5e-7,2.5e-07",
        "1e100,1e+100", "123456.5,123456"})
    void formatGMatchesPython(double x, String expected) {
        assertThat(Py.formatG(x)).isEqualTo(expected);
    }

    private static JsonNode sample() {
        return Json.parse("{\"b\":[1,2.0,{\"z\":null,\"a\":true}],\"a\":\"한글\\n\\\"q\\\"\\t\\u0001\\u007f😀\",\"c\":{},\"d\":[],\"é\":1e-07}");
    }

    @Test
    void dumpsSortedMatchesPython() {
        assertThat(PyJson.dumpsSorted(sample()))
                .isEqualTo("{\"a\": \"한글\\n\\\"q\\\"\\t\\u0001\u007f😀\", \"b\": [1, 2.0, {\"a\": true, \"z\": null}], \"c\": {}, \"d\": [], \"é\": 1e-07}");
    }

    @Test
    void dumpsEnsureAsciiMatchesPython() {
        assertThat(PyJson.dumps(sample(), false, true, null)).isEqualTo(
                "{\"b\": [1, 2.0, {\"z\": null, \"a\": true}], \"a\": \"\\ud55c\\uae00\\n\\\"q\\\"\\t\\u0001\\u007f\\ud83d\\ude00\", \"c\": {}, \"d\": [], \"\\u00e9\": 1e-07}");
    }

    @Test
    void dumpsIndentMatchesPython() {
        String expected = String.join("\n", "{", "  \"b\": [", "    1,", "    2.0,", "    {", "      \"z\": null,", "      \"a\": true",
                "    }", "  ],", "  \"a\": \"한글\\n\\\"q\\\"\\t\\u0001\u007f😀\",", "  \"c\": {},", "  \"d\": [],", "  \"é\": 1e-07", "}");
        assertThat(PyJson.dumps(sample(), false, false, 2)).isEqualTo(expected);
    }

    @Test
    void whitespaceFollowsPython() {
        assertThat(Py.strip(" a\u00a0b\u3000c\u0085 ")).isEqualTo("a\u00a0b\u3000c");
        assertThat(Py.split(" a\u00a0b\u3000c d ")).containsExactly("a", "b", "c", "d");
        assertThat(Py.splitlines("a\r\nb\rc\n\nd")).containsExactly("a", "b", "c", "", "d");
        assertThat(Py.strip("--x--", "-")).isEqualTo("x");
    }

    @Test
    void codePointSlicing() {
        String s = "가😀나다";
        assertThat(Py.len(s)).isEqualTo(4);
        assertThat(Py.slice(s, 1, 3)).isEqualTo("😀나");
        assertThat(Py.slice(s, -2, null)).isEqualTo("나다");
        assertThat(Py.find(s, "나")).isEqualTo(2);
        assertThat(Py.slice("abc", 5, 9)).isEmpty();
    }

    @Test
    void redactHidesKeys() {
        String out = Redact.secrets("https://x/DRF/lawService.do?OC=myoc&confmKey=abc&serviceKey=x%2By&key=v&doc=keep", List.of("supersecret"));
        assertThat(out).doesNotContain("myoc", "abc", "x%2By", "key=v").contains("OC=***", "doc=keep");
        assertThat(Redact.secrets("token supersecret here", List.of("supersecret"))).isEqualTo("token *** here");
    }

    @Test
    void sha256() {
        assertThat(Py.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void uuid5MatchesPython() {
        assertThat(Uuids.uuid5(java.util.UUID.fromString("6f1d3c2e-9a51-4c7e-8a0b-1f2e3d4c5b6a"), "abc한글"))
                .hasToString("8fc48db0-7d28-5620-a8a9-323df4216ce9");
    }

    @Test
    void isoformatMatchesPython() {
        assertThat(Times.isoformat(java.time.Instant.parse("2026-11-06T03:04:05.123456Z"))).isEqualTo("2026-11-06T03:04:05.123456+00:00");
        assertThat(Times.isoformat(java.time.Instant.parse("2026-11-06T03:04:05Z"))).isEqualTo("2026-11-06T03:04:05+00:00");
    }
}
