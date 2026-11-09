package com.autoparkingplan.server.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.ai.verification.KoQuantity;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.legal.ArticleText;
import com.autoparkingplan.legal.LegalPy;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.legal.Structure;
import com.autoparkingplan.legal.SupplementText;
import com.autoparkingplan.server.cli.Cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 수집한 원문 번들에서 규칙 추출 결과(rule_extraction@1.0)를 만든다 → data/seed/extractions/*.json
 * — 파이썬 {@code scripts/build_seed_extractions.py}.
 *
 * <p>이 스크립트는 '규칙형 추출기'다 (LLM 키 없이 개발·검증하기 위한 대체 경로). 결과는 실시간 LLM 출력과
 * 똑같이 기계 검증(근거 인용 원문 일치, 숫자 일치, 상위 범위)을 통과해야 저장되고, 사람이 승인해야 검토에 쓰인다.
 * extraction_run.model 에 'scripted:build_seed_extractions@v1' 로 남는다.
 *
 * <p>대상
 * <ul>
 *   <li>건폐율·용적률: 국토계획법 시행령 제84조①·제85조① (전국 범위), 대구광역시 도시계획 조례 제75조·제80조① (지역 값)</li>
 *   <li>건축 가능 용도: 시행령 별표 4·5·6·17 (공식 PDF 텍스트) + 조례 제33·34③·35·46조</li>
 *   <li>주차구획·차로 폭: 주차장법 시행규칙 제3조①(표 전사본), 제6조①3나2)(표 전사본)·제6조①4 (출입구 너비)</li>
 * </ul>
 *
 * <p>실행: {@code python scripts/build_seed_extractions.py} → 자바판 {@code java -jar app.jar build-seed-extractions}
 * (Spring·DB 를 띄우지 않는다). 파이썬판처럼 명령행 인자는 받지 않는다(무시). 데이터 폴더는 {@code ./data}
 * (CLI 에서는 APP_HOME 이 있으면 {@code $APP_HOME/data}).
 *
 * <p>출력 파일은 파이썬 {@code json.dumps(data, ensure_ascii=False, indent=2)} (끝 줄바꿈 없음) 와 바이트 단위로 같다.
 */
public final class BuildSeedExtractions {
    private BuildSeedExtractions() {
    }

    public static final String MODEL = "scripted:build_seed_extractions@v1";
    public static final Map<String, String> ZONE_CODES = ordered(
            "제1종전용주거지역", "R1E", "제2종전용주거지역", "R2E", "제1종일반주거지역", "R1G", "제2종일반주거지역", "R2G",
            "제3종일반주거지역", "R3G", "준주거지역", "RQ", "중심상업지역", "CC", "일반상업지역", "CG", "근린상업지역", "CN",
            "유통상업지역", "CD", "전용공업지역", "IE", "일반공업지역", "IG", "준공업지역", "IQ", "보전녹지지역", "GC",
            "생산녹지지역", "GP", "자연녹지지역", "GN", "보전관리지역", "MC", "생산관리지역", "MP", "계획관리지역", "MPL",
            "농림지역", "AG", "자연환경보전지역", "NC");
    static final Pattern ZONE_LINE = PyRe.compile("^\\s*\\d+\\.\\s*(?P<zone>\\S+지역)\\s*:\\s*(?P<rest>.+)$");

    // ── 원문 읽기 ─────────────────────────────────────────────

    /** 번들 하나의 조문을 path → 원문 으로 펼친다 (파이썬 {@code provisions_of}; 번들은 쓰지 않으므로 path 표만 돌려준다). */
    static Map<String, String> provisionsOf(Path src, String bundleFile) {
        SourceBundle b = SourceBundle.load(src.resolve(bundleFile));
        Map<String, String> paths = new LinkedHashMap<>();
        for (ArticleText a : b.articles()) {
            Structure.Node root = Structure.parseArticle(a.label(), a.path(), a.heading(), a.text(),
                    "annex".equals(a.kind()) ? "annex" : "article");
            for (Structure.ProvisionRow row : Structure.flatten(root, a.text())) {
                paths.put(row.path(), row.text());
            }
        }
        return paths;
    }

