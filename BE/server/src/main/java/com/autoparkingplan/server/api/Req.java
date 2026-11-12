package com.autoparkingplan.server.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 요청 본문·질의 값 검사 (파이썬판의 pydantic 모델과 같은 규칙). 오류는 모두 모아 {@link RequestValidationException}(422)로 던진다.
 *
 * <p>pydantic v2 기본(lax) 규칙을 따른다: 문자열 필드는 문자열만, 숫자 필드는 숫자 또는 숫자 문자열, 정수 필드는 소수부 0 인 실수도 허용,
 * bool 은 true/false·0/1·"yes"/"no" 등. NaN·Infinity 는 거부(allow_inf_nan=False).
 */
public final class Req {
    private final JsonNode body;
    private final List<Object> base;
    private final List<RequestValidationException.Detail> errors;

    private Req(JsonNode body, List<Object> base, List<RequestValidationException.Detail> errors) {
        this.body = body;
        this.base = base;
        this.errors = errors;
    }

    /**
     * 본문 객체 검사 시작.
     *
     * @param allowed     허용 키 (forbidExtra 일 때 그 밖의 키는 오류)
     * @param forbidExtra pydantic extra="forbid"
     */
    public static Req body(JsonNode body, Set<String> allowed, boolean forbidExtra) {
        Req r = new Req(body, List.of("body"), new ArrayList<>());
        if (body == null || !body.isObject()) {
            r.err(List.of(), "객체(JSON object)여야 합니다");
            r.done();
        }
        if (forbidExtra) {
            for (String k : body.propertyNames()) {
                if (!allowed.contains(k)) {
                    r.err(List.of(k), "허용되지 않은 필드입니다");
                }
            }
        }
        return r;
    }

    /** 중첩 객체 (null 이면 null). */
    public Req nested(String name, Set<String> allowed, boolean forbidExtra) {
        JsonNode v = body.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        List<Object> loc = new ArrayList<>(base);
        loc.add(name);
        Req r = new Req(v, loc, errors);
        if (!v.isObject()) {
            r.err(List.of(), "객체여야 합니다");
            return null;
        }
        if (forbidExtra) {
            for (String k : v.propertyNames()) {
                if (!allowed.contains(k)) {
                    r.err(List.of(k), "허용되지 않은 필드입니다");
                }
            }
        }
        return r;
    }

    public JsonNode raw() {
        return body;
    }

    public boolean has(String name) {
        return body.has(name);
    }

    void err(List<Object> sub, String msg) {
        List<Object> loc = new ArrayList<>(base);
        loc.addAll(sub);
        errors.add(new RequestValidationException.Detail(loc, msg));
    }

