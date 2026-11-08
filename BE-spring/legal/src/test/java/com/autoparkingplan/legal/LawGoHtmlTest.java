package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.legal.LawGoHtml.AnnexContent;
import com.autoparkingplan.legal.LawGoHtml.AnnexFetch;
import com.autoparkingplan.legal.LawGoHtml.AnnexRef;
import com.autoparkingplan.legal.LawGoHtml.BuildResult;
import com.autoparkingplan.legal.LawGoHtml.HeaderLine;
import com.autoparkingplan.legal.LawGoHtml.HistoryEntry;
import com.autoparkingplan.legal.LawGoHtml.Missing;
import com.autoparkingplan.legal.LawGoHtml.ParsedArticle;
import com.autoparkingplan.legal.LawGoHtml.ParsedBody;
import com.autoparkingplan.legal.LawGoHtml.VersionHeader;

/**
 * 파이썬 tests/unit/test_lawgo_html.py — app.legal.lawgo_html 파서 시험. 저장해 둔 law.go.kr 원문 페이지(fixtures/lawgo)만
 * 사용, 네트워크 없음.
 */
class LawGoHtmlTest {
    private static ParsedBody lawBody;
    private static ParsedBody decreeBody;
    private static ParsedBody ordinBody;

    @BeforeAll
    static void bodies() {
        lawBody = LawGoHtml.parseBody(TestFiles.fixture("law_body_nkpa_excerpt.html"), "law");
        decreeBody = LawGoHtml.parseBody(TestFiles.fixture("law_body_decree_excerpt.html"), "law");
        ordinBody = LawGoHtml.parseBody(TestFiles.fixture("ordin_body_urban_planning_excerpt.html"), "ordin");
    }

    private static List<String> lines(String text) {
        return LegalPy.split(text, "\n");
    }

    // --- 기본 도구 -----------------------------------------------------------------

    /** test_normalize_text_only_allowed_changes. */
    @Test
    void normalizeTextOnlyAllowedChanges() {
        String raw = "가 나  \r\n다\t \n\n\n\n\n라\n\n마";
        assertThat(LawGoHtml.normalizeText(raw)).isEqualTo("가 나\n다\n\n라\n\n마");
    }

    /** test_label_to_path (parametrize). */
    @ParameterizedTest
    @CsvSource({"제84조, art84", "제84조의2, art84-2", "별표 5, annex5", "별표 5의2, annex5-2"})
    void labelToPath(String label, String path) {
        assertThat(LawGoHtml.labelToPath(label)).isEqualTo(path);
    }

