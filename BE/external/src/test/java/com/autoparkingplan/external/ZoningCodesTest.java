package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.PyJson;

/** 파이썬 {@code tests/external/test_zoning_codes.py}. */
class ZoningCodesTest {

    /** test_verified_codes_present. */
    @Test
    void testVerifiedCodesPresent() {
        // 행정표준코드관리시스템 '용도지역지구구분' 에서 확인한 값
        assertThat(ZoningCodes.LAND_USE_CODES.get("UQA122")).isEqualTo("제2종일반주거지역");
        assertThat(ZoningCodes.LAND_USE_CODES.get("UQA430")).isEqualTo("자연녹지지역");
        assertThat(ZoningCodes.LAND_USE_CODES.get("UQQ300")).isEqualTo("지구단위계획구역");
        assertThat(ZoningCodes.LAND_USE_CODES.get("UDV100")).isEqualTo("개발제한구역");
        assertThat(ZoningCodes.LAND_USE_CODES.get("UQA01X")).isEqualTo("도시지역"); // 브이월드 컬럼정의서
        assertThat(ZoningCodes.nameForCode(" uqa122 ")).isEqualTo("제2종일반주거지역");
        assertThat(ZoningCodes.nameForCode("XXX999")).isNull();
    }

    /** test_twenty_one_statutory_zonings_plus_management. */
    @Test
    void testTwentyOneStatutoryZoningsPlusManagement() {
        assertThat(ZoningCodes.ZONING_NAMES).hasSize(22); // 21 + 세분 전 관리지역
    }

    static Stream<String> zoningNames() {
        return ZoningCodes.ZONING_NAMES.stream().sorted(PyJson::compareCodePoints);
    }

    /** test_zoning_names_classified_as_zoning. */
    @ParameterizedTest
    @MethodSource("zoningNames")
    void testZoningNamesClassifiedAsZoning(String name) {
        assertThat(ZoningCodes.classifyDesignation(name)).isEqualTo("zoning");
    }

    static Stream<Arguments> classifyCases() {
        return Stream.of(
                Arguments.of("제2종일반주거지역", "UQA122", "zoning"),
                Arguments.of("제2종 일반주거지역(7층이하)", null, "zoning"),
                Arguments.of("계획관리지역", "UQB100", "zoning"),
                Arguments.of("관리지역", "UQB001", "zoning"),
                // 상위 묶음 명칭은 zoning 이 아님
                Arguments.of("도시지역", "UQA01X", "other"),
                Arguments.of("도시지역", "UQA001", "other"),
                Arguments.of("일반주거지역", null, "other"),
                // 용도지구
                Arguments.of("자연경관지구", "UQF110", "district"),
                Arguments.of("자연경관지구", null, "district"),
                Arguments.of("방화지구", "UQI100", "district"),
                Arguments.of("최고고도지구", null, "district"),
                Arguments.of("관광·휴양개발진흥지구", null, "district"),
                Arguments.of("복합용도지구", "UQP501", "district"),
                Arguments.of("재해위험지구", null, "other"), // 타 법령 지구
                Arguments.of("화재경계지구", null, "other"),
                // 용도구역·지구단위계획구역
                Arguments.of("개발제한구역", "UDV100", "zone"),
                Arguments.of("지구단위계획구역", "UQQ300", "zone"),
                Arguments.of("제1종지구단위계획구역", null, "zone"),
                Arguments.of("도시자연공원구역", "UQQ810", "zone"),
                Arguments.of("시가화조정구역", null, "zone"),
                Arguments.of("수산자원보호구역", "UQQ500", "zone"),
                Arguments.of("입지규제최소구역", null, "zone"),
                Arguments.of("도시혁신구역", null, "zone"),
                Arguments.of("도시ㆍ군계획시설입체복합구역", null, "zone"),
                // 그 밖
                Arguments.of("가축사육제한구역", "UMZ100", "other"),
                Arguments.of("상대보호구역", "UOA120", "other"),
                Arguments.of("토지거래계약에관한허가구역", "UQQ600", "other"),
                Arguments.of("비오톱1등급", "ZA0011", "other"),
                Arguments.of("소로2류(폭 8M~10M)", "UQS121", "other"),
                Arguments.of("", null, "other"),
                Arguments.of(null, null, "other"));
    }

