package com.autoparkingplan.server.config;

import com.autoparkingplan.ai.llm.LLMClient;
import com.autoparkingplan.ai.llm.LLMSet;
import com.autoparkingplan.ai.llm.LlmFactory;
import com.autoparkingplan.ai.llm.LlmSettings;

/** 작성·판정·추출용 LLM (설정이 없으면 null → 템플릿/근거만 표시). 파이썬 app.llm.factory.LLMSet. */
public record Llms(LLMClient writer, LLMClient judge, LLMClient extractor) {
    public static Llms build(AppSettings s) {
        LLMSet set = LlmFactory.buildLlms(new LlmSettings(s.llmProvider, s.judgeProvider, s.anthropicApiKey, s.anthropicBaseUrl,
                s.anthropicFallbacks, s.openaiApiKey, s.openaiBaseUrl, s.llmWriterModel, s.llmJudgeModel, s.llmExtractorModel, s.llmWriterEffort,
                s.llmJudgeEffort, s.llmExtractorEffort, s.llmTimeoutS, s.llmExtractorTimeoutS));
        return new Llms(set.writer(), set.judge(), set.extractor());
    }
}
