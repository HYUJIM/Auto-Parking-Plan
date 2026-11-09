package com.autoparkingplan.legal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * 조문 구조 파서 (결정적, AI 미사용) — 설계서 6장 1단계. 파이썬 {@code app/legal/structure.py}.
 *
 * <p>조(article) 원문 전체를 받아 항(①)·호(1.)·목(가.) 트리로 나눈다.
 * 각 노드의 text 는 '그 단위 원문 전체'(자기 줄 + 하위 단위 줄)이며, 원문의 연속 부분 문자열이다.
 * 그래서 AI 가 하위 단위(호)를 근거로 인용해도 상위 단위 원문 안에서 같은 문자열을 찾을 수 있다.
 *
 * <p>위치(start/end)는 파이썬과 같은 코드포인트 단위다.
 */
public final class Structure {
    private Structure() {
    }

    public static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";
    /** 목 순서: 가~하 다음은 거·너·더… (법령 별표에서 실제로 쓰임). */
    public static final String HANGUL_ORDER = "가나다라마바사아자차카타파하거너더러머버서어저처커터퍼허";

    public static final Pattern ARTICLE_HEAD_RE = LegalPy.re("제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?\\s*(?:\\(([^)]*)\\))?\\s*");
    public static final Pattern PARA_RE = LegalPy.re("^\\s*([" + CIRCLED + "])\\s*");
    public static final Pattern ITEM_RE = LegalPy.re("^\\s*(\\d+)(?:\\s*의\\s*(\\d+))?\\s*\\.(?!\\d)\\s*");
    public static final Pattern SUBITEM_RE = LegalPy.re("^\\s*([" + HANGUL_ORDER + "])(?:\\s*의\\s*(\\d+))?\\s*\\.\\s*");

    /**
     * 조문 트리의 노드 (파이썬 {@code structure.Node}, 가변 — 파싱 중 end 를 늘리고, 적재기가 table_json 을 붙인다).
     * kind: article | paragraph | item | subitem | annex | annex_row (| addendum).
     */
    public static final class Node {
        private final String kind;
        private final String label;
        private final String path;
        private final String heading;
        private final int start; // 원문(article text) 안의 시작 위치 (코드포인트)
        private int end;
        private final List<Node> children = new ArrayList<>();
        private JsonNode tableJson;

        public Node(String kind, String label, String path, String heading, int start, int end) {
            this.kind = kind;
            this.label = label;
            this.path = path;
            this.heading = heading;
            this.start = start;
            this.end = end;
        }

        public String kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        public String path() {
            return path;
        }

        public String heading() {
            return heading;
        }

        /** 원문 안의 시작 위치 (코드포인트 단위). */
        public int start() {
            return start;
        }

        /** 원문 안의 끝 위치 (코드포인트 단위, 미포함). */
        public int end() {
            return end;
        }

        public void setEnd(int end) {
            this.end = end;
        }

        /** 하위 노드 (수정 가능한 목록). */
        public List<Node> children() {
            return children;
        }

        public JsonNode tableJson() {
            return tableJson;
        }

        public void setTableJson(JsonNode tableJson) {
            this.tableJson = tableJson == null || tableJson.isNull() ? null : tableJson;
        }

        /** {@code source[start:end].strip()}. */
        public String textOf(String source) {
            return Py.strip(Py.slice(source, start, end));
        }

        @Override
        public String toString() {
            return "Node(" + kind + ", " + label + ", " + path + ", " + start + ".." + end + ", children=" + children.size() + ")";
        }
    }

    /**
     * provision 행 하나 (파이썬 flatten 의 dict: kind, label, path, heading, text, table_json, parent_path).
     *
     * @param parentPath 최상위(조·별표)면 null
     */
    public record ProvisionRow(String kind, String label, String path, String heading, String text, JsonNode tableJson,
                               String parentPath) {
    }

