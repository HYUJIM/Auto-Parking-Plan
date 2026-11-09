package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.legal.LawGoHtml.ParsedArticle;
import com.autoparkingplan.legal.LawGoHtml.ParsedBody;

/**
 * 파이썬 tests/unit/test_lawgo_drf.py — app.legal.lawgo_drf 시험, 네트워크 없음.
 * (+ tests/unit/test_security.py 의 DRF 관련 시험 두 개)
 *
 * <p>drf_error_auth.xml 은 실제 DRF 응답(OC=test, 2026-11-06)이다.
 * drf_*_constructed.xml 은 가이드 필드 목록·활용방법 그림을 근거로 만든 표본이다(실제 응답 아님, 파일 머리 참고).
 */
class LawGoDrfTest {

    private static byte[] raw(String name) {
        return TestFiles.fixtureBytes(name);
    }

    private static HttpResponse xml(String name) {
        return HttpResponse.of(200, "application/xml", raw(name));
    }

    /** test_real_auth_failure_response_raises. */
    @Test
    void realAuthFailureResponseRaises() {
        assertThatThrownBy(() -> LawGoDrf.parseXml(raw("drf_error_auth.xml"))).isInstanceOf(DrfAuthError.class)
                .hasMessageContaining("사용자 정보 검증에 실패하였습니다");
    }