    static Map<String, String> supplementProvisions(Path src, String suppFile) {
        JsonNode supp = readJson(src.resolve("supplements").resolve(suppFile));
        Map<String, String> paths = new LinkedHashMap<>();
        for (JsonNode it : supp.get("items").values()) {
            if ("pdf_annex".equals(str(it, "kind"))) {
                String text = str(it, "text");
                JsonNode h = it.get("heading");
                String heading = h == null || h.isNull() ? null : h.stringValue();
                for (Structure.ProvisionRow row : Structure.flatten(
                        Structure.parseArticle(str(it, "label"), str(it, "path"), heading, text, "annex"), text)) {
                    paths.put(row.path(), row.text());
                }
            } else {
                List<JsonNode> rows = new ArrayList<>();
                for (JsonNode r : it.get("rows").values()) {
                    rows.add(r);
                }
                for (int i = 1; i < rows.size(); i++) {
                    List<String> cells = new ArrayList<>();
                    for (JsonNode c : rows.get(i).values()) {
                        cells.add(c.stringValue());
                    }
                    paths.put(str(it, "path") + "/row" + i, SupplementText.rowText(cells));
                }
            }
        }
        return paths;
    }

    // ── 단서 ──────────────────────────────────────────────────

    /**
     * '(다만, …)' 또는 '다만, …' 구간을 원문 그대로.
     *
     * <p>위치는 모두 같은 문자열 안의 find 결과와 BMP 글자('다.')의 길이로만 계산하므로 UTF-16 단위로 계산해도
     * 파이썬(코드포인트)과 같은 구간이 나온다.
     */
    static String exceptionQuote(String text) {
        int i = text.indexOf("(다만");
        if (i >= 0) {
            int depth = 0;
            int j = i;
            while (j < text.length()) {
                char c = text.charAt(j);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
                if (depth == 0) {
                    return text.substring(i, j + 1);
                }
                j++;
            }
            return firstLine(text.substring(i));
        }
        i = text.indexOf("다만");
        if (i >= 0) {
            String seg = firstLine(text.substring(i));
            int k = seg.indexOf("다.");
            return k > 0 ? seg.substring(0, k + 2) : seg;
        }
        return null;
    }

    // ── 건폐율·용적률 ─────────────────────────────────────────

