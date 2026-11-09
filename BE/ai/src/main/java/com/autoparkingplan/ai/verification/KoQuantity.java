package com.autoparkingplan.ai.verification;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.StringNode;

/**
 * 한국어 법규 문장의 '세부 사실' 파서 — 원문과 AI 답변에 똑같이 적용한다 ({@code app/verification/ko_quantity.py}).
 *
 * <p>같은 파서로 양쪽을 읽어 정규화된 값(canonical)끼리 비교하므로
 * '1년' ↔ '12개월'은 같다고 보고, '1년' ↔ '8개월', '이하' ↔ '미만'은 다르다고 판단한다.
 *
 * <p>추출 대상
 * <ul>
 *   <li>기간(PERIOD): 1년, 1년 6개월, 6개월, 한 달, 반년, 30일, 2주 → 개월 또는 일 단위</li>
 *   <li>수량(QUANTITY): 60%, 60퍼센트, 300㎡, 300제곱미터, 6m, 2.5미터, 12대, 5층, 30세대</li>
 *   <li>날짜(DATE): 2026-11-12, 2026년 11월 12일, 2026. 11. 12.</li>
 *   <li>비교어(COMPARATOR): 숫자 바로 뒤의 이하/미만/이상/초과/이내/까지/넘지 않는</li>
 *   <li>법령 참조(REFERENCE): 「…」, 제77조제1항제2호, 별표 1</li>
 *   <li>단위 없는 숫자(BARE): 위 어디에도 해당하지 않는 숫자 → 근거 없으면 날조로 간주</li>
 * </ul>
 *
 * <p>정규식은 파이썬 원문 그대로이며 {@link PyRe} 로 파이썬과 같은 뜻(유니코드 {@code \d}·{@code \s})으로 컴파일한다.
 * 위치({@link Q#start()}·{@link Q#end()})는 파이썬과 같은 코드포인트 단위다.
 */
public final class KoQuantity {
    private KoQuantity() {
    }

    /** 파이썬 re.escape 가 이스케이프하는 글자 (3.7+). 상수식이라 초기화 순서와 무관. */
    private static final String RE_SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\f" + (char) 0x0B;

    // ── 숫자 표기 ────────────────────────────────────────────────
    public static final Map<String, Integer> SINO = ordered(
            "일", 1, "이", 2, "삼", 3, "사", 4, "오", 5, "육", 6, "칠", 7, "팔", 8, "구", 9, "십", 10);
    public static final Map<String, Integer> NATIVE = ordered(
            "한", 1, "두", 2, "세", 3, "네", 4, "다섯", 5, "여섯", 6, "일곱", 7, "여덟", 8, "아홉", 9, "열", 10,
            "열한", 11, "열두", 12);
    /**
     * 법령 표기 "1천500퍼센트"(=1,500%) 같은 천 단위 혼용 숫자를 먼저 잡는다 (용적률 별표 등).
     * "1천500", "1천 300"(=1,300), "1천"(=1,000), "2천제곱미터" 를 하나의 수로 읽는다.
     */
    public static final String NUM = "\\d+천\\s?\\d{3}(?!\\d)|\\d+천(?!\\s?\\d)|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?";
    public static final String NATIVE_ALT = String.join("|", sortedByLenDesc(new ArrayList<>(NATIVE.keySet())));
    public static final String SINO_ALT = String.join("|", SINO.keySet());

    /** (패턴, 비교어 코드) — 앞에 있는 것이 우선. */
    public static final List<Map.Entry<String, String>> COMPARATORS = List.of(
            Map.entry("이하", "LE"), Map.entry("이내", "LE"), Map.entry("까지", "LE"), Map.entry("안에", "LE"),
            Map.entry("안으로", "LE"), Map.entry("내에", "LE"), Map.entry("내로", "LE"), Map.entry("넘지\\s*않", "LE"),
            Map.entry("초과하지\\s*않", "LE"),
            Map.entry("미만", "LT"), Map.entry("이상", "GE"), Map.entry("초과", "GT"), Map.entry("넘는", "GT"), Map.entry("넘으", "GT"));
    private static final List<Pattern> COMPARATOR_PATTERNS = COMPARATORS.stream().map(e -> PyRe.compile(e.getKey())).toList();
    public static final Pattern COMPARATOR_RE = PyRe.compile("^\\s*(?:을|를|의|로|으로)?\\s*("
            + COMPARATORS.stream().map(Map.Entry::getKey).collect(Collectors.joining("|")) + ")");

