"""
프론티어 LLM API 추상화.

AI 는 설계서 원칙 2에 따라 두 곳(+판정)에서만 쓴다.
  * rule_extraction    : 오프라인 배치에서 조문 → 규칙 JSON (사람 검수 전 draft)
  * explanation_writer : 확정된 review_item → 쉬운 설명 (사후 검증 통과해야 화면 노출)
  * semantic_judge     : 작성 모델과 분리된 판정 모델의 의미 검증 (실패만 추가 가능)

모든 호출은 'JSON 스키마를 따르는 객체 하나'를 돌려받는다. 제공자별 구조화 출력 기능으로 형식을 강제하고,
돌아온 객체는 호출한 쪽에서 원래 스키마(전체 제약 포함)로 다시 검증한다.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any, Literal, Protocol

Task = Literal["rule_extraction", "explanation_writer", "semantic_judge"]


class LLMError(Exception):
    """LLM 호출 실패 (네트워크·인증·시간 초과·형식 오류·거절). 호출자는 '실패'로 처리한다."""


class LLMRefusal(LLMError):
    pass


class LLMUnavailable(LLMError):
    """설정상 LLM 을 쓸 수 없음 (provider=none 또는 키 없음)."""


@dataclass
class LLMResult:
    data: dict[str, Any]
    model: str  # 실제 응답한 모델 (fallback 시 바뀔 수 있음)
    usage: dict[str, Any] | None = None
    request_id: str | None = None


class LLMClient(Protocol):
    provider: str
    model: str

    def generate_json(self, *, task: Task, system: str, user: str, schema: dict[str, Any], max_tokens: int = 8000) -> LLMResult:
        ...


# 구조화 출력이 지원하지 않는 JSON Schema 키워드 (제공자에 보내기 전에 제거하고, 원래 스키마로 사후 검증)
_UNSUPPORTED_KEYS = {
    "$schema", "$id", "title", "default", "minLength", "maxLength", "pattern", "minimum", "maximum",
    "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "uniqueItems",
    "if", "then", "else", "format",
}


def api_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """우리 JSON Schema(2020-12)를 구조화 출력용 부분집합으로 변환한다.

    - 미지원 제약 제거 (pattern, min/max, if/then 등) → 원래 스키마로 서버에서 다시 검증
    - oneOf → anyOf, type 배열 → anyOf
    - 모든 object 에 additionalProperties: false
    - 외부 $ref(https://...)는 지원하지 않으므로 호출 측이 쓰지 않는다 (사용 중인 출력 스키마에는 없음)
    """
    s = copy.deepcopy(schema)

    def walk(node: Any) -> Any:
        if isinstance(node, list):
            return [walk(x) for x in node]
        if not isinstance(node, dict):
            return node
        out: dict[str, Any] = {}
        for k, v in node.items():
            if k in _UNSUPPORTED_KEYS or k == "description" and not isinstance(v, str):
                continue
            if k == "allOf" and all(isinstance(x, dict) and "if" in x for x in v):
                continue  # 조건부 필수 규칙 → 사후 검증으로
            if k == "oneOf":
                out["anyOf"] = walk(v)
                continue
            if k in ("properties", "$defs"):
                out[k] = {pk: walk(pv) for pk, pv in v.items()}
                continue
            out[k] = walk(v)
        t = out.get("type")
        if isinstance(t, list):
            rest = {k: v for k, v in out.items() if k != "type"}
            variants = []
            for one in t:
                if one == "object":
                    variants.append({"type": "object", **{k: v for k, v in rest.items() if k in ("properties", "required", "additionalProperties")}})
                elif one == "array":
                    variants.append({"type": "array", **({"items": rest["items"]} if "items" in rest else {})})
                else:
                    variants.append({"type": one, **({"enum": [e for e in rest["enum"] if _matches(e, one)]} if "enum" in rest else {})})
            keep = {k: v for k, v in rest.items() if k in ("description",)}
            out = {**keep, "anyOf": variants}
        if out.get("type") == "object" or "properties" in out:
            out["additionalProperties"] = False
        return out

    s = walk(s)
    if "$defs" in s and not _uses_defs({k: v for k, v in s.items() if k != "$defs"}):
        s.pop("$defs")
    return s


def _matches(value: Any, json_type: str) -> bool:
    return {
        "string": isinstance(value, str), "null": value is None, "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "integer": isinstance(value, int) and not isinstance(value, bool), "boolean": isinstance(value, bool),
    }.get(json_type, False)


def _uses_defs(s: Any) -> bool:
    if isinstance(s, dict):
        return any((k == "$ref" and str(v).startswith("#/$defs")) or _uses_defs(v) for k, v in s.items())
    if isinstance(s, list):
        return any(_uses_defs(x) for x in s)
    return False