    /** (자바판 추가) label_to_path 의 ValueError 문구. */
    @Test
    void labelToPathUnknown() {
        assertThatThrownBy(() -> LawGoHtml.labelToPath("부칙")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown label: '부칙'");
    }

    /** test_parse_header_line_law_and_ordinance. */
    @Test
    void parseHeaderLineLawAndOrdinance() {
        HeaderLine h = LawGoHtml.parseHeaderLine("[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]");
        assertThat(h).isEqualTo(new HeaderLine("[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]", "2026-07-01",
                "법률 제21447호", "2026-03-05", "타법개정"));
        h = LawGoHtml.parseHeaderLine("\n [시행 2026. 11. 12.] [대구광역시조례 제6551호, 2026. 10. 30., 일부개정] ");
        assertThat(h.effectiveFrom()).isEqualTo("2026-11-12");
        assertThat(h.promulgationNumber()).isEqualTo("대구광역시조례 제6551호");
        assertThat(h.promulgationDate()).isEqualTo("2026-10-30");
    }

    // --- 법령 본문 -----------------------------------------------------------------

    /** test_law_header_and_hidden_values. */
    @Test
    void lawHeaderAndHiddenValues() {
        VersionHeader h = lawBody.header();
        assertThat(h.officialTitle()).isEqualTo("국토의 계획 및 이용에 관한 법률");
        assertThat(h.shortTitle()).isEqualTo("국토계획법");
        assertThat(List.of(h.effectiveFrom(), h.promulgationNumber(), h.promulgationDate()))
                .containsExactly("2026-07-01", "법률 제21447호", "2026-03-05");
        assertThat(lawBody.seq()).isEqualTo("284013");
        assertThat(lawBody.externalId()).isEqualTo("009294");
        assertThat(LawGoHtml.headerConsistency(h, lawBody.hidden())).isEmpty();
    }

    /** test_law_article_text_verbatim_structure. */
    @Test
    void lawArticleTextVerbatimStructure() {
        ParsedArticle art = lawBody.article("제77조");
        assertThat(art).isNotNull();
        assertThat(List.of(art.label(), art.path(), art.heading(), art.anchor())).containsExactly("제77조", "art77", "용도지역의 건폐율", "J77:0");
        List<String> lines = lines(art.text());
        // 첫 줄 = 조 제목 + ①항 (화면 그대로), 개정 주석 포함
        assertThat(lines.get(0)).startsWith("제77조(용도지역의 건폐율) ① 제36조에 따라 지정된 용도지역에서 건폐율의 최대한도는");
        assertThat(lines.get(0)).endsWith("<개정 2011. 4. 14., 2013. 7. 16., 2015. 8. 11.>");
        assertThat(lines.subList(1, 6)).containsExactly("1. 도시지역", "가. 주거지역: 70퍼센트 이하", "나. 상업지역: 90퍼센트 이하",
                "다. 공업지역: 70퍼센트 이하", "라. 녹지지역: 20퍼센트 이하");
        assertThat(lines).contains("⑤ 삭제 <2021. 1. 12.>");
        assertThat(lines.getLast()).isEqualTo("[전문개정 2009. 2. 6.]");
        // HTML 공백 정리 결과: 줄 앞뒤 공백·연속 공백·숨은 요소 없음
        assertThat(lines).allSatisfy(ln -> {
            assertThat(ln).isEqualTo(com.autoparkingplan.common.Py.strip(ln));
            assertThat(ln).doesNotContain("  ");
        });
        assertThat(art.text()).doesNotContain("javascript").doesNotContain("체크박스");
        assertThat(art.warnings()).isEmpty();
        assertThat(art.images()).isEmpty();
    }

    /** test_decree_rates_for_second_class_general_residential. */
    @Test
    void decreeRatesForSecondClassGeneralResidential() {
        ParsedArticle a84 = decreeBody.article("제84조");
        ParsedArticle a85 = decreeBody.article("제85조");
        assertThat(a84).isNotNull();
        assertThat(a85).isNotNull();
        assertThat(a84.heading()).isEqualTo("용도지역안에서의 건폐율");
        assertThat(a85.heading()).isEqualTo("용도지역 안에서의 용적률");
        assertThat(lines(a84.text())).contains("4. 제2종일반주거지역 : 60퍼센트 이하");
        assertThat(lines(a85.text())).contains("4. 제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하");
        // 원문에 공백이 없는 곳은 없는 그대로 ('①법')
        assertThat(a84.text()).startsWith("제84조(용도지역안에서의 건폐율) ①법 제77조제1항 및 제2항에 따른 건폐율은");
    }

    /** test_decree_article71_points_to_annexes. */
    @Test
    void decreeArticle71PointsToAnnexes() {
        ParsedArticle a71 = decreeBody.article("제71조");
        assertThat(a71).isNotNull();
        List<String> lines = lines(a71.text());
        assertThat(lines).contains("3. 제1종일반주거지역안에서 건축할 수 있는 건축물 : 별표 4에 규정된 건축물",
                "4. 제2종일반주거지역안에서 건축할 수 있는 건축물 : 별표 5에 규정된 건축물",
                "5. 제3종일반주거지역안에서 건축할 수 있는 건축물 : 별표 6에 규정된 건축물");
        AnnexRef ref = decreeBody.annex("별표 5");
        assertThat(ref).isNotNull();
        assertThat(ref.bylSeq()).isEqualTo("18442157");
        assertThat(ref.path()).isEqualTo("annex5");
        assertThat(ref.titleLine()).isEqualTo("[별표 5] 제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)");
        assertThat(ref.heading()).isEqualTo("제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)");
        assertThat(ref.fileLinks()).anyMatch(x -> x.contains("flSeq=168871301"));
    }

    /** test_article_images_become_marker_lines_without_alt_text. */
    @Test
    void articleImagesBecomeMarkerLinesWithoutAltText() {
        ParsedBody body = LawGoHtml.parseBody(TestFiles.fixture("law_body_rule_parking_excerpt.html"), "law");
        ParsedArticle art = body.article("제3조");
        assertThat(art).isNotNull();
        assertThat(art.heading()).isEqualTo("주차장의 주차구획");
        List<String> lines = lines(art.text());
        int i = lines.indexOf("1. 평행주차형식의 경우");
        assertThat(lines.get(i + 1)).isEqualTo("[[IMAGE flSeq=24335428]]");
        assertThat(lines.get(i + 2)).isEqualTo("2. 평행주차형식 외의 경우");
        assertThat(lines.get(i + 3)).isEqualTo("[[IMAGE flSeq=33758907]]");
        // 대체텍스트는 text 에 넣지 않는다
        assertThat(art.text()).doesNotContain("경형 1.7미터 이상").doesNotContain("구분 너비 길이");
        assertThat(art.images()).extracting(LawGoHtml.ImageRef::marker)
                .containsExactly("[[IMAGE flSeq=24335428]]", "[[IMAGE flSeq=33758907]]");
        assertThat(art.images().getFirst().alt()).startsWith("구분 너비 길이");
        assertThat(art.images()).allSatisfy(im -> assertThat(LawGoHtml.IMAGE_MARKER_RE.matcher(im.marker()).lookingAt()).isTrue());
    }

    // --- 자치법규 본문 -------------------------------------------------------------

    /** test_ordinance_header_and_articles. */
    @Test
    void ordinanceHeaderAndArticles() {
        VersionHeader h = ordinBody.header();
        assertThat(h.officialTitle()).isEqualTo("대구광역시 도시계획 조례");
        assertThat(List.of(h.effectiveFrom(), h.promulgationNumber(), h.promulgationDate(), h.revisionKind()))
                .containsExactly("2026-05-11", "대구광역시조례 제6487호", "2026-05-11", "일부개정");
        assertThat(ordinBody.seq()).isEqualTo("2128915");
        assertThat(ordinBody.externalId()).isEqualTo("2152107");
        assertThat(LawGoHtml.headerConsistency(h, ordinBody.hidden())).isEmpty();
        ParsedArticle a75 = ordinBody.article("제75조");
        ParsedArticle a80 = ordinBody.article("제80조");
        assertThat(a75).isNotNull();
        assertThat(a80).isNotNull();
        assertThat(lines(a75.text()).getFirst())
                .isEqualTo("제75조(용도지역 안에서의 건폐율) 영 제84조제1항에 따라 용도지역 안에서의 건폐율은 다음 각 호에서 정한 비율로 한다.");
        assertThat(lines(a75.text())).contains("4. 제2종일반주거지역 : 60퍼센트 이하");
        assertThat(lines(a75.text())).contains("19. 계획관리지역 : 40퍼센트 이하 <개정 2014.5.20,, 2017.4.10.>"); // 원문 오기 그대로
        assertThat(lines(a80.text())).contains("4. 제2종일반주거지역 : 220퍼센트 이하");
    }

    /** test_ordinance_alias_definitions_and_links. */
    @Test
    void ordinanceAliasDefinitionsAndLinks() {
        List<LawGoHtml.AliasDefinition> defs = LawGoHtml.extractAliasDefinitions(ordinBody.articles());
        assertThat(defs).contains(new LawGoHtml.AliasDefinition("법", "국토의 계획 및 이용에 관한 법률", "제2조"),
                new LawGoHtml.AliasDefinition("영", "국토의 계획 및 이용에 관한 법률 시행령", "제3조"),
                new LawGoHtml.AliasDefinition("규칙", "국토의 계획 및 이용에 관한 법률 시행규칙", "제30조"));
        Map<String, List<String>> links = LawGoHtml.extractAliasLinks(TestFiles.fixture("ordin_body_urban_planning_excerpt.html"));
        assertThat(links.get("법")).containsExactly("009294");
        assertThat(links.get("영")).containsExactly("009419");
    }

    /** test_ordinance_annex_title_parsing. */
    @Test
    void ordinanceAnnexTitleParsing() {
        AnnexRef ref = ordinBody.annex("별표 2");
        assertThat(ref).isNotNull();
        assertThat(ref.titleLine()).isEqualTo("별표_2_상업지역 안에서 주거복합건축물의 용도 비율 및 용적률");
        assertThat(ref.heading()).isEqualTo("상업지역 안에서 주거복합건축물의 용도 비율 및 용적률");
        assertThat(ref.bylSeq()).isEqualTo("22307003");
    }

    // --- 별표 내용 / 연혁 / 이름 주소 ---------------------------------------------

    /** test_annex_contents_attachment_only. */
    @Test
    void annexContentsAttachmentOnly() {
        AnnexContent c = LawGoHtml.parseAnnexContents(TestFiles.fixture("law_annex_contents_18442157.html"));
        assertThat(c.hasText()).isFalse();
        assertThat(c.text()).isNull();
        assertThat(c.tableJson()).isNull();
        assertThat(c.fileSeqs().get("hanFlSeq")).isEqualTo("168871299");
        assertThat(c.fileSeqs().get("pdfFlSeq")).isEqualTo("168871301");
        assertThat(c.availability()).startsWith("attachment_only:").contains("HWP").contains("PDF");
        AnnexContent o = LawGoHtml.parseAnnexContents(TestFiles.fixture("ordin_annex_contents_21279951.html"));
        assertThat(o.hasText()).isFalse();
        assertThat(o.availability()).isEqualTo("attachment_only:HWP/문서뷰어(이미지 변환)");
    }

    /** test_annex_contents_html_table_path — 합성 표 (법령 문장 아님): HTML 표로 제공되는 별표가 있을 때의 처리만 확인. */
    @Test
    void annexContentsHtmlTablePath() {
        String html = "<html><body><table><tr><th>A</th><th>B</th></tr><tr><td>x <br/>y</td><td> z </td></tr></table></body></html>";
        AnnexContent c = LawGoHtml.parseAnnexContents(html);
        assertThat(c.hasText()).isTrue();
        assertThat(c.tableJson()).isEqualTo(List.of(List.of("A", "B"), List.of("x\ny", "z")));
    }

    /** test_ordinance_history_in_force_and_pending. */
    @Test
    void ordinanceHistoryInForceAndPending() {
        List<HistoryEntry> hist = LawGoHtml.parseHistory(TestFiles.fixture("ordin_history_building.html"), "ordin");
        assertThat(hist.getFirst().seq()).isEqualTo("2181437");
        assertThat(hist.getFirst().siteCurrentFlag()).isTrue();
        assertThat(hist.getFirst().effectiveFrom()).isEqualTo("2026-11-12");
        assertThat(hist.getFirst().promulgationNumber()).isEqualTo("조례 제6551호");
        HistoryEntry cur = LawGoHtml.selectInForce(hist, "2026-11-06");
        assertThat(cur).isNotNull();
        assertThat(List.of(cur.seq(), cur.effectiveFrom())).containsExactly("2173139", "2026-09-30");
        assertThat(LawGoHtml.pendingAfter(hist, "2026-11-06")).extracting(HistoryEntry::seq).containsExactly("2181437");
        HistoryEntry later = LawGoHtml.selectInForce(hist, "2026-11-12");
        assertThat(later).isNotNull();
        assertThat(later.seq()).isEqualTo("2181437");
    }

    /** test_law_history_same_seq_with_staged_effective_dates. */
    @Test
    void lawHistorySameSeqWithStagedEffectiveDates() {
        List<HistoryEntry> hist = LawGoHtml.parseHistory(TestFiles.fixture("law_history_decree_building_excerpt.html"), "law");
        List<List<String>> rows = hist.stream().filter(e -> e.seq().equals("290833")).map(e -> List.of(e.seq(), e.effectiveFrom())).toList();
        assertThat(rows).containsExactly(List.of("290833", "2027-04-07"), List.of("290833", "2026-10-06"));
        HistoryEntry cur = LawGoHtml.selectInForce(hist, "2026-11-06");
        assertThat(cur).isNotNull();
        assertThat(cur.seq()).isEqualTo("290833");
        assertThat(cur.effectiveFrom()).isEqualTo("2026-10-06");
        assertThat(cur.siteCurrentFlag()).isTrue();
        assertThat(LawGoHtml.pendingAfter(hist, "2026-11-06").stream().map(e -> List.of(e.seq(), e.effectiveFrom())).toList())
                .contains(List.of("290833", "2027-04-07"));
    }

    /** test_name_resolver. */
    @Test
    void nameResolver() {
        Map<String, String> q = LawGoHtml.parseNameResolver(TestFiles.fixture("name_resolver_decree_nkpa.html"));
        assertThat(q.get("lsiSeq")).isEqualTo("288857");
        assertThat(q.get("efYd")).isEqualTo("20260918");
    }

    // --- 번들 ---------------------------------------------------------------------

    private static DocumentMeta decreeDoc() {
        return new DocumentMeta("kr.decree.nkpa", "L1_NATIONAL", "decree", "국토의 계획 및 이용에 관한 법률 시행령", "00", "law.go.kr",
                "009419", null, "kr.law.nkpa", null, null);
    }

    /** test_build_bundle_selection_missing_and_verification. */
    @Test
    void buildBundleSelectionMissingAndVerification() {
        AnnexFetch annex = new AnnexFetch(decreeBody.annex("별표 5"),
                LawGoHtml.parseAnnexContents(TestFiles.fixture("law_annex_contents_18442157.html")), "raw/x.html");
        BuildResult r = LawGoHtml.buildBundle(decreeDoc(), decreeBody,
                LawGoHtml.humanUrl("law", "288857", "20260918"), "2026-11-06T12:00:00+09:00", "raw/kr.decree.nkpa.html",
                List.of("제84조", "제85조", "제99조", "제71조"), List.of(annex),
                Map.of("제84조", "건폐율", "제85조", "용적률", "제71조", "용적률"), "2026-11-06", null, LawGoHtml.BUNDLE_NOTE);
        SourceBundle bundle = r.bundle();
        assertThat(bundle.articles()).extracting(ArticleText::label).containsExactly("제84조", "제85조");
        Map<String, String> reasons = new java.util.HashMap<>();
        for (Missing m : r.missing()) {
            reasons.put(m.label(), m.reason());
        }
        assertThat(reasons.get("제99조")).contains("찾지 못함");
        assertThat(reasons.get("제71조")).contains("예상과 다름");
        assertThat(reasons.get("별표 5")).contains("첨부파일로만");
        VersionMeta v = bundle.version();
        assertThat(List.of(v.promulgationNumber(), v.promulgationDate(), v.effectiveFrom()))
                .containsExactly("대통령령 제36587호", "2026-08-18", "2026-09-18");
        assertThat(List.of(v.vSourceAccess(), v.vVersionHeader(), v.vTextMatch(), v.vCurrentVersion()))
                .containsExactly("confirmed", "confirmed", "not_checked", "confirmed");
        assertThat(v.currentCheckedOn()).isEqualTo("2026-11-06");
        assertThat(v.rawFormat()).isEqualTo("html");
        assertThat(v.externalVersionId()).isEqualTo("288857");
        assertThat(v.note()).isEqualTo(LawGoHtml.BUNDLE_NOTE);
        assertThat(bundle.isExample()).isFalse();
        assertThat(v.sourceUrl())
                .isEqualTo("https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857&chrClsCd=010202&urlMode=lsInfoP&efYd=20260918&ancYnChk=0");
        // 직렬화 왕복
        SourceBundle again = SourceBundle.fromJson(Json.parse(PyJson.dumps(bundle.toJson())));
        assertThat(again).isEqualTo(bundle);
        assertThat(r.warnings()).isEmpty();
    }

    /** (자바판 추가) 조 제목 불일치 사유 문구는 파이썬 repr 표기 그대로. */
    @Test
    void missingReasonUsesPythonRepr() {
        BuildResult r = LawGoHtml.buildBundle(decreeDoc(), decreeBody, "u", "t", null, List.of("제71조"), List.of(),
                Map.of("제71조", "용적률"), null, null, LawGoHtml.BUNDLE_NOTE);
        assertThat(r.missing().getFirst().reason())
                .isEqualTo("조 제목이 예상과 다름 (예상 포함어 '용적률', 실제 '용도지역안에서의 건축제한') — 번호 재확인 필요");
    }

    /** test_build_bundle_not_in_force_is_needs_review. */
    @Test
    void buildBundleNotInForceIsNeedsReview() {
        BuildResult r = LawGoHtml.buildBundle(decreeDoc(), decreeBody, "u", "t", null);
        assertThat(r.bundle().version().vCurrentVersion()).isEqualTo("needs_review");
        assertThat(r.bundle().version().currentCheckedOn()).isNull();
        assertThat(r.bundle().articles()).isEmpty();
    }

    // --- HTTP 클라이언트 (모의 전송) -------------------------------------------------

    /** test_client_requests_are_polite_and_identified. */
    @Test
    void clientRequestsArePoliteAndIdentified() {
        MockTransport mock = new MockTransport(HttpResponse.of(200, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8)));
        long t0;
        double elapsed;
        try (LawGoHtmlClient c = LawGoHtmlClient.builder().minInterval(0.2).transport(mock).build()) {
            t0 = System.nanoTime();
            c.lawBody("288857", "20260918");
            c.ordinBody("2128915", "2152107");
            c.lawHistory("009419");
            elapsed = (System.nanoTime() - t0) / 1e9;
        }
        assertThat(elapsed).isGreaterThanOrEqualTo(0.4); // 요청 사이 간격
        List<HttpRequest> seen = mock.requests();
        String ua = seen.get(0).headers().get("User-Agent");
        assertThat(ua).isEqualTo("APP-legal-collector/0.1 (dev)").doesNotContain("@");
        assertThat(seen.get(0).method()).isEqualTo("POST");
        assertThat(seen.get(0).uri().getPath()).isEqualTo("/LSW/lsInfoR.do");
        String body = new String(seen.get(0).body(), StandardCharsets.UTF_8);
        assertThat(body).contains("lsiSeq=288857").contains("efYd=20260918");
        assertThat(seen.get(1).method()).isEqualTo("GET");
        assertThat(seen.get(1).queryParam("ordinSeq")).isEqualTo("2128915");
        assertThat(seen.get(2).queryParam("lsId")).isEqualTo("009419");
    }

