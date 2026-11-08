package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * {@link SupplementText} (파이썬 app/legal/supplement.py 의 pdf_text·normalize_annex_text·row_text) 시험 — 자바판 추가.
 * 파이썬은 pypdf, 자바는 PDFBox 로 PDF 텍스트를 뽑으므로, 저장소의 공식 PDF 원본으로 정규화 결과가 같은지 확인한다.
 */
class SupplementTextTest {

    static Stream<Arguments> pdfAnnexes() {
        Path file = TestFiles.DATA.resolve("supplements").resolve("kr.decree.nkpa__2026-09-18__annexes.json");
        if (!Files.isRegularFile(file)) {
            return Stream.of(Arguments.of(null, null));
        }
        List<Arguments> out = new ArrayList<>();
        for (JsonNode it : Json.parse(TestFiles.readText(file)).get("items").values()) {
            out.add(Arguments.of(it.get("path").stringValue(), it));
        }
        return out.stream();
    }

    /** 공식 PDF(raw/supp_*.pdf) → pdfText → normalizeAnnexText 가 파이썬(pypdf)이 저장한 text 와 같다. */
    @ParameterizedTest
    @MethodSource("pdfAnnexes")
    void pdfAnnexTextMatchesPython(String path, JsonNode item) {
        assumeTrue(item != null, "BE/data/legal_sources/supplements 없음");
        Path pdf = TestFiles.DATA.resolve(item.get("raw_file").stringValue());
        assumeTrue(Files.isRegularFile(pdf), "PDF 원본 없음");
        byte[] data = TestFiles.read(pdf);
        assertThat(Py.sha256Hex(data)).isEqualTo(item.get("raw_sha256").stringValue());
        String text = SupplementText.normalizeAnnexText(SupplementText.pdfText(data));
        assertThat(text).isEqualTo(item.get("text").stringValue());
    }

    /** normalize_annex_text 의 규칙 (파이썬 함수로 확인한 값). */
    @Test
    void normalizeAnnexTextRules() {
        String raw = "■ 시행령 [별표 5] <개정 2023. 5. 15.>\n제2종 건축물(제71조관련)1. 건축할 수 있는 건축물의 \n층수\n  가. 단독주택\n  나. 공동\n주택\r\n2. 둘째 ";
        assertThat(SupplementText.normalizeAnnexText(raw))
                .isEqualTo("■ 시행령 [별표 5] <개정 2023. 5. 15.>\n제2종 건축물(제71조관련)\n1. 건축할 수 있는 건축물의 층수\n가. 단독주택\n나. 공동주택\n2. 둘째");
    }

    @Test
    void rowText() {
        assertThat(SupplementText.rowText(List.of(" 경형 ", "2.0미터 이상", "3.6미터 이상"))).isEqualTo("경형 | 2.0미터 이상 | 3.6미터 이상");
    }
}
