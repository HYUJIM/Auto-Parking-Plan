package com.autoparkingplan.server.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Py;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleCondition;

import tools.jackson.databind.JsonNode;

/** 검토 엔진 보조 로직: 명칭 정규화, 조건 평가, 계산식 평가, 용도 묶음 (파이썬 app.review.rules_logic). */
public final class RulesLogic {
    private RulesLogic() {
    }

    /** rule_key 용 약어 (rule-extract 프롬프트와 동일). */
    public static final Map<String, String> ZONE_CODES = orderedMap(
            "제1종전용주거지역", "R1E", "제2종전용주거지역", "R2E", "제1종일반주거지역", "R1G", "제2종일반주거지역", "R2G",
            "제3종일반주거지역", "R3G", "준주거지역", "RQ", "중심상업지역", "CC", "일반상업지역", "CG", "근린상업지역", "CN",
            "유통상업지역", "CD", "전용공업지역", "IE", "일반공업지역", "IG", "준공업지역", "IQ", "보전녹지지역", "GC",
            "생산녹지지역", "GP", "자연녹지지역", "GN");

    /** 화면 묶음 (기능정의서 10장: 주거 / 근린생활 / 업무 / 기타). */
    private static final List<Map.Entry<String, Pattern>> USE_GROUPS = List.of(
            Map.entry("주거", Pattern.compile("단독주택|공동주택|다가구|다세대|연립|아파트|기숙사")),
            Map.entry("근린생활", Pattern.compile("근린생활시설")),
            Map.entry("업무", Pattern.compile("업무시설")));

    public static final Pattern L4_ESCALATE_RE = Pattern.compile(
            "지구단위계획|특별계획구역|연구개발특구|경제자유구역|산업단지|문화(?:유산|재)보호구역|역사문화환경\\s*보존지역|개발제한구역",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PAREN_SPLIT = Pattern.compile("[\\(\\[（［]");

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 파이썬 {@code str(s or "")} 를 하고 공백을 모두 지운다. */
    public static String norm(Object s) {
        return WS.matcher(pyStr(s)).replaceAll("");
    }

    /** 파이썬 {@code str(x or "")}: 거짓 값(None, "", 0, 빈 목록)은 "". */
    static String pyStr(Object s) {
        if (s instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) {
                return "";
            }
            if (n.isString()) {
                return n.stringValue();
            }
            if (n.isIntegralNumber()) {
                return n.bigIntegerValue().signum() == 0 ? "" : n.bigIntegerValue().toString();
            }
            if (n.isNumber()) {
                return n.doubleValue() == 0.0 ? "" : Py.floatRepr(n.doubleValue());
            }
            if (n.isBoolean()) {
                return n.booleanValue() ? "True" : "";
            }
            return n.isEmpty() ? "" : n.toString();
        }
        if (s == null || Boolean.FALSE.equals(s)) {
            return "";
        }
        if (s instanceof Boolean) {
            return "True";
        }
        if (s instanceof Double d) {
            return d == 0.0 ? "" : Py.floatRepr(d);
        }
        if (s instanceof Number num) {
            return num.longValue() == 0 ? "" : num.toString();
        }
        return s.toString();
    }

    /** '자동차관련시설 중 동호아목에 해당하는 것과 주차장 및 세차장' → '자동차관련시설', '공동주택(아파트를 제외한다)' → '공동주택'. */
    public static String baseUseName(String useName) {
        String b = PAREN_SPLIT.split(useName, -1)[0];
        return norm(b.split(" 중 ", -1)[0].split("로서", -1)[0]);
    }

    public static String useGroup(String useName) {
        for (Map.Entry<String, Pattern> e : USE_GROUPS) {
            if (e.getValue().matcher(useName).find()) {
                return e.getKey();
            }
        }
        return "기타";
    }

    // ── 조건 평가 ─────────────────────────────────────────────