    /** 모은 오류가 있으면 422 로 던진다. */
    public void done() {
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    private JsonNode field(String name, boolean required) {
        JsonNode v = body.get(name);
        if (v == null) {
            if (required) {
                err(List.of(name), "필수 값입니다");
            }
            return null;
        }
        return v;
    }

    // ── 문자열 ────────────────────────────────────────────────
    public String str(String name, boolean required, Integer minLen, Integer maxLen, Pattern pattern) {
        JsonNode v = field(name, required);
        if (v == null) {
            return null;
        }
        if (v.isNull()) {
            if (required) {
                err(List.of(name), "문자열이어야 합니다");
            }
            return null;
        }
        if (!v.isString()) {
            err(List.of(name), "문자열이어야 합니다");
            return null;
        }
        return checkStr(List.of(name), v.stringValue(), minLen, maxLen, pattern);
    }

    String checkStr(List<Object> loc, String s, Integer minLen, Integer maxLen, Pattern pattern) {
        int n = Py.len(s);
        if (minLen != null && n < minLen) {
            err(loc, "최소 " + minLen + "자여야 합니다");
            return null;
        }
        if (maxLen != null && n > maxLen) {
            err(loc, "최대 " + maxLen + "자여야 합니다");
            return null;
        }
        // 문자열 전체가 맞아야 한다. find() + '^…$' 는 끝에 줄바꿈이 붙은 값("…\n")도 통과시킨다
        if (pattern != null && !pattern.matcher(s).matches()) {
            err(loc, "형식이 올바르지 않습니다");
            return null;
        }
        return s;
    }

    public String choice(String name, String dflt, Set<String> allowed) {
        JsonNode v = field(name, false);
        if (v == null) {
            return dflt;
        }
        if (!v.isString() || !allowed.contains(v.stringValue())) {
            err(List.of(name), "허용된 값이 아닙니다: " + allowed);
            return dflt;
        }
        return v.stringValue();
    }

    // ── 숫자 ──────────────────────────────────────────────────
    /** 실수 (null·누락 → dflt). gt/lt/ge/le 는 null 이면 검사 안 함. */
    public Double num(String name, Double dflt, boolean nullable, Double gt, Double lt, Double ge, Double le) {
        JsonNode v = field(name, false);
        if (v == null) {
            return dflt;
        }
        if (v.isNull()) {
            if (!nullable) {
                err(List.of(name), "숫자여야 합니다");
            }
            return null;
        }
        Double d = toDouble(v);
        if (d == null) {
            err(List.of(name), "숫자여야 합니다");
            return null;
        }
        return checkNum(List.of(name), d, gt, lt, ge, le);
    }

    Double checkNum(List<Object> loc, double d, Double gt, Double lt, Double ge, Double le) {
        if (!Double.isFinite(d)) {
            err(loc, "유한한 숫자여야 합니다");
            return null;
        }
        if ((gt != null && !(d > gt)) || (lt != null && !(d < lt)) || (ge != null && !(d >= ge)) || (le != null && !(d <= le))) {
            err(loc, "허용 범위를 벗어났습니다");
            return null;
        }
        return d;
    }

    static Double toDouble(JsonNode v) {
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isString()) {
            String s = Py.strip(v.stringValue());
            if (s.matches("[+-]?(\\d+(_\\d+)*\\.?(\\d+(_\\d+)*)?|\\.\\d+(_\\d+)*)([eE][+-]?\\d+)?")) {
                return Double.parseDouble(s.replace("_", ""));
            }
            String low = s.toLowerCase(Locale.ROOT);
            if (Set.of("nan", "inf", "+inf", "-inf", "infinity", "+infinity", "-infinity").contains(low)) {
                return Double.NaN; // 유한하지 않음 → 범위 검사에서 거부
            }
        }
        return null;
    }

    /** 정수 (소수부 0 인 실수·정수 문자열 허용). */
    public Long integer(String name, Long dflt, boolean nullable, Long gt, Long lt, Long ge, Long le) {
        JsonNode v = field(name, false);
        if (v == null) {
            return dflt;
        }
        if (v.isNull()) {
            if (!nullable) {
                err(List.of(name), "정수여야 합니다");
            }
            return null;
        }
        Long x = toLong(v);
        if (x == null) {
            err(List.of(name), "정수여야 합니다");
            return null;
        }
        if ((gt != null && !(x > gt)) || (lt != null && !(x < lt)) || (ge != null && !(x >= ge)) || (le != null && !(x <= le))) {
            err(List.of(name), "허용 범위를 벗어났습니다");
            return null;
        }
        return x;
    }