    static List<ObjectNode> ratioRules(Map<String, String> paths, String prefix, String category, boolean national) {
        List<ObjectNode> out = new ArrayList<>();
        String abbr = "BUILDING_COVERAGE".equals(category) ? "BCR" : "FAR";
        String label = "BCR".equals(abbr) ? "건폐율" : "용적률";
        Pattern itemPath = fullPattern(prefix, "/item\\d+");
        for (Map.Entry<String, String> e : sortedByKey(paths)) {
            String path = e.getKey();
            String text = e.getValue();
            if (!itemPath.matcher(path).matches()) {
                continue;
            }
            String first = firstLine(text);
            Matcher m = ZONE_LINE.matcher(first);
            if (!m.lookingAt() || !ZONE_CODES.containsKey(m.group("zone"))) {
                continue;
            }
            String zone = m.group("zone");
            String quote = Py.strip(first.substring(m.start("zone")));
            // 단서 '(다만…' 이 같은 줄에 붙어 있으면 값 근거 인용에서는 뺀다
            String quoteMain = Py.rstrip(LegalPy.split(quote, "(다만").getFirst());
            List<KoQuantity.Q> qs = new ArrayList<>();
            for (KoQuantity.Q q : KoQuantity.parse(quoteMain)) {
                if ("QUANTITY".equals(q.kind()) && "ratio".equals(q.dim())) {
                    qs.add(q);
                }
            }
            Double lo = null;
            Double hi = null;
            for (KoQuantity.Q q : qs) {
                if (lo == null && "GE".equals(q.comparator())) {
                    lo = (Double) q.value();
                }
            }
            for (KoQuantity.Q q : qs) {
                if (hi == null && "LE".equals(q.comparator())) {
                    hi = (Double) q.value();
                }
            }
            if (hi == null) {
                continue;
            }
            ObjectNode val = Json.object();
            val.put("value_key", "ratio");
            val.put("unit", "%");
            val.set("evidence", evidence("@" + path, quoteMain));
            if (national && lo != null) {
                val.put("value_type", "range");
                val.put("num_min", lo.doubleValue());
                val.put("num_max", hi.doubleValue());
            } else {
                val.put("value_type", "max");
                val.put("num_max", hi.doubleValue());
            }
            ObjectNode rule = Json.object();
            rule.put("rule_key", abbr + ".ZONE." + ZONE_CODES.get(zone));
            rule.put("category", category);
            rule.put("title", zone + " " + label + " " + (national ? "범위(국가 법령)" : "(조례)"));
            rule.put("automation_level", "auto");
            rule.set("conditions", zoningCondition(zone));
            rule.set("values", Json.array().add(val));
            rule.put("confidence", "high");
            String exc = exceptionQuote(text);
            if (truthy(exc)) {
                rule.set("exceptions_mentioned", Json.array().add(evidence("@" + path, exc)));
            }
            out.add(rule);
        }
        return out;
    }

    // ── 건축 가능 용도 ────────────────────────────────────────

    static final Pattern USE_HEAD = PyRe.compile("「건축법\\s*시행령」\\s*별표\\s*1?\\s*제\\d+호(?:의\\d+)?(?:[가-힣]목)?의\\s*");
    private static final Pattern TRAILING_NOTE = PyRe.compile("\\s*<[^>]*>\\s*$");
    private static final Pattern ITEM_MARK = PyRe.compile("^\\s*(?:\\d+(?:의\\d+)?|[가-힣](?:의\\d+)?)\\.\\s*");
    private static final Pattern OPEN_BRACKET = PyRe.compile("[\\(\\[（［]");
    private static final Pattern AS_CLAUSE = PyRe.compile("(?:으로서|로서)\\s.*$");
    private static final Pattern DIGITS = PyRe.compile("(\\d+)");

    /** use_entry 결과: (근거 인용, 용도명, 조건 메모). */
    record UseEntry(String quote, String name, String note) {
    }

    /**
     * 목·호 한 줄 → (근거 인용, 용도명, 조건 메모). 용도명은 인용 안에 그대로 있어야 한다.
     *
     * <p>위치·길이는 파이썬과 같은 코드포인트 단위로 계산한다 ({@code body[:300]} 같은 고정 길이 자르기가 있다).
     */
    static UseEntry useEntry(String text) {
        String first = Py.strip(PyRe.sub(TRAILING_NOTE, "", firstLine(text)));
        String body = PyRe.sub(ITEM_MARK, "", first);
        Matcher m = USE_HEAD.matcher(body);
        String phrase;
        int quoteStart;
        if (m.find()) {
            phrase = body.substring(m.end());
            quoteStart = Py.cpIndex(body, m.start());
        } else if (body.contains("별표 1 제") && body.contains("「건축법")) {
            // '…별표 1 제21호마목부터 사목까지의 규정에 따른 시설 …' 처럼 호 번호 뒤에 바로 용도명이 오지 않는 경우
            int k = Py.find(body, "별표 1 ") + Py.len("별표 1 ");
            phrase = Py.slice(body, k, null);
            quoteStart = Py.find(body, "「건축법");
        } else {
            int k = rfind(body, "의 ");
            if (k < 0) {
                return null;
            }
            phrase = Py.slice(body, k + 2, null);
            // 예: '제33조제9호 및 제9호의2의 공장', '별표 4 제2호차목 및 카목의 공장' → 다른 조문이 범위를 정하므로 그 문구를 조건으로 남긴다
            String name0 = Py.strip(beforeFirst(OPEN_BRACKET, phrase));
            return new UseEntry(Py.slice(body, null, k + 2 + Py.len(name0)), name0, Py.slice(body, null, 300));
        }
        phrase = Py.strip(phrase);
        String name = Py.strip(beforeFirst(OPEN_BRACKET, phrase));
        name = Py.strip(PyRe.sub(AS_CLAUSE, "", name));
        if (Py.len(name) > 40) {
            name = Py.strip(LegalPy.split(name, " 중 ").getFirst());
        }
        if (name.isEmpty()) {
            return null;
        }
        int end = Py.find(body, name, quoteStart) + Py.len(name);
        String quote = Py.slice(body, quoteStart, end);
        String note = !phrase.equals(name) ? phrase : null;
        return new UseEntry(quote, name, truthy(note) ? Py.slice(note, null, 300) : null);
    }

