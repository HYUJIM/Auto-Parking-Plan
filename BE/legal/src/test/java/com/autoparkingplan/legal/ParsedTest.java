package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

/**
 * {@link SourceBundle} 등 parsed.py 자료형 시험 (자바판 추가). 예시 번들은 파이썬 tests/conftest.py example_bundles() 와 같은 값이고,
 * 해시 기대값은 파이썬 {@code json.dumps([a.__dict__ for a in b.articles], ensure_ascii=False, sort_keys=True)} 의 sha256 이다
 * (app/legal/ingest.py 의 _raw_bytes — 원문 파일이 없을 때).
 */
class ParsedTest {
    static final String EX_NOTE = "[예시 조문 — 실제 법령 원문 아님] ";
    static final String FETCHED = "2026-11-06T09:00:00+09:00";

    static SourceBundle exDecree() {
        return new SourceBundle(
                new DocumentMeta("ex.decree", "L1_NATIONAL", "decree", "예시 시행령", "00", "example", "ex-decree"),
                new VersionMeta("예시 제1호", "2026-01-01", "2026-01-01", null, null, "https://example.invalid/decree", FETCHED, "json", null),
                List.of(new ArticleText("article", "제84조", "art84", "용도지역안에서의 건폐율",
                                EX_NOTE + "제84조(용도지역안에서의 건폐율) ① 건폐율은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                        + "1. 제1종일반주거지역 : 60퍼센트 이하\n2. 제2종일반주거지역 : 60퍼센트 이하"),
                        new ArticleText("article", "제85조", "art85", "용도지역 안에서의 용적률",
                                EX_NOTE + "제85조(용도지역 안에서의 용적률) ① 용적률은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                        + "1. 제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하\n2. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")),
                true);
    }

    static SourceBundle exOrdin() {
        return new SourceBundle(
                new DocumentMeta("ex.ordin", "L3_LOCAL", "ordinance", "예시 도시계획 조례", "27", "example", "ex-ordin", null, null, null,
                        Map.of("영", "ex.decree")),
                new VersionMeta("예시 조례 제1호", "2026-02-01", "2026-02-01", null, null, "https://example.invalid/ordin", FETCHED, "json", null),
                List.of(new ArticleText("article", "제50조", "art50", "건폐율",
                                EX_NOTE + "제50조(건폐율) ① 영 제84조제1항에 따라 용도지역별 건폐율은 다음 각 호와 같다.\n1. 제2종일반주거지역 : 60퍼센트 이하"),
                        new ArticleText("article", "제51조", "art51", "용적률",
                                EX_NOTE + "제51조(용적률) ① 영 제85조제1항에 따라 용도지역별 용적률은 다음 각 호와 같다.\n1. 제2종일반주거지역 : 220퍼센트 이하")),
                true);
    }

    @Test
    void rawBytesFallbackHashMatchesPython() {
        assertThat(Py.sha256Hex(PyJson.dumpsSorted(exDecree().articlesJson()).getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("f4909eebbb8c0dc8d51cddb6f1596f8ed22fe5bf25057aae2115d71a8b519cbb");
        assertThat(Py.sha256Hex(PyJson.dumpsSorted(exOrdin().articlesJson()).getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("76c5cd372edae8c05a1ff4f8eeefd812f9202f4442b5adf931d3abd3bb59d534");
    }

    @Test
    void toJsonFieldOrderAndRoundTrip() {
        SourceBundle b = exOrdin();
        assertThat(PyJson.dumps(b.toJson())).startsWith("{\"document\": {\"key\": \"ex.ordin\", \"layer\": \"L3_LOCAL\", \"source_type\": "
                + "\"ordinance\", \"official_title\": \"예시 도시계획 조례\", \"jurisdiction_code\": \"27\", \"external_system\": \"example\", "
                + "\"external_id\": \"ex-ordin\", \"short_title\": null, \"parent_key\": null, \"homepage_url\": null, \"aliases\": {\"영\": \"ex.decree\"}}, \"versio");
        assertThat(SourceBundle.fromJson(Json.parse(b.toFileText()))).isEqualTo(b);
        assertThat(b.withDocument(b.document().withKey("k")).document().key()).isEqualTo("k");
    }

    /** SourceBundle.from_json 의 ValueError / DocumentMeta(**d) 의 TypeError. */
    @Test
    void fromJsonRejectsUnknownFormatAndFields() {
        assertThatThrownBy(() -> SourceBundle.fromJson(Json.parse("{\"format\": \"x\"}"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown bundle format: x");
        assertThatThrownBy(() -> SourceBundle.fromJson(Json.parse("{}"))).hasMessage("unknown bundle format: None");
        var d = exDecree().toJson();
        ((tools.jackson.databind.node.ObjectNode) d.get("document")).put("bogus", 1);
        assertThatThrownBy(() -> SourceBundle.fromJson(d)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bogus");
    }
}
