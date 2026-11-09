package com.autoparkingplan.ai.llm;

import tools.jackson.databind.JsonNode;

/**
 * 프론티어 LLM API 추상화 ({@code app/llm/base.py}).
 *
 * <p>AI 는 설계서 원칙 2에 따라 두 곳(+판정)에서만 쓴다 ({@link Task}).
 * 모든 호출은 'JSON 스키마를 따르는 객체 하나'를 돌려받는다. 제공자별 구조화 출력 기능으로 형식을 강제하고,
 * 돌아온 객체는 호출한 쪽에서 원래 스키마(전체 제약 포함)로 다시 검증한다.
 * 구현은 여러 스레드에서 동시에 불러도 안전해야 한다.
 */
public interface LLMClient {
    /** 제공자 이름: "anthropic" | "openai" | "fake". */
    String provider();

    /** 요청할 모델 이름. */
    String model();

    /**
     * @param task      용도 (오류 문구의 머리말)
     * @param system    시스템 프롬프트
     * @param user      사용자 메시지 (JSON 문자열)
     * @param schema    출력 JSON Schema (2020-12, 원본 그대로 — 제공자용 변환은 구현이 한다)
     * @param maxTokens 최대 출력 토큰
     * @throws LLMError 네트워크·인증·시간 초과·형식 오류·거절
     */
    LLMResult generateJson(Task task, String system, String user, JsonNode schema, int maxTokens);

    /** {@code max_tokens=8000} 기본값. */
    default LLMResult generateJson(Task task, String system, String user, JsonNode schema) {
        return generateJson(task, system, user, schema, 8000);
    }
}