    static ObjectNode useRulesFromAnnex(Map<String, String> paths, String annex, String zone) {
        ArrayNode values = Json.array();
        ArrayNode exc = Json.array();
        String head1 = firstLine(paths.getOrDefault(annex + "/item1", ""));
        Pattern subPath = fullPattern(annex, "/item(\\d)/sub(\\d+)(?:-\\d+)?");
        for (Map.Entry<String, String> e : sortedNatural(paths)) {
            String path = e.getKey();
            Matcher m = subPath.matcher(path);
            if (!m.matches()) {
                continue;
            }
            UseEntry u = useEntry(e.getValue());
            if (u == null) {
                continue;
            }
            String sec = m.group(1);
            ObjectNode v = Json.object();
            v.put("value_key", "use:" + sec + "." + m.group(2));
            v.put("enum_value", u.name());
            v.set("evidence", evidence("@" + path, u.quote()));
            if ("1".equals(sec)) {
                v.put("value_type", "enum_allowed");
                putStrOrNull(v, "condition_note", u.note());
            } else {
                v.put("value_type", "enum_conditional");
                v.put("condition_note", Py.slice("도시·군계획조례가 정하는 바에 따라 건축할 수 있는 건축물"
                        + (truthy(u.note()) ? " — " + u.note() : ""), null, 300));
            }
            values.add(v);
        }
        String q = exceptionQuote(head1);
        if (truthy(q)) {
            exc.add(evidence("@" + annex + "/item1", q));
        }
        ObjectNode rule = Json.object();
        rule.put("rule_key", "USE.ZONE." + zoneCode(zone));
        rule.put("category", "ZONING_USE");
        rule.put("title", zone + " 안에서 건축할 수 있는 건축물 (영 " + annex.replace("annex", "별표 ") + ")");
        rule.put("automation_level", "auto");
        rule.set("conditions", zoningCondition(zone));
        rule.set("values", values);
        rule.put("confidence", "high");
        if (!exc.isEmpty()) {
            rule.set("exceptions_mentioned", exc);
        }
        return rule;
    }

