package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

/**
 * (자바 추가) {@code app.external._http} 의 값 변환 함수와 파이썬 내장 동작 흉내({@link PyStr})가 파이썬과 같은 결과를 내는지.
 * 기대값은 파이썬 3.12(BE/.venv)에서 실제로 출력한 값.
 */
class HttpHelpersTest {

    static Stream<Arguments> toFloatCases() {
        return Stream.of(
                Arguments.of("1,234.5", 1234.5), Arguments.of(" 1_000 ", 1000.0), Arguments.of("１２３", 123.0),
                Arguments.of("٣.٥", 3.5), Arguments.of("inf", Double.POSITIVE_INFINITY),
                Arguments.of("-Infinity", Double.NEGATIVE_INFINITY), Arguments.of("nan", Double.NaN),
                Arguments.of("0x10", null), Arguments.of("1.5d", null), Arguments.of("1e5", 100000.0), Arguments.of(".5", 0.5),
                Arguments.of("5.", 5.0), Arguments.of("+.5e-3", 0.0005), Arguments.of("1__0", null), Arguments.of("", null),
                Arguments.of("  ", null), Arguments.of(true, null), Arguments.of(12, 12.0), Arguments.of(12.5, 12.5),
                Arguments.of(Json.parse("{\"a\": 1}"), null), Arguments.of(Json.parse("[1]"), null), Arguments.of(null, null),
                Arguments.of(Json.nodes().nullNode(), null), Arguments.of(Json.nodes().booleanNode(true), null),
                Arguments.of(Json.parse("12"), 12.0), Arguments.of("1 000", null), Arguments.of("1e1_0", 1e10));
    }

    /** _http.to_float. */
    @ParameterizedTest
    @MethodSource("toFloatCases")
    void toFloatMatchesPython(Object value, Double expected) {
        assertThat(Http.toFloat(value)).isEqualTo(expected);
    }

    /** _http.to_positive_int / to_positive_float. */
    @Test
    void toPositiveMatchesPython() {
        assertThat(Http.toPositiveInt("2.5")).isEqualTo(2L); // 은행원 반올림
        assertThat(Http.toPositiveInt("3.5")).isEqualTo(4L);
        assertThat(Http.toPositiveInt(0.4)).isEqualTo(0L); // 0 보다 크면 반올림 결과 0 도 그대로
        assertThat(Http.toPositiveInt("0")).isNull();
        assertThat(Http.toPositiveInt("-3")).isNull();
        assertThat(Http.toPositiveFloat("nan")).isNull();
        assertThat(Http.toPositiveFloat("0")).isNull();
        assertThat(Http.toPositiveFloat("625.0")).isEqualTo(625.0);
        assertThatThrownBy(() -> Http.toPositiveInt("inf")).isInstanceOf(ArithmeticException.class);
    }

    /** _http.clean_str (str() 표기). */
    @Test
    void cleanStrMatchesPython() {
        assertThat(Http.cleanStr(Json.parse("{\"a\": 1, \"b\": [true, null, 1.0, \"x\"]}")))
                .isEqualTo("{'a': 1, 'b': [True, None, 1.0, 'x']}");
        assertThat(Http.cleanStr(Json.parse("12.0"))).isEqualTo("12.0");
        assertThat(Http.cleanStr(Json.parse("1e16"))).isEqualTo("1e+16");
        assertThat(Http.cleanStr(Json.parse("false"))).isEqualTo("False");
        assertThat(Http.cleanStr("  　 ")).isNull();
        assertThat(Http.cleanStr(null)).isNull();
        assertThat(Http.joinUrl("https://a.example//", "//x/y")).isEqualTo("https://a.example/x/y");
    }

    /** urllib.parse.unquote. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "abc%2Bdef%3D%3D|abc+def==", "a%zz%|a%zz%", "%E2%82%AC%C3|€�", "a+b%20c|a+b c", "%ED%95%9C글%2|한글%2",
        "%41%e2%82%ac|A€"})
    void unquoteMatchesPython(String in, String expected) {
        assertThat(PyStr.unquote(in)).isEqualTo(expected);
    }

    /** f"{x:.8f}" / f"{x:.2f}". */
    @ParameterizedTest
    @CsvSource({
        "128.6261,8,128.62610000", "35.8605,8,35.86050000", "0.125,8,0.12500000", "-0.0,8,-0.00000000",
        "-1e-10,8,-0.00000000", "123.456789125,8,123.45678913", "1e20,8,100000000000000000000.00000000", "0.125,2,0.12",
        "2.675,2,2.67"})
    void formatFixedMatchesPython(double x, int digits, String expected) {
        assertThat(PyStr.formatFixed(x, digits)).isEqualTo(expected);
    }

    /** repr(str). */
    @Test
    void reprMatchesPython() {
        assertThat(PyStr.repr("a'b")).isEqualTo("\"a'b\"");
        assertThat(PyStr.repr("a\"b")).isEqualTo("'a\"b'");
        assertThat(PyStr.repr("a'\"b")).isEqualTo("'a\\'\"b'");
        assertThat(PyStr.repr("탭\t\u0000\u007f​　")).isEqualTo("'탭\\t\\x00\\x7f\\u200b\\u3000'");
        assertThat(PyStr.repr(null)).isEqualTo("None");
    }

    /** HttpJsonClient.decode_json: 빈 본문·뒤에 붙은 문자열은 JSON 아님, 깨진 UTF-8 은 대체 문자로 다시 해석. */
    @Test
    void decodeJsonMatchesPython() {
        HttpJsonClient c = new HttpJsonClient("t", 1.0, new MockTransport(Json.object()), List.of());
        UpstreamError empty = catchThrowableOfType(UpstreamError.class,
                () -> c.decodeJson(HttpResponse.of(502, null, new byte[0]), "ep"));
        assertThat(empty.message()).isEqualTo("ep 응답이 JSON 이 아닙니다(HTTP 502)");
        assertThat(empty.retriable()).isTrue();
        assertThat(empty.statusCode()).isEqualTo(502);
        UpstreamError trailing = catchThrowableOfType(UpstreamError.class,
                () -> c.decodeJson(HttpResponse.of(200, null, "{\"a\": 1} x".getBytes(StandardCharsets.UTF_8)), "ep"));
        assertThat(trailing.retriable()).isFalse();
        byte[] broken = {'{', '"', 'a', '"', ':', '"', (byte) 0xFF, '"', '}'};
        assertThat(c.decodeJson(HttpResponse.of(200, null, broken), "ep").get("a").stringValue()).isEqualTo("�");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
        assertThat(c.decodeJson(HttpResponse.of(200, null, bom), "ep")).isEqualTo(Json.object());
        assertThat(c.timeout()).isEqualTo(1.0);
        assertThat(c.retries()).isEqualTo(1);
    }

    /** UpstreamError: str(exc) = "[service] message", message 를 바꾸면 str 도 바뀐다. */
    @Test
    void upstreamErrorStrMatchesPython() {
        UpstreamError e = new UpstreamError("vworld:getLandUseAttr", "server error");
        assertThat(e.getMessage()).isEqualTo("[vworld:getLandUseAttr] server error");
        assertThat(e.retriable()).isTrue();
        assertThat(e.statusCode()).isNull();
        e.setMessage("x");
        assertThat(e.getMessage()).isEqualTo("[vworld:getLandUseAttr] x");
    }
}
