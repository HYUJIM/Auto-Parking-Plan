package com.autoparkingplan.ai.llm;

import tools.jackson.databind.JsonNode;

/**
 * LLM 호출 결과.
 *
 * @param data      스키마를 따르도록 요청한 JSON 객체 (호출 측이 원래 스키마로 다시 검증한다)
 * @param model     실제 응답한 모델 (fallback 시 바뀔 수 있음)
 * @param usage     토큰 사용량 (제공자 응답 그대로, 없으면 null)
 * @param requestId 제공자 요청 id (없으면 null)
 */
public record LLMResult(JsonNode data, String model, JsonNode usage, String requestId) {
    public LLMResult(JsonNode data, String model) {
        this(data, model, null, null);
    }
}
