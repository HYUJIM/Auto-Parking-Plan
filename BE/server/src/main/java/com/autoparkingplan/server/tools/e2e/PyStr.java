package com.autoparkingplan.server.tools.e2e;

import java.util.Collection;
import java.util.NoSuchElementException;

import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬판 점검과 같은 판정·같은 상세 문구를 내기 위한 도우미 ({@code json.loads} 로 읽은 dict/list 를 다루는 코드의 번역).
 *
 * <ul>
 *   <li>{@link #get}/{@link #path} = {@code d.get(k)} (없으면 null), {@link #item} = {@code d[k]} (없으면 KeyError 처럼 예외)</li>
 *   <li>{@link #truthy} = {@code bool(v)}, {@link #eq}/{@link #numEq} = {@code ==} (파이썬은 int 와 float 를 값으로 비교)</li>
 *   <li>{@link #str}/{@link #repr}/{@link #set} = {@code str()}·{@code repr()} 표기 (상세 문구가 파이썬판 보고서와 같도록)</li>
 * </ul>
 */
final class PyStr {
    private PyStr() {
    }

    // ── 문자열 ─────────────────────────────────────────────────

    /** {@code s[:n]} (코드포인트 단위). */
    static String head(String s, int n) {
        return Py.slice(s, 0, n);
    }

    /** {@code s[-n:]}. */
    static String tail(String s, int n) {
        return Py.slice(s, -n, null);
    }

    /** {@code str(v)} — 문자열은 그대로, None·숫자·리스트·dict 는 파이썬 repr. */
    static String str(JsonNode v) {
        return PyRepr.str(v);
    }

    /** {@code repr(v)} (JsonNode·String·Number·Boolean·List·Map·null). */
    static String repr(Object v) {
        return PyRepr.repr(v);
    }

    /** {@code repr(set[str])}. 파이썬은 해시 순서라 실행마다 다르므로 여기서는 삽입 순서로 쓴다. */
    static String set(Collection<String> items) {
        if (items.isEmpty()) {
            return "set()";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String s : items) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(PyRepr.reprString(s));
            first = false;
        }
        return sb.append('}').toString();
    }

    /** {@code str(bool)}. */
    static String str(boolean b) {
        return b ? "True" : "False";
    }

    // ── 진릿값·비교 ───────────────────────────────────────────

    /** 파이썬 {@code bool(v)}. */
    static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        if (v.isObject() || v.isArray()) {
            return !v.isEmpty();
        }
        return true;
    }

    /** 파이썬 {@code bool(s)} — null 이거나 빈 문자열이면 거짓. */
    static boolean truthyStr(String s) {
        return s != null && !s.isEmpty();
    }

    /** {@code v is True}. */
    static boolean isTrue(JsonNode v) {
        return v != null && v.isBoolean() && v.booleanValue();
    }

    /** {@code v is False}. */
    static boolean isFalse(JsonNode v) {
        return v != null && v.isBoolean() && !v.booleanValue();
    }

    /** {@code v is None} (없거나 JSON null). */
    static boolean isNone(JsonNode v) {
        return Json.isNull(v);
    }

    /** {@code v == "s"}. */
    static boolean eq(JsonNode v, String s) {
        return v != null && v.isString() && v.stringValue().equals(s);
    }

    /** {@code v == 숫자}. */
    static boolean numEq(JsonNode v, double d) {
        return v != null && v.isNumber() && v.doubleValue() == d;
    }

    /** {@code a == b} (둘 다 숫자일 때 값 비교). */
    static boolean numEq(JsonNode a, JsonNode b) {
        return a != null && b != null && a.isNumber() && b.isNumber() && a.doubleValue() == b.doubleValue();
    }

    // ── dict/list 접근 ────────────────────────────────────────

    /** {@code d.get(k)} — 없으면 null (JSON null 은 NullNode 그대로). d 가 dict 가 아니면 null. */
    static JsonNode get(JsonNode d, String k) {
        return d == null || !d.isObject() ? null : d.get(k);
    }

    /** {@code (d.get(k1) or {}).get(k2)…} — 중간이 없거나 None 이면 null. */
    static JsonNode path(JsonNode d, String... keys) {
        JsonNode cur = d;
        for (String k : keys) {
            if (cur == null || !cur.isObject()) {
                return null;
            }
            cur = cur.get(k);
        }
        return cur;
    }

    /** {@code d[k]} — 없으면 KeyError 에 해당하는 예외 (점검 전체가 "E2E 실행 중 예외 없음" 실패로 기록된다). */
    static JsonNode item(JsonNode d, String k) {
        if (d == null || !d.isObject()) {
            throw new IllegalStateException("TypeError: " + str(d) + " 에는 [" + PyRepr.reprString(k) + "] 첨자 접근을 할 수 없습니다");
        }
        JsonNode v = d.get(k);
        if (v == null) {
            throw new NoSuchElementException("KeyError: " + PyRepr.reprString(k));
        }
        return v;
    }

    /** {@code l[i]} — 범위 밖이면 IndexError 에 해당하는 예외. */
    static JsonNode item(JsonNode l, int i) {
        if (l == null || !l.isArray()) {
            throw new IllegalStateException("TypeError: " + str(l) + " 에는 [" + i + "] 첨자 접근을 할 수 없습니다");
        }
        JsonNode v = l.get(i);
        if (v == null) {
            throw new IndexOutOfBoundsException("IndexError: list index out of range");
        }
        return v;
    }

    /** 숫자 값 (숫자가 아니면 TypeError 에 해당하는 예외). */
    static double num(JsonNode v) {
        if (v == null || !v.isNumber()) {
            throw new IllegalStateException("TypeError: 숫자가 아닙니다: " + str(v));
        }
        return v.doubleValue();
    }

    /** 문자열 값 또는 null. */
    static String text(JsonNode v) {
        return v != null && v.isString() ? v.stringValue() : null;
    }

    /** {@code v or []}. */
    static JsonNode orEmptyList(JsonNode v) {
        return truthy(v) ? v : Json.array();
    }
}
