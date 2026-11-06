"""OpenAI 호환 제공자 (선택). `pip install .[openai]` 필요.

JSON 객체 모드로 응답을 받고, 스키마는 프롬프트에 함께 넣어 지시한다. 결과는 호출 측이 원래 스키마로 다시 검증한다.
모델 이름은 반드시 설정(LLM_WRITER_MODEL 등)으로 지정한다 (기본값을 추측하지 않음).
"""
from __future__ import annotations

import json
from typing import Any

from app.llm.base import LLMError, LLMResult, LLMUnavailable, Task, api_schema


class OpenAILLM:
    provider = "openai"

    def __init__(self, *, api_key: str, model: str, timeout_s: float = 90.0, base_url: str | None = None):
        try:
            import openai  # noqa: PLC0415
        except ImportError as e:  # pragma: no cover
            raise LLMUnavailable("openai 패키지가 설치되지 않았습니다: pip install .[openai]") from e
        self._openai = openai
        self._client = openai.OpenAI(api_key=api_key, base_url=base_url, timeout=timeout_s, max_retries=2)
        self.model = model

    def generate_json(self, *, task: Task, system: str, user: str, schema: dict[str, Any], max_tokens: int = 8000) -> LLMResult:
        sys = system + "\n\n출력은 아래 JSON Schema 를 따르는 JSON 객체 하나뿐이다.\n" + json.dumps(api_schema(schema), ensure_ascii=False)
        try:
            resp = self._client.chat.completions.create(
                model=self.model,
                messages=[{"role": "system", "content": sys}, {"role": "user", "content": user}],
                response_format={"type": "json_object"},
                max_completion_tokens=max_tokens,
            )
        except self._openai.APIError as e:
            raise LLMError(f"{task}: OpenAI API 오류 {type(e).__name__}") from e
        choice = resp.choices[0]
        if choice.finish_reason == "length":
            raise LLMError(f"{task}: 출력이 잘림")
        try:
            data = json.loads(choice.message.content or "")
        except json.JSONDecodeError as e:
            raise LLMError(f"{task}: JSON 파싱 실패") from e
        return LLMResult(data=data, model=resp.model, usage=resp.usage.model_dump() if resp.usage else None)
