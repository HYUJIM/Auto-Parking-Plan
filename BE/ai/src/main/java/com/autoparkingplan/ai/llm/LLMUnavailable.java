package com.autoparkingplan.ai.llm;

/** 설정상 LLM 을 쓸 수 없음 (provider=none 또는 키 없음). */
public class LLMUnavailable extends LLMError {
    public LLMUnavailable(String message) {
        super(message);
    }

    public LLMUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
