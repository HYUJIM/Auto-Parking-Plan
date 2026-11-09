package com.autoparkingplan.db.support;

import java.lang.reflect.Type;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * JSON 컬럼(H2 JSON, PostgreSQL JSONB) ↔ 자바 값. 엔티티의 JSON 필드는 모두 {@link JsonNode} 다.
 * 공용 Jackson 3 매퍼({@link Json})를 써서 앱 전체가 같은 숫자·문자열 규칙으로 읽고 쓴다.
 */
public final class Jackson3JsonFormatMapper implements FormatMapper {
    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        if (charSequence == null) {
            return null;
        }
        Type t = javaType.getJavaType();
        JsonNode node = Json.parse(charSequence.toString());
        if (node.isNull()) {
            return null; // JSON null 과 SQL NULL 을 같게 본다 (파이썬판이 None 을 JSON null 로 저장한 행 포함)
        }
        if (t == JsonNode.class || (t instanceof Class<?> c && JsonNode.class.isAssignableFrom(c))) {
            return (T) node;
        }
        if (t == String.class) {
            return (T) charSequence.toString();
        }
        return (T) Json.mapper().convertValue(node, Json.mapper().constructType(t));
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode n) {
            return Json.write(n);
        }
        if (value instanceof String s) {
            return s;
        }
        return Json.write(value);
    }
}
