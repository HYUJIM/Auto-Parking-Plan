package com.autoparkingplan.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 설정으로 작성·판정·추출용 LLM 클라이언트를 만든다. 키가 없으면 null (→ 템플릿/근거만 표시). {@code app/llm/factory.py}. */
public final class LlmFactory {
    private static final Logger log = LoggerFactory.getLogger(LlmFactory.class);

    private LlmFactory() {
    }

    /**
     * 제공자 하나로 클라이언트를 만든다.
     *
     * @return provider=none 이면 null
     * @throws LLMUnavailable 키가 없거나 모르는 제공자
     */
    static LLMClient make(String provider, LlmSettings settings, String model, String effort, double timeoutS) {
        switch (provider) {
            case "none":
                return null;
            case "fake":
                return new FakeLLM("fake:" + model);
            case "anthropic":
                if (isBlank(settings.anthropicApiKey())) {
                    throw new LLMUnavailable("LLM_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없습니다");
                }
                return new AnthropicLLM(settings.anthropicApiKey(), model, effort, timeoutS, settings.anthropicBaseUrl(),
                        settings.anthropicFallbacks(), 2);
            case "openai":
                if (isBlank(settings.openaiApiKey())) {
                    throw new LLMUnavailable("LLM_PROVIDER=openai 인데 OPENAI_API_KEY 가 없습니다");
                }
                return new OpenAILLM(settings.openaiApiKey(), model, timeoutS, settings.openaiBaseUrl());
            default:
                throw new LLMUnavailable("알 수 없는 LLM 제공자: " + provider);
        }
    }

    /** 파이썬 {@code build_llms(settings)}. */
    public static LLMSet buildLlms(LlmSettings settings) {
        LLMClient writer = make(settings.llmProvider(), settings, settings.llmWriterModel(), settings.llmWriterEffort(),
                settings.llmTimeoutS());
        LLMClient judge = make(settings.effectiveJudgeProvider(), settings, settings.llmJudgeModel(), settings.llmJudgeEffort(),
                settings.llmTimeoutS());
        LLMClient extractor = make(settings.llmProvider(), settings, settings.llmExtractorModel(), settings.llmExtractorEffort(),
                settings.llmExtractorTimeoutS());
        if (writer != null && judge != null && writer.provider().equals(judge.provider()) && writer.model().equals(judge.model())) {
            log.warn("판정 모델이 작성 모델과 같습니다. 설계서 9장은 분리된 판정 모델을 권장합니다.");
        }
        return new LLMSet(writer, judge, extractor);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
