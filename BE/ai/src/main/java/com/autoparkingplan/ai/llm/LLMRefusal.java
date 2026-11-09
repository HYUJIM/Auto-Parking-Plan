package com.autoparkingplan.ai.llm;

/** 모델이 요청을 거절함 (stop_reason = refusal). */
public class LLMRefusal extends LLMError {
    public LLMRefusal(String message) {
        super(message);
    }
}