    /** test_client_refuses_fast_interval_for_real_site. */
    @Test
    void clientRefusesFastIntervalForRealSite() {
        assertThatThrownBy(() -> LawGoHtmlClient.builder().minInterval(0.5).build()).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_min_interval_holds_even_if_sleep_wakes_early — 윈도우처럼 시계가 거칠거나 sleep 이 일찍 깨어나도 요청 간격은 min_interval 이상. */
    @Test
    void minIntervalHoldsEvenIfSleepWakesEarly() {
        List<Long> starts = new ArrayList<>();
        MockTransport mock = MockTransport.handler(req -> {
            starts.add(System.nanoTime());
            return HttpResponse.of(200, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8));
        });
        // 요청한 시간의 절반만 자고 깨어남
        LawGoHtmlClient.Sleeper half = s -> Thread.sleep(java.time.Duration.ofNanos((long) (s / 2 * 1e9)));
        try (LawGoHtmlClient c = LawGoHtmlClient.builder().minInterval(0.2).transport(mock).sleeper(half).build()) {
            for (int i = 0; i < 3; i++) {
                c.lawHistory("009419");
            }
        }
        assertThat(starts).hasSize(3);
        for (int i = 1; i < starts.size(); i++) {
            assertThat((starts.get(i) - starts.get(i - 1)) / 1e9).isGreaterThanOrEqualTo(0.2);
        }
    }

