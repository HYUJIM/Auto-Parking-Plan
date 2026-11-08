package com.autoparkingplan.ai.llm;

/** LLM 호출 실패 (네트워크·인증·시간 초과·형식 오류·거절). 호출자는 '실패'로 처리한다. */
public class LLMError extends RuntimeException {
    public LLMError(String message) {
        super(message);
    }

    public LLMError(String message, Throwable cause) {
        super(message, cause);
    }
}
