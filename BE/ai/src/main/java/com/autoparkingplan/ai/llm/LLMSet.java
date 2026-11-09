package com.autoparkingplan.ai.llm;

/** 작성·판정·추출용 LLM 클라이언트 (설정이 없으면 null → 템플릿/근거만 표시). */
public record LLMSet(LLMClient writer, LLMClient judge, LLMClient extractor) {
}
