package com.autoparkingplan.ai.pycompat;

import java.util.List;
import java.util.Map;

import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬 {@code repr()} / {@code str()} 과 같은 문자열을 만든다 ({@code json.loads} 로 읽은 값 기준).
 *
 * <p>검증 오류 문구(jsonschema 의 {@code f"{instance!r} is not of type ..."}, 사후 검증기의
 * {@code f"원장 누락: {quote!r}"}, 재생성 피드백의 {@code f"{expected}"})가 파이썬판과 같아야
 * 같은 보고서·같은 재생성 프롬프트가 나온다.
 * <ul>
 *   <li>dict → {@code {'k': v}}, list → {@code [a, b]}, None/True/False, int → 숫자, float → {@link Py#floatRepr(double)}</li>
 *   <li>str → 작은따옴표(문자열에 ' 만 있고 " 가 없으면 큰따옴표). 출력 불가 문자는 백슬래시-x 두 자리 / 백슬래시-u 네 자리 /
 *       백슬래시-U 여덟 자리 16진수</li>
 * </ul>
 */
public final class PyRepr {
    private PyRepr() {
    }

    /** 파이썬 {@code repr(v)}. null(자바)과 JSON null 은 {@code None}. */
    public static String repr(JsonNode v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    /** 파이썬 {@code str(v)} — f-string 의 {@code {v}}. 문자열은 그대로, 나머지는 repr 과 같다. */
    public static String str(JsonNode v) {
        if (v != null && v.isString()) {
            return v.stringValue();
        }
        return repr(v);
    }

    /** 자바 값(String·Number·Boolean·List·Map·JsonNode·null)의 파이썬 repr. */
    public static String repr(Object v) {
        StringBuilder sb = new StringBuilder();
        writeObject(sb, v);
        return sb.toString();
    }

    private static void writeObject(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("None");
        } else if (v instanceof JsonNode n) {
            write(sb, n);
        } else if (v instanceof String s) {
            sb.append(reprString(s));
        } else if (v instanceof Boolean b) {
            sb.append(b ? "True" : "False");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(Py.floatRepr(((Number) v).doubleValue()));
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(", ");
                }
                writeObject(sb, o);
                first = false;
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                writeObject(sb, e.getKey());
                sb.append(": ");
                writeObject(sb, e.getValue());
                first = false;
            }
            sb.append('}');
        } else {
            sb.append(reprString(v.toString()));
        }
    }

    private static void write(StringBuilder sb, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            sb.append("None");
        } else if (v.isBoolean()) {
            sb.append(v.booleanValue() ? "True" : "False");
        } else if (v.isIntegralNumber()) {
            sb.append(v.bigIntegerValue().toString());
        } else if (v.isNumber()) {
            sb.append(Py.floatRepr(v.doubleValue()));
        } else if (v.isString()) {
            sb.append(reprString(v.stringValue()));
        } else if (v.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode item : v.values()) {
                if (!first) {
                    sb.append(", ");
                }
                write(sb, item);
                first = false;
            }
            sb.append(']');
        } else if (v.isObject()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(reprString(e.getKey())).append(": ");
                write(sb, e.getValue());
                first = false;
            }
            sb.append('}');
        } else {
            sb.append(reprString(v.asString()));
        }
    }

    /** 파이썬 {@code repr(str)} (CPython unicode_repr 와 같은 규칙). */
    public static String reprString(String s) {
        boolean hasSingle = s.indexOf('\'') >= 0;
        boolean hasDouble = s.indexOf('"') >= 0;
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(quote);
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
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
            } else if (cp < 0x7F) {
                sb.append((char) cp);
            } else if (isPrintable(cp)) {
                sb.appendCodePoint(cp);
            } else if (cp <= 0xFF) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp <= 0xFFFF) {
                sb.append(String.format("\\u%04x", cp));
            } else {
                sb.append(String.format("\\U%08x", cp));
            }
        }
        sb.append(quote);
        return sb.toString();
    }

    /** 파이썬 {@code str.isprintable()} (문자 하나): Cc Cf Cs Co Cn Zl Zp Zs(공백 제외)는 출력 불가. */
    static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }
}