    /** 표기 → (차원, 정규 단위). */
    public static final Map<String, List<String>> UNIT_MAP = orderedUnits();
    public static final String UNIT_ALT = String.join("|", sortedByLenDesc(
            UNIT_MAP.keySet().stream().map(KoQuantity::reEscape).collect(Collectors.toCollection(ArrayList::new))));

    private static Map<String, List<String>> orderedUnits() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("%", List.of("ratio", "%"));
        m.put("퍼센트", List.of("ratio", "%"));
        m.put("프로", List.of("ratio", "%"));
        m.put("㎡", List.of("area", "㎡"));
        m.put("제곱미터", List.of("area", "㎡"));
        m.put("m²", List.of("area", "㎡"));
        m.put("m2", List.of("area", "㎡"));
        m.put("평", List.of("area_pyeong", "평")); // 원문에 없으면 단위 날조로 걸림 (환산 금지)
        m.put("m", List.of("length", "m"));
        m.put("미터", List.of("length", "m"));
        m.put("cm", List.of("length_cm", "cm"));
        m.put("센티미터", List.of("length_cm", "cm"));
        m.put("대", List.of("count_vehicle", "대"));
        m.put("층", List.of("floors", "층"));
        m.put("세대", List.of("households", "세대"));
        m.put("명", List.of("persons", "명"));
        m.put("배", List.of("multiple", "배"));
        m.put("개소", List.of("places", "개소"));
        m.put("개", List.of("count", "개")); // 출입구가 2개 이상 (개월은 기간 패턴이 먼저 잡는다)
        m.put("도", List.of("angle", "도")); // 60도 대향주차 (도로·도시 등은 아래 UNIT_RE 에서 제외)
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 세부 사실 하나. value 는 PERIOD·QUANTITY·BARE 이면 {@link Double}, DATE·REFERENCE 이면 {@link String}. */
    public record Q(String kind, String dim, Object value, String unit, String comparator, String raw, int start, int end,
                    Map<String, Object> extra) {
        public Q(String kind, String dim, Object value, String unit, String comparator, String raw, int start, int end) {
            this(kind, dim, value, unit, comparator, raw, start, end, Map.of());
        }

        /** 파이썬 {@code q.key()} = (kind, dim, value). */
        public List<Object> key() {
            return List.of(kind, dim, value);
        }

        /** 파이썬 {@code float(q.value)} (REFERENCE 의 문자열 값이면 ValueError). */
        public double floatValue() {
            return value instanceof Double d ? d : PyObj.parseFloat((String) value);
        }

        /** JSON 값 (canonical.value). */
        public JsonNode valueNode() {
            return value instanceof Double d ? DoubleNode.valueOf(d) : StringNode.valueOf((String) value);
        }

        /** 진단·비교용 JSON (kind, dim, value, unit, comparator, raw, start, end). */
        public JsonNode toJson() {
            var o = Json.object();
            o.put("kind", kind);
            o.put("dim", dim);
            o.set("value", valueNode());
            o.put("unit", unit);
            o.put("comparator", comparator);
            o.put("raw", raw);
            o.put("start", start);
            o.put("end", end);
            return o;
        }
    }

    /** 파이썬 {@code _num}: 고유어·한자어 수사, '1천500' 표기, 쉼표 숫자를 float 로. */
    public static double num(String s) {
        s = Py.strip(s);
        if (NATIVE.containsKey(s)) {
            return NATIVE.get(s);
        }
        if (SINO.containsKey(s)) {
            return SINO.get(s);
        }
        int k = s.indexOf('천');
        if (k >= 0) {
            String a = s.substring(0, k);
            String b = Py.strip(s.substring(k + 1));
            BigInteger v = PyObj.parseInt(a).multiply(BigInteger.valueOf(1000)).add(b.isEmpty() ? BigInteger.ZERO : PyObj.parseInt(b));
            return PyObj.bigIntToFloat(v);
        }
        return PyObj.parseFloat(s.replace(",", ""));
    }

    /** 비교어와 그 끝 위치 (UTF-16). 없으면 (null, end). */
    record CompAfter(String code, int end) {
    }