    /** test_classify_designation. */
    @ParameterizedTest
    @MethodSource("classifyCases")
    void testClassifyDesignation(String name, String code, String kind) {
        assertThat(ZoningCodes.classifyDesignation(name, code)).isEqualTo(kind);
    }

    /** test_code_wins_over_name. */
    @Test
    void testCodeWinsOverName() {
        assertThat(ZoningCodes.classifyDesignation("이상한 명칭", "UQA122")).isEqualTo("zoning");
        assertThat(ZoningCodes.classifyDesignation("제2종일반주거지역", "UMZ100")).isEqualTo("other");
    }

    /** test_normalize_and_canonical_names. */
    @Test
    void testNormalizeAndCanonicalNames() {
        assertThat(ZoningCodes.normalizeDesignationName("  제2종   일반주거지역 ")).isEqualTo("제2종일반주거지역");
        assertThat(ZoningCodes.normalizeDesignationName("성장관리방안  수립지역")).isEqualTo("성장관리방안 수립지역");
        assertThat(ZoningCodes.normalizeDesignationName("처음 보는   지정")).isEqualTo("처음 보는 지정");
        assertThat(ZoningCodes.normalizeDesignationName(null)).isEqualTo("");
        assertThat(ZoningCodes.canonicalDesignationName("제2종일반주거지역(7층이하)", "UQA122")).isEqualTo("제2종일반주거지역");
        assertThat(ZoningCodes.canonicalDesignationName("", "UQQ300")).isEqualTo("지구단위계획구역");
        // 코드와 다른 것을 가리키는 원천 명칭은 덮어쓰지 않는다
        assertThat(ZoningCodes.canonicalDesignationName("시가지경관지구", "UQF100")).isEqualTo("시가지경관지구");
    }

    static Stream<Arguments> conflictCases() {
        return Stream.of(
                Arguments.of("1", null, "included"),
                Arguments.of("2", null, "partially_included"),
                Arguments.of("3", null, "adjacent"),
                Arguments.of(3, null, "adjacent"),
                Arguments.of(null, "포함", "included"),
                Arguments.of(null, "저촉", "partially_included"),
                Arguments.of(null, "접합", "adjacent"),
                Arguments.of(null, "접함", "adjacent"),
                Arguments.of("", " 접함 ", "adjacent"),
                Arguments.of("9", "모름", "unknown"),
                Arguments.of(null, null, "unknown"));
    }

    /** test_conflict_from_source. */
    @ParameterizedTest
    @MethodSource("conflictCases")
    void testConflictFromSource(Object code, Object name, String expected) {
        assertThat(ZoningCodes.conflictFromSource(code, name)).isEqualTo(expected);
    }

    /** (자바 추가) 사전 크기·명칭 정규화 표기가 파이썬과 같다 (가운뎃점 변형은 사전 표기 'ㆍ'(U+318D)로). */
    @Test
    void dictionarySizesAndCanonicalSpellingMatchPython() {
        assertThat(ZoningCodes.LAND_USE_CODES).hasSize(152);
        assertThat(ZoningCodes.DISTRICT_NAMES).hasSize(75);
        assertThat(ZoningCodes.ZONE_NAMES).hasSize(11);
        assertThat(ZoningCodes.normalizeDesignationName("관광·휴양개발진흥지구")).isEqualTo("관광ㆍ휴양개발진흥지구");
        assertThat(ZoningCodes.normalizeDesignationName("도시ㆍ군계획시설입체복합구역")).isEqualTo("도시·군계획시설입체복합구역");
        assertThat(ZoningCodes.normalizeDesignationName("제2종　일반주거지역")).isEqualTo("제2종일반주거지역");
        assertThat(ZoningCodes.classifyDesignation("제2종일반주거지역（7층이하）")).isEqualTo("zoning");
    }
}
