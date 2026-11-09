package com.autoparkingplan.legal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import com.autoparkingplan.common.Py;

/**
 * BeautifulSoup(html.parser) 과 같은 결과를 내도록 맞춘 jsoup 도우미.
 *
 * <p>파이썬판은 {@code BeautifulSoup(html, "html.parser")} 를 쓴다. jsoup 은 HTML5 규칙으로 트리를 고치는데
 * (빠진 html/head/body·tbody 추가, 잘못 닫힌 p 정리 등) law.go.kr 의 실제 원문 페이지(data/legal_sources/raw 전부)와
 * 시험 표본에서 두 파서의 요소·텍스트 트리가 같음을 확인했다 (html/head/body/tbody 제외). 그래도 다를 수 있는 곳:
 * <ul>
 *   <li>엔티티: html.parser 는 속성값을 {@code html.unescape}(세미콜론 없는 접두 엔티티까지 해석)로, 본문은 이름이 정확히 맞는
 *       엔티티만 해석한다. jsoup 은 HTML5 규칙. 세미콜론 없는 엔티티가 있는 비정상 문서에서만 차이가 난다.</li>
 *   <li>같은 속성이 두 번 나오면 bs4 는 뒤의 값, jsoup 은 앞의 값.</li>
 *   <li>HTML 안의 {@code <![CDATA[…]]>}·{@code <?…?>} : bs4 는 문자열(CData 는 get_text 에 포함), jsoup 은 주석.</li>
 *   <li>{@code </br>} : bs4 는 무시, jsoup 은 {@code <br>}.</li>
 * </ul>
 *
 * <p>bs4 의 {@code get_text()} 는 일반 문자열만 잇는다: 주석과 script·style·template·rt·rp 안의 문자열(Script·Stylesheet·
 * TemplateString·RubyText 류)은 빠진다. {@link #getText} 가 같은 규칙을 따른다.
 */
public final class Bs4 {
    private Bs4() {
    }

    /** bs4 html.parser 빌더의 string_containers: 이 태그 안의 문자열은 별도 형식이라 보통 get_text 에서 빠진다. */
    static final Set<String> STRING_CONTAINERS = Set.of("rt", "rp", "style", "script", "template");

    /** {@code BeautifulSoup(html, "html.parser")}. */
    public static Document parse(String html) {
        return Jsoup.parse(html, "");
    }

    // ── 문자열 ────────────────────────────────────────────────

    /**
     * 문자열 노드(TextNode·DataNode)의 bs4 문자열. 그 밖이면 null.
     *
     * <p>bs4 는 ASCII 공백(스페이스·\t·\n·\f·\r)만으로 된 문자열을 pre/textarea 밖에서는 "\n"(줄바꿈이 있으면) 또는 " " 하나로 줄인다.
     */
    static String stringOf(Node n) {
        String s;
        if (n instanceof TextNode t) {
            s = t.getWholeText();
        } else if (n instanceof DataNode d) {
            s = d.getWholeData();
        } else {
            return null;
        }
        if (!s.isEmpty() && isAsciiSpaces(s) && !inPreserveWhitespace(n)) {
            return s.indexOf('\n') >= 0 ? "\n" : " ";
        }
        return s;
    }

    private static boolean isAsciiSpaces(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\t' && c != '\f' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private static boolean inPreserveWhitespace(Node n) {
        for (Node p = n.parent(); p != null; p = p.parent()) {
            if (p instanceof Element e && (e.normalName().equals("pre") || e.normalName().equals("textarea"))) {
                return true;
            }
        }
        return false;
    }

    /** 문자열 노드가 파싱 때 받은 형식: 가장 가까운 string container 조상의 태그 이름, 일반 문자열이면 null. */
    static String containerOf(Node n) {
        for (Element p = n.parent() instanceof Element e ? e : null; p != null; p = p.parent()) {
            if (STRING_CONTAINERS.contains(p.normalName())) {
                return p.normalName();
            }
        }
        return null;
    }

    /** {@code el.get_text()} 에 들어갈 형식인가 (el 자신이 string container 면 그 형식, 아니면 일반 문자열). */
    static boolean interesting(Node textNode, Element root) {
        String want = STRING_CONTAINERS.contains(root.normalName()) ? root.normalName() : null;
        String have = containerOf(textNode);
        return want == null ? have == null : want.equals(have);
    }