    /** 파이썬 {@code _comparator_after(text, end)} — end 는 UTF-16 위치, 다음 12 글자(코드포인트) 안을 본다. */
    static CompAfter comparatorAfter(String text, int end) {
        int stop = end;
        for (int i = 0; i < 12 && stop < text.length(); i++) {
            stop += Character.charCount(text.codePointAt(stop));
        }
        Matcher m = COMPARATOR_RE.matcher(text);
        m.region(end, stop);
        if (!m.lookingAt()) {
            return new CompAfter(null, end);
        }
        String word = m.group(1);
        for (int i = 0; i < COMPARATORS.size(); i++) {
            if (COMPARATOR_PATTERNS.get(i).matcher(word).matches()) {
                return new CompAfter(COMPARATORS.get(i).getValue(), m.end());
            }
        }
        return new CompAfter(null, end);
    }

    private static final Pattern WS_RUN = PyRe.compile("\\s+");

    /** 파이썬 {@code normalize_ref}: 공백 제거. */
    public static String normalizeRef(String s) {
        return PyRe.sub(WS_RUN, "", s);
    }

    // 패턴 순서가 중요: 날짜 → 참조 → 기간 → 단위 수량 → 단위 없는 숫자
    public static final Pattern DATE_RE = PyRe.compile(
            "(\\d{4})\\s*(?:년|[-.])\\s*(\\d{1,2})\\s*(?:월|[-.])\\s*(\\d{1,2})\\s*(?:일|\\.)?");
    public static final Pattern REF_RE = PyRe.compile(
            "「[^」]{1,60}」|제\\s*\\d+\\s*조(?:의\\s*\\d+)?(?:\\s*제\\s*\\d+\\s*항)?(?:\\s*제\\s*\\d+\\s*호)?|별표\\s*\\d+(?:의\\s*\\d+)?");
    public static final Pattern PERIOD_RE = PyRe.compile(
            "(?P<half>반\\s*년)"
                    + "|(?:(?P<y>" + NUM + "|" + SINO_ALT + "|" + NATIVE_ALT + ")\\s*(?:년|해)(?!\\s*\\d{1,2}\\s*월\\s*\\d))"
                    + "(?:\\s*(?P<ym>" + NUM + "|" + NATIVE_ALT + ")\\s*(?:개월|달))?"
                    + "|(?P<m>" + NUM + "|" + NATIVE_ALT + ")\\s*(?:개월|달)"
                    + "|(?P<w>" + NUM + "|" + NATIVE_ALT + ")\\s*주(?:일|간)?(?![가-힣]{0,0}택)"
                    + "|(?P<d>" + NUM + ")\\s*일(?!\\s*반|부)");
    public static final Pattern MONEY_RE = PyRe.compile("(?P<n>" + NUM + ")\\s*(?P<mul>억|천만|백만|만|천)?\\s*원");
    public static final Map<String, Double> MONEY_MUL = moneyMul();
    public static final Pattern UNIT_RE = PyRe.compile(
            "(?P<n>" + NUM + ")\\s*(?P<u>" + UNIT_ALT + ")(?![a-zA-Z²])(?!(?<=대)(?:구|학|상|로|한))(?!(?<=도)(?:로|시|록|면|지|서|의))"
                    + "|(?P<n2>" + NUM + ")\\s*(?P<u2>㎡|m²|%)");
    /** 단위 없는 숫자: '제2종'의 2처럼 '제' 뒤 숫자는 명칭의 일부로 보고 제외. */
    public static final Pattern BARE_RE = PyRe.compile("(?<![\\d.,제])(?P<n>" + NUM + ")(?![\\d.,])");