    static ObjectNode useRulesFromOrdinance(Map<String, String> paths, String base, String zone) {
        ArrayNode values = Json.array();
        Pattern itemPath = fullPattern(base, "/item(\\d+)(?:-(\\d+))?");
        for (Map.Entry<String, String> e : sortedNatural(paths)) {
            String path = e.getKey();
            Matcher m = itemPath.matcher(path);
            if (!m.matches()) {
                continue;
            }
            UseEntry u = useEntry(e.getValue());
            if (u == null) {
                continue;
            }
            ObjectNode v = Json.object();
            v.put("value_key", "use:" + m.group(1) + (truthy(m.group(2)) ? "_" + m.group(2) : ""));
            v.put("value_type", "enum_allowed");
            v.put("enum_value", u.name());
            putStrOrNull(v, "condition_note", u.note());
            v.set("evidence", evidence("@" + path, u.quote()));
            values.add(v);
        }
        ArrayNode exc = Json.array();
        for (JsonNode v : values.values()) {
            String path = Py.slice(v.get("evidence").get("provision_id").stringValue(), 1, null);
            String q = exceptionQuote(require(paths, path));
            if (truthy(q)) { // 호 안의 목에 단서('다만, …')가 있으면 규칙 단서로 기록 → 엔진이 조건부로 처리
                exc.add(evidence("@" + path, q));
            }
        }
        ObjectNode rule = Json.object();
        rule.put("rule_key", "USE.ZONE." + zoneCode(zone));
        rule.put("category", "ZONING_USE");
        rule.put("title", zone + " 안에서 건축할 수 있는 건축물 (조례)");
        rule.put("automation_level", "auto");
        rule.set("conditions", zoningCondition(zone));
        rule.set("values", values);
        rule.put("confidence", "high");
        if (!exc.isEmpty()) {
            rule.set("exceptions_mentioned", exc);
        }
        return rule;
    }

    // ── 주차구획·차로 폭 ──────────────────────────────────────

    public static final Map<String, String> STALL_TYPES = ordered(
            "경형", "compact", "일반형", "general", "확장형", "extended", "장애인전용", "disabled", "이륜자동차전용", "two_wheel",
            "이륜자동차 전용", "two_wheel", "보도와 차도의 구분이 없는 주거지역의 도로", "residential_road");
    public static final Map<String, String> AISLE_TYPES = ordered(
            "평행주차", "parallel_0", "직각주차", "perpendicular_90", "60도 대향주차", "angle_60", "45도 대향주차", "angle_45",
            "교차주차", "cross");
    private static final Pattern NUM_PREFIX = PyRe.compile("\\d+(?:\\.\\d+)?");

    static double numOf(String cell) {
        Matcher m = NUM_PREFIX.matcher(cell);
        if (!m.lookingAt()) {
            // 파이썬: re.match(...) 가 None → AttributeError
            throw new IllegalStateException("AttributeError: 'NoneType' object has no attribute 'group' (cell=" + cell + ")");
        }
        return PyObj.parseFloat(m.group(0));
    }