    /** (자바판 추가) httpx follow_redirects=True 처럼 리다이렉트를 따라가고, 2xx 가 아니면 HTTPStatusError. */
    @Test
    void clientFollowsRedirectsAndRaisesForStatus() {
        MockTransport mock = MockTransport.handler(req -> {
            if (req.uri().getPath().equals("/LSW/lsInfoR.do")) {
                return new HttpResponse(302, Map.of("location", List.of("/LSW/moved.do?x=1"), "set-cookie", List.of("JSESSIONID=abc; Path=/")),
                        new byte[0]);
            }
            if (req.uri().getPath().equals("/LSW/moved.do")) {
                return HttpResponse.of(200, "text/html", "<html>ok</html>".getBytes(StandardCharsets.UTF_8));
            }
            return HttpResponse.of(404, "text/html", new byte[0]);
        });
        try (LawGoHtmlClient c = LawGoHtmlClient.builder().minInterval(0).transport(mock).build()) {
            LawGoHtml.FetchedPage p = c.lawBody("1", null);
            assertThat(p.text()).isEqualTo("<html>ok</html>");
            assertThat(p.url()).isEqualTo("https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=1&chrClsCd=010202&urlMode=lsInfoP&ancYnChk=0");
            HttpRequest second = mock.requests().get(1);
            assertThat(second.method()).isEqualTo("GET"); // 302 → GET, 본문 버림
            assertThat(second.body()).isEmpty();
            assertThat(second.headers().get("Cookie")).isEqualTo("JSESSIONID=abc");
            assertThatThrownBy(() -> c.lawHistory("x")).isInstanceOf(LawGoHtmlClient.HttpStatusError.class)
                    .hasMessageStartingWith("Client error '404 Not Found' for url 'https://www.law.go.kr/LSW/lsHstListR.do?lsId=x'");
        }
    }