    private static Map<String, Double> moneyMul() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(null, 1.0);
        m.put("천", 1e3);
        m.put("만", 1e4);
        m.put("백만", 1e6);
        m.put("천만", 1e7);
        m.put("억", 1e8);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 문장에서 세부 사실 후보를 모두 뽑는다. 겹치는 구간은 먼저 잡힌 패턴이 이긴다. */
    public static List<Q> parse(String text) {
        List<int[]> taken = new ArrayList<>(); // UTF-16 [start, end)
        List<Q> out = new ArrayList<>();
        Adder add = (kind, dim, value, unit, comp, a, b) -> {
            taken.add(new int[] {a, b});
            out.add(new Q(kind, dim, value, unit, comp, text.substring(a, b), Py.cpIndex(text, a), Py.cpIndex(text, b)));
        };

        Matcher m = DATE_RE.matcher(text);
        while (m.find()) {
            int y = PyObj.parseInt(m.group(1)).intValueExact();
            int mo = PyObj.parseInt(m.group(2)).intValueExact();
            int d = PyObj.parseInt(m.group(3)).intValueExact();
            if (1 <= mo && mo <= 12 && 1 <= d && d <= 31) {
                add.add("DATE", "date", String.format("%04d-%02d-%02d", y, mo, d), null, null, m.start(), m.end());
            }
        }

        m = REF_RE.matcher(text);
        while (m.find()) {
            if (free(taken, m.start(), m.end())) {
                add.add("REFERENCE", "ref", normalizeRef(m.group()), null, null, m.start(), m.end());
            }
        }

        m = PERIOD_RE.matcher(text);
        while (m.find()) {
            if (Py.strip(m.group()).isEmpty() || !free(taken, m.start(), m.end())) {
                continue;
            }
            String dim;
            double val;
            if (nonEmpty(m.group("half"))) {
                dim = "period_month";
                val = 6.0;
            } else if (nonEmpty(m.group("y"))) {
                dim = "period_month";
                val = num(m.group("y")) * 12 + (nonEmpty(m.group("ym")) ? num(m.group("ym")) : 0);
            } else if (nonEmpty(m.group("m"))) {
                dim = "period_month";
                val = num(m.group("m"));
            } else if (nonEmpty(m.group("w"))) {
                dim = "period_day";
                val = num(m.group("w")) * 7;
            } else {
                dim = "period_day";
                val = num(m.group("d"));
            }
            CompAfter c = comparatorAfter(text, m.end());
            add.add("PERIOD", dim, val, dim.equals("period_month") ? "개월" : "일", c.code(), m.start(), c.end());
        }

        m = MONEY_RE.matcher(text);
        while (m.find()) {
            if (free(taken, m.start(), m.end())) {
                double val = num(m.group("n")) * MONEY_MUL.get(m.group("mul"));
                CompAfter c = comparatorAfter(text, m.end());
                add.add("QUANTITY", "money", val, "원", c.code(), m.start(), c.end());
            }
        }

        m = UNIT_RE.matcher(text);
        while (m.find()) {
            if (!free(taken, m.start(), m.end())) {
                continue;
            }
            String n = m.group("n") != null ? m.group("n") : m.group("n2");
            String u = m.group("u") != null ? m.group("u") : m.group("u2");
            List<String> du = UNIT_MAP.get(u);
            CompAfter c = comparatorAfter(text, m.end());
            add.add("QUANTITY", du.get(0), num(n), du.get(1), c.code(), m.start(), c.end());
        }

        m = BARE_RE.matcher(text);
        while (m.find()) {
            if (free(taken, m.start(), m.end())) {
                CompAfter c = comparatorAfter(text, m.end());
                add.add("BARE", "bare", num(m.group("n")), null, c.code(), m.start(), c.end());
            }
        }

        List<Q> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparingInt(Q::start));
        return sorted;
    }

    @FunctionalInterface
    private interface Adder {
        void add(String kind, String dim, Object value, String unit, String comparator, int start, int end);
    }

    private static boolean free(List<int[]> taken, int a, int b) {
        for (int[] t : taken) {
            if (!(b <= t[0] || a >= t[1])) {
                return false;
            }
        }
        return true;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // ── 서술 성격(허용/금지/의무)과 예외 표지 ────────────────────
    public static final Pattern EXEMPT_RE = PyRe.compile(
            "(하지\\s*않아도|안\\s*해도|필요\\s*(?:가\\s*)?없|의무가\\s*없|제외|그러하지\\s*아니하|적용(?:되지|하지)\\s*않|면제)");
    public static final Pattern NEG_RE = PyRe.compile(
            "(할\\s*수\\s*없|못\\s*하|못한|금지|불가|안\\s*됩|아니\\s*된|허용되지\\s*않|건축할\\s*수\\s*없|제한됩)");
    public static final Pattern POS_RE = PyRe.compile("(할\\s*수\\s*있|가능|허용|건축할\\s*수\\s*있|지을\\s*수\\s*있)");
    public static final Pattern OBL_RE = PyRe.compile(
            "(하여야\\s*한|해야\\s*하|해야\\s*합|해야\\s*해|의무|설치하여야|갖추어야|확보하여야|확보해야)");
    public static final Pattern EXCEPTION_RE = PyRe.compile("(다만[,，]?|단서|제외한다|제외하고|그러하지\\s*아니하다|예외)");
    public static final Pattern CONDITION_RE = PyRe.compile("([가-힣A-Za-z0-9·\\s]{2,40}?(?:경우|때)(?:에는|에|는)?)");

    /** 문장의 서술 성격: EXEMPT(의무 면제) > PROHIBIT(금지) > OBLIGE(의무) > ALLOW(허용). 없으면 null. */
    public static String modality(String text) {
        if (EXEMPT_RE.matcher(text).find()) {
            return "EXEMPT";
        }
        if (NEG_RE.matcher(text).find()) {
            return "PROHIBIT";
        }
        if (OBL_RE.matcher(text).find()) {
            return "OBLIGE";
        }
        if (POS_RE.matcher(text).find()) {
            return "ALLOW";
        }
        return null;
    }

    /** 문장 하나: 시작 위치(코드포인트, 앞 공백 포함 위치 — 파이썬과 같음)와 앞뒤 공백을 뺀 문장. */
    public record Sentence(int start, String text) {
    }

    private static final Pattern SENTENCE_SPLIT = PyRe.compile("(?<=[.。!?])\\s+|(?<=다\\.)|(?<=\\n)");

    /**
     * 파이썬 {@code split_sentences}. (빈 매치 다음 검색 위치는 자바가 한 글자 뒤에서 시작하지만, 이 패턴은
     * 빈 매치 자리에서 시작하는 비어 있지 않은 매치가 있을 수 없어 파이썬 {@code finditer} 와 결과가 같다.)
     */
    public static List<Sentence> splitSentences(String text) {
        List<Sentence> out = new ArrayList<>();
        int start = 0;
        Matcher m = SENTENCE_SPLIT.matcher(text);
        while (m.find()) {
            String seg = Py.strip(text.substring(start, Math.max(start, m.start())));
            if (!seg.isEmpty()) {
                out.add(new Sentence(Py.cpIndex(text, start), seg));
            }
            start = m.end();
        }
        String tail = Py.strip(text.substring(start));
        if (!tail.isEmpty()) {
            out.add(new Sentence(Py.cpIndex(text, start), tail));
        }
        return out;
    }

    // ── 명칭: 용도지역·지구·구역, 법령명 ─────────────────────────
    public static final Pattern ZONE_NAME_RE = PyRe.compile(
            "(?:제\\s*\\d\\s*종\\s*)?(?:전용|일반|준|중심|근린|유통|보전|생산|자연|계획)?\\s*"
                    + "(?:주거|상업|공업|녹지|관리|농림|자연환경보전)\\s*지역|지구단위계획\\s*구역|개발제한\\s*구역");
    public static final Pattern LAW_NAME_RE = PyRe.compile("[가-힣·]{2,30}(?:법률|시행령|시행규칙|조례|법)(?![가-힣])");
    public static final Set<String> NOT_LAW = Set.of("방법", "불법", "위법", "적법", "편법", "합법", "문법", "수법", "용법", "기법", "어법", "입법");
    private static final Pattern BRACKETED = PyRe.compile("「[^」]*」");

    public static List<String> zoneNames(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = ZONE_NAME_RE.matcher(text);
        while (m.find()) {
            out.add(normalizeRef(m.group()));
        }
        return out;
    }

    public static List<String> lawNames(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = LAW_NAME_RE.matcher(PyRe.sub(BRACKETED, " ", text));
        while (m.find()) {
            String w = m.group();
            if (!NOT_LAW.contains(w) && NOT_LAW.stream().noneMatch(w::endsWith)) {
                out.add(normalizeRef(w));
            }
        }
        return out;
    }

    // ── 도우미 ────────────────────────────────────────────────

    /** 파이썬 {@code re.escape} (3.7+: 정규식 특수 문자만). */
    static String reEscape(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (RE_SPECIAL.indexOf(cp) >= 0) {
                sb.append('\\');
            }
            sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    /** {@code sorted(xs, key=len, reverse=True)} (코드포인트 길이, 안정 정렬). */
    private static List<String> sortedByLenDesc(List<String> xs) {
        List<String> out = new ArrayList<>(xs);
        out.sort((a, b) -> Integer.compare(Py.len(b), Py.len(a)));
        return out;
    }

    private static Map<String, Integer> ordered(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