    /**
     * 조건 하나. TRUE/FALSE, 입력이 없어 판단 불가하면 null.
     *
     * @param inputs 필드 이름 → 값 (String, Number, Boolean, 또는 문자열 목록)
     */
    public static Boolean evalCondition(RuleCondition c, Map<String, ?> inputs) {
        Object val = inputs.get(c.getField());
        if (val == null || (val instanceof Collection<?> col && col.isEmpty())) {
            return "exists".equals(c.getOperator()) ? Boolean.FALSE : null;
        }
        JsonNode target = c.getValueJson();
        String op = c.getOperator();
        if (op.equals("exists")) {
            return truthy(val);
        }
        if (val instanceof Collection<?> vals0) { // district/zone 처럼 여러 값
            Set<String> vals = new HashSet<>();
            for (Object v : vals0) {
                vals.add(norm(v));
            }
            if (op.equals("eq")) {
                return vals.contains(norm(target));
            }
            if (op.equals("in")) {
                for (JsonNode t : target.values()) {
                    if (vals.contains(norm(t))) {
                        return true;
                    }
                }
                return false;
            }
            return null;
        }
        if (op.equals("eq")) {
            if (target.isString()) {
                return norm(val).equals(norm(target));
            }
            return pyFloat(val) == pyFloat(target);
        }
        if (op.equals("in")) {
            String nv = norm(val);
            for (JsonNode t : target.values()) {
                if (nv.equals(norm(t))) {
                    return true;
                }
            }
            return false;
        }
        double x;
        try {
            x = pyFloat(val);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return switch (op) {
            case "gte" -> x >= pyFloat(target);
            case "lte" -> x <= pyFloat(target);
            case "between" -> pyFloat(target.get(0)) <= x && x <= pyFloat(target.get(1));
            default -> null;
        };
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    /** 파이썬 {@code float(x)}: 숫자·숫자 문자열(앞뒤 공백 허용). 변환 불가면 IllegalArgumentException. */
    static double pyFloat(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNumber()) {
                return n.doubleValue();
            }
            if (n.isBoolean()) {
                return n.booleanValue() ? 1.0 : 0.0;
            }
            if (n.isString()) {
                return pyFloat(n.stringValue());
            }
            throw new IllegalArgumentException("float() argument must be a string or a real number");
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            String t = Py.strip(s).replace("_", "");
            try {
                String low = t.toLowerCase(Locale.ROOT);
                if (low.equals("inf") || low.equals("+inf") || low.equals("infinity") || low.equals("+infinity")) {
                    return Double.POSITIVE_INFINITY;
                }
                if (low.equals("-inf") || low.equals("-infinity")) {
                    return Double.NEGATIVE_INFINITY;
                }
                if (low.equals("nan") || low.equals("+nan") || low.equals("-nan")) {
                    return Double.NaN;
                }
                if (t.isEmpty() || !t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
                    throw new NumberFormatException();
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
            }
        }
        throw new IllegalArgumentException("float() argument must be a string or a real number");
    }

    /** 모든 조건(AND). 하나라도 FALSE 면 FALSE, 판단 불가가 있으면 null. */
    public static Boolean matchRule(Rule rule, Map<String, ?> inputs) {
        boolean unknown = false;
        for (RuleCondition c : rule.getConditions()) {
            Boolean r = evalCondition(c, inputs);
            if (Boolean.FALSE.equals(r)) {
                return false;
            }
            if (r == null) {
                unknown = true;
            }
        }
        return unknown ? null : Boolean.TRUE;
    }

    // ── 계산식 ────────────────────────────────────────────────

    private static final Set<String> ALLOWED_NAMES = Set.of("gross_floor_area", "facility_area", "households", "site_area");
    private static final Set<String> ALLOWED_FUNCS = Set.of("ceil", "floor", "min", "max");

    /**
     * 규칙 계산식 DSL (rule_extraction.schema 의 formula) 안전 평가. 허용: 변수 4개, + - * /, 단항 -, ceil floor min max.
     *
     * <p>파이썬판은 ast.parse 후 화이트리스트 노드만 계산한다 (AI 가 추출한 식에 코드가 섞여도 실행되지 않음).
     * 여기서는 같은 문법 범위만 받아들이는 작은 파서로 계산하고, 그 밖의 모든 것은 IllegalArgumentException(=ValueError).
     * 파이썬과 같은 지점에서 실패하도록 0 나누기, 무한대의 ceil/floor, 인자 개수 오류는 계산 도중에 바로 실패한다.
     */
    public static double evalFormula(String expr, Map<String, Double> variables) {
        if (expr == null || Py.len(expr) > 500) {
            throw new IllegalArgumentException("계산식이 너무 깁니다");
        }
        FormulaParser p = new FormulaParser(expr, variables);
        double out = p.parse();
        if (!Double.isFinite(out)) {
            throw new IllegalArgumentException("계산 결과가 유한한 수가 아닙니다");
        }
        return out;
    }

    private static final class FormulaParser {
        private final String s;
        private final Map<String, Double> vars;
        private int i;
        private int depth;

        FormulaParser(String s, Map<String, Double> vars) {
            this.s = s;
            this.vars = vars;
        }

        double parse() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
            double v = expr();
            skipWs();
            if (i < s.length()) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
            return v;
        }

        private void enter() {
            if (++depth > 200) { // 파이썬 파서의 중첩 한도처럼 너무 깊은 식은 거부
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
        }

        private double expr() {
            enter();
            double v = term();
            while (true) {
                skipWs();
                if (peek('+')) {
                    i++;
                    v = v + term();
                } else if (peek('-')) {
                    i++;
                    v = v - term();
                } else {
                    break;
                }
            }
            depth--;
            return v;
        }

        private double term() {
            double v = unary();
            while (true) {
                skipWs();
                if (peek('*')) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '*') {
                        throw new IllegalArgumentException("허용되지 않은 식: Pow");
                    }
                    i++;
                    v = v * unary();
                } else if (peek('/')) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '/') {
                        throw new IllegalArgumentException("허용되지 않은 식: FloorDiv");
                    }
                    i++;
                    double b = unary();
                    if (b == 0.0) {
                        throw new IllegalArgumentException("계산할 수 없는 식: ZeroDivisionError");
                    }
                    v = v / b;
                } else if (peek('%') || peek('@')) {
                    throw new IllegalArgumentException("허용되지 않은 식");
                } else {
                    break;
                }
            }
            return v;
        }

        private double unary() {
            skipWs();
            if (peek('-')) {
                i++;
                enter();
                double v = -unary();
                depth--;
                return v;
            }
            if (peek('+') || peek('~')) {
                throw new IllegalArgumentException("허용되지 않은 식: UnaryOp");
            }
            double v = atom();
            skipWs();
            if (peek('*') && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                throw new IllegalArgumentException("허용되지 않은 식: Pow");
            }
            return v;
        }

        private double atom() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                double v = expr();
                skipWs();
                expect(')');
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                return number();
            }
            if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String name = s.substring(st, i);
                skipWs();
                if (peek('(')) {
                    return call(name);
                }
                if (peek('.') || peek('[')) {
                    throw new IllegalArgumentException("허용되지 않은 식: " + name);
                }
                if (name.equals("True")) {
                    return 1.0;
                }
                if (name.equals("False")) {
                    return 0.0;
                }
                if (!ALLOWED_NAMES.contains(name)) {
                    throw new IllegalArgumentException("허용되지 않은 식: Name(" + name + ")");
                }
                Double v = vars.get(name);
                if (v == null) {
                    throw new IllegalArgumentException("변수 " + name + " 값이 없음");
                }
                return v;
            }
            throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
        }

        private double call(String name) {
            if (!ALLOWED_FUNCS.contains(name)) {
                throw new IllegalArgumentException("허용되지 않은 식: Call(" + name + ")");
            }
            expect('(');
            List<Double> args = new ArrayList<>();
            skipWs();
            if (!peek(')')) {
                while (true) {
                    skipWs();
                    if (peek('*')) {
                        throw new IllegalArgumentException("허용되지 않은 식: Starred");
                    }
                    int save = i;
                    // 키워드 인자 (name=...) 는 허용하지 않는다
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                    skipWs();
                    if (peek('=') && !(i + 1 < s.length() && s.charAt(i + 1) == '=')) {
                        throw new IllegalArgumentException("허용되지 않은 식: keyword");
                    }
                    i = save;
                    args.add(expr());
                    skipWs();
                    if (peek(',')) {
                        i++;
                        skipWs();
                        if (peek(')')) {
                            break; // 끝의 쉼표 허용 (파이썬과 같음)
                        }
                        continue;
                    }
                    break;
                }
            }
            skipWs();
            expect(')');
            return switch (name) {
                case "ceil", "floor" -> {
                    if (args.size() != 1) {
                        throw new IllegalArgumentException("계산할 수 없는 식: TypeError");
                    }
                    double a = args.getFirst();
                    if (Double.isNaN(a)) {
                        throw new IllegalArgumentException("계산할 수 없는 식: ValueError");
                    }
                    if (Double.isInfinite(a)) {
                        throw new IllegalArgumentException("계산할 수 없는 식: OverflowError");
                    }
                    yield name.equals("ceil") ? Math.ceil(a) : Math.floor(a);
                }
                default -> {
                    if (args.size() < 2) { // min(x) 는 'float' object is not iterable, min() 은 인자 없음
                        throw new IllegalArgumentException("계산할 수 없는 식: TypeError");
                    }
                    double best = args.getFirst();
                    for (double a : args.subList(1, args.size())) {
                        // 파이썬 min/max 는 앞의 값과 비교해 '더 작을/클 때만' 바꾼다 (NaN 처리 순서 포함)
                        if (name.equals("min") ? a < best : a > best) {
                            best = a;
                        }
                    }
                    yield best;
                }
            };
        }

        private double number() {
            int st = i;
            if (s.startsWith("0x", i) || s.startsWith("0X", i) || s.startsWith("0o", i) || s.startsWith("0O", i) || s.startsWith("0b", i)
                    || s.startsWith("0B", i)) {
                char kind = Character.toLowerCase(s.charAt(i + 1));
                i += 2;
                int ds = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String digits = s.substring(ds, i).replace("_", "");
                int radix = kind == 'x' ? 16 : kind == 'o' ? 8 : 2;
                try {
                    return new java.math.BigInteger(digits, radix).doubleValue();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
                }
            }
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isFloat = true;
                i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isFloat = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                int es = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (es == i) {
                    throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
                }
            }
            if (i < s.length() && (s.charAt(i) == 'j' || s.charAt(i) == 'J' || Character.isLetter(s.charAt(i)))) {
                throw new IllegalArgumentException("허용되지 않은 식: Constant");
            }
            String lit = s.substring(st, i);
            if (lit.equals(".") || lit.endsWith("_") || lit.contains("__")) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
            String clean = lit.replace("_", "");
            if (!isFloat && clean.length() > 1 && clean.startsWith("0") && !clean.matches("0+")) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다"); // 파이썬 3 은 07 같은 표기를 거부
            }
            return isFloat ? Double.parseDouble(clean) : new BigDecimal(clean).doubleValue();
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) {
                throw new IllegalArgumentException("계산식을 해석할 수 없습니다");
            }
            i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r') {
                    i++;
                } else if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '\n' || s.charAt(i + 1) == '\r')) {
                    i += 2; // 줄 잇기
                } else if (c == '#') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else {
                    break;
                }
            }
        }
    }

    /** 숫자 표시: 정수면 천 단위 쉼표, 아니면 소수 둘째 자리까지 (끝의 0 제거). null 이면 "?". */
    public static String fmtNum(Double x) {
        if (x == null) {
            return "?";
        }
        if (x == Math.rint(x) && Double.isFinite(x)) {
            return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(new BigDecimal(x));
        }
        BigDecimal r = new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN);
        String s = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(r);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