    static List<ObjectNode> parkingRules(Map<String, String> suppPaths, Map<String, String> rulePaths) {
        List<ObjectNode> rules = new ArrayList<>();
        String[][] forms = {
                {"parallel", "art3/para1/item1/tbl24335428", "주차단위구획 (평행주차형식)"},
                {"non_parallel", "art3/para1/item2/tbl33758907", "주차단위구획 (평행주차형식 외)"}};
        for (String[] f : forms) {
            String form = f[0];
            String tbl = f[1];
            String title = f[2];
            ArrayNode values = Json.array();
            for (Map.Entry<String, String> e : sortedByKey(suppPaths)) {
                String path = e.getKey();
                String text = e.getValue();
                if (!path.startsWith(tbl + "/row")) {
                    continue;
                }
                List<String> cells = LegalPy.split(text, " | ");
                String typ = require(STALL_TYPES, cells.get(0));
                String[][] dims = {{"width", cells.get(1)}, {"length", cells.get(2)}};
                for (String[] d : dims) {
                    ObjectNode v = Json.object();
                    v.put("value_key", form + "." + typ + "." + d[0]);
                    v.put("value_type", "min");
                    v.put("num_min", numOf(d[1]));
                    v.put("unit", "m");
                    v.putNull("condition_note");
                    v.set("evidence", evidence("@" + path, text));
                    values.add(v);
                }
            }
            ObjectNode rule = Json.object();
            rule.put("rule_key", "PARK.STALL." + form.toUpperCase(Locale.ROOT));
            rule.put("category", "PARKING_STALL_SIZE");
            rule.put("title", title);
            rule.put("automation_level", "auto");
            rule.set("conditions", Json.array());
            rule.set("values", values);
            rule.put("confidence", "high");
            rules.add(rule);
        }
        ArrayNode values = Json.array();
        for (Map.Entry<String, String> e : sortedByKey(suppPaths)) {
            String path = e.getKey();
            String text = e.getValue();
            if (!path.startsWith("art6/para1/item3/sub2/tbl24335458/row")) {
                continue;
            }
            List<String> cells = LegalPy.split(text, " | ");
            String ang = require(AISLE_TYPES, cells.get(0));
            values.add(minValue(ang + ".two_plus_gates", numOf(cells.get(1)), "@" + path, text));
            values.add(minValue(ang + ".one_gate", numOf(cells.get(2)), "@" + path, text));
        }
        String gate = require(rulePaths, "art6/para1/item4");
        String q1 = "노외주차장의 출입구 너비는 3.5미터 이상";
        String q2 = "주차대수 규모가 50대 이상인 경우에는 출구와 입구를 분리하거나 너비 5.5미터 이상의 출입구를 설치";
        if (!(gate.contains(q1) && gate.contains(q2))) {
            throw new AssertionError("제6조①4 원문이 예상과 다름 — 다시 확인");
        }
        values.add(minValue("gate.width_min", 3.5, "@art6/para1/item4", q1));
        ObjectNode g2 = Json.object();
        g2.put("value_key", "gate.width_50plus");
        g2.put("value_type", "min");
        g2.put("num_min", 5.5);
        g2.put("unit", "m");
        g2.put("condition_note", "주차대수 50대 이상: 출구·입구 분리 또는 너비 5.5미터 이상 출입구");
        g2.set("evidence", evidence("@art6/para1/item4", q2));
        values.add(g2);
        ObjectNode rule = Json.object();
        rule.put("rule_key", "PARK.AISLE.OFFSTREET");
        rule.put("category", "DRIVE_AISLE_WIDTH");
        rule.put("title", "노외주차장 차로 너비·출입구 너비");
        rule.put("automation_level", "auto");
        rule.set("conditions", Json.array());
        rule.set("values", values);
        rule.put("confidence", "high");
        rules.add(rule);
        return rules;
    }

    // ── 시드 파일 ─────────────────────────────────────────────

    static ObjectNode seed(String docKey, String effectiveFrom, List<ObjectNode> rules, String note) {
        ObjectNode o = Json.object();
        o.put("format", "app-seed-extraction@1");
        o.put("document_key", docKey);
        o.put("effective_from", effectiveFrom);
        o.put("model", MODEL);
        o.put("prompt_version", "seed-script@v1");
        o.put("note", note);
        ObjectNode ex = o.putObject("extraction");
        ex.put("schema_version", "rule_extraction@1.0");
        ex.put("document_version_id", "@version");
        ArrayNode rs = ex.putArray("rules");
        rules.forEach(rs::add);
        ex.putArray("delegations");
        ex.putArray("unresolved");
        return o;
    }

    /** 명령행 진입점: 데이터 폴더는 ./data. */
    public static void main(String[] args) {
        Cli.ensureUtf8Stdio();
        System.exit(run(args, Path.of("data")));
    }

    /**
     * 파이썬 {@code main()}. 인자는 쓰지 않는다 (파이썬판도 sys.argv 를 읽지 않는다).
     *
     * @param dataDir 데이터 폴더 (입력 {@code legal_sources/}, 출력 {@code seed/extractions/})
     * @return 종료 코드 (0)
     */
    public static int run(String[] args, Path dataDir) {
        return run(args, dataDir, System.out);
    }

