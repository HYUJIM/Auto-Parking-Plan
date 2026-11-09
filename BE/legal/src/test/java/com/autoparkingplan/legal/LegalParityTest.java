package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.legal.LawGoHtml.AnnexContent;
import com.autoparkingplan.legal.LawGoHtml.AnnexRef;
import com.autoparkingplan.legal.LawGoHtml.HistoryEntry;
import com.autoparkingplan.legal.LawGoHtml.ParsedArticle;
import com.autoparkingplan.legal.LawGoHtml.ParsedBody;
import com.autoparkingplan.legal.Structure.ProvisionRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 파이썬판과의 결과 대조 (자바판 추가 시험). 기대값은 파이썬 코드를 실제로 실행해 만든 JSON 이다:
 *
 * <pre>
 *   # 저장소 루트에서
 *   (git 이력 d70ca97 의 파이썬판으로) gen_parity.py
 * </pre>
 *
 * <ul>
 *   <li>structure_parity.json: data/legal_sources/*.json 번들(과 supplements 의 pdf_annex)의 모든 조문을
 *       structure.parse_article → flatten 한 행(kind·label·path·heading·text·parent_path)과 article_label_for 결과.
 *       번들은 {@code ../data/legal_sources} (모듈 폴더 기준) 에서 읽고, 없으면 건너뛴다.</li>
 *   <li>html_parity.json: data/legal_sources/raw/*.html (실제 law.go.kr 원문 페이지) 과 시험 표본을 lawgo_html 파서로 읽은
 *       결과 전부 (표제, 숨은 값, 모든 조의 text·제목·경고·이미지, 별표 목록, 연혁, 별표 내용, 약칭).</li>
 * </ul>
 * 긴 text 는 sha256 앞 16자리 + 코드포인트 길이로 비교한다. 원문 파일의 sha256 이 기대값과 다르면 기대값을 다시 만들라고 실패한다.
 */
class LegalParityTest {
    private static final JsonNode STRUCTURE = Json.parse(TestFiles.resourceText("/parity/structure_parity.json"));
    private static final JsonNode HTML = Json.parse(TestFiles.resourceText("/parity/html_parity.json"));
    private static final String REGEN = " — 원문이 바뀌었으면 기대값을 다시 만들 것: (git 이력 d70ca97 의 파이썬판으로) gen_parity.py";

    // ── 공통 ──────────────────────────────────────────────────

    private static JsonNode h(String s) {
        if (s == null) {
            return Json.nodes().nullNode();
        }
        ArrayNode a = Json.array();
        a.add(Py.sha256Hex(s).substring(0, 16));
        a.add(Py.len(s));
        return a;
    }

    private static JsonNode text(String s) {
        return s == null ? Json.nodes().nullNode() : Json.nodes().stringNode(s);
    }

    private static ArrayNode strings(List<String> xs) {
        ArrayNode a = Json.array();
        xs.forEach(a::add);
        return a;
    }

    private static ObjectNode stringMap(Map<String, String> m) {
        ObjectNode o = Json.object();
        m.forEach((k, v) -> o.set(k, text(v)));
        return o;
    }

    private static void checkSha(JsonNode expected, Path file) {
        assertThat(Py.sha256Hex(TestFiles.read(file))).as("sha256 " + file + REGEN).isEqualTo(expected.get("sha256").stringValue());
    }

    // ── 구조 파서 ─────────────────────────────────────────────

    private static ArrayNode rowsOf(String label, String path, String heading, String text, String kind) {
        ArrayNode out = Json.array();
        String base = kind.equals("annex") ? label.replace(" ", "") : label;
        for (ProvisionRow r : Structure.flatten(Structure.parseArticle(label, path, heading, text, kind), text)) {
            List<String> segs = new ArrayList<>();
            for (String seg : LegalPy.split(LegalPy.split(r.path(), "~").getFirst(), "/")) {
                if (!seg.startsWith("tbl") && !seg.startsWith("row")) {
                    segs.add(seg);
                }
            }
            ArrayNode row = out.addArray();
            row.add(r.kind());
            row.add(r.label());
            row.add(r.path());
            row.add(text(r.heading()));
            row.add(h(r.text()));
            row.add(text(r.parentPath()));
            row.add(Structure.articleLabelFor(String.join("/", segs), base));
        }
        return out;
    }

    static Stream<Arguments> bundleCases() {
        List<Arguments> out = new ArrayList<>();
        STRUCTURE.get("bundles").properties().forEach(e -> out.add(Arguments.of(e.getKey())));
        return out.stream();
    }

