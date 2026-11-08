package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.legal.Structure.Node;
import com.autoparkingplan.legal.Structure.ProvisionRow;

/**
 * {@link Structure} (파이썬 app/legal/structure.py) 시험. 파이썬판에는 구조 파서 전용 단위 시험이 없어
 * (적재 통합 시험에서만 쓰임) 기대값은 파이썬 함수를 직접 실행해 얻은 값이다. 전체 번들 대조는 {@link LegalParityTest}.
 */
class StructureTest {

    private static List<String> paths(List<ProvisionRow> rows) {
        return rows.stream().map(ProvisionRow::path).toList();
    }

    /** tests/conftest.py example_bundles() 의 예시 시행령 제84조 (앞에 주석 표지가 붙은 첫 줄). */
    @Test
    void exampleBundleArticleWithLeadingNote() {
        String text = "[예시 조문 — 실제 법령 원문 아님] 제84조(용도지역안에서의 건폐율) ① 건폐율은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                + "1. 제1종일반주거지역 : 60퍼센트 이하\n2. 제2종일반주거지역 : 60퍼센트 이하";
        List<ProvisionRow> rows = Structure.flatten(Structure.parseArticle("제84조", "art84", "용도지역안에서의 건폐율", text), text);
        assertThat(paths(rows)).containsExactly("art84", "art84/para1", "art84/para1/item1", "art84/para1/item2");
        assertThat(rows.get(0).text()).isEqualTo(text);
        assertThat(rows.get(0).parentPath()).isNull();
        assertThat(rows.get(1).text()).startsWith("① 건폐율은").endsWith("2. 제2종일반주거지역 : 60퍼센트 이하");
        assertThat(rows.get(2).text()).isEqualTo("1. 제1종일반주거지역 : 60퍼센트 이하");
        assertThat(rows.get(2).parentPath()).isEqualTo("art84/para1");
    }

    @Test
    void paragraphsItemsSubitemsContinuationAndDuplicatePaths() {
        String text = "제5조(예) ① 첫 항\n1. 호 하나\n가. 목 가\n나. 목 나\n  이어지는 줄\n2. 호 둘\n2. 중복 호\n② 둘째 항\n3의2. 가지 호\n거. 목 거";
        List<ProvisionRow> rows = Structure.flatten(Structure.parseArticle("제5조", "art5", "예", text), text);
        assertThat(rows).extracting(ProvisionRow::kind, ProvisionRow::label, ProvisionRow::path, ProvisionRow::parentPath, ProvisionRow::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("article", "제5조", "art5", null, text),
                        org.assertj.core.groups.Tuple.tuple("paragraph", "①", "art5/para1", "art5",
                                "① 첫 항\n1. 호 하나\n가. 목 가\n나. 목 나\n  이어지는 줄\n2. 호 둘\n2. 중복 호"),
                        org.assertj.core.groups.Tuple.tuple("item", "1.", "art5/para1/item1", "art5/para1",
                                "1. 호 하나\n가. 목 가\n나. 목 나\n  이어지는 줄"),
                        org.assertj.core.groups.Tuple.tuple("subitem", "가.", "art5/para1/item1/sub1", "art5/para1/item1", "가. 목 가"),
                        org.assertj.core.groups.Tuple.tuple("subitem", "나.", "art5/para1/item1/sub2", "art5/para1/item1",
                                "나. 목 나\n  이어지는 줄"),
                        org.assertj.core.groups.Tuple.tuple("item", "2.", "art5/para1/item2", "art5/para1", "2. 호 둘"),
                        org.assertj.core.groups.Tuple.tuple("item", "2.", "art5/para1/item2~1", "art5/para1", "2. 중복 호"),
                        org.assertj.core.groups.Tuple.tuple("paragraph", "②", "art5/para2", "art5", "② 둘째 항\n3의2. 가지 호\n거. 목 거"),
                        org.assertj.core.groups.Tuple.tuple("item", "3의2.", "art5/para2/item3-2", "art5/para2", "3의2. 가지 호\n거. 목 거"),
                        org.assertj.core.groups.Tuple.tuple("subitem", "거.", "art5/para2/item3-2/sub15", "art5/para2/item3-2", "거. 목 거"));
    }

    /** 위치는 파이썬처럼 코드포인트 단위 (보조 평면 글자가 있어도 같은 start/end). */
    @Test
    void offsetsAreCodePoints() {
        String text = "제1조(𝐀) 𝐀①𝐀\n1. 𝐁 호";
        Node root = Structure.parseArticle("제1조", "art1", null, text);
        assertThat(root.end()).isEqualTo(17);
        assertThat(root.children()).hasSize(1);
        Node item = root.children().getFirst();
        assertThat(item.start()).isEqualTo(11);
        assertThat(item.end()).isEqualTo(17);
        assertThat(item.textOf(text)).isEqualTo("1. 𝐁 호");
    }

    @Test
    void annexKindDoesNotSkipArticleHead() {
        String text = "제3조 관련\n1. 첫째";
        List<ProvisionRow> rows = Structure.flatten(Structure.parseArticle("별표 1", "annex1", null, text, "annex"), text);
        assertThat(paths(rows)).containsExactly("annex1", "annex1/item1");
        assertThat(rows.getFirst().kind()).isEqualTo("annex");
    }

    @Test
    void tableJsonIsCarriedOnRows() {
        Node root = Structure.parseArticle("별표 1", "annex1", null, "x", "annex");
        root.setTableJson(Json.parse("[[\"a\",\"b\"]]"));
        assertThat(Structure.flatten(root, "x").getFirst().tableJson()).isEqualTo(Json.parse("[[\"a\",\"b\"]]"));
    }

    @ParameterizedTest
    @CsvSource({
            "art84/para1/item4, 제84조제1항제4호",
            "art84/para2/item3-2/sub15, 제84조제2항제3호의2거목",
            "art84/item1~1, 제84조제1호",
            "art5/para1/item2~1/sub3-1, 제84조제1항제2호다목",
            "annex5/item1/sub1, 제84조제1호가목"})
    void articleLabelFor(String path, String expected) {
        assertThat(Structure.articleLabelFor(path, "제84조")).isEqualTo(expected);
    }

    @Test
    void articleLabelForSubitemOutOfRangeFailsLikePython() {
        assertThatThrownBy(() -> Structure.articleLabelFor("art1/item1/sub99", "제1조")).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(Structure.articleLabelFor("art1/item1/sub0", "제1조")).isEqualTo("제1조제1호허목"); // 파이썬 음수 색인
    }
}
