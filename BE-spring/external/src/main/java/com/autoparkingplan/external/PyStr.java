package com.autoparkingplan.external;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬 내장 동작(str·repr·truthiness·float()·int()·isdigit·unquote·{@code :.Nf})을 그대로 흉내 내는 도우미.
 *
 * <p>외부 API 응답의 값은 문자열·숫자·불리언·null 이 섞여 오므로, 파이썬판이 {@code str(value)}·{@code float(s)}·
 * {@code if not value} 로 처리하던 결과를 똑같이 내기 위해 쓴다. {@code common} 모듈에 없는 것만 모았다
 * (공용으로 옮길 만하면 {@code common.Py} 로 올려도 된다).
 */
final class PyStr {
    /** 파이썬 정규식 {@code \s} 와 같은 공백 문자 집합 ({@code str.isspace()} 기준, {@link Py#isSpace(int)} 와 같음). */
    static final String WS_CLASS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";

    /** 파이썬 {@code str.isdigit()} 가 참이지만 {@code isdecimal()} 은 거짓인 코드포인트 구간 (Unicode 15.0, 파이썬 3.12 에서 추출). */
    private static final int[] DIGIT_NOT_DECIMAL = {
        0xB2, 0xB3, 0xB9, 0xB9, 0x1369, 0x1371, 0x19DA, 0x19DA, 0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089,
        0x2460, 0x2468, 0x2474, 0x247C, 0x2488, 0x2490, 0x24EA, 0x24EA, 0x24F5, 0x24FD, 0x24FF, 0x24FF, 0x2776, 0x277E,
        0x2780, 0x2788, 0x278A, 0x2792, 0x10A40, 0x10A43, 0x10E60, 0x10E68, 0x11052, 0x1105A, 0x1F100, 0x1F10A,
    };

    // float() 문법: [부호] (숫자부 [. 숫자부] | . 숫자부) [e [부호] 숫자부] — 숫자 사이 밑줄 1개 허용
    private static final String DIGITPART = "[0-9](?:_?[0-9])*";
    private static final Pattern FLOAT_RE = Pattern.compile(
            "[+-]?(?:(?:" + DIGITPART + ")?\\." + DIGITPART + "|" + DIGITPART + "\\.?)(?:[eE][+-]?" + DIGITPART + ")?");
    private static final Pattern FLOAT_SPECIAL_RE = Pattern.compile("(?i)[+-]?(?:inf|infinity|nan)");
    private static final Pattern INT_RE = Pattern.compile("[+-]?" + DIGITPART);

    private PyStr() {
    }

    // ── str() / repr() / bool() ──────────────────────────────

    /** 파이썬 {@code str(value)}. JsonNode 는 json.loads 결과(dict/list/str/int/float/bool/None)로 보고 바꾼다. */
    static String str(Object v) {
        if (v instanceof JsonNode n && n.isString()) {
            return n.stringValue();
        }
        if (v instanceof String s) {
            return s;
        }
        return repr(v);
    }

    /** 파이썬 {@code repr(value)}. */
    static String repr(Object v) {
        if (v instanceof JsonNode n) {
            v = Json.toJava(n);
        }
        if (v == null) {
            return "None";
        }
        if (v instanceof String s) {
            return reprString(s);
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double || v instanceof Float) {
            return Py.floatRepr(((Number) v).doubleValue());
        }
        if (v instanceof BigDecimal d) {
            return Py.floatRepr(d.doubleValue());
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
            return sb.append('}').toString();
        }
        if (v instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            Iterator<?> it = c.iterator();
            while (it.hasNext()) {
                sb.append(repr(it.next()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }
            return sb.append(']').toString();
        }
        return v.toString();
    }

    /** 파이썬 {@code repr(str)}: 작은따옴표 우선, 출력 불가 문자는 {@code \xNN}/{@code \}{@code uNNNN} 이스케이프. */
    static String reprString(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(quote);
        s.codePoints().forEach(cp -> {
            if (cp == quote || cp == '\\') {
                sb.append('\\').appendCodePoint(cp);
            } else if (cp == '\t') {
                sb.append("\\t");
            } else if (cp == '\n') {
                sb.append("\\n");
            } else if (cp == '\r') {
                sb.append("\\r");
            } else if (cp < 0x20 || cp == 0x7F) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp < 0x7F || isPrintable(cp)) {
                sb.appendCodePoint(cp);
            } else if (cp <= 0xFF) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp <= 0xFFFF) {
                sb.append(String.format("\\u%04x", cp));
            } else {
                sb.append(String.format("\\U%08x", cp));
            }
        });
        return sb.append(quote).toString();
    }

