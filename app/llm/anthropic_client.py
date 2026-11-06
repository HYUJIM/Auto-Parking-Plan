"""Claude (Anthropic API) 구현 — 구조화 출력(output_config.format = json_schema) 사용."""
from __future__ import annotations

import json
import logging
from typing import Any

import anthropic

from app.llm.base import LLMError, LLMRefusal, LLMResult, Task, api_schema

log = logging.getLogger(__name__)

FALLBACK_BETA = "server-side-fallback-2026-07-01"


class AnthropicLLM:
    provider = "anthropic"

    def __init__(self, *, api_key: str, model: str, effort: str = "medium", timeout_s: float = 90.0,
                 base_url: str = "https://api.anthropic.com", fallbacks: str = "default", max_retries: int = 2):
        # base_url 을 명시해 환경변수(ANTHROPIC_BASE_URL)에 의한 의도치 않은 우회를 막는다
        self._client = anthropic.Anthropic(api_key=api_key, base_url=base_url, timeout=timeout_s, max_retries=max_retries)
        self.model = model
        self.effort = effort
        self.fallbacks = fallbacks

    def generate_json(self, *, task: Task, system: str, user: str, schema: dict[str, Any], max_tokens: int = 8000) -> LLMResult:
        kwargs: dict[str, Any] = {
            "model": self.model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "output_config": {"effort": self.effort, "format": {"type": "json_schema", "schema": api_schema(schema)}},
        }
        try:
            if self.fallbacks == "default":
                # 안전 분류기 거절 시 서버가 권장 대체 모델로 같은 요청을 다시 실행 (fallbacks: "default")
                resp = self._client.beta.messages.create(betas=[FALLBACK_BETA], extra_body={"fallbacks": "default"}, **kwargs)
            else:
                resp = self._client.messages.create(**kwargs)
        except anthropic.AuthenticationError as e:
            raise LLMError(f"{task}: 인증 실패 (API 키 확인)") from e
        except anthropic.RateLimitError as e:
            raise LLMError(f"{task}: 호출 한도 초과") from e
        except anthropic.BadRequestError as e:
            raise LLMError(f"{task}: 잘못된 요청 {e.message}") from e
        except anthropic.APIStatusError as e:
            raise LLMError(f"{task}: API 오류 {e.status_code}") from e
        except anthropic.APIConnectionError as e:  # APITimeoutError 포함
            raise LLMError(f"{task}: 연결 실패/시간 초과") from e

        if resp.stop_reason == "refusal":
            raise LLMRefusal(f"{task}: 모델이 요청을 거절함")
        if resp.stop_reason == "max_tokens":
            raise LLMError(f"{task}: 출력이 max_tokens 에서 잘림")
        text = next((b.text for b in resp.content if getattr(b, "type", None) == "text"), None)
        if text is None:
            raise LLMError(f"{task}: 텍스트 응답 없음")
        try:
            data = json.loads(text)
        except json.JSONDecodeError as e:
            raise LLMError(f"{task}: JSON 파싱 실패") from e
        if not isinstance(data, dict):
            raise LLMError(f"{task}: 최상위가 객체가 아님")
        usage = resp.usage.model_dump() if getattr(resp, "usage", None) is not None else None
        return LLMResult(data=data, model=getattr(resp, "model", self.model), usage=usage, request_id=getattr(resp, "_request_id", None))
