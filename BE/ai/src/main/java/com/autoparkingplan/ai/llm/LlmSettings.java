package com.autoparkingplan.ai.llm;

/**
 * LLM 설정 (파이썬 {@code app/config.py} 의 Settings 중 LLM 부분). 기본값은 {@link #defaults()}.
 *
 * @param llmProvider          "anthropic" | "openai" | "fake" | "none"
 * @param judgeProvider        판정 모델 제공자 (null → llmProvider 와 같음)
 * @param anthropicApiKey      ANTHROPIC_API_KEY (null·빈 문자열 = 없음)
 * @param anthropicBaseUrl     기본 https://api.anthropic.com
 * @param anthropicFallbacks   "default" | "off"
 * @param openaiApiKey         OPENAI_API_KEY
 * @param openaiBaseUrl        null 이면 OPENAI_BASE_URL 환경변수 또는 공식 주소
 * @param llmWriterModel       작성 모델
 * @param llmJudgeModel        판정 모델 (설계서 9장: 작성 모델과 분리)
 * @param llmExtractorModel    규칙 추출 모델
 * @param llmWriterEffort      "low" | "medium" | "high" | "xhigh" | "max"
 * @param llmJudgeEffort       판정 effort
 * @param llmExtractorEffort   추출 effort
 * @param llmTimeoutS          작성·판정 호출 시간 제한(초)
 * @param llmExtractorTimeoutS 추출 호출 시간 제한(초)
 */
public record LlmSettings(
        String llmProvider,
        String judgeProvider,
        String anthropicApiKey,
        String anthropicBaseUrl,
        String anthropicFallbacks,
        String openaiApiKey,
        String openaiBaseUrl,
        String llmWriterModel,
        String llmJudgeModel,
        String llmExtractorModel,
        String llmWriterEffort,
        String llmJudgeEffort,
        String llmExtractorEffort,
        double llmTimeoutS,
        double llmExtractorTimeoutS) {

    /** 파이썬 Settings 기본값과 같다. */
    public static LlmSettings defaults() {
        return new LlmSettings("none", null, null, "https://api.anthropic.com", "default", null, null,
                "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "medium", "medium", "high", 90.0, 600.0);
    }

    /** 파이썬 {@code effective_judge_provider}: judge_provider or llm_provider. */
    public String effectiveJudgeProvider() {
        return judgeProvider != null && !judgeProvider.isEmpty() ? judgeProvider : llmProvider;
    }

    public LlmSettings withProviders(String llmProvider, String judgeProvider) {
        return new LlmSettings(llmProvider, judgeProvider, anthropicApiKey, anthropicBaseUrl, anthropicFallbacks, openaiApiKey,
                openaiBaseUrl, llmWriterModel, llmJudgeModel, llmExtractorModel, llmWriterEffort, llmJudgeEffort, llmExtractorEffort,
                llmTimeoutS, llmExtractorTimeoutS);
    }

    public LlmSettings withKeys(String anthropicApiKey, String openaiApiKey) {
        return new LlmSettings(llmProvider, judgeProvider, anthropicApiKey, anthropicBaseUrl, anthropicFallbacks, openaiApiKey,
                openaiBaseUrl, llmWriterModel, llmJudgeModel, llmExtractorModel, llmWriterEffort, llmJudgeEffort, llmExtractorEffort,
                llmTimeoutS, llmExtractorTimeoutS);
    }

    @Override
    public String toString() { // 키는 남기지 않는다
        return "LlmSettings[provider=" + llmProvider + ", judgeProvider=" + judgeProvider + ", anthropicApiKey="
                + (anthropicApiKey == null || anthropicApiKey.isEmpty() ? "없음" : "***") + ", anthropicBaseUrl=" + anthropicBaseUrl
                + ", anthropicFallbacks=" + anthropicFallbacks + ", openaiApiKey="
                + (openaiApiKey == null || openaiApiKey.isEmpty() ? "없음" : "***") + ", openaiBaseUrl=" + openaiBaseUrl
                + ", writer=" + llmWriterModel + "/" + llmWriterEffort + ", judge=" + llmJudgeModel + "/" + llmJudgeEffort
                + ", extractor=" + llmExtractorModel + "/" + llmExtractorEffort + ", timeoutS=" + llmTimeoutS
                + ", extractorTimeoutS=" + llmExtractorTimeoutS + "]";
    }
}
