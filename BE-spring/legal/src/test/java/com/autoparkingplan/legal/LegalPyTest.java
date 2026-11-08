package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** {@link LegalPy} — 파이썬 동작 대조 (기대값은 파이썬 3.12 로 실행해 얻은 값). 자바판 추가 시험. */
class LegalPyTest {

    @Test
    void pythonWhitespaceInRegex() {
        assertThat(LegalPy.re("\\s").matcher("\u001c").matches()).isTrue(); // 파이썬 \s 는 U+001C~U+001F 포함
        assertThat(LegalPy.re("\\s").matcher("\u3000").matches()).isTrue();
        assertThat(LegalPy.re("[^\\s\\d.]").matcher("\u00a0").matches()).isFalse();
        assertThat(LegalPy.re("[^\\s\\d.]").matcher("가").matches()).isTrue();
        assertThat(LegalPy.re("\\d+").matcher("１２").matches()).isTrue(); // 전각 숫자
        assertThat(LegalPy.re("[^\\[\\],]+").matcher("ab").matches()).isTrue();
        assertThat(LegalPy.re("a.b").matcher("a\u2028b").matches()).isTrue(); // '.' 은 \n 만 제외
        assertThat(LegalPy.re("^a$").matcher("a\n").find()).isTrue();
        assertThat(LegalPy.re("display\\s*:\\s*none", Pattern.CASE_INSENSITIVE).matcher("DISPLAY : NONE").find()).isTrue();
    }

    @Test
    void reprLikePython() {
        assertThat(LegalPy.repr("a'b")).isEqualTo("\"a'b\"");
        assertThat(LegalPy.repr("a\"b'c")).isEqualTo("'a\"b\\'c'");
        assertThat(LegalPy.repr("x\u00a0y\u200bz\u007f\uD83D\uDE00\uE000")).isEqualTo("'x\\xa0y\\u200bz\\x7f\uD83D\uDE00\\ue000'");
        assertThat(LegalPy.repr(null)).isEqualTo("None");
        assertThat(LegalPy.repr(List.of("009294"))).isEqualTo("['009294']");
    }

    @Test
    void urllibLikePython() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("q", "국토 법률*~");
        q.put("a", "");
        assertThat(LegalPy.urlencode(q)).isEqualTo("q=%EA%B5%AD%ED%86%A0+%EB%B2%95%EB%A5%A0%2A~&a=");
        assertThat(LegalPy.quote("법령/주차장법")).isEqualTo("%EB%B2%95%EB%A0%B9/%EC%A3%BC%EC%B0%A8%EC%9E%A5%EB%B2%95");
        assertThat(LegalPy.quotePlus("a b+c")).isEqualTo("a+b%2Bc");
        assertThat(LegalPy.unquote("%EB%B2%95%ZZ%4")).isEqualTo("법%ZZ%4");
        assertThat(LegalPy.unquote("%ED%95")).isEqualTo("\uFFFD");
        assertThat(LegalPy.parseQs("a=1&b=&c&a=2&d=%ED%95%9C+x")).isEqualTo(Map.of("a", List.of("1", "2"), "d", List.of("한 x")));
        assertThat(LegalPy.urlsplitQuery("/LSW/lsInfoP.do?lsiSeq=1&efYd=2#frag")).isEqualTo("lsiSeq=1&efYd=2");
    }

    @Test
    void intAndDigits() {
        assertThat(LegalPy.pyInt(" ７_7 ")).isEqualTo(BigInteger.valueOf(77));
        assertThatThrownBy(() -> LegalPy.pyInt("①")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LegalPy.isDigit("①")).isTrue();
        assertThat(LegalPy.isDigit("²")).isTrue();
        assertThat(LegalPy.isDigit("1a")).isFalse();
        assertThat(LegalPy.thousands(1234567)).isEqualTo("1,234,567");
    }
}
