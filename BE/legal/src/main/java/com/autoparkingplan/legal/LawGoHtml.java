package com.autoparkingplan.legal;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * 국가법령정보센터(www.law.go.kr) 공개 HTML 페이지용 개발 수집기 — 파이썬 {@code app/legal/lawgo_html.py}.
 * HTTP 클라이언트는 {@link LawGoHtmlClient}.
 *
 * <p>DRF Open API(인증값 OC 필요)를 쓰기 전까지 개발 데이터를 만들기 위한 수집기다.
 * 페이지는 받은 그대로(raw bytes) 보관하고, 결정적 파서(bs4 + 정규식 → 자바는 jsoup + 정규식, {@link Bs4})로
 * 버전 표제와 선택한 조문·별표만 뽑아 SourceBundle 을 만든다. AI 는 쓰지 않는다.
 *
 * <p>law.go.kr 화면 구성 (2026-11 확인)
 * <ul>
 *   <li>법령 이름 주소   : /법령/&lt;법령명&gt;, /자치법규/&lt;자치법규명&gt;  → iframe src 에 현행 lsiSeq·efYd / ordinSeq</li>
 *   <li>법령 본문        : POST /LSW/lsInfoR.do   (lsiSeq, efYd, chrClsCd=010202, urlMode=lsInfoP, ancYnChk=0)</li>
 *   <li>자치법규 본문    : GET  /LSW/ordinInfoR.do (ordinSeq, ordinId, chrClsCd=010202, gubun=ELIS)</li>
 *   <li>법령 연혁 목록   : GET  /LSW/lsHstListR.do?lsId=...</li>
 *   <li>자치법규 연혁    : GET  /LSW/ordinHstListR.do?ordinId=...</li>
 *   <li>법령 별표 내용   : GET  /LSW/lsBylContentsInfoR.do?bylSeq=...&amp;chrClsCd=010202</li>
 *   <li>자치법규 별표    : GET  /LSW/ordinBylContentsInfoR.do?bylSeq=...&amp;gubun=ELIS</li>
 *   <li>사람이 보는 주소 : /LSW/lsInfoP.do?lsiSeq=..&amp;efYd=..  /  /LSW/ordinInfoP.do?ordinSeq=..</li>
 * </ul>
 *
 * <p>본문 HTML 구조: 조마다 &lt;a name="J{조}:{가지}"&gt; 다음에 &lt;div class="pgroup"&gt; … &lt;div class="lawcon"&gt;
 * 안에 항·호·목이 &lt;p&gt; 한 줄씩 들어 있다. 조 제목은 &lt;span class="bl"&gt;&lt;label&gt;제77조(용도지역의 건폐율)&lt;/label&gt;.
 *
 * <p>텍스트 정규화 (원문 대비 바꾸는 것은 이것뿐)
 * <ul>
 *   <li>HTML 표시 규칙에 따른 공백 처리: 태그 사이 줄바꿈·들여쓰기 등 연속 공백은 한 칸, 문단(&lt;p&gt;) 앞뒤 공백 제거</li>
 *   <li>&lt;p&gt; 하나 = 한 줄, &lt;br&gt; = 줄바꿈, 빈 문단은 버림</li>
 *   <li>\r\n → \n, NBSP → 공백, 줄 끝 공백 제거, 3줄 이상 연속 빈 줄 → 빈 줄 1줄</li>
 *   <li>〈개정 …〉/&lt;개정 …&gt;, [전문개정 …] 같은 연혁 주석은 화면에 보이는 그대로 남긴다.</li>
 * </ul>
 */
public final class LawGoHtml {
    private LawGoHtml() {
    }

    public static final String BASE_URL = "https://www.law.go.kr";
    public static final String USER_AGENT = "APP-legal-collector/0.1 (dev)";
    /** 한국 표준시. */
    public static final java.time.ZoneOffset KST = LegalPy.KST;
    /** 요청 사이 최소 간격(초). law.go.kr 에 부담을 주지 않기 위해 1초 이상. */
    public static final double DEFAULT_MIN_INTERVAL = 1.2;
    public static final String BUNDLE_NOTE = "선별 조문만 수집 (개발용 HTML 수집기)";
    /** 한글 본문. */
    public static final String KO_CHR_CLS = "010202";

    /** DocKind 값. */
    public static final String LAW = "law";
    public static final String ORDIN = "ordin";

    // ---------------------------------------------------------------------------
    // 텍스트 정규화
    // ---------------------------------------------------------------------------

    /** &lt;br&gt; 자리 표시 (사용자 영역 문자 U+E000, 원문에 나오지 않음). */
    static final String BR_MARK = "\uE000";
    /** HTML 이 한 칸으로 줄이는 공백 (NBSP 제외). */
    static final Pattern HTML_WS_RE = Pattern.compile("[ \\t\\n\\r\\f]+");
    private static final String HTML_WS_CHARS = " \t\n\r\f";
    private static final Pattern NL4_RE = Pattern.compile("\\n{4,}");
    private static final Pattern FLSEQ_RE = LegalPy.re("flSeq=(\\d+)");
    private static final Pattern DISPLAY_NONE_RE = LegalPy.re("display\\s*:\\s*none", Pattern.CASE_INSENSITIVE);
    private static final Set<String> HIDDEN_TAGS = Set.of("script", "style", "input", "textarea", "select", "button", "noscript");

    static String htmlWs(String s) {
        return HTML_WS_RE.matcher(s).replaceAll(" ");
    }

    /** 번들 text 에 허용된 정규화만 적용한다. */
    public static String normalizeText(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replace("\u00a0", " ");
        List<String> lines = new ArrayList<>();
        for (String line : LegalPy.split(text, "\n")) {
            lines.add(Py.rstrip(line));
        }
        text = String.join("\n", lines);
        // 빈 줄 3줄 이상(= 줄바꿈 4개 이상) → 빈 줄 1줄
        text = NL4_RE.matcher(text).replaceAll("\n\n");
        return Py.strip(text, "\n");
    }

    /** 본문 안 내용 이미지(표·그림) 자리에 넣는 표시 줄. 이미지 내용(대체텍스트 포함)은 text 에 넣지 않는다. */
    public static String imageMarker(Element img) {
        String src = LegalPy.or(Bs4.get(img, "src"), "");
        Matcher m = FLSEQ_RE.matcher(src);
        return m.find() ? "[[IMAGE flSeq=" + m.group(1) + "]]" : "[[IMAGE src=" + src + "]]";
    }

