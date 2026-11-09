package com.autoparkingplan.legal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;

import com.autoparkingplan.common.Py;

/**
 * 국가법령정보 공동활용 Open API(DRF) XML 파서 — 파이썬 {@code app/legal/lawgo_drf.py}. 클라이언트는 {@link LawGoDrfClient}.
 *
 * <pre>{@code
 * LawGoDrfClient client = new LawGoDrfClient("<신청한 OC>");
 * var hits = client.search("law", "국토의 계획 및 이용에 관한 법률");
 * SourceBundle bundle = client.getDocument("eflaw", null, "284013", "20260701",
 *         DrfDocHint.builder().key("kr.law.nkpa").layer("L1_NATIONAL").build(), null);
 * }</pre>
 *
 * <p>요청 규격 (open.law.go.kr 'OPEN API 활용가이드', 2026-11-06 열람)
 * <ul>
 *   <li>목록: GET {base}/lawSearch.do?OC=&amp;target=law|eflaw|ordin&amp;type=XML&amp;query=…</li>
 *   <li>본문: GET {base}/lawService.do?OC=&amp;target=law&amp;type=XML&amp;ID=|MST=
 *       target=eflaw 는 MST 와 efYd(필수, ID 로 조회할 때는 생략), target=ordin 은 ID=|MST=</li>
 *   <li>OC 는 등록된 서버 IP/도메인에서만 통과한다. 실패하면
 *       &lt;Response&gt;&lt;result&gt;사용자 정보 검증에 실패하였습니다.&lt;/result&gt;&lt;msg&gt;…&lt;/msg&gt;&lt;/Response&gt;
 *       (실제 응답, tests/fixtures/lawgo 참고)</li>
 * </ul>
 *
 * <p>XML 요소 이름의 근거
 * <ul>
 *   <li>법령(law/eflaw): 가이드의 '출력 결과 필드' 목록과 'OPEN API 활용방법' 화면의 본문조회 결과 그림
 *       (&lt;법령 법령키&gt;/&lt;기본정보&gt;/&lt;조문&gt;/&lt;조문단위 조문키&gt;/&lt;조문내용&gt; …, &lt;별표&gt;/&lt;별표단위 별표키&gt;/&lt;별표내용&gt; …).
 *       항·호·목은 필드 목록(항번호·항내용·호번호·호내용·목번호·목내용)에 따라 &lt;항&gt;/&lt;호&gt;/&lt;목&gt; 중첩으로 가정한다.</li>
 *   <li>자치법규(ordin): 필드 목록만 있고 중첩 구조 그림이 없다. 그래서 요소 위치에 기대지 않고
 *       '조문번호와 조내용을 자식으로 가진 요소 = 조 하나'로 찾는다.</li>
 *   <li>실제 응답 표본은 OC 가 없어 받지 못했다. 테스트 표본(tests/fixtures/lawgo/drf_*_constructed.xml)은
 *       위 근거로 만든 것이며 파일 머리에 그렇게 적혀 있다. 실제 OC 를 받으면 표본을 실제 응답으로 바꿀 것.</li>
 * </ul>
 *
 * <p>text 규칙은 lawgo_html 과 같게 맞춘다
 * <ul>
 *   <li>조 하나 = 조문내용 + 항내용 + 호내용 + 목내용을 문서 순서대로 줄 단위로 잇는다.</li>
 *   <li>조문내용이 '제N조(제목)' 한 줄뿐이면 첫 항 내용을 같은 줄에 한 칸 띄워 붙인다
 *       (law.go.kr 화면 표시 '제77조(용도지역의 건폐율) ① …' 와 같아지도록).</li>
 *   <li>연속한 CDATA 구획(]]&gt;&lt;![CDATA[)은 줄바꿈으로 본다. 각 줄 앞뒤 공백 제거, 빈 줄 제거.</li>
 * </ul>
 */
public final class LawGoDrf {
    private LawGoDrf() {
    }

    /** http 도 되지만 OC 가 평문으로 가므로 https. */
    public static final String DRF_BASE_URL = "https://www.law.go.kr/DRF";
    public static final String USER_AGENT = "APP-legal-collector/0.1 (dev)";
    public static final java.time.ZoneOffset KST = LegalPy.KST;
    /** DrfTarget 값: law | eflaw | ordin. */
    public static final List<String> DRF_TARGETS = List.of("law", "eflaw", "ordin");