    /** {@link #run(String[], Path)} — 콘솔 출력 대상 지정 (시험용). */
    public static int run(String[] args, Path dataDir, PrintStream out) {
        Path src = dataDir.resolve("legal_sources");
        Path outDir = dataDir.resolve("seed").resolve("extractions");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> decree = provisionsOf(src, "kr.decree.nkpa__2026-09-18.json");
        Map<String, String> ordin = provisionsOf(src, "daegu.ord.urban_planning__2026-05-11.json");
        Map<String, String> prule = provisionsOf(src, "kr.rule.parking__2026-07-27.json");
        Map<String, String> annex = supplementProvisions(src, "kr.decree.nkpa__2026-09-18__annexes.json");
        Map<String, String> tables = supplementProvisions(src, "kr.rule.parking__2026-07-27__tables.json");

        Map<String, ObjectNode> files = new LinkedHashMap<>();
        files.put("01_kr.decree.nkpa__ratios.json", seed("kr.decree.nkpa", "2026-09-18",
                concat(ratioRules(decree, "art84/para1", "BUILDING_COVERAGE", true),
                        ratioRules(decree, "art85/para1", "FLOOR_AREA_RATIO", true)),
                "시행령 제84조①·제85조① 각 호 (용도지역별 건폐율 상한·용적률 범위)"));
        files.put("02_kr.decree.nkpa__uses.json", seed("kr.decree.nkpa", "2026-09-18",
                List.of(useRulesFromAnnex(annex, "annex4", "제1종일반주거지역"), useRulesFromAnnex(annex, "annex5", "제2종일반주거지역"),
                        useRulesFromAnnex(annex, "annex6", "제3종일반주거지역"), useRulesFromAnnex(annex, "annex17", "자연녹지지역")),
                "시행령 별표 4·5·6·17 (공식 PDF 텍스트) 제1호=건축할 수 있는 건축물, 제2호=조례로 정하는 건축물"));
        files.put("03_kr.rule.parking__dimensions.json", seed("kr.rule.parking", "2026-07-27", parkingRules(tables, prule),
                "주차장법 시행규칙 제3조①(표 이미지 전사본), 제6조①3나2)(표 이미지 전사본), 제6조①4. 전사본은 사람 대조 후 승인"));
        files.put("10_daegu.ord.urban_planning__ratios.json", seed("daegu.ord.urban_planning", "2026-05-11",
                concat(ratioRules(ordin, "art75", "BUILDING_COVERAGE", false),
                        ratioRules(ordin, "art80/para1", "FLOOR_AREA_RATIO", false)),
                "대구광역시 도시계획 조례 제75조·제80조① 각 호"));
        files.put("11_daegu.ord.urban_planning__uses.json", seed("daegu.ord.urban_planning", "2026-05-11",
                List.of(useRulesFromOrdinance(ordin, "art33", "제1종일반주거지역"),
                        useRulesFromOrdinance(ordin, "art34/para3", "제2종일반주거지역"),
                        useRulesFromOrdinance(ordin, "art35", "제3종일반주거지역"), useRulesFromOrdinance(ordin, "art46", "자연녹지지역")),
                "대구광역시 도시계획 조례 제33조·제34조③·제35조·제46조 (영 별표 제2호 위임분)"));
        for (Map.Entry<String, ObjectNode> e : files.entrySet()) {
            String name = e.getKey();
            ObjectNode data = e.getValue();
            try {
                // json.dumps(data, ensure_ascii=False, indent=2) — 끝 줄바꿈 없음, 줄끝 \n
                Files.writeString(outDir.resolve(name), PyJson.dumps(data, false, false, 2), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            JsonNode rules = data.get("extraction").get("rules");
            int nValues = 0;
            for (JsonNode r : rules.values()) {
                nValues += r.get("values").size();
            }
            out.println(name + ": 규칙 " + rules.size() + "개, 값 " + nValues + "개");
        }
        return 0;
    }

    // ── 도우미 ────────────────────────────────────────────────

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static JsonNode readJson(Path p) {
        try {
            return Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(JsonNode o, String field) {
        JsonNode v = o.get(field);
        if (v == null) {
            throw new NoSuchElementException("KeyError: " + LegalPy.reprString(field));
        }
        return v.stringValue();
    }

    /** 파이썬 {@code d[key]} (없으면 KeyError). */
    private static String require(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) {
            throw new NoSuchElementException("KeyError: " + LegalPy.reprString(key));
        }
        return v;
    }

    private static String zoneCode(String zone) {
        return require(ZONE_CODES, zone);
    }

    /** 파이썬 {@code s.split("\n")[0]}. */
    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** 파이썬 {@code re.split(p, s)[0]}. */
    private static String beforeFirst(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? s.substring(0, m.start()) : s;
    }

    /** 파이썬 {@code s.rfind(sub)} (코드포인트 위치). */
    private static int rfind(String s, String sub) {
        int i = s.lastIndexOf(sub);
        return i < 0 ? -1 : Py.cpIndex(s, i);
    }

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /** 파이썬 {@code re.fullmatch(re.escape(literal) + pythonPattern, …)} 용 패턴. */
    private static Pattern fullPattern(String literal, String pythonPattern) {
        return Pattern.compile(Pattern.quote(literal) + PyRe.translate(pythonPattern),
                Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    private static ObjectNode evidence(String provisionId, String quote) {
        ObjectNode o = Json.object();
        o.put("provision_id", provisionId);
        o.put("quote", quote);
        return o;
    }

    private static ArrayNode zoningCondition(String zone) {
        ObjectNode c = Json.object();
        c.put("field", "zoning");
        c.put("operator", "eq");
        c.put("value", zone);
        return Json.array().add(c);
    }

    private static ObjectNode minValue(String key, double num, String provisionId, String quote) {
        ObjectNode v = Json.object();
        v.put("value_key", key);
        v.put("value_type", "min");
        v.put("num_min", num);
        v.put("unit", "m");
        v.set("evidence", evidence(provisionId, quote));
        return v;
    }

    private static void putStrOrNull(ObjectNode o, String field, String v) {
        if (v == null) {
            o.putNull(field);
        } else {
            o.put(field, v);
        }
    }

    private static List<ObjectNode> concat(List<ObjectNode> a, List<ObjectNode> b) {
        List<ObjectNode> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** 파이썬 {@code sorted(d.items())} — 키(코드포인트 순)로 정렬. */
    private static List<Map.Entry<String, String>> sortedByKey(Map<String, String> m) {
        List<Map.Entry<String, String>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> PyJson.compareCodePoints(a.getKey(), b.getKey()));
        return l;
    }

    /**
     * 파이썬 {@code sorted(d.items(), key=lambda kv: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", kv[0])])}
     * — 숫자 부분은 수로 비교하는 자연 정렬 (안정 정렬).
     */
    private static List<Map.Entry<String, String>> sortedNatural(Map<String, String> m) {
        List<Map.Entry<String, String>> l = new ArrayList<>(m.entrySet());
        Map<String, List<Object>> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : l) {
            keys.put(e.getKey(), naturalKey(e.getKey()));
        }
        l.sort(Comparator.comparing(e -> keys.get(e.getKey()), BuildSeedExtractions::compareKeys));
        return l;
    }

    private static List<Object> naturalKey(String s) {
        List<Object> out = new ArrayList<>();
        Matcher m = DIGITS.matcher(s);
        int last = 0;
        while (m.find()) {
            out.add(s.substring(last, m.start()));
            out.add(m.group(1));
            last = m.end();
        }
        out.add(s.substring(last));
        out.replaceAll(x -> LegalPy.isDigit((String) x) ? LegalPy.pyInt((String) x) : x);
        return out;
    }

    /** 파이썬 리스트 비교 (원소는 str 또는 int). */
    private static int compareKeys(List<Object> a, List<Object> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            if (x.equals(y)) {
                continue;
            }
            if (x instanceof BigInteger bx && y instanceof BigInteger by) {
                return bx.compareTo(by);
            }
            if (x instanceof String sx && y instanceof String sy) {
                return PyJson.compareCodePoints(sx, sy);
            }
            throw new IllegalArgumentException("TypeError: '<' not supported between instances of 'int' and 'str'");
        }
        return Integer.compare(a.size(), b.size());
    }
}
