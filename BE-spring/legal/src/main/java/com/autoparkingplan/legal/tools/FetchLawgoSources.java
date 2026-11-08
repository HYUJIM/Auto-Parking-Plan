package com.autoparkingplan.legal.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.legal.Bs4;
import com.autoparkingplan.legal.DocumentMeta;
import com.autoparkingplan.legal.LawGoHtml;
import com.autoparkingplan.legal.LawGoHtml.AliasDefinition;
import com.autoparkingplan.legal.LawGoHtml.AnnexContent;
import com.autoparkingplan.legal.LawGoHtml.AnnexFetch;
import com.autoparkingplan.legal.LawGoHtml.AnnexRef;
import com.autoparkingplan.legal.LawGoHtml.BuildResult;
import com.autoparkingplan.legal.LawGoHtml.FetchedPage;
import com.autoparkingplan.legal.LawGoHtml.HistoryEntry;
import com.autoparkingplan.legal.LawGoHtml.ImageRef;
import com.autoparkingplan.legal.LawGoHtml.Missing;
import com.autoparkingplan.legal.LawGoHtml.ParsedArticle;
import com.autoparkingplan.legal.LawGoHtml.ParsedBody;
import com.autoparkingplan.legal.LawGoHtmlClient;
import com.autoparkingplan.legal.LegalPy;
import com.autoparkingplan.legal.SourceBundle;

import tools.jackson.databind.node.ObjectNode;

/**
 * law.go.kr 공개 HTML 에서 법규 원문을 받아 data/legal_sources/ 에 SourceBundle(JSON)로 저장한다
 * — 파이썬 {@code scripts/fetch_lawgo_sources.py}.
 *
 * <pre>
 *   FetchLawgoSources                                  # 전체
 *   FetchLawgoSources --only kr.law.nkpa kr.decree.nkpa
 *   FetchLawgoSources --as-of 2026-11-06
 *   FetchLawgoSources --sources-dir ../BE/data/legal_sources   # (자바판 추가) 저장 폴더, 기본 ./data/legal_sources
 * </pre>
 *
 * <ul>
 *   <li>대상 목록(TARGETS)은 선언형이다. 조문 번호는 받은 페이지에서 확인한 번호이며,
 *       expect_headings 로 조 제목을 함께 확인해 번호가 바뀌면 '누락'으로 기록한다(잘못된 조를 담지 않는다).</li>
 *   <li>버전은 연혁 목록에서 '시행일 ≤ 기준일' 중 가장 늦은 것을 고른다. 시행 예정 버전은 include_pending 일 때만 따로 저장.</li>
 *   <li>원문 페이지는 받은 바이트 그대로 raw/ 에 저장, MANIFEST.md 는 매번 다시 쓴다(재실행 가능).</li>
 *   <li>요청은 순차, 요청 사이 1.2초 이상 (LawGoHtmlClient).</li>
 * </ul>
 * 번들 JSON 은 파이썬 {@code json.dump(…, ensure_ascii=False, indent=2)} + 줄바꿈과 바이트 단위로 같다.
 */
public final class FetchLawgoSources {

    /** 수집 대상 하나 (파이썬 dataclass Target). kind = 'law' | 'ordin', name = 공식 제명 (법령 이름 주소로 현행본을 찾는 데 사용). */
    public record Target(String key, String kind, String name, String layer, String sourceType, String jurisdictionCode,
                         String priority, String purpose, String parentKey, List<String> articles,
                         Map<String, String> expectHeadings, List<String> annexes, Map<String, String> expectAnnexHeadings,
                         boolean headerOnly, boolean includePending, boolean resolveAliases) {
        public Target {
            articles = List.copyOf(articles);
            expectHeadings = unmodifiable(expectHeadings);
            annexes = List.copyOf(annexes);
            expectAnnexHeadings = unmodifiable(expectAnnexHeadings);
        }

        private static Map<String, String> unmodifiable(Map<String, String> m) {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
        }
    }

    private static final class T {
        private final String key;
        private final String kind;
        private final String name;
        private final String layer;
        private final String sourceType;
        private final String jurisdictionCode;
        private final String priority;
        private String purpose;
        private String parentKey;
        private List<String> articles = List.of();
        private Map<String, String> expectHeadings = Map.of();
        private List<String> annexes = List.of();
        private Map<String, String> expectAnnexHeadings = Map.of();
        private boolean headerOnly;
        private boolean includePending;
        private boolean resolveAliases;

        private T(String key, String kind, String name, String layer, String sourceType, String jurisdictionCode, String priority) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.layer = layer;
            this.sourceType = sourceType;
            this.jurisdictionCode = jurisdictionCode;
            this.priority = priority;
        }

        T purpose(String v) {
            purpose = v;
            return this;
        }

        T parentKey(String v) {
            parentKey = v;
            return this;
        }

        T articles(String... v) {
            articles = List.of(v);
            return this;
        }

        T expectHeadings(String... kv) {
            expectHeadings = pairs(kv);
            return this;
        }

        T annexes(String... v) {
            annexes = List.of(v);
            return this;
        }

        T expectAnnexHeadings(String... kv) {
            expectAnnexHeadings = pairs(kv);
            return this;
        }

        T headerOnly() {
            headerOnly = true;
            return this;
        }

        T includePending() {
            includePending = true;
            return this;
        }

        T resolveAliases() {
            resolveAliases = true;
            return this;
        }

