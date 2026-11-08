package com.autoparkingplan.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공용 JSON 도구 (Jackson 3). 파이썬판의 dict/list 를 그대로 다루는 곳은 {@link JsonNode} 를 쓴다.
 *
 * <p>읽기 규칙은 파이썬 {@code json.loads} 와 맞춘다: 실수는 double, 정수는 long/BigInteger, NaN/Infinity 허용.
 */
public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNodeFactory nodes() {
        return MAPPER.getNodeFactory();
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** JSON 문자열 해석. 형식 오류는 {@link IllegalArgumentException} (파이썬 ValueError 와 같은 취급). */
    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 형식 오류: " + e.getOriginalMessage(), e);
        }
    }

    /** UTF-8 바이트 해석 (파이썬 {@code json.loads(bytes)} 와 같이 BOM·UTF-16/32 자동 판별은 Jackson 이 한다). */
    public static JsonNode parse(byte[] data) {
        try {
            return MAPPER.readTree(data);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 형식 오류: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode parse(InputStream in) {
        try (in) {
            return MAPPER.readTree(in);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 형식 오류: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 클래스패스 자원(JSON) 읽기. */
    public static JsonNode resource(Class<?> anchor, String path) {
        InputStream in = anchor.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("자원을 찾을 수 없습니다: " + path);
        }
        return parse(in);
    }

    /** 클래스패스 자원(텍스트, UTF-8) 읽기. */
    public static String resourceText(Class<?> anchor, String path) {
        try (InputStream in = anchor.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("자원을 찾을 수 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 자바 객체(레코드·Map·List) → JsonNode. */
    public static JsonNode valueToTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.treeToValue(node, type);
    }

    /** Jackson 기본 직렬화 (압축). 파이썬과 같은 바이트가 필요하면 {@link PyJson} 을 쓴다. */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** JsonNode → 자바 기본 값(Map/List/String/Number/Boolean/null). 순서 보존. */
    public static Object toJava(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                m.put(e.getKey(), toJava(e.getValue()));
            }
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>(n.size());
            for (Iterator<JsonNode> it = n.values().iterator(); it.hasNext(); ) {
                l.add(toJava(it.next()));
            }
            return l;
        }
        if (n.isString()) {
            return n.stringValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            return n.canConvertToLong() ? (Object) n.longValue() : n.bigIntegerValue();
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        return n.toString();
    }

    /** 문자열 값 또는 null (파이썬 {@code d.get(k)} 가 str 일 때). 없거나 null 이면 null. */
    public static String text(JsonNode obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonNode v = obj.get(field);
        return v == null || v.isNull() || v.isMissingNode() ? null : v.isString() ? v.stringValue() : v.toString();
    }

    /** 숫자 값 또는 null. */
    public static Double number(JsonNode obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonNode v = obj.get(field);
        return v == null || !v.isNumber() ? null : v.doubleValue();
    }

    /** null·누락·JSON null 이면 true. */
    public static boolean isNull(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    /** 깊은 복사. */
    public static JsonNode copy(JsonNode n) {
        return n == null ? null : n.deepCopy();
    }
}
