"""
결정적 가짜 LLM — 오프라인 개발·테스트용 (외부 호출 없음).

  * explanation_writer: 원장 사실만 인용해 문장을 만든다 (render_fallback 과 다른 문체, source='ai')
  * semantic_judge    : 모든 문장 ENTAILED, 모든 사실 COVERED
  * rule_extraction   : scripted 응답이 없으면 LLMError (실제 추출은 사람이 준비한 기록 또는 실제 LLM 사용)
scripted={task: [응답 dict 또는 callable(payload)->dict, ...]} 로 실패 시나리오를 주입할 수 있다.
"""
from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Callable
from typing import Any

from app.llm.base import LLMError, LLMResult, Task
from app.verification.post_verifier import render_fallback

ROLE_BY_KIND = {"EXCEPTION": "exception", "CONDITION": "condition", "SUBJECT": "summary"}


class FakeLLM:
    provider = "fake"

    def __init__(self, model: str = "fake-deterministic-1", scripted: dict[str, list[Any]] | None = None):
        self.model = model
        self.scripted: dict[str, list[Any]] = defaultdict(list, scripted or {})
        self.calls: list[dict[str, Any]] = []

    def generate_json(self, *, task: Task, system: str, user: str, schema: dict[str, Any], max_tokens: int = 8000) -> LLMResult:
        payload = json.loads(user)
        self.calls.append({"task": task, "payload": payload})
        if self.scripted.get(task):
            nxt = self.scripted[task].pop(0)
            if isinstance(nxt, Exception):
                raise nxt
            data = nxt(payload) if callable(nxt) else nxt
            return LLMResult(data=data, model=self.model)
        if task == "explanation_writer":
            return LLMResult(data=_write(payload), model=self.model)
        if task == "semantic_judge":
            return LLMResult(data=_judge(payload), model=self.model)
        raise LLMError(f"FakeLLM: {task} 에 대한 scripted 응답이 없습니다")


def _write(payload: dict[str, Any]) -> dict[str, Any]:
    ledger = {"facts": payload["facts"]}
    base = render_fallback(ledger, payload["category"], payload["audience"])
    owner = payload["audience"] == "owner"
    facts = {f["fact_id"]: f for f in payload["facts"]}
    sentences = []
    for i, s in enumerate(base["sentences"]):
        f = facts.get(s["fact_ids"][0], {})
        if s["role"] == "caution":
            text = "개별 조건에 따라 달라질 수 있어 확인이 필요해요." if owner else "개별 조건에 따라 달라질 수 있어 확인 필요."
            role = "caution"
        else:
            quote = f.get("quote", "")
            text = (f"원문 기준은 “{quote}”예요." if owner else f"근거: “{quote}”")
            role = "headline" if i == 0 else ROLE_BY_KIND.get(f.get("kind"), "meaning")
        sentences.append({"sid": f"s{i + 1}", "role": role, "text": text[:200], "fact_ids": s["fact_ids"]})
    return {"schema_version": "explanation@2.0", "category": payload["category"], "audience": payload["audience"],
            "source": "ai", "sentences": sentences, "glossary": []}


def _judge(payload: dict[str, Any]) -> dict[str, Any]:
    return {"schema_version": "judge_verdict@1.0",
            "sentences": [{"sid": s["sid"], "verdict": "ENTAILED", "note": "가짜 판정기: 원문 인용 문장"} for s in payload["sentences"]],
            "coverage": [{"fact_id": f["fact_id"], "verdict": "COVERED"} for f in payload["facts"] if f.get("kind") != "STATUS"]}


ScriptFn = Callable[[dict[str, Any]], dict[str, Any]]
