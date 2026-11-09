package com.autoparkingplan.ai.schema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JSON Schema 검증 오류 하나 (파이썬 jsonschema {@code ValidationError} 의 {@code absolute_path}·{@code message}·{@code validator}).
 *
 * @param path    인스턴스 안의 위치. 원소는 객체 키({@link String}) 또는 배열 위치({@link Integer})
 * @param message 파이썬 jsonschema 4.26 과 같은 문구 (예: {@code 'sid' is a required property})
 * @param keyword 실패한 키워드 (예: {@code required}, {@code type}, {@code format})
 */
public record SchemaError(List<Object> path, String message, String keyword) {
    public SchemaError {
        path = List.copyOf(path);
    }

    /** 파이썬 {@code '/'.join(map(str, e.absolute_path))} (루트면 빈 문자열). */
    public String pathString() {
        return path.stream().map(String::valueOf).collect(Collectors.joining("/"));
    }

    /** 파이썬판 오류 문구 형식 {@code f"{path or '(root)'}: {message}"}. */
    public String describe() {
        String p = pathString();
        return (p.isEmpty() ? "(root)" : p) + ": " + message;
    }
}
