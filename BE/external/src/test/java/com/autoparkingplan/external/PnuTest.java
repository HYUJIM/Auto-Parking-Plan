package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 파이썬 {@code tests/external/test_pnu.py}. */
class PnuTest {
    /** 파이썬 테스트에서 부번 인자를 생략한 경우. */
    private static final Object OMIT = new Object();

    private static String build(Object adm, Object mt, Object main, Object sub) {
        return sub == OMIT ? Pnu.buildPnu(adm, mt, main) : Pnu.buildPnu(adm, mt, main, sub);
    }

    static Stream<Arguments> buildCases() {
        return Stream.of(
                Arguments.of("2726010100", "0", "123", "4", "2726010100101230004"),
                Arguments.of("2726010100", "1", "12", "0", "2726010100200120000"), // 산 → 필지구분 2
                Arguments.of("2726010100", 0, 9001, 0, "2726010100190010000"),
                Arguments.of("2726010100", true, 1, 1, "2726010100200010001"),
                Arguments.of("1111010100", "0", "0001", OMIT, "1111010100100010000"), // 부번 생략 = 0
                Arguments.of("1111010100", "0", "9999", "9999", "1111010100199999999"),
                Arguments.of(" 2726010100 ", " 0 ", " 12 ", "", "2726010100100120000"));
    }

    /** test_build_pnu. */
    @ParameterizedTest
    @MethodSource("buildCases")
    void testBuildPnu(Object adm, Object mt, Object main, Object sub, String expected) {
        assertThat(build(adm, mt, main, sub)).isEqualTo(expected);
        assertThat(expected).hasSize(19);
    }

    static Stream<Arguments> badCases() {
        return Stream.of(
                Arguments.of("272601010", "0", "1", "0"), // 9자리
                Arguments.of("27260101AA", "0", "1", "0"),
                Arguments.of("2726010100", "2", "1", "0"), // mtYn 은 0/1 만
                Arguments.of("2726010100", "", "1", "0"),
                Arguments.of("2726010100", "0", "10000", "0"),
                Arguments.of("2726010100", "0", "-1", "0"),
                Arguments.of("2726010100", "0", "12a", "0"),
                Arguments.of("2726010100", "0", true, "0"));
    }

    /** test_build_pnu_rejects_bad_input. */
    @ParameterizedTest
    @MethodSource("badCases")
    void testBuildPnuRejectsBadInput(Object adm, Object mt, Object main, Object sub) {
        assertThatThrownBy(() -> build(adm, mt, main, sub)).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_parse_pnu_fields. */
    @Test
    void testParsePnuFields() {
        Pnu.ParsedPnu p = Pnu.parsePnu("2726010100101230004");
        assertThat(p.admCode()).isEqualTo("2726010100");
        assertThat(p.sidoCode()).isEqualTo("27");
        assertThat(p.sigunguCode()).isEqualTo("27260");
        assertThat(p.bjdongCode()).isEqualTo("10100");
        assertThat(p.emdCode()).isEqualTo("101");
        assertThat(p.riCode()).isEqualTo("00");
        assertThat(p.landType()).isEqualTo("1");
        assertThat(p.isMountain()).isFalse();
        assertThat(new int[] {p.mainNo(), p.subNo()}).containsExactly(123, 4);
        assertThat(p.jibun()).isEqualTo("123-4");
        assertThat(new String[] {p.mtYn(), p.platGbCd()}).containsExactly("0", "0");
        assertThat(new String[] {p.bun(), p.ji()}).containsExactly("0123", "0004");
    }

    /** test_parse_pnu_mountain_and_roundtrip. */
    @Test
    void testParsePnuMountainAndRoundtrip() {
        String pnu = Pnu.buildPnu("2726011400", "1", 12, 0);
        Pnu.ParsedPnu p = Pnu.parsePnu(pnu);
        assertThat(p.isMountain()).isTrue();
        assertThat(p.jibun()).isEqualTo("산 12");
        assertThat(p.platGbCd()).isEqualTo("1");
        assertThat(Pnu.buildPnu(p.admCode(), p.mtYn(), p.mainNo(), p.subNo())).isEqualTo(pnu);
    }

    static Stream<String> rejectCases() {
        return Stream.of("", "123", "27260101001012300041", "2726010100301230004", "27260101001O1230004", null);
    }

    /** test_parse_pnu_rejects. */
    @ParameterizedTest
    @MethodSource("rejectCases")
    void testParsePnuRejects(String bad) {
        assertThatThrownBy(() -> Pnu.parsePnu(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Pnu.isValidPnu(bad)).isFalse();
    }

    /** test_is_valid_pnu_and_format. */
    @Test
    void testIsValidPnuAndFormat() {
        assertThat(Pnu.isValidPnu("2726010100190010000")).isTrue();
        assertThat(Pnu.formatJibun(5, 0)).isEqualTo("5");
        assertThat(Pnu.formatJibun(5, 2, true)).isEqualTo("산 5-2");
    }

    /** (자바 추가) 오류 문구는 파이썬 ValueError 문구와 같다 ({@code {value!r}} 표기 포함). */
    @Test
    void errorMessagesMatchPython() {
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", "0", true, "0")).hasMessage("본번: 숫자가 아닙니다: True");
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", "0", "12a", "0")).hasMessage("본번: 숫자가 아닙니다: '12a'");
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", "0", "10000", "0")).hasMessage("본번: 0~9999 범위를 벗어났습니다: 10000");
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", "0", 1.0, "0")).hasMessage("본번: 숫자가 아닙니다: 1.0");
        assertThatThrownBy(() -> Pnu.buildPnu("272601010", "0", "1", "0")).hasMessage("법정동코드는 10자리 숫자여야 합니다: '272601010'");
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", 2, "1", "0")).hasMessage("산여부(mtYn)는 '0' 또는 '1' 이어야 합니다: 2");
        assertThatThrownBy(() -> Pnu.parsePnu(null)).hasMessage("PNU 는 문자열이어야 합니다: None");
        assertThatThrownBy(() -> Pnu.parsePnu("123")).hasMessage("PNU 는 19자리 숫자여야 합니다: '123'");
        // 파이썬 isdigit() 은 전각 숫자도 숫자로 본다 (필지구분 자리만 ASCII '1'/'2' 여야 한다)
        assertThat(Pnu.isValidPnu("２７２６０１０１００１９００１００００")).isFalse();
        assertThat(Pnu.isValidPnu("２７２６０１０１００1９００１００００")).isTrue();
        assertThat(Pnu.buildPnu("2726010100", "0", "１２", "0")).isEqualTo("2726010100100120000");
        assertThat(Pnu.buildPnu("２７２６０１０１００", "0", "1", "0")).isEqualTo("２７２６０１０１００100010000");
        Pnu.ParsedPnu fw = Pnu.parsePnu("２７２６０１０１００1９００１００００");
        assertThat(fw.mainNo()).isEqualTo(9001);
        assertThat(fw.jibun()).isEqualTo("9001");
        assertThat(fw.bun()).isEqualTo("９００１");
        // 위첨자는 isdigit() 이지만 int() 가 거절 (파이썬과 같은 ValueError)
        assertThatThrownBy(() -> Pnu.buildPnu("2726010100", "0", "²", "0"))
                .hasMessage("invalid literal for int() with base 10: '²'");
    }
}