    /** 각 줄의 [시작, 끝) 위치 (코드포인트). */
    static List<int[]> lineSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int pos = 0;
        for (String line : LegalPy.split(text, "\n")) {
            int len = Py.len(line);
            spans.add(new int[] {pos, pos + len});
            pos += len + 1;
        }
        return spans;
    }

    /** {@code parse_article(label, path, heading, text)} (kind='article'). */
    public static Node parseArticle(String label, String path, String heading, String text) {
        return parseArticle(label, path, heading, text, "article");
    }

    /**
     * 조 하나의 원문을 트리로 만든다. 줄 단위로 항·호·목 표지를 인식한다.
     *
     * <p>첫 줄이 '제84조(…) ① …' 처럼 조 제목과 첫 항을 함께 담고 있으면 제목 부분을 건너뛰고 항을 인식한다.
     *
     * @param kind article | annex | addendum
     */
    public static Node parseArticle(String label, String path, String heading, String text, String kind) {
        Node root = new Node(kind, label, path, heading, 0, Py.len(text));
        Node para = null;
        Node item = null;
        Node sub = null;
        List<int[]> spans = lineSpans(text);
        for (int i = 0; i < spans.size(); i++) {
            int s = spans.get(i)[0];
            int e = spans.get(i)[1];
            String line = Py.slice(text, s, e);
            if (Py.strip(line).isEmpty()) {
                continue;
            }
            int offset = 0;
            if (i == 0 && "article".equals(kind)) {
                // 첫 줄의 '제84조(제목)' 머리 부분은 건너뛴다 (앞에 주석·표지가 붙어 있어도)
                String head = Py.slice(line, null, 80);
                Matcher m = ARTICLE_HEAD_RE.matcher(head);
                if (m.find()) {
                    offset = Py.cpIndex(head, m.end());
                }
            }
            String body = Py.slice(line, offset, null);
            Matcher m;
            if ((m = PARA_RE.matcher(body)).lookingAt()) {
                int n = CIRCLED.indexOf(m.group(1)) + 1;
                para = new Node("paragraph", m.group(1), path + "/para" + n, null, s + offset, e);
                root.children.add(para);
                item = null;
                sub = null;
            } else if ((m = ITEM_RE.matcher(body)).lookingAt()) {
                String n = m.group(1) + (m.group(2) != null ? "-" + m.group(2) : "");
                String lab = m.group(1) + (m.group(2) != null ? "의" + m.group(2) : "") + ".";
                Node parent = para != null ? para : root;
                item = new Node("item", lab, parent.path + "/item" + n, null, s + offset, e);
                parent.children.add(item);
                sub = null;
            } else if ((m = SUBITEM_RE.matcher(body)).lookingAt() && item != null) {
                String n = (HANGUL_ORDER.indexOf(m.group(1)) + 1) + (m.group(2) != null ? "-" + m.group(2) : "");
                String lab = m.group(1) + (m.group(2) != null ? "의" + m.group(2) : "") + ".";
                sub = new Node("subitem", lab, item.path + "/sub" + n, null, s + offset, e);
                item.children.add(sub);
            }
            // else: 이어지는 줄(표지 없음) — 가장 깊은 열린 노드에 붙인다
            for (Node open : new Node[] {sub, item, para}) {
                if (open != null) {
                    open.end = Math.max(open.end, e);
                }
            }
            // 상위 노드의 end 를 하위 노드 끝까지 늘린다
            if (item != null && sub != null) {
                item.end = Math.max(item.end, sub.end);
            }
            if (para != null && item != null) {
                para.end = Math.max(para.end, item.end);
            }
        }
        return root;
    }

    /** 트리를 provision 행 목록으로 (부모 path 포함, 정렬 순서 유지). */
    public static List<ProvisionRow> flatten(Node root, String source) {
        List<String[]> rows = new ArrayList<>(); // [kind, label, path, heading, text, parentPath]
        List<JsonNode> tables = new ArrayList<>();
        walk(root, null, source, rows, tables);
        // 같은 path 가 중복되면(원문 번호 오기 등) 뒤의 것에 접미사를 붙여 유일하게 만든다
        Map<String, Integer> seen = new HashMap<>();
        List<ProvisionRow> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String p = r[2];
            if (seen.containsKey(p)) {
                int k = seen.get(p) + 1;
                seen.put(p, k);
                p = p + "~" + k;
            } else {
                seen.put(p, 0);
            }
            out.add(new ProvisionRow(r[0], r[1], p, r[3], r[4], tables.get(i), r[5]));
        }
        return out;
    }

    private static void walk(Node n, String parentPath, String source, List<String[]> rows, List<JsonNode> tables) {
        rows.add(new String[] {n.kind, n.label, n.path, n.heading, parentPath == null ? Py.strip(source) : n.textOf(source),
                parentPath});
        tables.add(n.tableJson);
        for (Node c : n.children) {
            walk(c, n.path, source, rows, tables);
        }
    }

    /** 'art84/para1/item4' → '제84조제1항제4호' (근거 표시용). */
    public static String articleLabelFor(String path, String baseLabel) {
        List<String> parts = LegalPy.split(path, "/");
        StringBuilder label = new StringBuilder(baseLabel);
        for (String p : parts.subList(1, parts.size())) {
            if (p.startsWith("para")) {
                label.append("제").append(LegalPy.split(Py.slice(p, 4, null), "~").getFirst()).append("항");
            } else if (p.startsWith("item")) {
                String n = LegalPy.split(Py.slice(p, 4, null), "~").getFirst();
                label.append("제").append(n.replace("-", "호의")).append(n.contains("-") ? "" : "호");
            } else if (p.startsWith("sub")) {
                String num = LegalPy.split(LegalPy.split(Py.slice(p, 3, null), "~").getFirst(), "-").getFirst();
                int n = LegalPy.pyInt(num).intValueExact();
                int idx = n - 1;
                if (idx < 0) {
                    idx += HANGUL_ORDER.length(); // 파이썬 음수 색인
                }
                if (idx < 0 || idx >= HANGUL_ORDER.length()) {
                    throw new IndexOutOfBoundsException("string index out of range");
                }
                label.append(HANGUL_ORDER.charAt(idx)).append("목");
            }
        }
        return label.toString();
    }
}