    public static final Pattern IMAGE_MARKER_RE = LegalPy.re("^\\[\\[IMAGE (?:flSeq=\\d+|src=[^\\]]*)\\]\\]$");

    private static boolean isImageMarker(String s) {
        return IMAGE_MARKER_RE.matcher(s).lookingAt();
    }

    /** 화면에서 지우는 요소인가 (_visible_copy 의 decompose 대상: 숨은 태그, display:none). */
    private static boolean removedInVisibleCopy(Element e) {
        if (HIDDEN_TAGS.contains(e.normalName())) {
            return true;
        }
        String style = Bs4.get(e, "style");
        return style != null && DISPLAY_NONE_RE.matcher(style).find();
    }

    /**
     * {@code _visible_copy(el, mark_images=…).get_text()} — 화면에 보이지 않는 요소를 지운 사본의 텍스트.
     * 내용 이미지는 독립된 줄의 [[IMAGE …]] 표시로, &lt;br&gt; 은 BR_MARK 로 바꾼다. 원본 트리는 건드리지 않는다.
     *
     * @param exclude 사본에서 함께 지울 요소 (없으면 null)
     */
    static String visibleText(Element el, boolean markImages, Element exclude) {
        StringBuilder sb = new StringBuilder();
        visibleWalk(el, el, markImages, exclude, sb);
        return sb.toString();
    }

    private static void visibleWalk(Element root, Element node, boolean markImages, Element exclude, StringBuilder sb) {
        for (Node c : node.childNodes()) {
            if (c instanceof Comment) {
                continue;
            }
            String s = Bs4.stringOf(c);
            if (s != null) {
                if (Bs4.interesting(c, root)) {
                    sb.append(s);
                }
                continue;
            }
            if (!(c instanceof Element e) || e == exclude || removedInVisibleCopy(e)) {
                continue;
            }
            if (e.normalName().equals("img")) {
                if (markImages && !isUiImage(e)) {
                    sb.append(BR_MARK).append(imageMarker(e)).append(BR_MARK);
                }
            } else if (e.normalName().equals("br")) {
                sb.append(BR_MARK);
            } else {
                visibleWalk(root, e, markImages, exclude, sb);
            }
        }
    }