    /** 가이드의 자치법규종류 코드 (ordinInfoGuide). */
    public static final Map<String, String> ORDIN_KIND_NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("C0001", "조례");
        m.put("C0002", "규칙");
        m.put("C0003", "훈령");
        m.put("C0004", "예규");
        m.put("C0006", "기타");
        m.put("C0010", "고시");
        m.put("C0011", "의회규칙");
        ORDIN_KIND_NAMES = java.util.Collections.unmodifiableMap(m);
    }

    // ---------------------------------------------------------------------------
    // XML 유틸
    // ---------------------------------------------------------------------------

    /** 바이트 정규식 (ISO-8859-1 로 바이트 하나 = 글자 하나). {@code \s} 는 파이썬 bytes 패턴처럼 ASCII 공백. */
    private static final Pattern CDATA_JOIN_RE = Pattern.compile("\\]\\]>[ \\t\\n\\r\\f\\x0B]*<!\\[CDATA\\[");

    /** 연속한 CDATA 구획 경계 → 줄바꿈 (ElementTree 는 구획 경계를 잃어버리므로 미리 표시). */
    static byte[] prepareXml(byte[] raw) {
        String latin = new String(raw, StandardCharsets.ISO_8859_1);
        return CDATA_JOIN_RE.matcher(latin).replaceAll("\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        s = s.replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ");
        for (String ln : LegalPy.split(s, "\n")) {
            String t = Py.strip(ln);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static String text(XmlElement el) {
        if (el == null) {
            return null;
        }
        String t = Py.strip(el.itertext());
        return t.isEmpty() ? null : t;
    }

    static String childText(XmlElement el, String tag) {
        return text(el.find(tag));
    }

    static XmlElement first(XmlElement root, String tag) {
        List<XmlElement> all = root.iter(tag);
        return all.isEmpty() ? null : all.getFirst();
    }

    static String firstText(XmlElement root, String tag) {
        return text(first(root, tag));
    }

    private static final Pattern NON_DIGIT_RE = LegalPy.re("\\D");

    static String ymd(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String d = NON_DIGIT_RE.matcher(s).replaceAll("");
        if (Py.len(d) != 8 || d.equals("99991231")) {
            return null;
        }
        return Py.slice(d, null, 4) + "-" + Py.slice(d, 4, 6) + "-" + Py.slice(d, 6, null);
    }

    static BigInteger intOrNone(String s) {
        if (s == null) {
            return null;
        }
        String d = NON_DIGIT_RE.matcher(s).replaceAll("");
        return d.isEmpty() ? null : LegalPy.pyInt(d);
    }

    /** 파이썬 {@code x or None} (정수 0 → None). */
    private static BigInteger nonZero(BigInteger v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    /** DRF 오류 응답이면 예외. */
    public static void checkError(XmlElement root) {
        if (root.tag().equals("Response")) {
            String result = LegalPy.or(childText(root, "result"), "");
            String msg = LegalPy.or(childText(root, "msg"), "");
            if (result.contains("검증") || result.contains("인증")) {
                throw new DrfAuthError(Py.strip(result + " " + msg));
            }
            String m = Py.strip(result + " " + msg);
            throw new DrfError(m.isEmpty() ? "DRF error response" : m);
        }
    }

    /**
     * XML 해석 + 오류 응답 확인.
     *
     * @throws DrfError     XML 이 아님 (HTML 오류 화면일 수 있음)
     * @throws DrfAuthError OC 검증 실패 응답
     */
    public static XmlElement parseXml(byte[] raw) {
        XmlElement root;
        try {
            root = XmlElement.parse(prepareXml(raw));
        } catch (XMLStreamException | RuntimeException e) {
            byte[] head = java.util.Arrays.copyOf(raw, Math.min(raw.length, 200));
            String shown = new String(head, StandardCharsets.UTF_8);
            throw new DrfError("XML 이 아님 (HTML 오류 화면일 수 있음): " + LegalPy.reprString(shown), e);
        }
        checkError(root);
        return root;
    }

    static String label(BigInteger jo, BigInteger branch) {
        return "제" + jo + "조" + (branch != null && branch.signum() != 0 ? "의" + branch : "");
    }

    static String path(String kind, BigInteger n, BigInteger branch) {
        return kind + n + (branch != null && branch.signum() != 0 ? "-" + branch : "");
    }

    private static final Pattern TITLE_ONLY_RE = LegalPy.re("^제\\d+조(?:의\\d+)?(?:\\s*\\(.*\\))?$");

    // ---------------------------------------------------------------------------
    // 법령 (target=law / eflaw)
    // ---------------------------------------------------------------------------

    /** 조문단위 → 조 전체 텍스트 (조문내용 + 항/호/목). */
    static String lawArticleText(XmlElement unit) {
        List<String> head = lines(childText(unit, "조문내용"));
        List<String> body = new ArrayList<>();
        for (XmlElement hang : unit.findall("항")) {
            body.addAll(lines(childText(hang, "항내용")));
            for (XmlElement ho : hang.findall("호")) {
                walkHo(ho, body);
            }
        }
        // 항 없이 호가 바로 오는 경우도 받아 둔다
        for (XmlElement ho : unit.findall("호")) {
            walkHo(ho, body);
        }
        List<String> out;
        if (head.size() == 1 && TITLE_ONLY_RE.matcher(head.getFirst()).lookingAt() && !body.isEmpty()) {
            out = new ArrayList<>();
            out.add(head.getFirst() + " " + body.getFirst());
            out.addAll(body.subList(1, body.size()));
        } else {
            out = new ArrayList<>(head);
            out.addAll(body);
        }
        return String.join("\n", out);
    }

    private static void walkHo(XmlElement ho, List<String> body) {
        body.addAll(lines(childText(ho, "호내용")));
        for (XmlElement mok : ho.findall("목")) {
            body.addAll(lines(childText(mok, "목내용")));
        }
    }

    /** {@code parse_law_xml(raw)} — 나머지 인자는 None. */
    public static SourceBundle parseLawXml(byte[] raw) {
        return parseLawXml(raw, null, null, null, null, null);
    }

    /**
     * lawService.do?target=law|eflaw&amp;type=XML 응답 → SourceBundle (전 조문·별표).
     *
     * @param sourceUrl null 이면 law.go.kr 화면 주소를 만든다
     * @param fetchedAt null 이면 지금(KST)
     * @param meta      XML 에 없는 저장소 쪽 메타데이터 (null = 빈 힌트)
     */
    public static SourceBundle parseLawXml(byte[] raw, String sourceUrl, String fetchedAt, String rawFile, String mst,
                                           DrfDocHint meta) {
        DrfDocHint hint = meta != null ? meta : DrfDocHint.empty();
        XmlElement root = parseXml(raw);
        XmlElement info = root.find("기본정보");
        if (info == null) {
            info = root;
        }
        String lawId = childText(info, "법령ID");
        String title = LegalPy.or(childText(info, "법령명_한글"), childText(info, "법령명한글"));
        String shortTitle = childText(info, "법령명약칭");
        String kindName = childText(info, "법종구분");
        String ancNo = childText(info, "공포번호");
        String ancYd = ymd(childText(info, "공포일자"));
        String efYd = ymd(childText(info, "시행일자"));
        String promNo = (LegalPy.truthy(kindName) && LegalPy.truthy(ancNo) && LegalPy.isDigit(ancNo))
                ? kindName + " 제" + LegalPy.pyInt(ancNo) + "호" : null;

        List<ArticleText> articles = new ArrayList<>();
        for (XmlElement unit : root.iter("조문단위")) {
            if (!LegalPy.or(childText(unit, "조문여부"), "조문").equals("조문")) {
                continue; // 편·장·절 제목('전문')
            }
            BigInteger jo = intOrNone(childText(unit, "조문번호"));
            if (jo == null) {
                continue;
            }
            BigInteger br = nonZero(intOrNone(childText(unit, "조문가지번호")));
            String artEf = ymd(childText(unit, "조문시행일자"));
            articles.add(new ArticleText("article", label(jo, br), path("art", jo, br), childText(unit, "조문제목"),
                    lawArticleText(unit), null, LegalPy.truthy(artEf) && !artEf.equals(efYd) ? artEf : null));
        }
        for (XmlElement unit : root.iter("별표단위")) {
            if (!LegalPy.or(childText(unit, "별표구분"), "별표").equals("별표")) {
                continue; // 서식 제외
            }
            BigInteger no = intOrNone(childText(unit, "별표번호"));
            if (no == null) {
                continue;
            }
            BigInteger br = nonZero(intOrNone(childText(unit, "별표가지번호")));
            String content = String.join("\n", lines(childText(unit, "별표내용")));
            if (content.isEmpty()) {
                continue; // 내용 없는 별표(파일 전용)는 넣지 않는다
            }
            String bef = ymd(childText(unit, "별표시행일자"));
            articles.add(new ArticleText("annex", "별표 " + no + (br != null ? "의" + br : ""), path("annex", no, br),
                    childText(unit, "별표제목"), content, null, LegalPy.truthy(bef) && !bef.equals(efYd) ? bef : null));
        }

        String vHeader = LegalPy.truthy(promNo) && LegalPy.truthy(ancYd) && LegalPy.truthy(efYd) ? Parsed.CONFIRMED : Parsed.NEEDS_REVIEW;
        if (sourceUrl == null) {
            Map<String, String> q = new LinkedHashMap<>();
            if (LegalPy.truthy(mst)) {
                q.put("lsiSeq", mst);
            } else {
                q.put("lsId", LegalPy.or(lawId, ""));
            }
            if (LegalPy.truthy(mst) && LegalPy.truthy(efYd)) {
                q.put("efYd", efYd.replace("-", ""));
            }
            sourceUrl = "https://www.law.go.kr/LSW/lsInfoP.do?" + LegalPy.urlencode(q);
        }
        DocumentMeta doc = new DocumentMeta(
                LegalPy.or(hint.key(), "law.go.kr.law." + LegalPy.str(lawId)),
                LegalPy.or(hint.layer(), "L1_NATIONAL"),
                LegalPy.or(hint.sourceType(), lawSourceType(kindName)),
                LegalPy.or(title, ""),
                LegalPy.or(hint.jurisdictionCode(), "00"),
                "law.go.kr",
                lawId,
                shortTitle,
                hint.parentKey(),
                hint.homepageUrl(),
                hint.aliases());
        VersionMeta version = new VersionMeta(promNo, ancYd, efYd, null, mst, sourceUrl,
                LegalPy.or(fetchedAt, LegalPy.nowKstIso()), "xml", rawFile, Parsed.CONFIRMED, vHeader, Parsed.NOT_CHECKED,
                Parsed.NEEDS_REVIEW, null, "DRF Open API XML (공포번호 = 법종구분 + 공포번호 로 구성)");
        return new SourceBundle(doc, version, articles, false);
    }

    static String lawSourceType(String kindName) {
        String k = kindName == null ? "" : kindName;
        if (k.equals("법률")) {
            return "law";
        }
        if (k.equals("대통령령")) {
            return "decree";
        }
        if (k.endsWith("령") || k.contains("규칙")) { // 총리령·부령 = 시행규칙
            return "rule";
        }
        return "law";
    }

    // ---------------------------------------------------------------------------
    // 자치법규 (target=ordin)
    // ---------------------------------------------------------------------------

    private static Set<String> childTags(XmlElement el) {
        Set<String> tags = new HashSet<>();
        for (XmlElement c : el) {
            tags.add(c.tag());
        }
        return tags;
    }

    /** '조문번호'와 '조내용'(또는 '조제목')을 자식으로 가진 요소 = 조 하나. */
    static List<XmlElement> ordinUnits(XmlElement root) {
        List<XmlElement> out = new ArrayList<>();
        for (XmlElement el : root.iter()) {
            Set<String> tags = childTags(el);
            if (tags.contains("조문번호") && (tags.contains("조내용") || tags.contains("조제목"))) {
                out.add(el);
            }
        }
        return out;
    }

    /** (조번호, 가지번호) — 없으면 null. */
    static BigInteger[] ordinJoNo(String s) {
        if (s == null || s.isEmpty()) {
            return new BigInteger[] {null, null};
        }
        String d = NON_DIGIT_RE.matcher(s).replaceAll("");
        if (d.isEmpty()) {
            return new BigInteger[] {null, null};
        }
        if (Py.len(d) == 6) { // 조번호 4자리 + 가지번호 2자리 (법령 JO 형식과 같다고 가정)
            return new BigInteger[] {LegalPy.pyInt(Py.slice(d, null, 4)), nonZero(LegalPy.pyInt(Py.slice(d, 4, null)))};
        }
        return new BigInteger[] {LegalPy.pyInt(d), null};
    }

    private static final Pattern ALL_DIGITS_RE = LegalPy.re("\\d+");

    /** {@code parse_ordin_xml(raw)} — 나머지 인자는 None. */
    public static SourceBundle parseOrdinXml(byte[] raw) {
        return parseOrdinXml(raw, null, null, null, null);
    }

    /** lawService.do?target=ordin&amp;type=XML 응답 → SourceBundle. */
    public static SourceBundle parseOrdinXml(byte[] raw, String sourceUrl, String fetchedAt, String rawFile, DrfDocHint meta) {
        DrfDocHint hint = meta != null ? meta : DrfDocHint.empty();
        XmlElement root = parseXml(raw);
        String ordinId = firstText(root, "자치법규ID");
        String ordinSeq = firstText(root, "자치법규일련번호");
        String title = firstText(root, "자치법규명");
        String ancNo = firstText(root, "공포번호");
        String ancYd = ymd(firstText(root, "공포일자"));
        String efYd = ymd(firstText(root, "시행일자"));
        String kind = firstText(root, "자치법규종류");
        String org = firstText(root, "지자체기관명");
        String kindName = ORDIN_KIND_NAMES.getOrDefault(kind == null ? "" : kind, kind);
        String promNo = null;
        if (LegalPy.truthy(org) && LegalPy.truthy(kindName) && LegalPy.truthy(ancNo) && ALL_DIGITS_RE.matcher(ancNo).matches()) {
            promNo = org.replace(" ", "") + kindName + " 제" + LegalPy.pyInt(ancNo) + "호";
        }

        List<ArticleText> articles = new ArrayList<>();
        for (XmlElement unit : ordinUnits(root)) {
            String yn = childText(unit, "조문여부");
            if (yn != null && !yn.equals("Y") && !yn.equals("조문")) {
                continue; // 편·장·절
            }
            BigInteger[] jb = ordinJoNo(childText(unit, "조문번호"));
            if (jb[0] == null) {
                continue;
            }
            articles.add(new ArticleText("article", label(jb[0], jb[1]), path("art", jb[0], jb[1]), childText(unit, "조제목"),
                    String.join("\n", lines(childText(unit, "조내용")))));
        }
        for (XmlElement unit : root.iter()) {
            Set<String> tags = childTags(unit);
            if (!(tags.contains("별표번호") && tags.contains("별표내용"))) {
                continue;
            }
            if (!LegalPy.or(childText(unit, "별표구분"), "별표").equals("별표")) {
                continue;
            }
            BigInteger no = intOrNone(childText(unit, "별표번호"));
            String content = String.join("\n", lines(childText(unit, "별표내용")));
            if (no == null || content.isEmpty()) {
                continue;
            }
            BigInteger br = nonZero(intOrNone(childText(unit, "별표가지번호")));
            articles.add(new ArticleText("annex", "별표 " + no + (br != null ? "의" + br : ""), path("annex", no, br),
                    childText(unit, "별표제목"), content));
        }

        String vHeader = LegalPy.truthy(promNo) && LegalPy.truthy(ancYd) && LegalPy.truthy(efYd) ? Parsed.CONFIRMED : Parsed.NEEDS_REVIEW;
        if (sourceUrl == null) {
            sourceUrl = LegalPy.truthy(ordinSeq)
                    ? "https://www.law.go.kr/LSW/ordinInfoP.do?" + LegalPy.urlencode(Map.of("ordinSeq", ordinSeq))
                    : "https://www.law.go.kr/LSW/ordinInfoP.do?" + LegalPy.urlencode(Map.of("ordinId", LegalPy.or(ordinId, "")));
        }
        DocumentMeta doc = new DocumentMeta(
                LegalPy.or(hint.key(), "law.go.kr.ordin." + LegalPy.str(ordinId)),
                LegalPy.or(hint.layer(), "L3_LOCAL"),
                LegalPy.or(hint.sourceType(), "ordinance"),
                LegalPy.or(title, ""),
                LegalPy.or(hint.jurisdictionCode(), "unknown"),
                "law.go.kr",
                ordinId,
                null,
                hint.parentKey(),
                hint.homepageUrl(),
                hint.aliases());
        VersionMeta version = new VersionMeta(promNo, ancYd, efYd, null, ordinSeq, sourceUrl,
                LegalPy.or(fetchedAt, LegalPy.nowKstIso()), "xml", rawFile, Parsed.CONFIRMED, vHeader, Parsed.NOT_CHECKED,
                Parsed.NEEDS_REVIEW, null, "DRF Open API XML (공포번호 = 지자체기관명 + 자치법규종류 + 공포번호 로 구성)");
        return new SourceBundle(doc, version, articles, false);
    }

    // ---------------------------------------------------------------------------
    // 목록 (lawSearch.do)
    // ---------------------------------------------------------------------------

    /**
     * 목록 응답의 결과 항목들. 자식 요소를 가진 루트 직계 요소 하나 = 결과 하나 (요소명 그대로 맵, 속성 먼저).
     * 값은 문자열 또는 null (빈 요소).
     */
    public static List<Map<String, String>> parseSearchXml(byte[] raw) {
        XmlElement root = parseXml(raw);
        List<Map<String, String>> out = new ArrayList<>();
        for (XmlElement item : root) {
            if (item.size() == 0) {
                continue;
            }
            Map<String, String> rec = new LinkedHashMap<>(item.attrib());
            for (XmlElement c : item) {
                rec.put(c.tag(), text(c));
            }
            out.add(rec);
        }
        return out;
    }
}
