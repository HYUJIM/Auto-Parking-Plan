"""검토 엔진 보조 로직: 명칭 정규화, 조건 평가, 계산식 평가, 용도 묶음."""
from __future__ import annotations

import ast
import math
import re
from typing import Any

from app.db.models import Rule

ZONE_CODES = {  # rule_key 용 약어 (rule-extract 프롬프트와 동일)
    "제1종전용주거지역": "R1E", "제2종전용주거지역": "R2E", "제1종일반주거지역": "R1G", "제2종일반주거지역": "R2G",
    "제3종일반주거지역": "R3G", "준주거지역": "RQ", "중심상업지역": "CC", "일반상업지역": "CG", "근린상업지역": "CN",
    "유통상업지역": "CD", "전용공업지역": "IE", "일반공업지역": "IG", "준공업지역": "IQ", "보전녹지지역": "GC",
    "생산녹지지역": "GP", "자연녹지지역": "GN",
}
# 화면 묶음 (기능정의서 10장: 주거 / 근린생활 / 업무 / 기타)
USE_GROUPS = [
    ("주거", re.compile(r"단독주택|공동주택|다가구|다세대|연립|아파트|기숙사")),
    ("근린생활", re.compile(r"근린생활시설")),
    ("업무", re.compile(r"업무시설")),
]
L4_ESCALATE_RE = re.compile(r"지구단위계획|특별계획구역|연구개발특구|경제자유구역|산업단지|문화(?:유산|재)보호구역|역사문화환경\s*보존지역|개발제한구역")


def norm(s: Any) -> str:
    return re.sub(r"\s+", "", str(s or ""))


def base_use_name(use_name: str) -> str:
    """'자동차관련시설 중 동호아목에 해당하는 것과 주차장 및 세차장' → '자동차관련시설', '공동주택(아파트를 제외한다)' → '공동주택'."""
    b = re.split(r"[\(\[（［]", use_name)[0]
    return norm(b.split(" 중 ")[0].split("로서")[0])


def use_group(use_name: str) -> str:
    for label, rx in USE_GROUPS:
        if rx.search(use_name):
            return label
    return "기타"


def eval_condition(c, inputs: dict[str, Any]) -> bool | None:
    """조건 하나. True/False, 입력이 없어 판단 불가하면 None."""
    val = inputs.get(c.field)
    if val is None or val == []:
        return False if c.operator == "exists" else None
    target = c.value_json
    if c.operator == "exists":
        return bool(val)
    if isinstance(val, list):  # district/zone 처럼 여러 값
        vals = {norm(v) for v in val}
        if c.operator == "eq":
            return norm(target) in vals
        if c.operator == "in":
            return bool(vals & {norm(t) for t in target})
        return None
    if c.operator == "eq":
        return norm(val) == norm(target) if isinstance(target, str) else float(val) == float(target)
    if c.operator == "in":
        return norm(val) in {norm(t) for t in target}
    try:
        x = float(val)
    except (TypeError, ValueError):
        return None
    if c.operator == "gte":
        return x >= float(target)
    if c.operator == "lte":
        return x <= float(target)
    if c.operator == "between":
        lo, hi = target
        return float(lo) <= x <= float(hi)
    return None


def match_rule(rule: Rule, inputs: dict[str, Any]) -> bool | None:
    """모든 조건(AND). 하나라도 False 면 False, 판단 불가가 있으면 None."""
    unknown = False
    for c in rule.conditions:
        r = eval_condition(c, inputs)
        if r is False:
            return False
        if r is None:
            unknown = True
    return None if unknown else True


_ALLOWED_NAMES = {"gross_floor_area", "facility_area", "households", "site_area"}
_ALLOWED_FUNCS = {"ceil": math.ceil, "floor": math.floor, "min": min, "max": max}


def eval_formula(expr: str, variables: dict[str, float]) -> float:
    """규칙 계산식 DSL (rule_extraction.schema 의 formula) 안전 평가. 허용: 변수 4개, + - * /, ceil floor min max.

    eval() 을 쓰지 않고 AST 화이트리스트로만 계산한다 (AI 가 추출한 식에 코드가 섞여도 실행되지 않음)."""
    if not isinstance(expr, str) or len(expr) > 500:
        raise ValueError("계산식이 너무 깁니다")
    try:
        tree = ast.parse(expr, mode="eval")
    except (SyntaxError, RecursionError, MemoryError) as e:
        raise ValueError("계산식을 해석할 수 없습니다") from e

    def ev(n):
        if isinstance(n, ast.Expression):
            return ev(n.body)
        if isinstance(n, ast.Constant) and isinstance(n.value, (int, float)):
            return float(n.value)
        if isinstance(n, ast.Name) and n.id in _ALLOWED_NAMES:
            if variables.get(n.id) is None:
                raise ValueError(f"변수 {n.id} 값이 없음")
            return float(variables[n.id])
        if isinstance(n, ast.BinOp) and isinstance(n.op, (ast.Add, ast.Sub, ast.Mult, ast.Div)):
            a, b = ev(n.left), ev(n.right)
            return {ast.Add: a + b, ast.Sub: a - b, ast.Mult: a * b}.get(type(n.op)) if not isinstance(n.op, ast.Div) else a / b
        if isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.USub):
            return -ev(n.operand)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id in _ALLOWED_FUNCS and not n.keywords:
            return float(_ALLOWED_FUNCS[n.func.id](*[ev(a) for a in n.args]))
        raise ValueError(f"허용되지 않은 식: {ast.dump(n)}")

    try:
        out = ev(tree)
    except (ArithmeticError, TypeError, RecursionError) as e:  # 0 나누기·overflow·min() 인자 없음 등
        raise ValueError(f"계산할 수 없는 식: {type(e).__name__}") from e
    if out is None or not math.isfinite(out):
        raise ValueError("계산 결과가 유한한 수가 아닙니다")
    return out


def fmt_num(x: float | None) -> str:
    if x is None:
        return "?"
    if float(x).is_integer():
        return f"{int(x):,}"
    return f"{x:,.2f}".rstrip("0").rstrip(".")
