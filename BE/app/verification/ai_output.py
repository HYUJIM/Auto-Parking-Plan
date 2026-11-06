"""
AI 출력 검증기 (법규 서버 6장 '검증 장치' 구현 예시)

1) rule_extraction 출력 검증
   - JSON Schema 검사
   - 근거(evidence.quote)가 해당 조문 원문에 '그대로' 있는지 → char_start/char_end 계산
   - 값(num_*)이 그 근거 인용문 안에 숫자로 실제 등장하는지
   - 같은 rule_key 중복 여부
2) explanation 출력 검증 → post_verifier.verify (날조·세부 왜곡·누락 전부)

사용:
  python validate_ai_output.py extraction <output.json> <provisions.json>
  python validate_ai_output.py explanation <output.json> <fact_ledger.json> [provisions.json]

provisions.json = {"<provision_id>": "<조문 원문>", ...}
"""
import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "resources" / "schemas"
NUM_RE = re.compile(r"\d+(?:\.\d+)?")


def _registry() -> Registry:
    reg = Registry()
    for p in SCHEMA_DIR.glob("*.schema.json"):
        doc = json.loads(p.read_text(encoding="utf-8"))
        reg = reg.with_resource(doc["$id"], Resource.from_contents(doc))
    return reg


def _schema_errors(instance, schema_file):
    schema = json.loads((SCHEMA_DIR / schema_file).read_text(encoding="utf-8"))
    v = Draft202012Validator(schema, registry=_registry(), format_checker=Draft202012Validator.FORMAT_CHECKER)
    return [f"schema: {'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}"
            for e in sorted(v.iter_errors(instance), key=lambda e: list(e.absolute_path))]


KOREAN_THOUSANDS_RE = re.compile(r"(\d+)천\s?(\d{3})?(?!\d)")


def _numbers(text: str) -> set[float]:
    """인용문 안의 숫자 집합. '1천500'(=1500) 같은 법령 표기도 함께 넣는다."""
    t = text.replace(",", "")
    nums = {float(n) for n in NUM_RE.findall(t)}
    nums |= {float(int(a) * 1000 + int(b or 0)) for a, b in KOREAN_THOUSANDS_RE.findall(t)}
    return nums


def locate(quote: str, provision_text: str):
    """인용문 위치. 정확히 1회 이상 등장해야 함. 반환 (start, end) 또는 None."""
    i = provision_text.find(quote)
    return None if i < 0 else (i, i + len(quote))


def validate_extraction(output: dict, provisions: dict[str, str]):
    errors = _schema_errors(output, "rule_extraction.schema.json")
    if errors:
        return errors, []
    spans = []

    def check_ev(ev, where):
        text = provisions.get(ev["provision_id"])
        if text is None:
            errors.append(f"{where}: 입력에 없는 provision_id {ev['provision_id']}")
            return None
        span = locate(ev["quote"], text)
        if span is None:
            errors.append(f"{where}: 인용문이 원문에 없음 → {ev['quote']!r}")
            return None
        spans.append({"where": where, "provision_id": ev["provision_id"], "char_start": span[0], "char_end": span[1]})
        return ev["quote"]

    seen = set()
    for i, rule in enumerate(output["rules"]):
        if rule["rule_key"] in seen:
            errors.append(f"rules[{i}]: rule_key 중복 {rule['rule_key']}")
        seen.add(rule["rule_key"])
        for j, val in enumerate(rule["values"]):
            where = f"rules[{i}].values[{j}]"
            quote = check_ev(val["evidence"], where)
            if quote is None:
                continue
            nums = _numbers(quote)
            for k in ("num_min", "num_max", "num_exact"):
                if val.get(k) is not None and float(val[k]) not in nums:
                    errors.append(f"{where}.{k}={val[k]}: 근거 인용문에 이 숫자가 없음")
            if val.get("enum_value") and val["enum_value"].replace(" ", "") not in quote.replace(" ", ""):
                errors.append(f"{where}.enum_value={val['enum_value']!r}: 근거 인용문에 이 용도명이 없음")
        for j, ev in enumerate(rule.get("exceptions_mentioned", [])):
            check_ev(ev, f"rules[{i}].exceptions_mentioned[{j}]")
    for i, d in enumerate(output["delegations"]):
        check_ev(d["evidence"], f"delegations[{i}]")
    return errors, spans


def validate_explanation(output: dict, ledger: dict, provisions: dict | None = None):
    """쉬운 설명 검증은 사후 검증기(post_verifier)로 위임한다."""
    from app.verification.post_verifier import verify
    return verify(output, ledger, provisions=provisions)


def main():
    from app.console import ensure_utf8_stdio  # noqa: PLC0415

    ensure_utf8_stdio()
    if len(sys.argv) not in (4, 5) or sys.argv[1] not in ("extraction", "explanation"):
        print(__doc__)
        sys.exit(2)
    mode, out_path, ctx_path = sys.argv[1:4]
    output = json.loads(Path(out_path).read_text(encoding="utf-8"))
    ctx = json.loads(Path(ctx_path).read_text(encoding="utf-8"))
    if mode == "extraction":
        errors, spans = validate_extraction(output, ctx)
        result = {"status": "ok" if not errors else "rejected", "errors": errors, "evidence_spans": spans}
    else:
        provs = json.loads(Path(sys.argv[4]).read_text(encoding="utf-8")) if len(sys.argv) == 5 else None
        result = validate_explanation(output, ctx, provs)
        errors = result["errors"]
    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(0 if not errors else 1)


if __name__ == "__main__":
    main()