    /** {@code _visible_copy(el).find(...)}: 사본에 남는 첫 하위 요소. */
    static Element visibleFind(Element node, Predicate<Element> p) {
        for (Element c : node.children()) {
            if (removedInVisibleCopy(c) || c.normalName().equals("img") || c.normalName().equals("br")) {
                continue;
            }
            if (p.test(c)) {
                return c;
            }
            Element found = visibleFind(c, p);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 한 요소(&lt;p&gt; 등)의 표시 텍스트를 줄 목록으로. HTML 공백 규칙 적용. */
    static List<String> elementLines(Element el, boolean markImages) {
        String raw = visibleText(el, markImages, null);
        List<String> out = new ArrayList<>();
        for (String part : LegalPy.split(raw, BR_MARK)) {
            out.add(Py.strip(htmlWs(part), HTML_WS_CHARS));
        }
        // 이미지 표시 줄 앞뒤에 생긴 빈 조각은 버린다 (원문의 빈 줄이 아님)
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            String x = out.get(i);
            boolean nearImage = (i > 0 && isImageMarker(out.get(i - 1))) || (i + 1 < out.size() && isImageMarker(out.get(i + 1)));
            if (!Py.strip(x).isEmpty() || !nearImage) {
                kept.add(x);
            }
        }
        // 문단 끝/처음의 빈 조각은 버린다 (<br> 로 끝나는 문단 등)
        while (!kept.isEmpty() && Py.strip(kept.getLast()).isEmpty()) {
            kept.removeLast();
        }
        while (!kept.isEmpty() && Py.strip(kept.getFirst()).isEmpty()) {
            kept.removeFirst();
        }
        return kept;
    }

    static boolean isUiImage(Element img) {
        String src = LegalPy.or(Bs4.get(img, "src"), "").toLowerCase(java.util.Locale.ROOT);
        String alt = LegalPy.or(Bs4.get(img, "alt"), "");
        return src.contains("/images/button/")
                || src.contains("images/button/")
                || src.contains("images/common/btn_")
                || alt.contains("버튼")
                || Set.of("연혁", "위임행정규칙", "펼침", "접기", "별표").contains(alt);
    }

    // ---------------------------------------------------------------------------
    // 라벨 / 날짜
    // ---------------------------------------------------------------------------

    private static final Pattern ART_LABEL_RE = LegalPy.re("^(제\\d+조(?:의\\d+)?)(?:\\s*\\((.*)\\))?\\s*$", Pattern.DOTALL);
    private static final Pattern ANNEX_LABEL_RE = LegalPy.re("^별표\\s*(\\d+)(?:\\s*의\\s*(\\d+))?$");
    private static final Pattern ART_PATH_LABEL_RE = LegalPy.re("제(\\d+)조(?:의(\\d+))?");

    /** '제84조'→'art84', '제84조의2'→'art84-2', '별표 5'→'annex5', '별표 5의2'→'annex5-2'. */
    public static String labelToPath(String label) {
        String s = Py.strip(label);
        Matcher m = ART_PATH_LABEL_RE.matcher(s);
        if (m.matches()) {
            return "art" + LegalPy.pyInt(m.group(1)) + (m.group(2) != null ? "-" + LegalPy.pyInt(m.group(2)) : "");
        }
        m = ANNEX_LABEL_RE.matcher(s);
        if (m.matches()) {
            return "annex" + LegalPy.pyInt(m.group(1)) + (m.group(2) != null ? "-" + LegalPy.pyInt(m.group(2)) : "");
        }
        throw new IllegalArgumentException("unknown label: " + LegalPy.reprString(label));
    }

    private static final Pattern KDATE_RE = LegalPy.re("(\\d{4})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.?");

    /**
     * '2026. 7. 1.' / '2026.7.1' → '2026-07-01'.
     *
     * @throws IllegalArgumentException 없는 날짜 (파이썬 {@code dt.date()} 의 ValueError)
     */
    public static String parseKdate(String s) {
        Matcher m = KDATE_RE.matcher(s);
        if (!m.find()) {
            return null;
        }
        int y = LegalPy.pyInt(m.group(1)).intValue();
        int mo = LegalPy.pyInt(m.group(2)).intValue();
        int d = LegalPy.pyInt(m.group(3)).intValue();
        if (y < 1) {
            throw new IllegalArgumentException("year " + y + " is out of range");
        }
        try {
            return LocalDate.of(y, mo, d).toString();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static final Pattern YMD8_RE = LegalPy.re("\\d{8}");

    public static String ymd8ToIso(String s) {
        if (s == null || s.isEmpty() || !YMD8_RE.matcher(s).matches()) {
            return null;
        }
        if (s.equals("99991231")) {
            return null;
        }
        return Py.slice(s, null, 4) + "-" + Py.slice(s, 4, 6) + "-" + Py.slice(s, 6, null);
    }

    // ---------------------------------------------------------------------------
    // 버전 표제
    // ---------------------------------------------------------------------------

    private static final Pattern EF_RE = LegalPy.re("\\[\\s*시행\\s*([0-9.\\s]+?)\\s*\\]");
    private static final Pattern ANC_RE = LegalPy.re(
            "\\[\\s*([^\\[\\],]*?제\\s*[0-9-]+\\s*호)\\s*,\\s*([0-9.\\s]+?)\\s*,\\s*([^\\]]*?)\\s*\\]");

    /**
     * 버전 표제.
     *
     * @param headerLine         화면 표제 원문: '[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]'
     * @param promulgationNumber '법률 제21447호' (표제에 보이는 그대로)
     * @param revisionKind       '일부개정', '타법개정' …
     */
    public record VersionHeader(String officialTitle, String shortTitle, String headerLine, String effectiveFrom,
                                String promulgationNumber, String promulgationDate, String revisionKind) {
        static VersionHeader of(String officialTitle, String shortTitle, HeaderLine h) {
            return new VersionHeader(officialTitle, shortTitle, h.headerLine(), h.effectiveFrom(), h.promulgationNumber(),
                    h.promulgationDate(), h.revisionKind());
        }

        public boolean complete() {
            return LegalPy.truthy(effectiveFrom) && LegalPy.truthy(promulgationNumber) && LegalPy.truthy(promulgationDate);
        }
    }

    /** {@code parse_header_line} 결과 (파이썬 dict: header_line, effective_from, promulgation_number, promulgation_date, revision_kind). */
    public record HeaderLine(String headerLine, String effectiveFrom, String promulgationNumber, String promulgationDate,
                             String revisionKind) {
    }

    public static HeaderLine parseHeaderLine(String line) {
        line = Py.strip(htmlWs(line.replace("\u00a0", " ")));
        Matcher ef = EF_RE.matcher(line);
        boolean hasEf = ef.find();
        Matcher anc = ANC_RE.matcher(line);
        boolean hasAnc = anc.find();
        return new HeaderLine(
                line.isEmpty() ? null : line,
                hasEf ? parseKdate(ef.group(1)) : null,
                hasAnc ? Py.strip(htmlWs(anc.group(1))) : null,
                hasAnc ? parseKdate(anc.group(2)) : null,
                hasAnc && !Py.strip(anc.group(3)).isEmpty() ? Py.strip(anc.group(3)) : null);
    }

    private static final Pattern SHORT_TITLE_RE = LegalPy.re("약칭\\s*:\\s*([^)]+?)\\s*\\)");

    static VersionHeader parseLawHeader(Document soup) {
        Element h2 = Bs4.find(soup, Bs4.tag("h2"));
        String title = null;
        String shortTitle = null;
        if (h2 != null) {
            Element span = visibleFind(h2, Bs4.tag("span"));
            if (span != null) {
                String st = Py.strip(htmlWs(visibleText(span, false, null)));
                Matcher m = SHORT_TITLE_RE.matcher(st);
                shortTitle = m.find() ? Py.strip(m.group(1)) : null;
            }
            String t = Py.strip(htmlWs(visibleText(h2, false, span)));
            title = t.isEmpty() ? null : t;
        }
        Element sub = Bs4.find(soup, Bs4.tag("div").and(Bs4.hasClass("ct_sub")));
        String line = sub != null ? Bs4.getText(sub, " ") : "";
        return VersionHeader.of(title, shortTitle, parseHeaderLine(line));
    }

    static VersionHeader parseOrdinHeader(Document soup) {
        Element h2 = Bs4.find(soup, Bs4.tag("h2"));
        String title = h2 != null ? Py.strip(htmlWs(Bs4.getText(h2))) : null;
        Element sub = Bs4.find(soup, Bs4.tag("div").and(Bs4.hasClass("subtit1")));
        String line = sub != null ? Bs4.getText(sub, " ") : "";
        return VersionHeader.of(LegalPy.truthy(title) ? title : null, null, parseHeaderLine(line));
    }

    static Map<String, String> hiddenInputs(Document soup) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element inp : Bs4.findAll(soup, Bs4.tag("input").and(Bs4.attrEquals("type", "hidden")))) {
            String id = Bs4.get(inp, "id");
            String key = LegalPy.truthy(id) ? id : Bs4.get(inp, "name");
            if (LegalPy.truthy(key) && !out.containsKey(key)) {
                out.put(key, LegalPy.or(Bs4.get(inp, "value"), ""));
            }
        }
        return out;
    }

    private static final Pattern ANC_NO_RE = LegalPy.re("제\\s*([0-9-]+)\\s*호");

    private static String lstripZeros(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    /** 표제와 페이지 숨은 값(ancYd/ancNo/efYd)이 서로 맞는지. 문제 목록을 돌려준다. */
    public static List<String> headerConsistency(VersionHeader header, Map<String, String> hidden) {
        List<String> problems = new ArrayList<>();
        if (!header.complete()) {
            problems.add("표제에서 시행일·공포번호·공포일 중 일부를 읽지 못함");
            return problems;
        }
        String ancYd = ymd8ToIso(hidden.get("ancYd"));
        if (LegalPy.truthy(ancYd) && !ancYd.equals(header.promulgationDate())) {
            problems.add("공포일 불일치: 표제 " + LegalPy.str(header.promulgationDate()) + " / ancYd " + ancYd);
        }
        String efYd = ymd8ToIso(hidden.get("efYd"));
        if (LegalPy.truthy(efYd) && !efYd.equals(header.effectiveFrom())) {
            problems.add("시행일 불일치: 표제 " + LegalPy.str(header.effectiveFrom()) + " / efYd " + efYd);
        }
        String ancNo = hidden.get("ancNo");
        if (LegalPy.truthy(ancNo) && LegalPy.truthy(header.promulgationNumber())) {
            Matcher m = ANC_NO_RE.matcher(header.promulgationNumber());
            if (!m.find() || !lstripZeros(m.group(1)).equals(lstripZeros(ancNo))) {
                problems.add("공포번호 불일치: 표제 " + header.promulgationNumber() + " / ancNo " + ancNo);
            }
        }
        return problems;
    }

    // ---------------------------------------------------------------------------
    // 조문 / 별표 목록
    // ---------------------------------------------------------------------------

    private static final Pattern ART_ANCHOR_RE = LegalPy.re("^J(\\d+):(\\d+)$");

    /**
     * 본문 내용 이미지 (파이썬 dict {'marker','src','alt'}).
     *
     * @param alt 사이트 대체텍스트 원문
     */
    public record ImageRef(String marker, String src, String alt) {
    }

    /**
     * 본문에서 읽은 조 하나.
     *
     * @param label  '제84조', '제84조의2'
     * @param path   'art84', 'art84-2'
     * @param text   조 전체 (조 제목 줄 + 항·호·목), 정규화 후
     * @param anchor 'J84:0'
     * @param images 본문 내용 이미지 (alt 는 사이트 대체텍스트 원문)
     */
    public record ParsedArticle(String label, String path, String heading, String text, String anchor, List<String> warnings,
                                List<ImageRef> images) {
        public ParsedArticle {
            warnings = List.copyOf(warnings);
            images = List.copyOf(images);
        }

        public ArticleText toArticleText() {
            return new ArticleText("article", label, path, heading, text);
        }
    }

    private record ArticleLines(List<String> lines, List<String> warnings, List<ImageRef> images) {
    }

    private static ArticleLines articleLines(Element lawcon) {
        List<String> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ImageRef> images = new ArrayList<>();
        for (Node child : lawcon.childNodes()) {
            if (child instanceof Comment) {
                continue;
            }
            String str = Bs4.stringOf(child);
            if (str != null) {
                String s = Py.strip(htmlWs(str));
                if (!s.isEmpty()) {
                    lines.add(s);
                }
                continue;
            }
            if (!(child instanceof Element el)) {
                continue;
            }
            String name = el.normalName();
            if (name.equals("script") || name.equals("style") || name.equals("input")) {
                continue;
            }
            String style = LegalPy.or(Bs4.get(el, "style"), "");
            if (DISPLAY_NONE_RE.matcher(style).find()) {
                continue;
            }
            if (name.equals("table") || Bs4.find(el, Bs4.tag("table")) != null) {
                warnings.add("조문 안에 표(table)가 있음 — 표 셀은 ' | ' 로 이어 붙임");
                List<Element> tables = name.equals("table") ? List.of(el) : Bs4.findAll(el, Bs4.tag("table"));
                for (Element tbl : tables) {
                    for (List<String> row : tableRows(tbl)) {
                        lines.add(String.join(" | ", row));
                    }
                }
                continue;
            }
            List<Element> imgs = name.equals("img") ? List.of(el) : Bs4.findAll(el, Bs4.tag("img"));
            for (Element img : imgs) {
                if (!isUiImage(img)) {
                    String mk = imageMarker(img);
                    images.add(new ImageRef(mk, LegalPy.or(Bs4.get(img, "src"), ""), LegalPy.or(Bs4.get(img, "alt"), "")));
                    warnings.add("조문 안에 내용 이미지가 있음 (" + mk + ") — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음");
                }
            }
            if (name.equals("img")) {
                if (!isUiImage(el)) {
                    lines.add(imageMarker(el));
                }
                continue;
            }
            for (String ln : elementLines(el, true)) {
                if (!Py.strip(ln).isEmpty()) {
                    lines.add(ln);
                }
            }
        }
        return new ArticleLines(lines, warnings, images);
    }

    static List<ParsedArticle> parseArticles(Document soup) {
        List<ParsedArticle> out = new ArrayList<>();
        for (Element a : Bs4.findAll(soup, Bs4.tag("a").and(Bs4.attrSearch("name", ART_ANCHOR_RE)))) {
            String anchor = a.attr("name");
            Element block = Bs4.findNextSibling(a);
            if (block == null || !Bs4.classes(block).contains("pgroup")) {
                continue;
            }
            Element lawcon = Bs4.find(block, Bs4.tag("div").and(Bs4.hasClass("lawcon")));
            if (lawcon == null) {
                continue;
            }
            Element lab = Bs4.find(lawcon, Bs4.tag("label"));
            if (lab == null) {
                lab = Bs4.find(lawcon, Bs4.tag("span").and(Bs4.hasClass("bl")));
            }
            if (lab == null) {
                continue;
            }
            String labelLine = Py.strip(htmlWs(Bs4.getText(lab)));
            Matcher m = ART_LABEL_RE.matcher(labelLine);
            if (!m.lookingAt()) {
                continue;
            }
            String label = m.group(1);
            String heading = m.group(2) != null ? Py.strip(m.group(2)) : null;
            String path = labelToPath(label);
            Matcher am = ART_ANCHOR_RE.matcher(anchor);
            if (!am.lookingAt()) {
                throw new IllegalStateException("anchor");
            }
            BigInteger branch = LegalPy.pyInt(am.group(2));
            String expect = "art" + LegalPy.pyInt(am.group(1)) + (branch.signum() != 0 ? "-" + branch : "");
            ArticleLines al = articleLines(lawcon);
            List<String> warnings = new ArrayList<>(al.warnings());
            if (!expect.equals(path)) {
                warnings.add("앵커 " + anchor + " 와 라벨 " + label + " 의 조번호가 다름");
            }
            out.add(new ParsedArticle(label, path, LegalPy.truthy(heading) ? heading : null,
                    normalizeText(String.join("\n", al.lines())), anchor, warnings, al.images()));
        }
        return out;
    }

    /**
     * 본문 하단 별표·서식 목록의 한 항목 (제목과 첨부 파일 링크).
     *
     * @param titleLine 화면에 보이는 그대로: '[별표 5] 제2종일반주거지역안에서 …(제71조제1항제4호관련)'
     * @param label     '별표 5' (별표가 아니면 null)
     * @param path      'annex5'
     * @param fileLinks flDownload.do?… (상대경로 그대로)
     */
    public record AnnexRef(String bylSeq, String titleLine, String label, String path, String heading, List<String> fileLinks) {
        public AnnexRef {
            fileLinks = List.copyOf(fileLinks);
        }
    }

    private static final Pattern LAW_ANNEX_TITLE_RE = LegalPy.re("^\\[\\s*(별표\\s*\\d+(?:\\s*의\\s*\\d+)?)\\s*\\]\\s*(.*)$");
    private static final Pattern ORDIN_ANNEX_TITLE_RE = LegalPy.re("^(별표)\\s*_?\\s*(\\d+(?:\\s*의\\s*\\d+)?)\\s*_\\s*(.*)$");
    private static final Pattern WS_RUN_RE = LegalPy.re("\\s+");
    private static final Pattern ANNEX_PREFIX_RE = LegalPy.re("^별표\\s*");

    /** (label, heading) — 별표 제목 줄이 아니면 둘 다 null. */
    static String[] parseAnnexTitle(String title) {
        Matcher m = LAW_ANNEX_TITLE_RE.matcher(title);
        if (m.lookingAt()) {
            String label = WS_RUN_RE.matcher(m.group(1)).replaceAll(" ").replace(" 의 ", "의").replace(" 의", "의");
            label = ANNEX_PREFIX_RE.matcher(label).replaceAll("별표 ");
            String h = Py.strip(m.group(2));
            return new String[] {label, h.isEmpty() ? null : h};
        }
        m = ORDIN_ANNEX_TITLE_RE.matcher(title);
        if (m.lookingAt()) {
            String num = WS_RUN_RE.matcher(m.group(2)).replaceAll("");
            String h = Py.strip(m.group(3));
            return new String[] {"별표 " + num, h.isEmpty() ? null : h};
        }
        return new String[] {null, null};
    }

    private static final Pattern BYL_ONCLICK_RE = LegalPy.re("bylInfoDiv\\('\\d+'\\)");
    private static final Pattern BYL_SEQ_RE = LegalPy.re("bylInfoDiv\\('(\\d+)'\\)");
    private static final Pattern FLDOWNLOAD_RE = LegalPy.re("flDownload\\.do");

    static List<AnnexRef> parseAnnexRefs(Document soup) {
        Map<String, AnnexRef> refs = new LinkedHashMap<>();
        for (Element a : Bs4.findAll(soup, Bs4.tag("a").and(Bs4.attrSearch("onclick", BYL_ONCLICK_RE)))) {
            Matcher sm = BYL_SEQ_RE.matcher(a.attr("onclick"));
            if (!sm.find()) {
                throw new IllegalStateException("bylInfoDiv");
            }
            String seq = sm.group(1);
            String title = Py.strip(htmlWs(visibleText(a, false, null)));
            if (title.isEmpty()) {
                continue;
            }
            if (refs.containsKey(seq)) {
                continue;
            }
            String[] lh = parseAnnexTitle(title);
            List<String> links = new ArrayList<>();
            Element container = Bs4.findParent(a, "li");
            if (container != null) {
                for (Element fl : Bs4.findAll(container, Bs4.tag("a").and(Bs4.attrSearch("href", FLDOWNLOAD_RE)))) {
                    String href = fl.attr("href");
                    if (!links.contains(href)) {
                        links.add(href);
                    }
                }
            }
            refs.put(seq, new AnnexRef(seq, title, lh[0], LegalPy.truthy(lh[0]) ? labelToPath(lh[0]) : null, lh[1], links));
        }
        return new ArrayList<>(refs.values());
    }

    /** lsInfoR.do / ordinInfoR.do 응답을 파싱한 결과. kind = "law" | "ordin". */
    public record ParsedBody(String kind, VersionHeader header, Map<String, String> hidden, List<ParsedArticle> articles,
                             List<AnnexRef> annexes) {
        public ParsedBody {
            hidden = Collections.unmodifiableMap(new LinkedHashMap<>(hidden));
            articles = List.copyOf(articles);
            annexes = List.copyOf(annexes);
        }

        /** 라벨이 같은 첫 조, 없으면 null. */
        public ParsedArticle article(String label) {
            return articles.stream().filter(a -> a.label().equals(label)).findFirst().orElse(null);
        }

        /** 라벨이 같은 첫 별표, 없으면 null. */
        public AnnexRef annex(String label) {
            return annexes.stream().filter(a -> label.equals(a.label())).findFirst().orElse(null);
        }

        public String externalId() {
            return LAW.equals(kind) ? hidden.get("lsId") : hidden.get("ordinId");
        }

        public String seq() {
            return LAW.equals(kind) ? hidden.get("lsiSeq") : hidden.get("ordinSeq");
        }
    }

    /** lsInfoR.do / ordinInfoR.do 응답 HTML 을 파싱한다. */
    public static ParsedBody parseBody(String html, String kind) {
        Document soup = Bs4.parse(html);
        VersionHeader header = LAW.equals(kind) ? parseLawHeader(soup) : parseOrdinHeader(soup);
        return new ParsedBody(kind, header, hiddenInputs(soup), parseArticles(soup), parseAnnexRefs(soup));
    }

    // ---------------------------------------------------------------------------
    // 별표 내용
    // ---------------------------------------------------------------------------

    public static List<List<String>> tableRows(Element tbl) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : Bs4.findAll(tbl, Bs4.tag("tr"))) {
            if (Bs4.findParent(tr, "table") != tbl) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : Bs4.findAll(tr, Bs4.tag("td", "th"))) {
                if (Bs4.findParent(cell, "tr") != tr) {
                    continue;
                }
                cells.add(Py.strip(normalizeText(String.join("\n", elementLines(cell, true)))));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /**
     * 별표 내용 페이지.
     *
     * @param hasText       law.go.kr 이 HTML 텍스트(표)로 제공하는지
     * @param viewerIframe  문서 뷰어(이미지 변환) 주소
     * @param contentImages 별표 본문 이미지(flDownload.do?flSeq=…)
     * @param fileSeqs      {'hanFlSeq': …, 'pdfFlSeq': …}
     */
    public record AnnexContent(String bylSeq, boolean hasText, String text, List<List<String>> tableJson, String viewerIframe,
                               List<String> contentImages, Map<String, String> fileSeqs) {
        public AnnexContent {
            contentImages = List.copyOf(contentImages);
            fileSeqs = Collections.unmodifiableMap(new LinkedHashMap<>(fileSeqs));
        }

        public String availability() {
            if (hasText) {
                return "html_text";
            }
            List<String> kinds = new ArrayList<>();
            if (LegalPy.truthy(fileSeqs.get("hanFlSeq")) && !"0".equals(fileSeqs.get("hanFlSeq"))) {
                kinds.add("HWP");
            }
            if (LegalPy.truthy(fileSeqs.get("pdfFlSeq")) && !"0".equals(fileSeqs.get("pdfFlSeq"))) {
                kinds.add("PDF");
            }
            if (!contentImages.isEmpty()) {
                kinds.add("이미지");
            }
            if (LegalPy.truthy(viewerIframe)) {
                kinds.add("문서뷰어(이미지 변환)");
            }
            return kinds.isEmpty() ? "unknown" : "attachment_only:" + String.join("/", kinds);
        }

        /** table_json 을 JSON 으로 ([[셀,...],...]), 없으면 null. */
        public JsonNode tableJsonNode() {
            if (tableJson == null) {
                return null;
            }
            ArrayNode arr = Json.array();
            for (List<String> row : tableJson) {
                ArrayNode r = arr.addArray();
                row.forEach(r::add);
            }
            return arr;
        }
    }

    /** lsBylContentsInfoR.do / ordinBylContentsInfoR.do 응답. */
    public static AnnexContent parseAnnexContents(String html) {
        Document soup = Bs4.parse(html);
        Map<String, String> hidden = hiddenInputs(soup);
        Element iframe = Bs4.find(soup, Bs4.tag("iframe"));
        List<String> imgs = new ArrayList<>();
        for (Element i : Bs4.findAll(soup, Bs4.tag("img"))) {
            if (LegalPy.or(Bs4.get(i, "alt"), "").contains("본문이미지")) {
                imgs.add(Bs4.get(i, "src"));
            }
        }
        List<Element> tables = Bs4.findAll(soup, Bs4.tag("table")).stream().filter(t -> Bs4.findParent(t, "table") == null).toList();
        List<List<String>> tableJson = null;
        String text = null;
        if (!tables.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (Element t : tables) {
                rows.addAll(tableRows(t));
            }
            if (rows.stream().anyMatch(r -> r.stream().anyMatch(c -> !Py.strip(c).isEmpty()))) {
                tableJson = rows;
                List<String> joined = new ArrayList<>();
                for (List<String> r : rows) {
                    joined.add(String.join("\t", r));
                }
                text = normalizeText(String.join("\n", joined));
            }
        }
        Map<String, String> fileSeqs = new LinkedHashMap<>();
        hidden.forEach((k, v) -> {
            if (k.endsWith("FlSeq")) {
                fileSeqs.put(k, v);
            }
        });
        return new AnnexContent(null, text != null, text, tableJson, iframe != null ? Bs4.get(iframe, "src") : null,
                imgs.stream().filter(LegalPy::truthy).toList(), fileSeqs);
    }

    // ---------------------------------------------------------------------------
    // 연혁 / 이름 주소
    // ---------------------------------------------------------------------------

    /**
     * 연혁 목록의 한 줄.
     *
     * @param seq                lsiSeq / ordinSeq (법령은 같은 lsiSeq 가 시행일별로 여러 번 나올 수 있음)
     * @param text               목록에 보이는 그대로 (NBSP 는 공백으로)
     * @param promulgationNumber 목록 표기 그대로 ('조례 제6551호' 처럼 본문 표제와 다를 수 있음)
     * @param siteCurrentFlag    사이트가 표시한 현행 여부 (자치법규는 '최신 공포본'이라 시행 예정일 수 있음). 없으면 null
     */
    public record HistoryEntry(String seq, String text, String effectiveFrom, String promulgationDate, String promulgationNumber,
                               String revisionKind, Boolean siteCurrentFlag) {
    }

    private static final Pattern LAW_HST_RE = LegalPy.re(
            "lsViewLsHst2\\(\\s*'(\\d+)'\\s*,\\s*'(\\d*)'\\s*,\\s*'(\\d*)'\\s*,\\s*'(\\d*)'\\s*,\\s*'([YN]?)'");
    private static final Pattern ORDIN_HST_RE = LegalPy.re("ordinViewOrdinHst\\(\\s*'(\\d+)'\\s*,\\s*'([YN]?)'\\s*\\)");

    public static List<HistoryEntry> parseHistory(String html, String kind) {
        Document soup = Bs4.parse(html);
        List<HistoryEntry> out = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        boolean law = LAW.equals(kind);
        Pattern pat = law ? LAW_HST_RE : ORDIN_HST_RE;
        for (Element a : Bs4.findAll(soup, Bs4.tag("a").and(Bs4.attrSearch("onclick", pat)))) {
            Matcher m = pat.matcher(a.attr("onclick"));
            if (!m.find()) {
                throw new IllegalStateException("onclick");
            }
            String seq = m.group(1);
            String text = Py.strip(htmlWs(Bs4.getText(a).replace("\u00a0", " ")));
            HeaderLine h = parseHeaderLine(text);
            // 법령은 한 공포본(lsiSeq)이 시행일별로 여러 줄 나올 수 있다(부분 시행). (일련번호, 시행일)로 구분한다.
            String ef = law ? ymd8ToIso(m.group(4)) : h.effectiveFrom();
            String eff = LegalPy.truthy(ef) ? ef : h.effectiveFrom();
            List<String> ident = java.util.Arrays.asList(seq, eff);
            if (seen.contains(ident)) {
                continue;
            }
            seen.add(ident);
            String flag = law ? m.group(5) : m.group(2);
            out.add(new HistoryEntry(seq, text, eff, h.promulgationDate(), h.promulgationNumber(), h.revisionKind(),
                    LegalPy.truthy(flag) ? (Boolean) flag.equals("Y") : null));
        }
        return out;
    }

    /** 기준일(as_of, 'YYYY-MM-DD')에 시행 중인 버전 = 시행일 ≤ 기준일 중 시행일이 가장 늦은 것. 없으면 null. */
    public static HistoryEntry selectInForce(Iterable<HistoryEntry> entries, String asOf) {
        HistoryEntry best = null;
        for (HistoryEntry e : entries) {
            if (!LegalPy.truthy(e.effectiveFrom()) || PyJson.compareCodePoints(e.effectiveFrom(), asOf) > 0) {
                continue;
            }
            // 같은 시행일이 여럿이면 사이트 현행 표시 → 공포일 → 일련번호 순으로 늦은 것 (max 는 처음 나온 최댓값)
            if (best == null || IN_FORCE_ORDER.compare(e, best) > 0) {
                best = e;
            }
        }
        return best;
    }

    private static final Comparator<HistoryEntry> IN_FORCE_ORDER = (a, b) -> {
        int c = PyJson.compareCodePoints(a.effectiveFrom(), b.effectiveFrom());
        if (c != 0) {
            return c;
        }
        c = Boolean.compare(Boolean.TRUE.equals(a.siteCurrentFlag()), Boolean.TRUE.equals(b.siteCurrentFlag()));
        if (c != 0) {
            return c;
        }
        c = PyJson.compareCodePoints(LegalPy.or(a.promulgationDate(), ""), LegalPy.or(b.promulgationDate(), ""));
        if (c != 0) {
            return c;
        }
        return LegalPy.pyInt(a.seq()).compareTo(LegalPy.pyInt(b.seq()));
    };

    /** 기준일 뒤에 시행되는 버전(시행 예정). 시행일·일련번호 순. */
    public static List<HistoryEntry> pendingAfter(Iterable<HistoryEntry> entries, String asOf) {
        List<HistoryEntry> out = new ArrayList<>();
        for (HistoryEntry e : entries) {
            if (LegalPy.truthy(e.effectiveFrom()) && PyJson.compareCodePoints(e.effectiveFrom(), asOf) > 0) {
                out.add(e);
            }
        }
        out.sort((a, b) -> {
            int c = PyJson.compareCodePoints(a.effectiveFrom(), b.effectiveFrom());
            return c != 0 ? c : LegalPy.pyInt(a.seq()).compareTo(LegalPy.pyInt(b.seq()));
        });
        return out;
    }

    private static final Pattern RESOLVER_IFRAME_RE = LegalPy.re("(lsInfoP|ordinInfoP)\\.do");

    /** /법령/&lt;이름&gt;, /자치법규/&lt;이름&gt; 응답의 iframe src 질의값. */
    public static Map<String, String> parseNameResolver(String html) {
        Document soup = Bs4.parse(html);
        Element iframe = Bs4.find(soup, Bs4.tag("iframe").and(Bs4.attrSearch("src", RESOLVER_IFRAME_RE)));
        Map<String, String> out = new LinkedHashMap<>();
        if (iframe == null) {
            return out;
        }
        LegalPy.parseQs(LegalPy.urlsplitQuery(iframe.attr("src"))).forEach((k, v) -> {
            if (!v.isEmpty()) {
                out.put(k, v.getFirst());
            }
        });
        return out;
    }

    // ---------------------------------------------------------------------------
    // 약칭 근거 (조례 본문의 '법', '영', '규칙')
    // ---------------------------------------------------------------------------

    private static final Pattern ALIAS_DEF_RE = LegalPy.re(
            "「([^」]+)」\\s*\\(\\s*이하\\s*[“\"]([^”\"]+)[”\"]\\s*(?:이)?라\\s*한다\\s*\\)");

    /** 약칭 정의 하나 (파이썬 tuple (alias, title, label)). */
    public record AliasDefinition(String alias, String title, String label) {
    }

    /** '「국토의 계획 및 이용에 관한 법률」(이하 “법”이라 한다)' → [('법', '국토의 계획 및 이용에 관한 법률', '제2조')]. */
    public static List<AliasDefinition> extractAliasDefinitions(Iterable<ParsedArticle> articles) {
        List<AliasDefinition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ParsedArticle art : articles) {
            Matcher m = ALIAS_DEF_RE.matcher(art.text());
            while (m.find()) {
                String title = Py.strip(m.group(1));
                String alias = Py.strip(m.group(2));
                if (seen.contains(alias)) {
                    continue;
                }
                seen.add(alias);
                out.add(new AliasDefinition(alias, title, art.label()));
            }
        }
        return out;
    }

    private static final Pattern ORDIN_LAW_POP_RE = LegalPy.re("fncOrdinLawPop\\(");
    private static final Pattern ORDIN_LAW_POP_ARGS_RE = LegalPy.re("fncOrdinLawPop\\('(\\d+)'\\s*,\\s*'(\\d+)'");

    /** {@code extract_alias_links(html)} (약칭 '법', '영', '규칙'). */
    public static Map<String, List<String>> extractAliasLinks(String html) {
        return extractAliasLinks(html, List.of("법", "영", "규칙"));
    }

    /** 자치법규 본문의 약칭 링크가 가리키는 법령ID(lsId). fncOrdinLawPop('&lt;lsId&gt;','010101','012601',…). */
    public static Map<String, List<String>> extractAliasLinks(String html, Iterable<String> aliases) {
        Set<String> wanted = new HashSet<>();
        aliases.forEach(wanted::add);
        Document soup = Bs4.parse(html);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Element a : Bs4.findAll(soup, Bs4.tag("a").and(Bs4.attrSearch("onclick", ORDIN_LAW_POP_RE)))) {
            String txt = Py.strip(htmlWs(Bs4.getText(a)));
            if (!wanted.contains(txt)) {
                continue;
            }
            Matcher m = ORDIN_LAW_POP_ARGS_RE.matcher(a.attr("onclick"));
            if (!m.find() || !m.group(2).equals("010101")) {
                continue;
            }
            List<String> ids = out.computeIfAbsent(txt, k -> new ArrayList<>());
            if (!ids.contains(m.group(1))) {
                ids.add(m.group(1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------
    // HTTP (클라이언트는 LawGoHtmlClient)
    // ---------------------------------------------------------------------------

    public static String nowKstIso() {
        return LegalPy.nowKstIso();
    }

    /**
     * 받은 페이지.
     *
     * @param url     요청 주소 (GET 은 질의 포함, POST 는 본문을 질의 형태로 덧붙인 기록용 주소)
     * @param content 받은 그대로
     */
    public record FetchedPage(String url, String method, Map<String, String> params, int status, byte[] content,
                              String fetchedAt) {
        public FetchedPage {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        /** {@code content.decode("utf-8")} (잘못된 UTF-8 이면 IllegalArgumentException — 파이썬 UnicodeDecodeError). */
        public String text() {
            return decodeUtf8Strict(content);
        }
    }

    /** 엄격한 UTF-8 해석 (파이썬 {@code bytes.decode("utf-8")}). */
    public static String decodeUtf8Strict(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("'utf-8' codec can't decode bytes: " + e.getMessage(), e);
        }
    }

    /** 사람이 브라우저로 여는 버전 고정 주소. */
    public static String humanUrl(String kind, String seq, String efYd) {
        Map<String, String> q = new LinkedHashMap<>();
        if (LAW.equals(kind)) {
            q.put("lsiSeq", seq);
            q.put("chrClsCd", KO_CHR_CLS);
            q.put("urlMode", "lsInfoP");
            if (LegalPy.truthy(efYd)) {
                q.put("efYd", efYd);
            }
            q.put("ancYnChk", "0");
            return BASE_URL + "/LSW/lsInfoP.do?" + LegalPy.urlencode(q);
        }
        q.put("ordinSeq", seq);
        q.put("chrClsCd", KO_CHR_CLS);
        q.put("gubun", "ELIS");
        return BASE_URL + "/LSW/ordinInfoP.do?" + LegalPy.urlencode(q);
    }

    /** 현행본으로 연결되는 법령 이름 주소 (버전 고정 아님). */
    public static String homepageUrl(String kind, String name) {
        String prefix = LAW.equals(kind) ? "법령" : "자치법규";
        return BASE_URL + "/" + prefix + "/" + name.replace(" ", "");
    }

    // ---------------------------------------------------------------------------
    // SourceBundle 만들기
    // ---------------------------------------------------------------------------

    public record Missing(String label, String reason) {
    }

    public record AnnexFetch(AnnexRef ref, AnnexContent content, String rawFile) {
    }

    /** {@code build_bundle} 결과 (bundle, 누락 목록, 경고 목록). */
    public record BuildResult(SourceBundle bundle, List<Missing> missing, List<String> warnings) {
    }

    /** {@code build_bundle(document, body, source_url=…, fetched_at=…, raw_file=…)} — 나머지는 기본값. */
    public static BuildResult buildBundle(DocumentMeta document, ParsedBody body, String sourceUrl, String fetchedAt,
                                          String rawFile) {
        return buildBundle(document, body, sourceUrl, fetchedAt, rawFile, List.of(), List.of(), null, null, null, BUNDLE_NOTE);
    }

    /**
     * 파싱 결과 → SourceBundle. (bundle, 누락 목록, 경고 목록)
     *
     * @param inForceOn       연혁 목록으로 이 버전이 current_checked_on 기준 시행 중임을 확인했으면 그 날짜
     * @param expectHeadings  {'제75조': '건폐율'} 처럼 조 제목에 들어 있어야 할 말 (번호가 바뀌었을 때 잘못 고르지 않도록)
     * @param note            기본 {@link #BUNDLE_NOTE}
     */
    public static BuildResult buildBundle(DocumentMeta document, ParsedBody body, String sourceUrl, String fetchedAt,
                                          String rawFile, List<String> articleLabels, List<AnnexFetch> annexes,
                                          Map<String, String> expectHeadings, String inForceOn, String currentCheckedOn,
                                          String note) {
        List<Missing> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ArticleText> articles = new ArrayList<>();
        Map<String, String> expect = expectHeadings == null ? Map.of() : expectHeadings;

        for (String label : articleLabels == null ? List.<String>of() : articleLabels) {
            ParsedArticle art = body.article(label);
            if (art == null) {
                missing.add(new Missing(label, "본문에서 해당 조를 찾지 못함"));
                continue;
            }
            String want = expect.get(label);
            if (LegalPy.truthy(want) && (art.heading() == null || !art.heading().contains(want))) {
                missing.add(new Missing(label, "조 제목이 예상과 다름 (예상 포함어 " + LegalPy.repr(want) + ", 실제 "
                        + LegalPy.repr(art.heading()) + ") — 번호 재확인 필요"));
                continue;
            }
            for (String w : art.warnings()) {
                warnings.add(label + ": " + w);
            }
            articles.add(art.toArticleText());
        }

        for (AnnexFetch af : annexes == null ? List.<AnnexFetch>of() : annexes) {
            AnnexRef ref = af.ref();
            AnnexContent content = af.content();
            String label = LegalPy.or(ref.label(), ref.titleLine());
            if (!content.hasText()) {
                missing.add(new Missing(label, "별표가 첨부파일로만 제공됨 (" + content.availability() + ") — 전사하지 않음. 제목: "
                        + ref.titleLine()));
                continue;
            }
            articles.add(new ArticleText("annex", LegalPy.or(ref.label(), ref.titleLine()),
                    LegalPy.or(ref.path(), "annex-" + ref.bylSeq()), ref.heading(), LegalPy.or(content.text(), ""),
                    content.tableJsonNode(), null));
        }

        VersionHeader h = body.header();
        List<String> problems = headerConsistency(h, body.hidden());
        String vHeader = h.complete() && problems.isEmpty() ? Parsed.CONFIRMED : Parsed.NEEDS_REVIEW;
        for (String p : problems) {
            warnings.add("표제: " + p);
        }

        String vCurrent = Parsed.NEEDS_REVIEW;
        String checkedOn = null;
        if (LegalPy.truthy(inForceOn) && LegalPy.truthy(h.effectiveFrom())
                && PyJson.compareCodePoints(h.effectiveFrom(), inForceOn) <= 0) {
            vCurrent = Parsed.CONFIRMED;
            checkedOn = LegalPy.or(currentCheckedOn, inForceOn);
        }

        VersionMeta version = new VersionMeta(h.promulgationNumber(), h.promulgationDate(), h.effectiveFrom(), null, body.seq(),
                sourceUrl, fetchedAt, "html", rawFile, Parsed.CONFIRMED, vHeader, Parsed.NOT_CHECKED, vCurrent, checkedOn, note);
        SourceBundle bundle = new SourceBundle(document, version, articles, false);
        return new BuildResult(bundle, missing, warnings);
    }

    public static String bundleFilename(String key, String effectiveFrom) {
        return key + "__" + LegalPy.or(effectiveFrom, "unknown") + ".json";
    }

    public static JsonNode bundleJson(SourceBundle bundle) {
        return bundle.toJson();
    }
}