    /** 번들의 모든 조문: 구조 트리 행(path·text 등)과 근거 표시 라벨이 파이썬과 같다. */
    @ParameterizedTest
    @MethodSource("bundleCases")
    void structureOfSavedBundles(String name) {
        Path file = TestFiles.DATA.resolve(name);
        assumeTrue(Files.isRegularFile(file), "data/legal_sources 번들 없음");
        JsonNode expected = STRUCTURE.get("bundles").get(name);
        checkSha(expected, file);
        SourceBundle b = SourceBundle.load(file);
        List<JsonNode> exp = new ArrayList<>(expected.get("articles").values());
        assertThat(b.articles()).hasSize(exp.size());
        for (int i = 0; i < exp.size(); i++) {
            ArticleText a = b.articles().get(i);
            String kind = a.kind().equals("annex") ? "annex" : a.kind().equals("addendum") ? "addendum" : "article";
            assertThat(a.path()).isEqualTo(exp.get(i).get("path").stringValue());
            assertThat(rowsOf(a.label(), a.path(), a.heading(), a.text(), kind)).as(name + " " + a.path())
                    .isEqualTo(exp.get(i).get("rows"));
        }
    }

    /** supplements 의 pdf_annex 별표 텍스트도 같은 구조 트리. */
    @Test
    void structureOfSupplementAnnexes() {
        Path dir = TestFiles.DATA.resolve("supplements");
        assumeTrue(Files.isDirectory(dir), "data/legal_sources/supplements 없음");
        STRUCTURE.get("supplements").properties().forEach(e -> {
            Path file = dir.resolve(e.getKey());
            checkSha(e.getValue(), file);
            JsonNode supp = Json.parse(TestFiles.readText(file));
            List<JsonNode> exp = new ArrayList<>(e.getValue().get("items").values());
            int k = 0;
            for (JsonNode it : supp.get("items").values()) {
                if (!it.get("kind").stringValue().equals("pdf_annex")) {
                    continue;
                }
                JsonNode x = exp.get(k++);
                assertThat(it.get("path").stringValue()).isEqualTo(x.get("path").stringValue());
                assertThat(rowsOf(it.get("label").stringValue(), it.get("path").stringValue(), Json.text(it, "heading"),
                        it.get("text").stringValue(), "annex")).as(e.getKey() + " " + it.get("path")).isEqualTo(x.get("rows"));
            }
            assertThat(k).isEqualTo(exp.size());
        });
    }

    // ── HTML 파서 ─────────────────────────────────────────────

    private static ObjectNode bodyJson(ParsedBody b) {
        ObjectNode o = Json.object();
        LawGoHtml.VersionHeader hd = b.header();
        ArrayNode header = o.putArray("header");
        for (String s : new String[] {hd.officialTitle(), hd.shortTitle(), hd.headerLine(), hd.effectiveFrom(), hd.promulgationNumber(),
                hd.promulgationDate(), hd.revisionKind()}) {
            header.add(text(s));
        }
        o.set("hidden", stringMap(b.hidden()));
        o.set("consistency", strings(LawGoHtml.headerConsistency(hd, b.hidden())));
        o.set("external_id", text(b.externalId()));
        o.set("seq", text(b.seq()));
        ArrayNode arts = o.putArray("articles");
        for (ParsedArticle a : b.articles()) {
            ObjectNode x = arts.addObject();
            x.put("label", a.label());
            x.put("path", a.path());
            x.set("heading", text(a.heading()));
            x.set("text", h(a.text()));
            x.put("anchor", a.anchor());
            x.set("warnings", strings(a.warnings()));
            ArrayNode ims = x.putArray("images");
            for (LawGoHtml.ImageRef im : a.images()) {
                ArrayNode y = ims.addArray();
                y.add(im.marker());
                y.add(im.src());
                y.add(h(im.alt()));
            }
        }
        ArrayNode anx = o.putArray("annexes");
        for (AnnexRef r : b.annexes()) {
            ArrayNode y = anx.addArray();
            y.add(r.bylSeq());
            y.add(r.titleLine());
            y.add(text(r.label()));
            y.add(text(r.path()));
            y.add(text(r.heading()));
            y.add(strings(r.fileLinks()));
        }
        return o;
    }

    private static ObjectNode linksJson(Map<String, List<String>> links) {
        ObjectNode o = Json.object();
        links.forEach((k, v) -> o.set(k, strings(v)));
        return o;
    }