        Target build() {
            return new Target(key, kind, name, layer, sourceType, jurisdictionCode, priority, purpose, parentKey, articles,
                    expectHeadings, annexes, expectAnnexHeadings, headerOnly, includePending, resolveAliases);
        }

        private static Map<String, String> pairs(String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return m;
        }
    }

    /** 조례 약칭이 가리키는 '공식 제명' → 문서 key. 번들이 없는 문서는 넣지 않는다(존재하지 않는 key 를 만들지 않음). */
    public static final Map<String, String> TITLE_TO_KEY;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("국토의 계획 및 이용에 관한 법률", "kr.law.nkpa");
        m.put("국토의 계획 및 이용에 관한 법률 시행령", "kr.decree.nkpa");
        m.put("주차장법", "kr.law.parking");
        m.put("주차장법 시행령", "kr.decree.parking");
        m.put("주차장법 시행규칙", "kr.rule.parking");
        m.put("건축법 시행령", "kr.decree.building");
        TITLE_TO_KEY = java.util.Collections.unmodifiableMap(m);
    }

    public static final List<Target> TARGETS = List.of(
            // ---------------- P1 건폐율·용적률 ----------------
            new T("kr.law.nkpa", "law", "국토의 계획 및 이용에 관한 법률", "L1_NATIONAL", "law", "00", "P1·P3")
                    .purpose("용도지역 건폐율·용적률 상한(제77조·제78조), 건축제한 위임(제76조)")
                    .articles("제76조", "제77조", "제78조")
                    .expectHeadings("제76조", "건축", "제77조", "건폐율", "제78조", "용적률")
                    .build(),
            new T("kr.decree.nkpa", "law", "국토의 계획 및 이용에 관한 법률 시행령", "L1_NATIONAL", "decree", "00", "P1·P3")
                    .parentKey("kr.law.nkpa")
                    .purpose("세분 용도지역 건폐율·용적률 범위(제84조·제85조), 용도지역 건축제한(제71조·별표)")
                    .articles("제71조", "제84조", "제85조")
                    .expectHeadings("제71조", "건축제한", "제84조", "건폐율", "제85조", "용적률")
                    .annexes("별표 4", "별표 5", "별표 6", "별표 7", "별표 9", "별표 17")
                    .expectAnnexHeadings(
                            "별표 4", "제1종일반주거지역", "별표 5", "제2종일반주거지역", "별표 6", "제3종일반주거지역",
                            "별표 7", "준주거지역", "별표 9", "일반상업지역", "별표 17", "자연녹지지역")
                    .build(),
            new T("daegu.ord.urban_planning", "ordin", "대구광역시 도시계획 조례", "L3_LOCAL", "ordinance", "27", "P1·P3")
                    .purpose("대구 용도지역 건폐율(제75조)·용적률(제80조), 용도지역별 건축제한(제33~36·38·46조), 약칭 정의(제2·3·30조)")
                    .articles("제1조", "제2조", "제3조", "제30조", "제33조", "제34조", "제35조", "제36조", "제38조", "제46조",
                            "제75조", "제80조")
                    .expectHeadings(
                            "제1조", "목적", "제2조", "기본방향", "제3조", "자문단", "제30조", "이행보증금",
                            "제33조", "제1종일반주거지역", "제34조", "제2종일반주거지역", "제35조", "제3종일반주거지역",
                            "제36조", "준주거지역", "제38조", "일반상업지역", "제46조", "자연녹지지역",
                            "제75조", "건폐율", "제80조", "용적률")
                    .resolveAliases()
                    .build(),
            // ---------------- P2 주차 구획·구조 ----------------
            new T("kr.law.parking", "law", "주차장법", "L1_NATIONAL", "law", "00", "P2")
                    .purpose("주차장 설비기준 위임(제6조), 부설주차장 설치·지정(제19조)")
                    .articles("제6조", "제19조")
                    .expectHeadings("제6조", "설비기준", "제19조", "부설주차장")
                    .build(),
            new T("kr.rule.parking", "law", "주차장법 시행규칙", "L1_NATIONAL", "rule", "00", "P2")
                    .parentKey("kr.law.parking")
                    .purpose("주차구획(제3조), 노외주차장 구조·설비(제6조), 부설주차장 구조·설비(제11조)")
                    .articles("제3조", "제6조", "제11조")
                    .expectHeadings("제3조", "주차구획", "제6조", "노외주차장", "제11조", "부설주차장")
                    .build(),
            // ---------------- P4 주차 설치기준·버전 ----------------
            new T("kr.decree.parking", "law", "주차장법 시행령", "L1_NATIONAL", "decree", "00", "P4")
                    .parentKey("kr.law.parking")
                    .purpose("부설주차장 설치기준(제6조, 별표 1)")
                    .articles("제6조")
                    .expectHeadings("제6조", "부설주차장")
                    .annexes("별표 1")
                    .expectAnnexHeadings("별표 1", "부설주차장")
                    .build(),
            new T("daegu.ord.parking", "ordin", "대구광역시 주차장 설치 및 관리 조례", "L3_LOCAL", "ordinance", "27", "P4")
                    .purpose("대구 부설주차장 설치기준(제15조, 별표 2), 약칭 정의(제1조의2)")
                    .articles("제1조", "제1조의2", "제15조")
                    .expectHeadings("제1조", "목적", "제1조의2", "실태조사", "제15조", "부설주차장")
                    .annexes("별표 2")
                    .expectAnnexHeadings("별표 2", "부설주차장")
                    .resolveAliases()
                    .build(),
            new T("suseong.ord.parking", "ordin", "대구광역시 수성구 주차장 설치 및 관리 조례", "L3_LOCAL", "ordinance", "27260", "P4")
                    .purpose("수성구 주차장 조례 존재·버전 표제 확인 (조문 미수집)")
                    .headerOnly()
                    .build(),
            new T("daegu.ord.building", "ordin", "대구광역시 건축 조례", "L3_LOCAL", "ordinance", "27", "P4")
                    .purpose("버전 표제만: 기준일 시행본 + 시행 예정본(PENDING_AMENDMENT 시연용)")
                    .headerOnly().includePending()
                    .build(),
            new T("kr.decree.building", "law", "건축법 시행령", "L1_NATIONAL", "decree", "00", "P4")
                    .parentKey(null) // 건축법(법률) 번들이 없으므로 비워 둔다
                    .purpose("버전 표제 + 별표 1(용도별 건축물의 종류) 제공 형태 확인")
                    .headerOnly()
                    .annexes("별표 1")
                    .expectAnnexHeadings("별표 1", "용도별 건축물의 종류")
                    .build());

    // ---------------------------------------------------------------------------

    /** 버전 하나의 수집 결과. role = 'in_force' | 'pending', images = (조 라벨, 이미지). */
    public record VersionResult(Target target, SourceBundle bundle, String bundleFile, String role, ParsedBody body,
                                List<String> rawFiles, List<Missing> missing, List<String> warnings, List<String> aliasEvidence,
                                List<String> annexNotes, List<Map.Entry<String, ImageRef>> images) {
    }

    /** 대상 하나의 수집 결과 (파이썬 dataclass TargetResult — 수집 중에 채운다). */
    public static final class TargetResult {
        public final Target target;
        public final List<VersionResult> versions = new ArrayList<>();
        public List<HistoryEntry> history = new ArrayList<>();
        public String historyUrl;
        public String resolverUrl;
        public String error;
        public final List<String> notes = new ArrayList<>();

        TargetResult(Target target) {
            this.target = target;
        }
    }

    /** 파이썬 RuntimeError (MANIFEST 의 오류 표기 {@code repr(e)} 용). */
    static final class ScriptError extends RuntimeException implements LegalPy.PyNamed {
        ScriptError(String message) {
            super(message);
        }

        @Override
        public String pythonName() {
            return "RuntimeError";
        }
    }

    private final Path outDir;
    private final Path rawDir;
    private final Path manifest;
    private final HttpTransport transport; // null = 실제 전송
    private final double minInterval;
    private final PrintStream out;
    private final PrintStream err;

    private FetchLawgoSources(Path sourcesDir, HttpTransport transport, double minInterval, PrintStream out, PrintStream err) {
        this.outDir = sourcesDir;
        this.rawDir = sourcesDir.resolve("raw");
        this.manifest = sourcesDir.resolve("MANIFEST.md");
        this.transport = transport;
        this.minInterval = minInterval;
        this.out = out;
        this.err = err;
    }

    private LawGoHtmlClient client(String baseUrl) {
        return LawGoHtmlClient.builder().baseUrl(baseUrl).transport(transport).minInterval(minInterval).build();
    }

    private String writeRaw(String name, FetchedPage page) {
        try {
            Files.createDirectories(rawDir);
            Files.write(rawDir.resolve(name), page.content()); // 받은 그대로
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "raw/" + name;
    }

    private static FetchedPage fetchBody(LawGoHtmlClient client, Target t, String seq, String efYd, String extId) {
        if (t.kind().equals("law")) {
            return client.lawBody(seq, efYd);
        }
        return client.ordinBody(seq, extId);
    }

    private record Aliases(Map<String, String> aliases, List<String> evidence) {
    }

    private static final List<String> ALIAS_WORDS = List.of("법", "영", "규칙", "시행규칙");

    private static Aliases resolveAliases(ParsedBody body, String html, Map<String, String> lsidToKey) {
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();
        Map<String, List<String>> links = LawGoHtml.extractAliasLinks(html, ALIAS_WORDS);
        for (AliasDefinition d : LawGoHtml.extractAliasDefinitions(body.articles())) {
            String alias = d.alias();
            if (!ALIAS_WORDS.contains(alias)) {
                continue;
            }
            String key = TITLE_TO_KEY.get(d.title());
            List<String> linked = links.getOrDefault(alias, List.of());
            TreeSet<String> lk = new TreeSet<>(PyJson::compareCodePoints);
            for (String i : linked) {
                if (lsidToKey.containsKey(i)) {
                    lk.add(lsidToKey.get(i));
                }
            }
            List<String> linkedKeys = new ArrayList<>(lk);
            String line = "'" + alias + "' = 「" + d.title() + "」 (" + d.label() + "에서 정의); 본문 링크 법령ID "
                    + (linked.isEmpty() ? "없음" : LegalPy.repr(linked));
            if (key == null) {
                evidence.add(line + " → 이 저장소에 번들이 없어 aliases 에 넣지 않음");
                continue;
            }
            if (!linkedKeys.isEmpty() && !linkedKeys.equals(List.of(key))) {
                evidence.add(line + " → 경고: 링크 대상 " + LegalPy.repr(linkedKeys) + " 와 제명 대상 " + key + " 가 다름, aliases 에 넣지 않음");
                continue;
            }
            aliases.put(alias, key);
            evidence.add(line + " → aliases['" + alias + "'] = '" + key + "'");
        }
        return new Aliases(aliases, evidence);
    }

    TargetResult collectTarget(LawGoHtmlClient client, Target t, String asOf, Map<String, String> lsidToKey) {
        TargetResult res = new TargetResult(t);
        boolean law = t.kind().equals("law");
        LawGoHtmlClient.Resolved resolved = client.resolveByName(t.kind(), t.name());
        res.resolverUrl = resolved.page().url();
        String seq0 = law ? resolved.query().get("lsiSeq") : resolved.query().get("ordinSeq");
        if (!LegalPy.truthy(seq0)) {
            throw new ScriptError("이름 주소에서 일련번호를 찾지 못함: " + resolved.page().url());
        }
        String ef0 = resolved.query().get("efYd");
        FetchedPage page0 = fetchBody(client, t, seq0, ef0, null);
        ParsedBody body0 = LawGoHtml.parseBody(page0.text(), t.kind());
        String extId = body0.externalId();
        if (!LegalPy.truthy(extId)) {
            throw new ScriptError("본문에서 법령ID/자치법규ID 를 찾지 못함");
        }
        if (law) {
            lsidToKey.put(extId, t.key());
        }

        FetchedPage histPage = law ? client.lawHistory(extId) : client.ordinHistory(extId);
        res.historyUrl = histPage.url();
        writeRaw(t.key() + "__history.html", histPage);
        res.history = LawGoHtml.parseHistory(histPage.text(), t.kind());
        HistoryEntry inForce = LawGoHtml.selectInForce(res.history, asOf);
        if (inForce == null) {
            throw new ScriptError("연혁 목록에서 기준일 시행본을 고르지 못함");
        }
        List<HistoryEntry> flagged = res.history.stream().filter(e -> Boolean.TRUE.equals(e.siteCurrentFlag())).toList();
        if (law && !flagged.isEmpty() && !(flagged.getFirst().seq().equals(inForce.seq())
                && java.util.Objects.equals(flagged.getFirst().effectiveFrom(), inForce.effectiveFrom()))) {
            res.notes.add("주의: 사이트 '현행' 표시(" + flagged.getFirst().seq() + ")와 시행일 기준 선택(" + inForce.seq() + ")이 다름");
        }
        if (!law && !flagged.isEmpty() && !flagged.getFirst().seq().equals(inForce.seq())) {
            res.notes.add("사이트가 '현행'으로 표시한 버전(" + flagged.getFirst().seq() + ": " + flagged.getFirst().text() + ")은 기준일 "
                    + asOf + " 현재 시행 전 — 시행일 기준으로 " + inForce.seq() + " 를 시행본으로 사용");
        }

        List<Map.Entry<HistoryEntry, String>> wanted = new ArrayList<>();
        wanted.add(Map.entry(inForce, "in_force"));
        if (t.includePending()) {
            for (HistoryEntry e : LawGoHtml.pendingAfter(res.history, asOf)) {
                wanted.add(Map.entry(e, "pending"));
            }
        }

        // 법령은 (lsiSeq, 시행일) 이 한 버전, 자치법규는 ordinSeq 가 한 버전
        Map<List<String>, Map.Entry<FetchedPage, ParsedBody>> pages = new LinkedHashMap<>();
        pages.put(pageKey(law, seq0, ef0), Map.entry(page0, body0));
        for (Map.Entry<HistoryEntry, String> w : wanted) {
            HistoryEntry entry = w.getKey();
            String ef = law && LegalPy.truthy(entry.effectiveFrom()) ? entry.effectiveFrom().replace("-", "") : null;
            List<String> k = pageKey(law, entry.seq(), ef);
            Map.Entry<FetchedPage, ParsedBody> pb = pages.get(k);
            if (pb == null) {
                FetchedPage page = fetchBody(client, t, entry.seq(), ef, extId);
                pb = Map.entry(page, LawGoHtml.parseBody(page.text(), t.kind()));
                pages.put(k, pb);
            }
            res.versions.add(makeVersion(client, t, w.getValue(), entry, pb.getKey(), pb.getValue(), asOf, lsidToKey));
        }
        return res;
    }

    private static List<String> pageKey(boolean law, String seq, String efYd8) {
        return Arrays.asList(seq, law ? efYd8 : null);
    }

    private VersionResult makeVersion(LawGoHtmlClient client, Target t, String role, HistoryEntry entry, FetchedPage page,
                                      ParsedBody body, String asOf, Map<String, String> lsidToKey) {
        boolean law = t.kind().equals("law");
        LawGoHtml.VersionHeader h = body.header();
        String eff = LegalPy.or(h.effectiveFrom(), "unknown");
        String endpoint = law ? "lsInfoR" : "ordinInfoR";
        List<String> rawFiles = new ArrayList<>();
        rawFiles.add(writeRaw(t.key() + "__" + eff + "__" + endpoint + "-" + LegalPy.str(body.seq()) + ".html", page));
        List<String> warnings = new ArrayList<>();
        if (!entry.seq().equals(body.seq())) {
            warnings.add("요청한 일련번호 " + entry.seq() + " 와 본문 일련번호 " + LegalPy.str(body.seq()) + " 가 다름");
        }
        if (!java.util.Objects.equals(h.effectiveFrom(), entry.effectiveFrom())) {
            warnings.add("연혁 목록 시행일 " + LegalPy.str(entry.effectiveFrom()) + " 와 본문 표제 시행일 " + LegalPy.str(h.effectiveFrom())
                    + " 가 다름");
        }

        List<AnnexFetch> annexFetches = new ArrayList<>();
        List<Missing> annexMissing = new ArrayList<>();
        List<String> annexNotes = new ArrayList<>();
        if (role.equals("in_force")) {
            for (String label : t.annexes()) {
                AnnexRef ref = body.annex(label);
                if (ref == null) {
                    annexMissing.add(new Missing(label, "본문 별표 목록에서 찾지 못함"));
                    continue;
                }
                String want = t.expectAnnexHeadings().get(label);
                if (LegalPy.truthy(want) && (!LegalPy.truthy(ref.heading()) || !ref.heading().contains(want))) {
                    annexMissing.add(new Missing(label, "별표 제목이 예상과 다름 (예상 포함어 " + LegalPy.repr(want) + ", 실제 "
                            + LegalPy.repr(ref.titleLine()) + ")"));
                    continue;
                }
                FetchedPage apage = law ? client.lawAnnex(ref.bylSeq()) : client.ordinAnnex(ref.bylSeq());
                String araw = writeRaw(t.key() + "__" + eff + "__annex-" + ref.bylSeq() + ".html", apage);
                rawFiles.add(araw);
                AnnexContent content = LawGoHtml.parseAnnexContents(apage.text());
                annexFetches.add(new AnnexFetch(ref, content, araw));
                annexNotes.add(label + ": 「" + ref.titleLine() + "」 bylSeq=" + ref.bylSeq() + ", 제공 형태 " + content.availability()
                        + ", 첨부 " + LegalPy.or(String.join(", ", ref.fileLinks()), "없음"));
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> aliasEvidence = new ArrayList<>();
        if (t.resolveAliases()) {
            Aliases a = resolveAliases(body, page.text(), lsidToKey);
            aliases = a.aliases();
            aliasEvidence = a.evidence();
        }

        DocumentMeta doc = new DocumentMeta(t.key(), t.layer(), t.sourceType(), LegalPy.or(h.officialTitle(), t.name()),
                t.jurisdictionCode(), "law.go.kr", body.externalId(), h.shortTitle(), t.parentKey(),
                LawGoHtml.homepageUrl(t.kind(), t.name()), aliases);
        String note = "선별 조문만 수집 (개발용 HTML 수집기)";
        List<String> labels = (t.headerOnly() || role.equals("pending")) ? List.of() : t.articles();
        List<Map.Entry<String, ImageRef>> images = new ArrayList<>();
        for (String label : labels) {
            ParsedArticle art = body.article(label);
            if (art != null) {
                for (ImageRef im : art.images()) {
                    images.add(Map.entry(label, im));
                }
            }
        }
        if (!images.isEmpty()) {
            Set<String> uniq = new LinkedHashSet<>();
            images.forEach(e -> uniq.add(e.getKey()));
            List<String> imgLabels = new ArrayList<>(uniq);
            imgLabels.sort(java.util.Comparator.comparingInt(labels::indexOf));
            note += " — " + String.join("·", imgLabels) + "에 본문 표 이미지가 있음: text 의 [[IMAGE flSeq=…]] 줄이 이미지 자리이며 "
                    + "이미지 내용(표 수치)은 수집하지 않음";
        }
        if (t.headerOnly()) {
            note += " — 이 번들은 버전 표제만 담음(조문 없음)";
        }
        if (role.equals("pending")) {
            note += " — 시행 예정 버전: 기준일 " + asOf + " 현재 시행 전";
        }
        String efYd = law && LegalPy.truthy(h.effectiveFrom()) ? h.effectiveFrom().replace("-", "") : null;
        BuildResult br = LawGoHtml.buildBundle(doc, body,
                LawGoHtml.humanUrl(t.kind(), LegalPy.or(body.seq(), entry.seq()), efYd), page.fetchedAt(), rawFiles.getFirst(),
                labels, annexFetches, t.expectHeadings(), role.equals("in_force") ? asOf : null, asOf, note);
        List<Missing> missing = new ArrayList<>(annexMissing);
        missing.addAll(br.missing());
        String fname = LawGoHtml.bundleFilename(t.key(), h.effectiveFrom());
        writeText(outDir.resolve(fname), br.bundle().toFileText());
        List<String> allWarnings = new ArrayList<>(warnings);
        allWarnings.addAll(br.warnings());
        return new VersionResult(t, br.bundle(), fname, role, body, rawFiles, missing, allWarnings, aliasEvidence, annexNotes, images);
    }

    private static void writeText(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------
    // 법정동코드 확인 (행정표준코드관리시스템)
    // ---------------------------------------------------------------------------

    /** (법정동코드, 기대 이름, 접두). */
    public static final List<List<String>> REGION_CHECKS = List.of(
            List.of("2700000000", "대구광역시", "27"), List.of("2726000000", "대구광역시 수성구", "27260"));

    public record RegionCheck(String code, String expectedName, String prefix, String foundName, boolean ok, String rawFile,
                              String url, String fetchedAt) {
    }

    List<RegionCheck> verifyRegionCodes() {
        List<RegionCheck> res = new ArrayList<>();
        try (LawGoHtmlClient cc = client("https://www.code.go.kr")) {
            for (List<String> rc : REGION_CHECKS) {
                String code = rc.get(0);
                String name = rc.get(1);
                String prefix = rc.get(2);
                Map<String, String> params = new LinkedHashMap<>();
                params.put("cPage", "1");
                params.put("pageSize", "10");
                params.put("disuseAt", "0");
                params.put("codeseId", "00002");
                params.put("searchOk", "0");
                params.put("sidoCd", "*");
                params.put("sggCd", "*");
                params.put("umdCd", "*");
                params.put("riCd", "*");
                params.put("regionCd", code);
                params.put("locataddNm", "");
                FetchedPage page;
                try {
                    page = cc.request("POST", "/stdcode/regCodeL.do", params);
                } catch (RuntimeException e) {
                    res.add(new RegionCheck(code, name, prefix, null, false, null, "https://www.code.go.kr/stdcode/regCodeL.do", null));
                    err.println("[region] " + code + ": " + e.getMessage());
                    continue;
                }
                String raw = writeRaw("code.go.kr__regCodeL__" + code + ".html", page);
                Document soup = Bs4.parse(page.text());
                String found = null;
                for (Element td : Bs4.findAll(soup, Bs4.tag("td").and(Bs4.hasClass("table_left")))) {
                    if (Bs4.getText(td, "", true).equals(code)) {
                        Element nx = Bs4.findNextSibling(td, "td");
                        found = nx != null ? Bs4.getText(nx, "", true) : null;
                        break;
                    }
                }
                res.add(new RegionCheck(code, name, prefix, found, name.equals(found), raw, page.url(), page.fetchedAt()));
            }
        }
        return res;
    }

    // ---------------------------------------------------------------------------
    // MANIFEST
    // ---------------------------------------------------------------------------

    private static String md(String s) {
        return LegalPy.or(s, "—").replace("|", "\\|").replace("\n", " ");
    }

    void writeManifest(List<TargetResult> results, List<RegionCheck> regions, String asOf, String started) {
        List<String> l = new ArrayList<>();
        l.add("# 법규 원문 수집 기록 (law.go.kr 공개 HTML, 개발용)");
        l.add("");
        l.add("- 생성: `scripts/fetch_lawgo_sources.py` · 실행 시작 " + started + " · 검토 기준일 **" + asOf + "**");
        l.add("- 수집기: `app/legal/lawgo_html.py` (bs4 + 정규식, AI 미사용) · User-Agent `" + LawGoHtml.USER_AGENT
                + "` · 순차 요청, 요청 간 1.2초 이상");
        l.add("- 형식: `app-legal-source@1` (`app/legal/parsed.py`) · 원문 페이지는 받은 바이트 그대로 `raw/` 에 보관");
        l.add("- DRF Open API(`/DRF/lawService.do`)는 OC 인증값이 없어 사용하지 않음(OC=test → '사용자 정보 검증에 실패하였습니다.'). "
                + "클라이언트·파서는 `app/legal/lawgo_drf.py` 에 있으나 실제 응답 표본이 없어 가이드 필드 목록으로 만든 표본으로만 시험함.");
        l.add("");
        l.add("## 텍스트 규칙");
        l.add("");
        l.add("- `text` 는 조 전체: 첫 줄이 화면 그대로의 `제N조(제목)` + ①항 본문이고, 이어서 항·호·목이 원문 순서대로 한 줄씩 (화면의 `<p>` 1개 = 1줄).");
        l.add("- 정규화는 다음만 적용: HTML 표시 규칙의 공백 처리(태그 사이 연속 공백 → 한 칸, 줄 앞뒤 공백 제거), `<br>`→줄바꿈, 빈 문단 제거, `\\r\\n`→`\\n`, NBSP→공백, 줄 끝 공백 제거, 빈 줄 3줄 이상 → 1줄.");
        l.add("- **개정 연혁 주석은 포함한다.** `<개정 2011. 4. 14., …>`, `<신설 …>`, `[전문개정 …]`, `(개정 2008. 12. 30 조례 제4006호)` 등 화면에 보이는 주석을 그대로 남겼다. "
                + "규칙 추출 시 주석을 떼려면 파서에서 별도로 처리해야 한다.");
        l.add("- 링크·약칭(「」, ㆍ, ·, ①②, `1.`, `가.`)은 원문 문자 그대로. 화면 아이콘·숨은 요소(display:none)·체크박스는 제외.");
        l.add("- 조문 안에 표가 **이미지**로 실린 곳은 그 자리에 `[[IMAGE flSeq=…]]` 한 줄을 넣었다(원문에 없는 유일한 추가 줄). "
                + "이미지 내용은 text 에 없다 — 아래 '누락(본문 이미지)' 참고. 구조 파서는 `^\\[\\[IMAGE ` 줄을 '내용 미수집' 표시로 다뤄야 한다.");
        l.add("- 별표가 HWP/PDF/이미지(문서뷰어 이미지 변환)로만 제공되면 전사하지 않고 아래 '누락'에 기록했다. law.go.kr 에 HTML 텍스트판이 없었다.");
        l.add("- `v_current_version=confirmed` 는 연혁 목록에서 '시행일 ≤ 기준일 중 가장 늦은 버전'이고 본문 표제 시행일도 기준일 이전일 때만. "
                + "`v_version_header=confirmed` 는 표제에서 시행일·공포번호·공포일을 모두 읽었고 페이지 숨은 값(ancYd/ancNo/efYd)과 일치할 때만.");
        l.add("");
        l.add("## 번들 목록");
        l.add("");
        l.add("| 우선 | key | 제명 | 화면 표제 | 시행일 | 일련번호 | 기준일 시행본 | 조문/별표 수 | 파일 |");
        l.add("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        for (TargetResult r : results) {
            for (VersionResult v : r.versions) {
                SourceBundle b = v.bundle();
                if (b == null) {
                    continue;
                }
                String state = b.version().vCurrentVersion().equals("confirmed") ? "예 (confirmed)"
                        : (v.role().equals("pending") ? "아니오 — 시행 예정" : "needs_review");
                l.add("| " + r.target.priority() + " | `" + r.target.key() + "` | " + md(b.document().officialTitle()) + " | "
                        + md(v.body() != null ? v.body().header().headerLine() : null) + " "
                        + "| " + md(b.version().effectiveFrom()) + " | " + md(b.version().externalVersionId()) + " "
                        + "| " + state + " "
                        + "| " + b.articles().size() + " | `" + v.bundleFile() + "` |");
            }
            if (r.error != null) {
                l.add("| " + r.target.priority() + " | `" + r.target.key() + "` | " + md(r.target.name()) + " | 수집 실패 | — | — | — | 0 | — |");
            }
        }
        l.add("");
        l.add("## 문서별 상세");
        for (TargetResult r : results) {
            Target t = r.target;
            l.add("");
            l.add("### `" + t.key() + "` — " + t.name());
            l.add("");
            l.add("- 목적: " + t.purpose());
            l.add("- 이름 주소: " + LegalPy.or(r.resolverUrl, "—"));
            l.add("- 연혁 목록: " + LegalPy.or(r.historyUrl, "—") + " (raw: `raw/" + t.key() + "__history.html`)");
            if (r.error != null) {
                l.add("- **수집 실패**: " + r.error);
                continue;
            }
            for (String n : r.notes) {
                l.add("- " + n);
            }
            List<HistoryEntry> pend = LawGoHtml.pendingAfter(r.history, asOf);
            if (!pend.isEmpty()) {
                l.add("- 연혁 목록상 기준일(" + asOf + ") 뒤에 시행되는 버전:");
                for (HistoryEntry e : pend) {
                    l.add("  - `" + e.seq() + "` " + e.text());
                }
            }
            for (VersionResult v : r.versions) {
                SourceBundle b = v.bundle();
                if (b == null) {
                    continue;
                }
                l.add("- **" + (v.role().equals("in_force") ? "시행본" : "시행 예정본") + "** `" + v.bundleFile() + "`");
                l.add("  - 표제: " + (v.body() != null ? LegalPy.str(v.body().header().headerLine()) : "—"));
                l.add("  - source_url: " + b.version().sourceUrl());
                l.add("  - raw: " + String.join(", ", v.rawFiles().stream().map(x -> "`" + x + "`").toList()));
                l.add("  - 검증: source_access=" + b.version().vSourceAccess() + ", version_header=" + b.version().vVersionHeader() + ", "
                        + "current_version=" + b.version().vCurrentVersion() + ", text_match=" + b.version().vTextMatch());
                if (!b.articles().isEmpty()) {
                    l.add("  - 수집: " + String.join(", ", b.articles().stream()
                            .map(a -> LegalPy.truthy(a.heading()) ? a.label() + "(" + a.heading() + ")" : a.label()).toList()));
                }
                if (!b.document().aliases().isEmpty() || !v.aliasEvidence().isEmpty()) {
                    ObjectNode al = Json.object();
                    b.document().aliases().forEach(al::put);
                    l.add("  - aliases: `" + PyJson.dumps(al) + "`");
                    for (String ev : v.aliasEvidence()) {
                        l.add("    - " + ev);
                    }
                }
                for (String an : v.annexNotes()) {
                    l.add("  - 별표 확인: " + an);
                }
                for (Missing m : v.missing()) {
                    l.add("  - **누락** " + m.label() + ": " + m.reason());
                }
                for (Map.Entry<String, ImageRef> e : v.images()) {
                    ImageRef im = e.getValue();
                    l.add("  - **누락(본문 이미지)** " + e.getKey() + " `" + im.marker() + "` (https://www.law.go.kr" + im.src() + "): "
                            + "표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — "
                            + "이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것.");
                    l.add("");
                    l.add("    ```text");
                    for (String ln : LegalPy.split(im.alt().replace("\r\n", "\n"), "\n")) {
                        if (!com.autoparkingplan.common.Py.strip(ln).isEmpty()) {
                            l.add("    " + com.autoparkingplan.common.Py.rstrip(ln));
                        }
                    }
                    l.add("    ```");
                    l.add("");
                }
                for (String w : v.warnings()) {
                    l.add("  - 경고: " + w);
                }
            }
        }
        l.add("");
        l.add("## 법정동코드 확인");
        l.add("");
        l.add("출처: 행정표준코드관리시스템(행정안전부) 법정동코드목록조회 `https://www.code.go.kr/stdcode/regCodeL.do` (폐지구분 '현존', 법정동코드 검색)");
        l.add("");
        l.add("| 법정동코드 | 조회된 법정동명 | 기대 | 접두 | 결과 | raw |");
        l.add("| --- | --- | --- | --- | --- | --- |");
        for (RegionCheck c : regions) {
            l.add("| " + c.code() + " | " + md(c.foundName()) + " | " + c.expectedName() + " | `" + c.prefix() + "` | "
                    + (c.ok() ? "일치" : "불일치/실패") + " | `" + LegalPy.or(c.rawFile(), "—") + "` |");
        }
        l.add("");
        l.add("## 아직 없는 것");
        l.add("");
        l.add("- 모든 별표 본문(시행령 별표 4·5·6·7·9·17, 주차장법 시행령 별표 1, 대구 주차장 조례 별표 2, 건축법 시행령 별표 1): 첨부파일 전용 → 미전사. "
                + "DRF Open API(`<별표내용>`) 또는 공식 HWP/PDF 를 별도 검수 절차로 처리해야 함.");
        l.add("- 주차장법 시행규칙 제3조(주차단위구획 너비·길이), 제6조(노외주차장 차로 너비), 제11조⑤1(소규모 부설주차장 차로 너비)의 **표 이미지 내용**: "
                + "text 에는 `[[IMAGE flSeq=…]]` 자리 표시만 있다. 위 대체텍스트는 참고용이며 원본 이미지와 대조한 값이 아니다.");
        l.add("- 대구광역시 도시계획 조례는 용도지역별 건축제한을 별표가 아니라 조문(제31조~제50조의2)으로 둔다. 1·2·3종일반주거(제33~35조), 준주거(제36조), "
                + "일반상업(제38조), 자연녹지(제46조)만 수집했다.");
        l.add("- 수성구 주차장 조례·대구 건축 조례·건축법 시행령: 버전 표제만(조문 없음).");
        l.add("- `v_text_match`: 다른 경로(DRF XML 등)와 대조하지 않았으므로 전부 `not_checked`.");
        l.add("- 부칙·조문별 시행일: 수집하지 않음. 시행 예정 개정이 있는 문서는 위 '기준일 뒤에 시행되는 버전' 참고.");
        l.add("");
        writeText(manifest, String.join("\n", l));
    }

    // ---------------------------------------------------------------------------

    private static final String DESCRIPTION = "law.go.kr 공개 HTML 에서 법규 원문을 받아 data/legal_sources/ 에 SourceBundle(JSON)로 저장한다.";

    /** 명령줄 실행 (저장 폴더는 {@code --sources-dir}, 기본 ./data/legal_sources). */
    public static int run(String[] args) {
        return run(args, null, null, LawGoHtml.DEFAULT_MIN_INTERVAL, System.out, System.err);
    }

    /** 저장 폴더(data/legal_sources)를 지정해 실행. */
    public static int run(String[] args, Path sourcesDir) {
        return run(args, sourcesDir, null, LawGoHtml.DEFAULT_MIN_INTERVAL, System.out, System.err);
    }

    /**
     * 시험·재현용: 전송과 요청 간격을 지정해 실행.
     *
     * @param sourcesDir  null 이면 {@code --sources-dir} 또는 ./data/legal_sources
     * @param transport   null 이면 실제 전송 (이때 minInterval 은 1.0 이상이어야 한다)
     * @param minInterval 요청 사이 최소 간격(초)
     */
    public static int run(String[] args, Path sourcesDir, HttpTransport transport, double minInterval, PrintStream out,
                          PrintStream err) {
        ArgParser ap = new ArgParser("FetchLawgoSources", DESCRIPTION)
                .add("--as-of", 1, "AS_OF", "검토 기준일 YYYY-MM-DD (기본: 오늘 KST)", LocalDate.now(LegalPy.KST).toString())
                .add("--only", -1, "ONLY", "이 key 만 수집 (MANIFEST 는 이번 실행분으로 다시 씀)", null)
                .add("--skip-region", 0, null, "법정동코드 확인 생략", Boolean.FALSE)
                .add("--sources-dir", 1, "SOURCES_DIR", "저장 폴더 (자바판 추가, 기본 data/legal_sources)", null);
        Map<String, Object> a;
        try {
            a = ap.parse(args, out, err);
        } catch (ArgParser.Exit e) {
            return e.code;
        }
        Path dir = sourcesDir;
        if (dir == null) {
            dir = a.get("--sources-dir") != null ? Path.of((String) a.get("--sources-dir")) : Path.of("data", "legal_sources");
        }
        String asOf = (String) a.get("--as-of");
        @SuppressWarnings("unchecked")
        List<String> only = (List<String>) a.get("--only");
        boolean skipRegion = (Boolean) a.get("--skip-region");
        return new FetchLawgoSources(dir, transport, minInterval, out, err).main(asOf, only, skipRegion);
    }

    private int main(String asOf, List<String> only, boolean skipRegion) {
        try {
            Files.createDirectories(outDir);
            Files.createDirectories(rawDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String started = LegalPy.nowKstIso();
        List<Target> targets = TARGETS.stream().filter(t -> only == null || only.isEmpty() || only.contains(t.key())).toList();
        List<TargetResult> results = new ArrayList<>();
        Map<String, String> lsidToKey = new LinkedHashMap<>();
        try (LawGoHtmlClient client = client(LawGoHtml.BASE_URL)) {
            for (Target t : targets) {
                out.println("[" + t.key() + "] " + t.name());
                out.flush();
                TargetResult r;
                try {
                    r = collectTarget(client, t, asOf, lsidToKey);
                } catch (RuntimeException e) {
                    e.printStackTrace(err);
                    r = new TargetResult(t);
                    r.error = LegalPy.exceptionRepr(e);
                }
                for (VersionResult v : r.versions) {
                    if (v.bundle() != null) {
                        out.println("   -> " + v.bundleFile() + ": " + v.bundle().articles().size() + " items, missing " + v.missing().size());
                        out.flush();
                    }
                }
                results.add(r);
            }
        }
        List<RegionCheck> regions = skipRegion ? List.of() : verifyRegionCodes();
        writeManifest(results, regions, asOf, started);
        out.println("MANIFEST: " + manifest);
        return results.stream().anyMatch(r -> r.error != null) ? 1 : 0;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
