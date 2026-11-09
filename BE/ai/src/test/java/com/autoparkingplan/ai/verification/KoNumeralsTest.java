package com.autoparkingplan.ai.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.ai.verification.KoQuantity.Q;

/** 법령의 천 단위 혼용 숫자 표기(1천500퍼센트) 회귀 테스트 — {@code tests/unit/test_ko_numerals.py}. */
class KoNumeralsTest {

    private static List<Q> quantities(String text) {
        return KoQuantity.parse(text).stream().filter(q -> q.kind().equals("QUANTITY")).toList();
    }

    /** test_parse_korean_thousands_percent */
    @Test
    void parseKoreanThousandsPercent() {
        List<Q> qs = quantities("7. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하");
        assertThat(qs).anySatisfy(q -> {
            assertThat(q.value()).isEqualTo(200.0);
            assertThat(q.comparator()).isEqualTo("GE");
        });
        assertThat(qs).anySatisfy(q -> {
            assertThat(q.value()).isEqualTo(1500.0);
            assertThat(q.comparator()).isEqualTo("LE");
        });
    }

    /** test_money_with_korean_multiplier_still_works */
    @Test
    void moneyWithKoreanMultiplierStillWorks() {
        assertThat(KoQuantity.parse("과태료 5천만원")).anyMatch(q -> q.dim().equals("money") && q.value().equals(5e7));
    }

    /** test_validator_number_set_includes_korean_thousands */
    @Test
    void validatorNumberSetIncludesKoreanThousands() {
        assertThat(AiOutput.numbers("중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")).contains(1500.0);
    }

    /** test_count_unit_gae */
    @Test
    void countUnitGae() {
        List<Q> qs = KoQuantity.parse("출입구가 2개 이상인 경우 6.0미터");
        assertThat(qs).anyMatch(q -> q.dim().equals("count") && q.value().equals(2.0) && "GE".equals(q.comparator()));
        assertThat(qs).noneMatch(q -> q.kind().equals("BARE"));
        // '개월'은 여전히 기간으로 읽는다
        assertThat(KoQuantity.parse("6개월 이내")).anyMatch(q -> q.kind().equals("PERIOD") && q.value().equals(6.0));
    }

    /** test_korean_thousands_spacing_variants_from_daegu_ordinance (대구광역시 도시계획 조례 제80조 원문 표기) */
    @Test
    void koreanThousandsSpacingVariantsFromDaeguOrdinance() {
        Q q = quantities("7. 중심상업지역 : 1천 300퍼센트 이하").get(0);
        assertThat(q.value()).isEqualTo(1300.0);
        assertThat(q.comparator()).isEqualTo("LE");
        q = quantities("8. 일반상업지역 : 1천 퍼센트 이하").get(0);
        assertThat(q.value()).isEqualTo(1000.0);
        assertThat(q.comparator()).isEqualTo("LE");
        q = quantities("바닥면적의 합계가 2천제곱미터 미만인 것").get(0);
        assertThat(q.value()).isEqualTo(2000.0);
        assertThat(q.dim()).isEqualTo("area");
        assertThat(q.comparator()).isEqualTo("LT");
        assertThat(AiOutput.numbers("1천 300퍼센트 이하")).contains(1300.0);
        assertThat(AiOutput.numbers("1천 퍼센트 이하")).contains(1000.0);
    }

    /** test_angle_unit_and_road_not_angle */
    @Test
    void angleUnitAndRoadNotAngle() {
        assertThat(KoQuantity.parse("60도 대향주차 | 4.5미터 | 5.5미터")).anyMatch(q -> q.dim().equals("angle") && q.value().equals(60.0));
        assertThat(KoQuantity.parse("너비 8도로")).noneMatch(q -> q.dim().equals("angle")); // '도로' 는 각도가 아님
    }
}
