package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 도로명주소 검색어 사전 검사 — 비정상 검색어가 상위 API 로 나가지 않는지 (2026-11-12 상위 차단 재발 방지). */
class JusoKeywordTest {

    @Test
    void 정상_주소는_통과하고_구두점은_공백으로_바뀐다() {
        assertThat(JusoKeyword.check("범어동 177-1").keyword()).isEqualTo("범어동 177-1");
        assertThat(JusoKeyword.check("대구광역시 수성구 달구벌대로 2450 (범어동)").keyword()).isEqualTo("대구광역시 수성구 달구벌대로 2450 범어동");
        assertThat(JusoKeyword.check("달구벌대로 2450(범어동, 수성구청)").keyword()).isEqualTo("달구벌대로 2450 범어동 수성구청");
        assertThat(JusoKeyword.check("  수성구  범어로[12] ").keyword()).isEqualTo("수성구 범어로 12");
        assertThat(JusoKeyword.check("4·19로 3").keyword()).isEqualTo("4 19로 3");
        assertThat(JusoKeyword.check("범어 SK뷰 101동").keyword()).isEqualTo("범어 SK뷰 101동");
    }

    @Test
    void 줄바꿈과_특수_공백은_공백_하나로_정리한다() {
        assertThat(JusoKeyword.check("수성구\t범어동\r\n177-1").keyword()).isEqualTo("수성구 범어동 177-1");
        assertThat(JusoKeyword.check("수성구 범어동　177-1").keyword()).isEqualTo("수성구 범어동 177-1");
    }

    @Test
    void 입력_중간_상태의_낱자와_OR이_들어간_낱말은_통과한다() {
        assertThat(JusoKeyword.check("범어ㄷ").ok()).isTrue();
        assertThat(JusoKeyword.check("KOREA타워 1").ok()).isTrue();
        assertThat(JusoKeyword.check("FORUM빌딩 2층").ok()).isTrue();
        assertThat(JusoKeyword.check("오르막길 12").ok()).isTrue();
    }

    static List<String> abnormal() {
        return List.of(
                "<script>alert(1)</script>", "범어동<img src=x onerror=alert(1)>", "' OR '1'='1", "1; DROP TABLE users", "범어동\" OR \"\"=\"",
                "범어동%", "범어동=1", "a>b", "범어동 -- 주석", "범어동/*x*/", "1 OR 1", "1or1", "범어동 or 1", "union select 1", "UNION ALL", "범어동 DROP",
                "exec xp", "Select 1", "delete 범어동", "범어동\u0000", "범어동\u0007", "범어동\u001F", "../../etc/passwd", "${jndi:ldap://x/a}", "범어동\\",
                "범어동|ls", "범어동&x", "범어동#1", "범어동 1+1", "범어동;", "범어동`id`", "범어동'", "범어동?", "범어동!", "범어동@", "범어동:1", "범어_동",
                "범어동~", "범어동*", "{범어동}", "＜범어동＞", "범어동１２", "漢字 12", "범어동 😀", "범어동​", "범어동‮");
    }

    @ParameterizedTest
    @MethodSource("abnormal")
    void 허용하지_않는_문자나_예약어가_있으면_거절한다(String raw) {
        JusoKeyword.Check c = JusoKeyword.check(raw);
        assertThat(c.ok()).isFalse();
        assertThat(c.code()).isEqualTo("E0013");
        assertThat(c.keyword()).isNull();
        assertThat(c.message()).isEqualTo(JusoKeyword.REJECT_MESSAGE);
    }

    @Test
    void 형식_오류는_가이드_오류코드와_안내로_거절한다() {
        for (String blank : new String[] {"", "   ", "( )", ".,", "\t\n"}) {
            assertThat(JusoKeyword.check(blank).code()).as(blank).isEqualTo("E0005");
        }
        assertThat(JusoKeyword.check(null).code()).isEqualTo("E0005");
        assertThat(JusoKeyword.check("가").code()).isEqualTo("E0008");
        assertThat(JusoKeyword.check("a.").code()).isEqualTo("E0008");
        assertThat(JusoKeyword.check("12345").code()).isEqualTo("E0009");
        assertThat(JusoKeyword.check("12-3").code()).isEqualTo("E0009");
        assertThat(JusoKeyword.check("1 2").code()).isEqualTo("E0009");
        assertThat(JusoKeyword.check("범어동 12345678901").code()).isEqualTo("E0011");
        assertThat(JusoKeyword.check("범어동 1234567890").ok()).isTrue();
        assertThat(JusoKeyword.check("가").message()).isEqualTo(Juso.QUERY_ERROR_MESSAGES.get("E0008"));
    }

    @Test
    void 길이는_한글_40자_영문_80자까지() {
        assertThat(JusoKeyword.check("가".repeat(40)).ok()).isTrue();
        assertThat(JusoKeyword.check("가".repeat(41)).code()).isEqualTo("E0010");
        assertThat(JusoKeyword.check("a".repeat(80)).ok()).isTrue();
        assertThat(JusoKeyword.check("a".repeat(81)).code()).isEqualTo("E0010");
    }

    @ParameterizedTest
    @ValueSource(strings = {"범어동 177-1", "대구광역시 수성구 달구벌대로 2450 (범어동)", " 수성구  범어로[12] ", "4·19로 3", "범어 SK뷰, 101동", "수성구\t범어동"})
    void 통과한_검색어는_안전한_문자만_남고_다시_검사해도_같다(String raw) {
        String kw = JusoKeyword.check(raw).keyword();
        assertThat(kw).matches("[가-힣ㄱ-ㆎA-Za-z0-9-]+( [가-힣ㄱ-ㆎA-Za-z0-9-]+)*");
        assertThat(JusoKeyword.check(kw).keyword()).isEqualTo(kw);
    }
}
