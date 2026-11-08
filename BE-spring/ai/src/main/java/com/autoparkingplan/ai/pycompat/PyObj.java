package com.autoparkingplan.ai.pycompat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * {@code json.loads} 로 읽은 파이썬 값(dict·list·str·int·float·bool·None)을 {@link JsonNode} 로 다룰 때의 파이썬 규칙.
 *
 * <p>사후 검증기는 원장(dict)을 스키마 검사 없이 그대로 읽으므로, 파이썬의 참/거짓 판정({@code if f.get("canonical")}),
 * {@code float(x)} 변환, {@code d["k"]} (없으면 KeyError) 를 같은 뜻으로 옮긴다.
 */
public final class PyObj {
    private PyObj() {
    }

    /** 파이썬 {@code bool(v)}: None·False·0·0.0·""·[]·{} 는 거짓. */
    public static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber()) {
            return v.bigIntegerValue().signum() != 0;
        }
        if (v.isNumber()) {
            double d = v.doubleValue();
            return d != 0.0; // NaN 은 참
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        if (v.isArray() || v.isObject()) {
            return !v.isEmpty();
        }
        return true;
    }

    /** 파이썬 {@code d.get(k)}: 없으면 null (JSON null 값은 NullNode 그대로). */
    public static JsonNode get(JsonNode dict, String key) {
        if (dict == null || !dict.isObject()) {
            throw new IllegalArgumentException("TypeError: dict 가 아닌 값에서 " + PyRepr.reprString(key) + " 조회");
        }
        return dict.get(key);
    }

    /** 파이썬 {@code d[k]}: 없으면 KeyError. */
    public static JsonNode item(JsonNode dict, String key) {
        JsonNode v = get(dict, key);
        if (v == null) {
            throw new PyKeyError(key);
        }
        return v;
    }

    /** 값이 None(누락 포함)인가. */
    public static boolean isNone(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode();
    }

    /** 파이썬 {@code node == s} (node 가 str 이고 같은 내용일 때만 참). */
    public static boolean eq(JsonNode node, String s) {
        if (s == null) {
            return isNone(node);
        }
        return node != null && node.isString() && node.stringValue().equals(s);
    }

    /** str 이어야 하는 자리 (예: {@code re.sub(..., v)}) — 아니면 TypeError. */
    public static String requireStr(JsonNode v) {
        if (v == null || !v.isString()) {
            throw new IllegalArgumentException("TypeError: expected string, got " + PyRepr.repr(v));
        }
        return v.stringValue();
    }

    /** 파이썬 {@code float(v)}. */
    public static double toFloat(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            throw new IllegalArgumentException("TypeError: float() argument must be a string or a real number, not 'NoneType'");
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? 1.0 : 0.0;
        }
        if (v.isIntegralNumber()) {
            return bigIntToFloat(v.bigIntegerValue());
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        if (v.isString()) {
            return parseFloat(v.stringValue());
        }
        throw new IllegalArgumentException("TypeError: float() argument must be a string or a real number, not "
                + (v.isArray() ? "'list'" : "'dict'"));
    }

    /** 파이썬 {@code float(int)}: 가장 가까운 double, 범위를 넘으면 OverflowError. */
    public static double bigIntToFloat(BigInteger i) {
        double d = i.doubleValue(); // 가장 가까운 값(짝수 쪽) 으로 반올림
        if (Double.isInfinite(d)) {
            throw new ArithmeticException("OverflowError: int too large to convert to float");
        }
        return d;
    }

    private static final Pattern FLOAT_RE = Pattern.compile(
            "[+-]?(?:(?:[0-9](?:_?[0-9])*)(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?");
    private static final Pattern SPECIAL_RE = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

    /** 파이썬 {@code float(str)}: 앞뒤 공백, 유니코드 숫자, 숫자 사이 밑줄, inf/nan 허용. 형식 오류는 ValueError. */
    public static double parseFloat(String s) {
        String t = numStrip(toAsciiDigits(s));
        if (SPECIAL_RE.matcher(t).matches()) {
            boolean neg = t.startsWith("-");
            String body = t.replaceFirst("^[+-]", "").toLowerCase(java.util.Locale.ROOT);
            if (body.equals("nan")) {
                return Double.NaN;
            }
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!FLOAT_RE.matcher(t).matches()) {
            throw new IllegalArgumentException("ValueError: could not convert string to float: " + PyRepr.reprString(s));
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /**
     * 파이썬 int()/float() 의 앞뒤 공백 제거: ASCII 는 C isspace(공백 \t \n \v \f \r)만, ASCII 밖은 유니코드 공백 전부.
     * (U+001C~U+001F 는 str.isspace() 이지만 숫자 변환에서는 공백이 아니다.)
     */
    public static String numStrip(String s) {
        int i = 0;
        int j = s.length();
        while (i < j && isNumSpace(s.codePointAt(i))) {
            i += Character.charCount(s.codePointAt(i));
        }
        while (j > i && isNumSpace(s.codePointBefore(j))) {
            j -= Character.charCount(s.codePointBefore(j));
        }
        return s.substring(i, j);
    }

    private static boolean isNumSpace(int cp) {
        return cp < 128 ? (cp == ' ' || (cp >= 0x09 && cp <= 0x0D)) : Py.isSpace(cp);
    }

    /** 유니코드 십진 숫자(Nd)를 ASCII 숫자로 (파이썬 int()/float() 가 받아들이는 숫자). */
    public static String toAsciiDigits(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp > 0x7F && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
                sb.append((char) ('0' + Character.digit(cp, 10)));
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += len;
        }
        return sb == null ? s : sb.toString();
    }

    /** 파이썬 {@code int(s)} (십진, 유니코드 숫자 허용). */
    public static BigInteger parseInt(String s) {
        String t = numStrip(toAsciiDigits(s));
        if (!t.matches("[+-]?[0-9](?:_?[0-9])*")) {
            throw new IllegalArgumentException("ValueError: invalid literal for int() with base 10: " + PyRepr.reprString(s));
        }
        return new BigInteger(t.replace("_", ""));
    }

    /** 숫자 두 개의 정확한 비교 (파이썬 int/float 비교와 같음). NaN 이 끼면 null. */
    public static Integer compareNumbers(JsonNode a, JsonNode b) {
        double da = a.doubleValue();
        double db = b.doubleValue();
        boolean fa = !a.isIntegralNumber();
        boolean fb = !b.isIntegralNumber();
        if ((fa && Double.isNaN(da)) || (fb && Double.isNaN(db))) {
            return null;
        }
        boolean ia = fa && Double.isInfinite(da);
        boolean ib = fb && Double.isInfinite(db);
        if (ia && ib) {
            return da == db ? 0 : (da < db ? -1 : 1);
        }
        if (ia) {
            return da > 0 ? 1 : -1;
        }
        if (ib) {
            return db > 0 ? -1 : 1;
        }
        return exact(a).compareTo(exact(b));
    }

    private static BigDecimal exact(JsonNode n) {
        if (n.isIntegralNumber()) {
            return new BigDecimal(n.bigIntegerValue());
        }
        if (n.isBigDecimal()) {
            return n.decimalValue();
        }
        return new BigDecimal(n.doubleValue());
    }

    /**
     * 파이썬 dict 키·set 원소로서의 동일성 키: {@code "a"}, {@code 1 == 1.0 == True} 처럼 파이썬에서 같은 키가 되는 값은 같은 키를 돌려준다.
     * list·dict 는 unhashable (TypeError).
     */
    public static Object hashKey(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "N";
        }
        if (v.isString()) {
            return "s" + v.stringValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "n1" : "n0";
        }
        if (v.isNumber()) {
            if (v.isIntegralNumber()) {
                return "n" + v.bigIntegerValue();
            }
            double d = v.doubleValue();
            if (Double.isNaN(d)) {
                return new Object(); // NaN 은 자기 자신 외에는 같지 않다
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "ninf" : "n-inf";
            }
            BigDecimal bd = new BigDecimal(d).stripTrailingZeros();
            return "n" + (bd.signum() == 0 ? "0" : bd.toPlainString());
        }
        throw new IllegalArgumentException("TypeError: unhashable type: " + (v.isArray() ? "'list'" : "'dict'"));
    }

    /** 파이썬 {@code a == b} (json 값끼리, 해시 가능한 값 기준). */
    public static boolean pyEquals(JsonNode a, JsonNode b) {
        if ((a != null && (a.isArray() || a.isObject())) || (b != null && (b.isArray() || b.isObject()))) {
            return a != null && b != null && a.equals(b);
        }
        return hashKey(a).equals(hashKey(b));
    }

    /** dict 의 키 집합에 key 가 있는가 (파이썬 {@code key in d}). */
    public static boolean contains(JsonNode dict, String key) {
        return dict != null && dict.isObject() && dict.has(key);
    }

    /** 파이썬 KeyError. 메시지(= {@code str(KeyError)})는 키의 repr. */
    public static final class PyKeyError extends RuntimeException {
        public PyKeyError(String key) {
            super(PyRepr.reprString(key));
        }
    }
}