    /** 파이썬 {@code str.isprintable()} (코드포인트 하나): 제어·서식·대리·사용자 정의·미지정·구분자(공백 ' ' 제외)는 출력 불가. */
    private static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** 파이썬 {@code bool(value)} (JsonNode 는 json.loads 결과 기준). */
    static boolean truthy(Object v) {
        if (v instanceof JsonNode n) {
            if (n.isNull() || n.isMissingNode()) {
                return false;
            }
            if (n.isString()) {
                return !n.stringValue().isEmpty();
            }
            if (n.isBoolean()) {
                return n.booleanValue();
            }
            if (n.isNumber()) {
                return n.isIntegralNumber() ? n.bigIntegerValue().signum() != 0 : n.doubleValue() != 0.0;
            }
            return !n.isEmpty();
        }
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double || v instanceof Float) {
            return ((Number) v).doubleValue() != 0.0; // NaN 은 참
        }
        if (v instanceof BigInteger bi) {
            return bi.signum() != 0;
        }
        if (v instanceof Number num) {
            return num.longValue() != 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** JSON 값이 None(null·누락)인지. */
    static boolean isNone(Object v) {
        return v == null || (v instanceof JsonNode n && (n.isNull() || n.isMissingNode()));
    }

    // ── 숫자 해석 ────────────────────────────────────────────

    /** 파이썬 {@code float(s)} (문자열). ValueError 이면 null. 유니코드 숫자·밑줄·inf/nan 을 파이썬과 같이 받는다. */
    static Double parseFloat(String s) {
        String t = toAsciiDigits(Py.strip(s));
        if (t == null) {
            return null;
        }
        if (FLOAT_SPECIAL_RE.matcher(t).matches()) {
            boolean neg = t.startsWith("-");
            String body = t.replaceFirst("^[+-]", "").toLowerCase(java.util.Locale.ROOT);
            if (body.equals("nan")) {
                return Double.NaN;
            }
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!FLOAT_RE.matcher(t).matches()) {
            return null;
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /** 파이썬 {@code int(s)} (문자열, 10진). ValueError 이면 null. */
    static BigInteger parseInt(String s) {
        String t = toAsciiDigits(Py.strip(s));
        if (t == null || !INT_RE.matcher(t).matches()) {
            return null;
        }
        return new BigInteger(t.replace("_", ""));
    }

    /** 유니코드 10진 숫자(Nd)를 ASCII 로 바꾼다. 그 밖의 비 ASCII 문자가 있으면 null (float()/int() 가 거절). */
    private static String toAsciiDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                int d = Character.digit(cp, 10);
                if (d < 0) {
                    return null;
                }
                sb.append((char) ('0' + d));
            }
        }
        return sb.toString();
    }

    /** 파이썬 {@code str.isdecimal()} (코드포인트 하나) = 유니코드 Nd. */
    static boolean isDecimal(int cp) {
        return Character.isDigit(cp);
    }

    /** 파이썬 {@code str.isdigit()} (코드포인트 하나) = Numeric_Type 이 Decimal 또는 Digit. */
    static boolean isDigit(int cp) {
        if (Character.isDigit(cp)) {
            return true;
        }
        for (int i = 0; i < DIGIT_NOT_DECIMAL.length; i += 2) {
            if (cp >= DIGIT_NOT_DECIMAL[i] && cp <= DIGIT_NOT_DECIMAL[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** 파이썬 {@code s.isdigit()}: 비어 있지 않고 모두 숫자. */
    static boolean isDigit(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(PyStr::isDigit);
    }

    /**
     * {@code isdigit()} 을 통과한 문자열의 파이썬 {@code int(s)}. 위첨자 등 Decimal 이 아닌 숫자가 섞여 있으면 파이썬처럼
     * {@code invalid literal for int()} 오류.
     */
    static BigInteger intOfDigits(String s) {
        BigInteger v = parseInt(s);
        if (v == null) {
            throw new IllegalArgumentException("invalid literal for int() with base 10: " + reprString(s));
        }
        return v;
    }

    // ── 서식 ─────────────────────────────────────────────────

    /** 파이썬 {@code f"{x:.{digits}f}"}: 이진 값 그대로를 기준으로 한 은행원 반올림, 음수 부호(-0.0 포함) 유지. */
    static String formatFixed(double x, int digits) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
        boolean negative = x < 0 || (x == 0.0 && 1.0 / x < 0);
        if (negative && !s.startsWith("-")) {
            s = "-" + s;
        }
        return s;
    }

    // ── 문자열 ───────────────────────────────────────────────

    /** 파이썬 {@code re.sub(r"\s+", repl, s)} (파이썬 공백 기준). */
    static String subWhitespace(String s, String repl) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inWs = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Py.isSpace(cp)) {
                if (!inWs) {
                    sb.append(repl);
                    inWs = true;
                }
            } else {
                sb.appendCodePoint(cp);
                inWs = false;
            }
        }
        return sb.toString();
    }

    /**
     * 파이썬 {@code urllib.parse.unquote(s)} (UTF-8, errors='replace'). '+' 는 그대로 두고, 올바르지 않은 '%' 열은 남긴다.
     * 파이썬처럼 ASCII 구간에서만 %XX 를 풀고 비 ASCII 문자는 그대로 둔다.
     */
    static String unquote(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            int start = i;
            if (s.charAt(i) < 0x80) {
                while (i < n && s.charAt(i) < 0x80) {
                    i++;
                }
                out.append(unquoteAscii(s.substring(start, i)));
            } else {
                while (i < n && s.charAt(i) >= 0x80) {
                    i++;
                }
                out.append(s, start, i);
            }
        }
        return out.toString();
    }

    private static String unquoteAscii(String run) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(run.length());
        int i = 0;
        int n = run.length();
        while (i < n) {
            char c = run.charAt(i);
            if (c == '%' && i + 2 < n && isHex(run.charAt(i + 1)) && isHex(run.charAt(i + 2))) {
                bytes.write(Integer.parseInt(run.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                bytes.write(c);
                i++;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