    /** test_html_instead_of_xml_raises. */
    @Test
    void htmlInsteadOfXmlRaises() {
        assertThatThrownBy(() -> LawGoDrf.parseXml("<html><body>error</body>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DrfError.class)
                .hasMessage("XML 이 아님 (HTML 오류 화면일 수 있음): '<html><body>error</body>'");
    }

    /** test_parse_law_xml_header_and_articles. */
    @Test
    void parseLawXmlHeaderAndArticles() {
        SourceBundle b = LawGoDrf.parseLawXml(raw("drf_law_constructed.xml"), null, null, null, "284013",
                DrfDocHint.builder().key("kr.law.nkpa").layer("L1_NATIONAL").jurisdictionCode("00").build());
        assertThat(b.document().key()).isEqualTo("kr.law.nkpa");
        assertThat(b.document().externalId()).isEqualTo("009294");
        assertThat(b.document().officialTitle()).isEqualTo("국토의 계획 및 이용에 관한 법률");
        assertThat(b.document().shortTitle()).isEqualTo("국토계획법");
        assertThat(b.document().sourceType()).isEqualTo("law");
        VersionMeta v = b.version();
        assertThat(List.of(v.promulgationNumber(), v.promulgationDate(), v.effectiveFrom()))
                .containsExactly("법률 제21447호", "2026-03-05", "2026-07-01");
        assertThat(v.externalVersionId()).isEqualTo("284013");
        assertThat(v.rawFormat()).isEqualTo("xml");
        assertThat(v.vVersionHeader()).isEqualTo("confirmed");
        assertThat(v.vCurrentVersion()).isEqualTo("needs_review");
        assertThat(v.sourceUrl()).isEqualTo("https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=284013&efYd=20260701");
        // 전문(장 제목)·서식은 건너뛴다
        assertThat(b.articles()).extracting(ArticleText::kind, ArticleText::label, ArticleText::path).containsExactly(
                org.assertj.core.groups.Tuple.tuple("article", "제77조", "art77"),
                org.assertj.core.groups.Tuple.tuple("annex", "별표 1의2", "annex1-2"));
    }

    /** test_law_xml_text_matches_html_rendering — 항·호·목으로 나뉜 XML 을 이어 붙인 결과가 HTML 수집기의 text 와 같은 모양인지 (첫 항은 제목 줄에 붙음). */
    @Test
    void lawXmlTextMatchesHtmlRendering() {
        SourceBundle b = LawGoDrf.parseLawXml(raw("drf_law_constructed.xml"));
        ArticleText art = b.articles().getFirst();
        ParsedArticle html = LawGoHtml.parseBody(TestFiles.fixture("law_body_nkpa_excerpt.html"), "law").article("제77조");
        assertThat(html).isNotNull();
        List<String> htmlLines = LegalPy.split(html.text(), "\n");
        assertThat(htmlLines.getLast()).isEqualTo("[전문개정 2009. 2. 6.]"); // 표본에서 뺀 줄 (DRF 에서의 위치 미확인)
        assertThat(art.text()).isEqualTo(String.join("\n", htmlLines.subList(0, htmlLines.size() - 1)));
        assertThat(art.heading()).isEqualTo("용도지역의 건폐율");
        assertThat(art.effectiveFrom()).isNull(); // 조문시행일자 == 문서 시행일자
    }

    /** test_law_xml_annex_cdata_blocks_become_lines. */
    @Test
    void lawXmlAnnexCdataBlocksBecomeLines() {
        SourceBundle b = LawGoDrf.parseLawXml(raw("drf_law_constructed.xml"));
        ArticleText annex = b.articles().get(1);
        assertThat(annex.text()).isEqualTo("[구조 시험용 1줄]\n[구조 시험용 2줄]"); // 빈 CDATA 구획은 버림
        assertThat(annex.effectiveFrom()).isEqualTo("2027-01-01");
    }

    /** test_parse_ordin_xml_matches_html_header_and_text. */
    @Test
    void parseOrdinXmlMatchesHtmlHeaderAndText() {
        SourceBundle b = LawGoDrf.parseOrdinXml(raw("drf_ordin_constructed.xml"), null, null, null,
                DrfDocHint.builder().key("daegu.ord.urban_planning").jurisdictionCode("27").build());
        ParsedBody html = LawGoHtml.parseBody(TestFiles.fixture("ordin_body_urban_planning_excerpt.html"), "ordin");
        assertThat(b.document().externalId()).isEqualTo("2152107");
        assertThat(b.version().externalVersionId()).isEqualTo("2128915");
        assertThat(b.document().layer()).isEqualTo("L3_LOCAL");
        assertThat(b.document().jurisdictionCode()).isEqualTo("27");
        // XML 필드로 구성한 공포번호가 HTML 표제 표기와 같아야 한다
        assertThat(b.version().promulgationNumber()).isEqualTo(html.header().promulgationNumber()).isEqualTo("대구광역시조례 제6487호");
        assertThat(b.version().effectiveFrom()).isEqualTo("2026-05-11");
        assertThat(b.version().promulgationDate()).isEqualTo("2026-05-11");
        assertThat(b.articles()).extracting(ArticleText::label).containsExactly("제75조", "제80조"); // 조문여부 N 은 건너뜀
        for (ArticleText a : b.articles()) {
            ParsedArticle h = html.article(a.label());
            assertThat(h).isNotNull();
            assertThat(a.text()).isEqualTo(h.text());
            assertThat(a.heading()).isEqualTo(h.heading());
        }
        assertThat(b.version().sourceUrl()).isEqualTo("https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2128915");
    }

    /** test_parse_search_xml. */
    @Test
    void parseSearchXml() {
        List<Map<String, String>> hits = LawGoDrf.parseSearchXml(raw("drf_search_constructed.xml"));
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().get("id")).isEqualTo("1");
        assertThat(hits.getFirst().get("법령ID")).isEqualTo("009294");
        assertThat(hits.getFirst().get("법령일련번호")).isEqualTo("284013");
        assertThat(hits.getFirst().get("법령명한글")).isEqualTo("국토의 계획 및 이용에 관한 법률");
        assertThat(hits.getFirst().keySet().iterator().next()).isEqualTo("id"); // 속성 먼저 (dict 순서)
    }

    /** test_client_builds_official_query_and_saves_raw. */
    @Test
    void clientBuildsOfficialQueryAndSavesRaw(@TempDir Path tmp) throws IOException {
        MockTransport mock = MockTransport.handler(req -> req.uri().getPath().endsWith("lawSearch.do")
                ? xml("drf_search_constructed.xml") : xml("drf_law_constructed.xml"));
        List<Map<String, String>> hits;
        SourceBundle b;
        try (LawGoDrfClient c = new LawGoDrfClient("my-oc", LawGoDrf.DRF_BASE_URL, Duration.ofSeconds(10), mock)) {
            hits = c.search("law", "국토의 계획 및 이용에 관한 법률");
            b = c.getDocument("eflaw", null, "284013", "20260701", DrfDocHint.builder().key("kr.law.nkpa").build(), tmp.resolve("raw"));
            // eflaw + MST 에는 efYd 필요
            assertThatThrownBy(() -> c.getDocument("eflaw", null, "284013", null, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.getDocument("law", null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(hits.getFirst().get("법령ID")).isEqualTo("009294");
        HttpRequest s0 = mock.requests().get(0);
        HttpRequest s1 = mock.requests().get(1);
        assertThat(s0.uri().toString()).startsWith("https://www.law.go.kr/DRF/lawSearch.do");
        assertThat(List.of(s0.queryParam("OC"), s0.queryParam("target"), s0.queryParam("type"), s0.queryParam("query")))
                .containsExactly("my-oc", "law", "XML", "국토의 계획 및 이용에 관한 법률");
        assertThat(s1.uri().getPath()).isEqualTo("/DRF/lawService.do");
        assertThat(List.of(s1.queryParam("OC"), s1.queryParam("target"), s1.queryParam("MST"), s1.queryParam("efYd"), s1.queryParam("type")))
                .containsExactly("my-oc", "eflaw", "284013", "20260701", "XML");
        assertThat(s1.queryParams()).doesNotContainKey("ID");
        assertThat(s1.headers().get("User-Agent")).isEqualTo("APP-legal-collector/0.1 (dev)");
        assertThat(b.document().key()).isEqualTo("kr.law.nkpa");
        assertThat(b.version().rawFile()).isNotNull();
        Path saved = tmp.resolve(b.version().rawFile());
        assertThat(Files.readAllBytes(saved)).isEqualTo(raw("drf_law_constructed.xml"));
        assertThat(b.version().rawFile()).isEqualTo("raw/drf_eflaw_284013_20260701.xml");
    }

    /** test_client_auth_failure_propagates. */
    @Test
    void clientAuthFailurePropagates() {
        MockTransport mock = new MockTransport(xml("drf_error_auth.xml"));
        try (LawGoDrfClient c = new LawGoDrfClient("test", null, null, mock)) {
            assertThatThrownBy(() -> c.getDocument("law", "009294", null)).isInstanceOf(DrfAuthError.class);
            assertThatThrownBy(() -> c.search("ordin", "대구")).isInstanceOf(DrfAuthError.class);
        }
    }

    /** test_client_requires_oc. */
    @Test
    void clientRequiresOc() {
        assertThatThrownBy(() -> new LawGoDrfClient("")).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_security.py: test_drf_errors_do_not_carry_oc_in_message. */
    @Test
    void drfErrorsDoNotCarryOcInMessage() {
        LawGoDrfClient c = new LawGoDrfClient("secret-oc-value", null, null,
                new MockTransport(HttpResponse.of(403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> c.search("law", "주차장법")).isInstanceOf(DrfError.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("secret-oc-value").isEqualTo("lawSearch.do HTTP 403");
                    assertThat(e.getCause()).isNull();
                });

        LawGoDrfClient boom = new LawGoDrfClient("secret-oc-value", null, null, MockTransport.handler(req -> {
            throw new ConnectException("connection refused " + req.uri());
        }));
        assertThatThrownBy(() -> boom.search("law", "주차장법")).isInstanceOf(DrfError.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("secret-oc-value").isEqualTo("lawSearch.do 요청 실패 (ConnectError)");
                    assertThat(e.getCause()).isNull();
                });
    }

    /** test_security.py: test_drf_default_base_url_is_https (클라이언트 부분). */
    @Test
    void drfDefaultBaseUrlIsHttps() {
        assertThat(new LawGoDrfClient("x").baseUrl()).startsWith("https://");
    }

    /** (자바판 추가) 자치법규종류 코드·조문번호 6자리 해석, 공포번호 구성. */
    @Test
    void ordinHelpers() {
        assertThat(LawGoDrf.ordinJoNo("000102")).containsExactly(java.math.BigInteger.ONE, java.math.BigInteger.TWO);
        assertThat(LawGoDrf.ordinJoNo("000100")).containsExactly(java.math.BigInteger.ONE, null);
        assertThat(LawGoDrf.ordinJoNo("제75조")).containsExactly(java.math.BigInteger.valueOf(75), null);
        assertThat(LawGoDrf.lawSourceType("총리령")).isEqualTo("rule");
        assertThat(LawGoDrf.lawSourceType("대통령령")).isEqualTo("decree");
        assertThat(LawGoDrf.lawSourceType(null)).isEqualTo("law");
        assertThat(LawGoDrf.ymd("2026.07.01")).isEqualTo("2026-07-01");
        assertThat(LawGoDrf.ymd("99991231")).isNull();
    }
}