    static Long toLong(JsonNode v) {
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.longValue();
        }
        if (v.isNumber() && !v.isIntegralNumber()) {
            double d = v.doubleValue();
            return Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (long) d : null;
        }
        if (v.isString() && Py.strip(v.stringValue()).matches("[+-]?\\d+(_\\d+)*")) {
            try {
                return Long.parseLong(Py.strip(v.stringValue()).replace("_", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── bool · 날짜 · 목록 ─────────────────────────────────────
    public Boolean bool(String name, Boolean dflt, boolean nullable) {
        JsonNode v = field(name, false);
        if (v == null) {
            return dflt;
        }
        if (v.isNull()) {
            if (!nullable) {
                err(List.of(name), "true/false 여야 합니다");
            }
            return null;
        }
        Boolean b = toBool(v);
        if (b == null) {
            err(List.of(name), "true/false 여야 합니다");
        }
        return b != null ? b : dflt;
    }

    /** pydantic bool 해석. 질의 문자열(?refresh=true)에도 쓴다. */
    public static Boolean toBool(JsonNode v) {
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber()) {
            long x = v.longValue();
            return x == 1 ? Boolean.TRUE : x == 0 ? Boolean.FALSE : null;
        }
        if (v.isString()) {
            return parseBool(v.stringValue());
        }
        return null;
    }

    public static Boolean parseBool(String s) {
        return switch (Py.strip(s).toLowerCase(Locale.ROOT)) {
            case "1", "true", "t", "yes", "y", "on" -> Boolean.TRUE;
            case "0", "false", "f", "no", "n", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    public LocalDate date(String name) {
        JsonNode v = field(name, false);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isString()) {
            LocalDate d = parseDate(v.stringValue());
            if (d != null) {
                return d;
            }
        }
        err(List.of(name), "날짜(YYYY-MM-DD)여야 합니다");
        return null;
    }

    public static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public UUID uuid(String name, boolean required) {
        JsonNode v = field(name, required);
        if (v == null || v.isNull()) {
            if (v != null && required) {
                err(List.of(name), "UUID 여야 합니다");
            }
            return null;
        }
        UUID u = v.isString() ? parseUuid(v.stringValue()) : null;
        if (u == null) {
            err(List.of(name), "UUID 여야 합니다");
        }
        return u;
    }

    public static UUID parseUuid(String s) {
        String t = s.strip();
        if (!t.matches("(?i)(urn:uuid:)?\\{?[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}}?")) {
            return null;
        }
        String hex = t.replaceFirst("(?i)^urn:uuid:", "").replace("{", "").replace("}", "").replace("-", "");
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20));
    }

    /** 배열 필드 (null·누락 → dflt). 항목 검사는 호출한 쪽에서. */
    public JsonNode array(String name, JsonNode dflt, boolean nullable, Integer maxLen) {
        JsonNode v = field(name, false);
        if (v == null) {
            return dflt;
        }
        if (v.isNull()) {
            if (!nullable) {
                err(List.of(name), "배열이어야 합니다");
            }
            return null;
        }
        if (!v.isArray()) {
            err(List.of(name), "배열이어야 합니다");
            return null;
        }
        if (maxLen != null && v.size() > maxLen) {
            err(List.of(name), "최대 " + maxLen + "개까지입니다");
            return null;
        }
        return v;
    }

    /** 문자열 배열의 각 항목 검사. */
    public List<String> strItems(String name, JsonNode arr, Pattern pattern, Integer maxLen) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return null;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonNode x = arr.get(i);
            if (!x.isString()) {
                err(List.of(name, i), "문자열이어야 합니다");
                continue;
            }
            String s = checkStr(List.of(name, i), x.stringValue(), null, maxLen, pattern);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** dict 필드 (pydantic dict[str, Any]). */
    public ObjectNode object(String name) {
        JsonNode v = field(name, false);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isObject()) {
            err(List.of(name), "객체여야 합니다");
            return null;
        }
        return (ObjectNode) v;
    }

    /** 사용자 정의 검사 실패 (field_validator 의 ValueError). */
    public void fail(String name, String msg) {
        err(List.of(name), msg);
    }

    /** 값 검사 결과에 따라 쓰는 키 집합. */
    public static Set<String> keys(String... ks) {
        return new HashSet<>(List.of(ks));
    }

    public static JsonNode orEmpty(JsonNode n) {
        return n == null ? Json.object() : n;
    }
}
