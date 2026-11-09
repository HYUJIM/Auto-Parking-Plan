package com.autoparkingplan.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.autoparkingplan.common.Py;

/**
 * 원문 보충 자료의 DB 와 무관한 부분 — 파이썬 {@code app/legal/supplement.py} 의 {@code SUPPLEMENT_FORMAT}, {@code ROW_SEP},
 * {@code pdf_text}, {@code normalize_annex_text}, {@code row_text}. (적재 {@code ingest_supplement} 는 server 모듈)
 *
 * <p>설계서 11장 리스크: '별표가 이미지·복잡한 표로 제공됨 → 수작업 입력 허용, 근거로 원문 링크'.
 * law.go.kr 화면에 텍스트로 없는 부분을 같은 문서 버전에 '보충 조문'으로 붙인다.
 * <ul>
 *   <li>pdf_annex   : 별표의 공식 PDF 첨부파일. 텍스트 층을 결정적으로 추출하고 공백만 정규화한다 → 원문 그대로.</li>
 *   <li>image_table : 조문 안의 표 이미지. 사람(또는 AI 보조)이 칸 단위로 옮겨 적은 전사본.</li>
 * </ul>
 */
public final class SupplementText {
    private SupplementText() {
    }

    public static final String SUPPLEMENT_FORMAT = "app-legal-supplement@1";
    public static final String ROW_SEP = " | ";

    /**
     * PDF 별표 텍스트 (결정적). 파이썬판은 pypdf 의 {@code "".join(page.extract_text() for page in pages)},
     * 자바는 PDFBox 로 쪽마다 뽑아 잇는다. 두 추출기의 줄바꿈·공백 위치는 다를 수 있으나 {@link #normalizeAnnexText} 뒤의
     * 결과는 저장소의 공식 PDF 4개(data/legal_sources/raw/supp_*.pdf)에서 같음을 시험으로 확인한다.
     */
    /** PDF 입력 상한 — 받은 바이트를 그대로 해석하므로 조작된 PDF 가 메모리를 다 쓰지 못하게. 공식 별표 PDF 는 1MB 안팎·수십 쪽이다. */
    public static final int MAX_PDF_BYTES = 32 * 1024 * 1024;
    public static final int MAX_PDF_PAGES = 300;

    public static String pdfText(byte[] data) {
        if (data.length > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF 가 너무 큽니다 (" + data.length + " 바이트, 최대 " + MAX_PDF_BYTES + ")");
        }
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF 쪽수가 너무 많습니다 (" + doc.getNumberOfPages() + "쪽, 최대 " + MAX_PDF_PAGES + ")");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setParagraphStart("");
            stripper.setParagraphEnd("");
            stripper.setPageStart("");
            stripper.setPageEnd("");
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String page = stripper.getText(doc);
                // pypdf 의 쪽 텍스트는 마지막 줄 뒤에 줄바꿈을 붙이지 않는다
                if (page.endsWith("\n")) {
                    page = page.substring(0, page.length() - 1);
                }
                sb.append(page);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern SUBITEM_GAP_RE = LegalPy.re("\\n?[ \\t]{2,}(?=[가-힣]\\.\\s)");
    private static final Pattern AMEND_NOTE_RE = LegalPy.re("(<개정[^>]*>|<신설[^>]*>)");
    private static final Pattern ITEM_START_RE = LegalPy.re("(?<=[^\\s\\d.])(?=\\d{1,2}\\.\\s)");

    /** 공백만 바꾼다: 머리말 뒤·호(1.)·목(가.) 앞에 줄바꿈, 줄 끝 공백 제거. 글자는 바꾸지 않는다. */
    public static String normalizeAnnexText(String raw) {
        String t = raw.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ");
        // 목: 두 칸 이상 공백(+줄바꿈)으로 구분됨 → 임시 표지
        t = SUBITEM_GAP_RE.matcher(t).replaceAll("\u0000");
        // 나머지 줄바꿈은 PDF 화면 줄넘김이다. pypdf 는 원래 띄어쓰기가 있던 곳은 공백을 남기므로(예: '건축물의 \n층수')
        // 줄바꿈만 지우면 원문 문장이 복원된다 (예: '초등학\n교' → '초등학교'). pypdf 6.10(줄바꿈 없음)·6.21(줄바꿈 있음) 모두 같은 결과.
        t = t.replace("\n", "").replace("\u0000", "\n");
        t = AMEND_NOTE_RE.matcher(t).replaceFirst("$1\n");
        t = ITEM_START_RE.matcher(t).replaceAll("\n"); // 호: 앞 글자에 붙어 있음 ('…관련)1. ')
        List<String> lines = new ArrayList<>();
        for (String ln : LegalPy.split(t, "\n")) {
            String r = Py.rstrip(ln);
            if (!Py.strip(r).isEmpty()) {
                lines.add(r);
            }
        }
        return String.join("\n", lines);
    }

    /** 표 한 행을 ' | ' 로 잇는다 (각 칸은 앞뒤 공백 제거). */
    public static String rowText(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) {
            out.add(Py.strip(c));
        }
        return String.join(ROW_SEP, out);
    }
}
