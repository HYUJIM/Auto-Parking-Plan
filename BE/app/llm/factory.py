"""설정으로 작성·판정·추출용 LLM 클라이언트를 만든다. 키가 없으면 None (→ 템플릿/근거만 표시)."""
from __future__ import annotations

import logging
from dataclasses import dataclass

from app.config import Settings
from app.llm.base import LLMClient, LLMUnavailable

log = logging.getLogger(__name__)


@dataclass
class LLMSet:
    writer: LLMClient | None
    judge: LLMClient | None
    extractor: LLMClient | None


def _make(provider: str, settings: Settings, *, model: str, effort: str, timeout_s: float) -> LLMClient | None:
    if provider == "none":
        return None
    if provider == "fake":
        from app.llm.fake import FakeLLM  # noqa: PLC0415
        return FakeLLM(model=f"fake:{model}")
    if provider == "anthropic":
        if not settings.anthropic_api_key:
            raise LLMUnavailable("LLM_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없습니다")
        from app.llm.anthropic_client import AnthropicLLM  # noqa: PLC0415
        return AnthropicLLM(api_key=settings.anthropic_api_key, model=model, effort=effort, timeout_s=timeout_s,
                            base_url=settings.anthropic_base_url, fallbacks=settings.anthropic_fallbacks)
    if provider == "openai":
        if not settings.openai_api_key:
            raise LLMUnavailable("LLM_PROVIDER=openai 인데 OPENAI_API_KEY 가 없습니다")
        from app.llm.openai_client import OpenAILLM  # noqa: PLC0415
        return OpenAILLM(api_key=settings.openai_api_key, model=model, timeout_s=timeout_s, base_url=settings.openai_base_url)
    raise LLMUnavailable(f"알 수 없는 LLM 제공자: {provider}")


def build_llms(settings: Settings) -> LLMSet:
    writer = _make(settings.llm_provider, settings, model=settings.llm_writer_model,
                   effort=settings.llm_writer_effort, timeout_s=settings.llm_timeout_s)
    judge = _make(settings.effective_judge_provider, settings, model=settings.llm_judge_model,
                  effort=settings.llm_judge_effort, timeout_s=settings.llm_timeout_s)
    extractor = _make(settings.llm_provider, settings, model=settings.llm_extractor_model,
                      effort=settings.llm_extractor_effort, timeout_s=settings.llm_extractor_timeout_s)
    if writer is not None and judge is not None and writer.provider == judge.provider and writer.model == judge.model:
        log.warning("판정 모델이 작성 모델과 같습니다. 설계서 9장은 분리된 판정 모델을 권장합니다.")
    return LLMSet(writer=writer, judge=judge, extractor=extractor)