    /** bs4 {@code el.get_text()}. */
    public static String getText(Element el) {
        return getText(el, "", false);
    }

    /** bs4 {@code el.get_text(separator)}. */
    public static String getText(Element el, String separator) {
        return getText(el, separator, false);
    }

    /** bs4 {@code el.get_text(separator, strip=strip)} (strip 이면 각 문자열을 파이썬 strip 하고 빈 것은 뺀다). */
    public static String getText(Element el, String separator, boolean strip) {
        List<String> parts = new ArrayList<>();
        collect(el, el, strip, parts);
        return String.join(separator, parts);
    }

    private static void collect(Element root, Node node, boolean strip, List<String> out) {
        for (Node c : node.childNodes()) {
            String s = stringOf(c);
            if (s != null) {
                if (interesting(c, root)) {
                    if (strip) {
                        s = Py.strip(s);
                        if (s.isEmpty()) {
                            continue;
                        }
                    }
                    out.add(s);
                }
            } else if (c instanceof Element e) {
                collect(root, e, strip, out);
            }
        }
    }

    // ── 찾기 ──────────────────────────────────────────────────

    /** {@code el.find_all(...)}: 하위 요소(자기 자신 제외), 문서 순서. */
    public static List<Element> findAll(Element root, Predicate<Element> p) {
        List<Element> out = new ArrayList<>();
        boolean first = true;
        for (Element e : root.getAllElements()) {
            if (first) { // getAllElements() 의 첫 항목은 자기 자신
                first = false;
                continue;
            }
            if (p.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** {@code el.find(...)}: 첫 하위 요소 또는 null. */
    public static Element find(Element root, Predicate<Element> p) {
        boolean first = true;
        for (Element e : root.getAllElements()) {
            if (first) {
                first = false;
                continue;
            }
            if (p.test(e)) {
                return e;
            }
        }
        return null;
    }

    /** {@code el.find_parent(name)}: 가장 가까운 조상 또는 null. */
    public static Element findParent(Element el, String name) {
        for (Element p = el.parent(); p != null; p = p.parent()) {
            if (p.normalName().equals(name) && !(p instanceof Document)) {
                return p;
            }
        }
        return null;
    }

    /** {@code el.find_next_sibling()}: 다음 형제 요소 또는 null. */
    public static Element findNextSibling(Element el) {
        return el.nextElementSibling();
    }

    /** {@code el.find_next_sibling(name)}. */
    public static Element findNextSibling(Element el, String name) {
        for (Element s = el.nextElementSibling(); s != null; s = s.nextElementSibling()) {
            if (s.normalName().equals(name)) {
                return s;
            }
        }
        return null;
    }

    // ── 조건 ──────────────────────────────────────────────────

    /** 태그 이름 조건. */
    public static Predicate<Element> tag(String... names) {
        Set<String> set = Set.of(names);
        return e -> set.contains(e.normalName());
    }

    /** {@code el.get(attr)}: 속성값, 없으면 null. */
    public static String get(Element e, String attr) {
        return e.hasAttr(attr) ? e.attr(attr) : null;
    }

    /** bs4 class 속성 목록 (파이썬 {@code \S+} 기준으로 나눔). 없으면 빈 목록. */
    public static List<String> classes(Element e) {
        String v = get(e, "class");
        return v == null ? List.of() : Py.split(v);
    }

    /** bs4 {@code class_=name}: 값 하나가 같거나 전체(공백 한 칸으로 이은 것)가 같으면. */
    public static Predicate<Element> hasClass(String name) {
        return e -> {
            List<String> cs = classes(e);
            return !cs.isEmpty() && (cs.contains(name) || String.join(" ", cs).equals(name));
        };
    }

    /** bs4 {@code attr=re.compile(...)}: 속성이 있고 정규식 search 가 맞으면. */
    public static Predicate<Element> attrSearch(String attr, Pattern re) {
        return e -> {
            String v = get(e, attr);
            return v != null && re.matcher(v).find();
        };
    }

    /** bs4 {@code attrs={attr: "value"}}: 속성값이 정확히 같으면. */
    public static Predicate<Element> attrEquals(String attr, String value) {
        return e -> value.equals(get(e, attr));
    }
}
