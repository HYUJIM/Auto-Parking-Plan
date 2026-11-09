package com.autoparkingplan.ai.llm;

import com.autoparkingplan.common.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

/** 모델 응답 텍스트의 {@code json.loads} — 값 하나만 (뒤에 다른 토큰·빈 문자열이면 형식 오류). */
final class LlmJson {
    private static final ObjectReader READER = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LlmJson() {
    }

    /** @throws IllegalArgumentException JSON 이 아님 (파이썬 JSONDecodeError) */
    static JsonNode loads(String text) {
        JsonNode n;
        try {
            n = READER.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 형식 오류: " + e.getOriginalMessage(), e);
        }
        if (n == null || n.isMissingNode()) {
            throw new IllegalArgumentException("JSON 형식 오류: Expecting value");
        }
        return n;
    }
}
