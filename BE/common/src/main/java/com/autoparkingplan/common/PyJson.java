package com.autoparkingplan.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬 {@code json.dumps} 와 바이트 단위로 같은 출력을 만든다.
 *
 * <p>원장 해시(ledger_hash)·설명 해시·원문 해시·추출 입력 해시처럼 "JSON 을 직렬화해서 sha256" 하는 값이
 * 파이썬판과 같아야 같은 DB·같은 기록 데이터(data/seed)를 그대로 쓸 수 있다.
 * <ul>
 *   <li>기본 구분자 {@code ", "} / {@code ": "} (indent 가 있으면 {@code ","} / {@code ": "})</li>
 *   <li>ensure_ascii=False: {@code "} 백슬래시와 U+0000~U+001F 만 이스케이프 (\n \r \t \b \f 는 짧은 형태, 나머지는 백슬래시-u 네 자리 소문자 16진수)</li>
 *   <li>ensure_ascii=True: 출력 가능한 ASCII 밖(DEL 포함)을 백슬래시-u 네 자리 16진수(소문자, 보조 평면은 서로게이트 쌍)로</li>
 *   <li>실수는 {@link Py#floatRepr(double)} (NaN·Infinity 는 파이썬 기본값 allow_nan=True 처럼 그대로 씀)</li>
 *   <li>sort_keys=True 는 코드포인트 순 정렬 (파이썬 문자열 비교와 같음)</li>
 * </ul>
 */
public final class PyJson {
    private PyJson() {
    }

    /** {@code json.dumps(v, ensure_ascii=False, sort_keys=True)} — 해시용 표준 형태. */
    public static String dumpsSorted(JsonNode v) {
        return dumps(v, true, false, null);
    }

    /** {@code json.dumps(v, ensure_ascii=False)} — 키 순서 유지. */
    public static String dumps(JsonNode v) {
        return dumps(v, false, false, null);
    }

    /**
     * @param sortKeys   파이썬 sort_keys
     * @param ensureAscii 파이썬 ensure_ascii
     * @param indent     null 이면 한 줄, 숫자면 그만큼 들여쓰기
     */
    public static String dumps(JsonNode v, boolean sortKeys, boolean ensureAscii, Integer indent) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, sortKeys, ensureAscii, indent, 0);
        return sb.toString();
    }

    /** 자바 객체(Map·List·레코드)를 먼저 JsonNode 로 바꿔서 직렬화. */
    public static String dumpsSorted(Object v) {
        return dumpsSorted(v instanceof JsonNode n ? n : Json.valueToTree(v));
    }

    private static void write(StringBuilder sb, JsonNode v, boolean sortKeys, boolean ascii, Integer indent, int level) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            sb.append("null");
        } else if (v.isBoolean()) {
            sb.append(v.booleanValue() ? "true" : "false");
        } else if (v.isIntegralNumber()) {
            sb.append(v.bigIntegerValue().toString());
        } else if (v.isNumber()) {
            double d = v.doubleValue();
            if (Double.isNaN(d)) {
                sb.append("NaN");
            } else if (Double.isInfinite(d)) {
                sb.append(d > 0 ? "Infinity" : "-Infinity");
            } else {
                sb.append(Py.floatRepr(d));
            }
        } else if (v.isString()) {
            writeString(sb, v.stringValue(), ascii);
        } else if (v.isArray()) {
            if (v.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (JsonNode item : v.values()) {
                if (!first) {
                    sb.append(indent == null ? ", " : ",");
                }
                newline(sb, indent, level + 1);
                write(sb, item, sortKeys, ascii, indent, level + 1);
                first = false;
            }
            newline(sb, indent, level);
            sb.append(']');
        } else if (v.isObject()) {
            if (v.isEmpty()) {
                sb.append("{}");
                return;
            }
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(v.properties());
            if (sortKeys) {
                entries.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : entries) {
                if (!first) {
                    sb.append(indent == null ? ", " : ",");
                }
                newline(sb, indent, level + 1);
                writeString(sb, e.getKey(), ascii);
                sb.append(": ");
                write(sb, e.getValue(), sortKeys, ascii, indent, level + 1);
                first = false;
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (v.isBinary()) {
            throw new IllegalArgumentException("이진 값은 JSON 으로 직렬화할 수 없습니다");
        } else {
            writeString(sb, v.asString(), ascii);
        }
    }

    private static void newline(StringBuilder sb, Integer indent, int level) {
        if (indent != null) {
            sb.append('\n').append(" ".repeat(indent * level));
        }
    }

    /** 파이썬 문자열 비교 (코드포인트 순). 자바 compareTo 는 UTF-16 단위라 보조 평면 문자에서 순서가 다를 수 있다. */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static void writeString(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // ensure_ascii=True 는 ' '~'~' 밖을 모두 이스케이프 (DEL 0x7F 포함). 서로게이트는 char 단위라 자동으로 쌍이 된다
                    if (c < 0x20 || (ascii && c > 0x7E)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