    /** gen_parity.py 의 html_case 와 같은 일. */
    private static ObjectNode htmlCase(String name, String html, JsonNode expected) {
        ObjectNode o = Json.object();
        String type = expected.get("type").stringValue();
        o.put("type", type);
        switch (type) {
            case "body" -> {
                String kind = expected.get("kind").stringValue();
                o.put("kind", kind);
                ParsedBody b = LawGoHtml.parseBody(html, kind);
                o.set("result", bodyJson(b));
                if (kind.equals("ordin")) {
                    o.set("alias_links", linksJson(LawGoHtml.extractAliasLinks(html)));
                    o.set("alias_links_rule", linksJson(LawGoHtml.extractAliasLinks(html, List.of("법", "영", "규칙", "시행규칙"))));
                    ArrayNode defs = o.putArray("alias_definitions");
                    for (LawGoHtml.AliasDefinition d : LawGoHtml.extractAliasDefinitions(b.articles())) {
                        defs.add(strings(List.of(d.alias(), d.title(), d.label())));
                    }
                }
            }
            case "history" -> {
                String kind = expected.get("kind").stringValue();
                o.put("kind", kind);
                List<HistoryEntry> hist = LawGoHtml.parseHistory(html, kind);
                ArrayNode es = o.putArray("entries");
                for (HistoryEntry e : hist) {
                    ArrayNode y = es.addArray();
                    y.add(e.seq());
                    y.add(h(e.text()));
                    y.add(text(e.effectiveFrom()));
                    y.add(text(e.promulgationDate()));
                    y.add(text(e.promulgationNumber()));
                    y.add(text(e.revisionKind()));
                    if (e.siteCurrentFlag() == null) {
                        y.addNull();
                    } else {
                        y.add(e.siteCurrentFlag());
                    }
                }
                HistoryEntry cur = LawGoHtml.selectInForce(hist, "2026-11-06");
                o.set("in_force", cur == null ? Json.nodes().nullNode() : strings(java.util.Arrays.asList(cur.seq(), cur.effectiveFrom())));
                ArrayNode pend = o.putArray("pending");
                for (HistoryEntry e : LawGoHtml.pendingAfter(hist, "2026-11-06")) {
                    pend.add(strings(java.util.Arrays.asList(e.seq(), e.effectiveFrom())));
                }
            }
            case "annex" -> {
                AnnexContent c = LawGoHtml.parseAnnexContents(html);
                o.put("has_text", c.hasText());
                o.set("text", h(c.text()));
                o.set("table_json", c.tableJsonNode() == null ? Json.nodes().nullNode() : c.tableJsonNode());
                o.set("viewer_iframe", text(c.viewerIframe()));
                o.set("content_images", strings(c.contentImages()));
                o.set("file_seqs", stringMap(c.fileSeqs()));
                o.put("availability", c.availability());
            }
            case "resolver" -> o.set("query", stringMap(LawGoHtml.parseNameResolver(html)));
            default -> {
            }
        }
        return o;
    }

    private static ObjectNode withoutSha(JsonNode n) {
        ObjectNode o = ((ObjectNode) n).deepCopy();
        o.remove("sha256");
        return o;
    }

    static Stream<Arguments> rawPages() {
        List<Arguments> out = new ArrayList<>();
        HTML.get("raw").properties().forEach(e -> out.add(Arguments.of(e.getKey())));
        return out.stream();
    }

    /** law.go.kr 실제 원문 페이지(data/legal_sources/raw): 파서 결과 전부가 파이썬(bs4)과 같다. */
    @ParameterizedTest
    @MethodSource("rawPages")
    void rawPagesParseLikePython(String name) {
        Path file = TestFiles.DATA.resolve("raw").resolve(name);
        assumeTrue(Files.isRegularFile(file), "data/legal_sources/raw 없음");
        JsonNode expected = HTML.get("raw").get(name);
        checkSha(expected, file);
        String html = new String(TestFiles.read(file), StandardCharsets.UTF_8);
        assertThat(htmlCase(name, html, expected)).as(name).isEqualTo(withoutSha(expected));
    }

    static Stream<Arguments> fixturePages() {
        List<Arguments> out = new ArrayList<>();
        HTML.get("fixtures").properties().forEach(e -> out.add(Arguments.of(e.getKey())));
        return out.stream();
    }

    /** 시험 표본(fixtures/lawgo/*.html): 파서 결과 전부가 파이썬과 같다. */
    @ParameterizedTest
    @MethodSource("fixturePages")
    void fixturePagesParseLikePython(String name) {
        JsonNode expected = HTML.get("fixtures").get(name);
        assertThat(Py.sha256Hex(TestFiles.fixtureBytes(name))).as("sha256 " + name + REGEN).isEqualTo(expected.get("sha256").stringValue());
        assertThat(htmlCase(name, TestFiles.fixture(name), expected)).as(name).isEqualTo(withoutSha(expected));
    }
}
