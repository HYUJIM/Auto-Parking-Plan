package com.autoparkingplan.ai.llm;

import java.util.Map;
import java.util.Set;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 우리 JSON Schema(2020-12)를 제공자 구조화 출력용 부분집합으로 바꾼다 ({@code app/llm/base.py} 의 {@code api_schema}).
 *
 * <ul>
 *   <li>미지원 제약 제거 (pattern, min/max, if/then 등) → 원래 스키마로 서버에서 다시 검증</li>
 *   <li>oneOf → anyOf, type 배열 → anyOf</li>
 *   <li>모든 object 에 additionalProperties: false</li>
 *   <li>외부 $ref(https://...)는 지원하지 않으므로 호출 측이 쓰지 않는다 (사용 중인 출력 스키마에는 없음)</li>
 * </ul>
 * 키 순서까지 파이썬판과 같은 결과를 만든다 (제공자에 보내는 요청 본문이 같도록).
 */
public final class ApiSchema {
    /** 구조화 출력이 지원하지 않는 JSON Schema 키워드 (제공자에 보내기 전에 제거하고, 원래 스키마로 사후 검증). */
    public static final Set<String> UNSUPPORTED_KEYS = Set.of(
            "$schema", "$id", "title", "default", "minLength", "maxLength", "pattern", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "uniqueItems",
            "if", "then", "else", "format");

    private ApiSchema() {
    }

    /** 파이썬 {@code api_schema(schema)}. 입력은 바꾸지 않는다. */
    public static JsonNode apiSchema(JsonNode schema) {
        JsonNode s = walk(schema);
        if (s.isObject() && s.has("$defs")) {
            ObjectNode rest = Json.object();
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                if (!e.getKey().equals("$defs")) {
                    rest.set(e.getKey(), e.getValue());
                }
            }
            if (!usesDefs(rest)) {
                ((ObjectNode) s).remove("$defs");
            }
        }
        return s;
    }

    private static JsonNode walk(JsonNode node) {
        if (node.isArray()) {
            ArrayNode out = Json.array();
            for (JsonNode x : node.values()) {
                out.add(walk(x));
            }
            return out;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = Json.object();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (UNSUPPORTED_KEYS.contains(k) || (k.equals("description") && !v.isString())) {
                continue;
            }
            if (k.equals("allOf") && allConditional(v)) {
                continue; // 조건부 필수 규칙 → 사후 검증으로
            }
            if (k.equals("oneOf")) {
                out.set("anyOf", walk(v));
                continue;
            }
            if (k.equals("properties") || k.equals("$defs")) {
                ObjectNode m = Json.object();
                for (Map.Entry<String, JsonNode> p : v.properties()) {
                    m.set(p.getKey(), walk(p.getValue()));
                }
                out.set(k, m);
                continue;
            }
            out.set(k, walk(v));
        }
        JsonNode t = out.get("type");
        if (t != null && t.isArray()) {
            ObjectNode rest = Json.object();
            for (Map.Entry<String, JsonNode> e : out.properties()) {
                if (!e.getKey().equals("type")) {
                    rest.set(e.getKey(), e.getValue());
                }
            }
            ArrayNode variants = Json.array();
            for (JsonNode one : t.values()) {
                ObjectNode v = Json.object();
                if (PyObj.eq(one, "object")) {
                    v.put("type", "object");
                    for (Map.Entry<String, JsonNode> e : rest.properties()) {
                        if (Set.of("properties", "required", "additionalProperties").contains(e.getKey())) {
                            v.set(e.getKey(), e.getValue());
                        }
                    }
                } else if (PyObj.eq(one, "array")) {
                    v.put("type", "array");
                    if (rest.has("items")) {
                        v.set("items", rest.get("items"));
                    }
                } else {
                    v.set("type", one);
                    if (rest.has("enum")) {
                        ArrayNode en = v.putArray("enum");
                        for (JsonNode e : rest.get("enum").values()) {
                            if (matches(e, one)) {
                                en.add(e);
                            }
                        }
                    }
                }
                variants.add(v);
            }
            ObjectNode replaced = Json.object();
            if (rest.has("description")) {
                replaced.set("description", rest.get("description"));
            }
            replaced.set("anyOf", variants);
            out = replaced;
        }
        if (PyObj.eq(out.get("type"), "object") || out.has("properties")) {
            out.set("additionalProperties", BooleanNode.FALSE);
        }
        return out;
    }

    /** {@code all(isinstance(x, dict) and "if" in x for x in v)} (빈 목록이면 참). */
    private static boolean allConditional(JsonNode v) {
        if (v.isObject()) { // 파이썬은 dict 를 키로 순회한다: 키(str)는 dict 가 아니므로 거짓 (빈 dict 면 참)
            return v.isEmpty();
        }
        if (v.isString()) {
            return v.stringValue().isEmpty();
        }
        if (!v.isArray()) {
            throw new IllegalArgumentException("TypeError: 'allOf' 값을 순회할 수 없음");
        }
        for (JsonNode x : v.values()) {
            if (!(x.isObject() && x.has("if"))) {
                return false;
            }
        }
        return true;
    }

    /** 파이썬 {@code _matches(value, json_type)}. */
    static boolean matches(JsonNode value, JsonNode jsonType) {
        if (!jsonType.isString()) {
            return false;
        }
        return switch (jsonType.stringValue()) {
            case "string" -> value.isString();
            case "null" -> value.isNull();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
    }

    /** 파이썬 {@code _uses_defs(s)}: "#/$defs" 로 시작하는 $ref 가 있는가. */
    static boolean usesDefs(JsonNode s) {
        if (s.isObject()) {
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                if (e.getKey().equals("$ref") && pyStr(e.getValue()).startsWith("#/$defs")) {
                    return true;
                }
                if (usesDefs(e.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (s.isArray()) {
            for (JsonNode x : s.values()) {
                if (usesDefs(x)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String pyStr(JsonNode v) {
        return com.autoparkingplan.ai.pycompat.PyRepr.str(v);
    }
}