    // --- 저장된 번들 ↔ 원문 페이지 재파싱 일치 -----------------------------------------

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "kr.law.nkpa", "kr.decree.nkpa", "kr.law.parking", "kr.decree.parking", "kr.rule.parking", "kr.decree.building",
            "daegu.ord.urban_planning", "daegu.ord.parking", "daegu.ord.building", "suseong.ord.parking");

    static Stream<Path> savedBundles() throws IOException {
        if (!Files.isDirectory(TestFiles.DATA)) {
            return Stream.of((Path) null);
        }
        try (Stream<Path> s = Files.list(TestFiles.DATA)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    /** test_saved_bundle_traces_back_to_raw_page (parametrize: data/legal_sources/*.json; 없으면 건너뜀). */
    @ParameterizedTest
    @MethodSource("savedBundles")
    void savedBundleTracesBackToRawPage(Path path) {
        assumeTrue(path != null, "data/legal_sources 번들 없음");
        SourceBundle b = SourceBundle.load(path);
        assertThat(ALLOWED_KEYS).contains(b.document().key());
        assertThat(b.isExample()).isFalse();
        assertThat(path.getFileName().toString()).isEqualTo(LawGoHtml.bundleFilename(b.document().key(), b.version().effectiveFrom()));
        assertThat(b.version().rawFile()).isNotEmpty();
        Path raw = TestFiles.DATA.resolve(b.version().rawFile());
        assertThat(raw).exists();
        String kind = b.document().key().contains(".ord.") ? "ordin" : "law";
        ParsedBody body = LawGoHtml.parseBody(LawGoHtml.decodeUtf8Strict(TestFiles.read(raw)), kind);
        assertThat(body.header().effectiveFrom()).isEqualTo(b.version().effectiveFrom());
        assertThat(body.header().promulgationNumber()).isEqualTo(b.version().promulgationNumber());
        assertThat(body.seq()).isEqualTo(b.version().externalVersionId());
        for (ArticleText art : b.articles()) {
            if (art.kind().equals("article")) {
                ParsedArticle again = body.article(art.label());
                assertThat(again).isNotNull();
                assertThat(again.text()).isEqualTo(art.text());
                assertThat(again.heading()).isEqualTo(art.heading());
            }
        }
        for (Map.Entry<String, String> e : b.document().aliases().entrySet()) {
            assertThat(ALLOWED_KEYS).as(e.getKey()).contains(e.getValue());
        }
        if (LegalPy.truthy(b.document().parentKey())) {
            assertThat(ALLOWED_KEYS).contains(b.document().parentKey());
        }
        String all = String.join("\n", b.articles().stream().map(ArticleText::text).toList());
        assertThat(Pattern.compile("\\n{4,}").matcher(all).find()).isFalse();
        // (자바판 추가) 번들 파일은 파이썬 json.dump(indent=2) + "\n" 과 바이트 단위로 같게 다시 써진다
        assertThat(b.toFileText()).isEqualTo(TestFiles.readText(path));
    }
}
