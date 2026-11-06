"""
AI 추출 결과(rule_extraction@1.0) → 기계 검증 → rule / rule_condition / rule_value / rule_evidence 저장.

설계서 6장 4단계 기계 검증
  * JSON Schema 위반                     → schema_error (규칙 저장 안 함)
  * evidence.quote 가 원문에 그대로 없음   → evidence_error (규칙 저장 안 함, 재추출)
  * 값이 인용문 안의 숫자가 아님           → evidence_error
  * L3 값이 연결된 L1 범위를 벗어남        → range_error (규칙은 draft 로 저장, 검수 알림)
  * 카테고리별 필수 조건 누락 / 단서 문구 미기재 → 경고 (draft 로 저장, 검수 필수)
통과한 규칙만 auto_checked 가 되고, 사람이 승인해야 approved 가 된다.
"""
from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import (
    DocumentVersion,
    ExtractionRun,
    Provision,
    Rule,
    RuleCondition,
    RuleEvidence,
    RuleValue,
    SourceDocument,
)
from app.verification.ai_output import locate, validate_extraction

PROMPT_VERSION = "rule-extract@v1"
SCHEMA_VERSION = "rule_extraction.schema.json@1.0"
ZONING_REQUIRED = {"BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "ZONING_USE"}
# 단서 문구. 용도명 괄호 안의 "(…를 제외한다)" 는 용도 범위 한정이라 condition_note 로 다루고 단서로 보지 않는다.
EXCEPTION_MARKERS = ("다만", "그러하지 아니하다")
ARTICLE_SPECIAL_MARKERS = ("다만", "불구하고", "그러하지 아니하다")


def _root_text(prov_by_id: dict, pid: str) -> str:
    p = prov_by_id.get(pid)
    while p is not None and p.parent_id is not None:
        p = prov_by_id.get(str(p.parent_id))
    return p.text if p is not None else ""


@dataclass
class StoreOutcome:
    run_id: uuid.UUID
    status: str
    rule_ids: list[uuid.UUID] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _norm(s: str) -> str:
    return "".join(str(s).split())


def _condition_zoning(rule_json: dict) -> str | None:
    for c in rule_json.get("conditions", []):
        if c["field"] == "zoning" and c["operator"] == "eq" and isinstance(c["value"], str):
            return _norm(c["value"])
    return None


def upper_range(s: Session, *, category: str, zoning: str | None, effective_on) -> tuple[float | None, float | None, list[str]]:
    """같은 카테고리·용도지역의 승인된 L1 규칙 범위 (min, max)."""
    q = select(Rule).where(Rule.category == category, Rule.layer == "L1_NATIONAL", Rule.review_status == "approved")
    lo = hi = None
    ids = []
    for r in s.scalars(q).all():
        # L3 규칙의 시행 기간과 겹치는 L1 규칙만 비교한다 (조례가 시행령 개정보다 먼저 시행될 수 있으므로 시작일로 거르지 않음)
        if effective_on and r.effective_to and r.effective_to < effective_on:
            continue
        z = next((_norm(c.value_json) for c in r.conditions if c.field == "zoning" and c.operator == "eq"), None)
        if zoning and z != zoning:
            continue
        for v in r.values:
            if v.value_key != "ratio":
                continue
            lo = v.num_min if v.num_min is not None else lo
            hi = v.num_max if v.num_max is not None else hi
            ids.append(str(r.id))
    return lo, hi, ids


def store_extraction(s: Session, *, version: DocumentVersion, output: dict, model: str,
                     provision_ids: list[uuid.UUID] | None = None, prompt_version: str = PROMPT_VERSION) -> StoreOutcome:
    doc = s.get(SourceDocument, version.document_id)
    prov_rows = s.scalars(select(Provision).where(Provision.version_id == version.id)).all()
    provisions = {str(p.id): p.text for p in prov_rows}
    prov_by_id = {str(p.id): p for p in prov_rows}
    used_ids = sorted({str(pid) for pid in (provision_ids or [])} or provisions.keys())
    input_sha = hashlib.sha256(json.dumps({k: provisions[k] for k in used_ids if k in provisions},
                                          ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()

    errors, spans = validate_extraction(output, provisions)
    if output.get("document_version_id") not in (None, str(version.id)):
        errors.append(f"document_version_id 불일치: {output.get('document_version_id')}")
    status = "ok"
    if errors:
        status = "schema_error" if any(e.startswith("schema:") for e in errors) else "evidence_error"
    run = ExtractionRun(version_id=version.id, provision_ids=used_ids, task="rule_extraction", model=model,
                        prompt_version=prompt_version, schema_version=SCHEMA_VERSION, input_sha256=input_sha,
                        output_json=output, validation={"errors": errors, "evidence_spans": spans}, status=status)
    s.add(run)
    s.flush()
    outcome = StoreOutcome(run_id=run.id, status=status, errors=list(errors))
    if errors:
        return outcome

    warnings: list[str] = []
    range_errors: list[str] = []
    for i, rj in enumerate(output["rules"]):
        rule_warn: list[str] = []
        zoning = _condition_zoning(rj)
        if rj["category"] in ZONING_REQUIRED and not zoning:
            rule_warn.append(f"rules[{i}]: {rj['category']} 규칙에 용도지역 조건이 없음 (조건 누락)")
        ev_texts = {provisions[v["evidence"]["provision_id"]] for v in rj["values"]}
        if any(m in t for t in ev_texts for m in EXCEPTION_MARKERS) and not rj.get("exceptions_mentioned"):
            rule_warn.append(f"rules[{i}]: 원문에 단서·예외 문구가 있으나 exceptions_mentioned 가 비어 있음 (검수 필수)")
        # 같은 조의 다른 항·호에 있는 단서·특례('다만', '…에도 불구하고')도 이 규칙에 해당하는지 사람이 봐야 한다 (설계서 6장 예외 누락)
        roots = {_root_text(prov_by_id, v["evidence"]["provision_id"]) for v in rj["values"]}
        if any(m in rt for rt in roots for m in ARTICLE_SPECIAL_MARKERS) and not rj.get("exceptions_mentioned"):
            rule_warn.append(f"rules[{i}]: 같은 조의 다른 항·호에 단서·특례 문구('다만'/'불구하고')가 있음 — 이 규칙에 적용되는지 검수 필요")
        # L3 값의 상위 범위 검사 (건폐율·용적률)
        if doc.layer == "L3_LOCAL" and rj["category"] in ("BUILDING_COVERAGE", "FLOOR_AREA_RATIO"):
            lo, hi, upper_ids = upper_range(s, category=rj["category"], zoning=zoning, effective_on=version.effective_from)
            for v in rj["values"]:
                val = v.get("num_max") if v.get("num_max") is not None else v.get("num_exact")
                if val is None:
                    continue
                if (hi is not None and val > hi) or (lo is not None and val < lo):
                    range_errors.append(f"rules[{i}]: {val} 이 상위 법령 범위({lo}~{hi})를 벗어남 (상위 규칙 {upper_ids})")
                elif hi is None and lo is None:
                    # 상위 규칙 승인 전이면 승인 시점(approve_rule)에 다시 검사한다
                    warnings.append(f"rules[{i}]: 비교할 승인된 상위(L1) 규칙이 아직 없어 범위 검사를 승인 시점으로 미룸")
        warnings += rule_warn

        prev = s.scalar(select(Rule).where(Rule.rule_key == rj["rule_key"], Rule.jurisdiction_code == doc.jurisdiction_code,
                                           Rule.review_status == "approved", Rule.version_id != version.id)
                        .order_by(Rule.effective_from.desc()))
        rule = Rule(rule_key=rj["rule_key"], category=rj["category"], layer=doc.layer, jurisdiction_code=doc.jurisdiction_code,
                    version_id=version.id, effective_from=version.effective_from, effective_to=version.effective_to,
                    title=rj["title"], automation_level=rj["automation_level"],
                    review_status="draft" if (rule_warn or any(e.startswith(f"rules[{i}]") for e in range_errors)) else "auto_checked",
                    extraction_run_id=run.id, confidence=rj.get("confidence"), supersedes_rule_id=prev.id if prev else None)
        s.add(rule)
        s.flush()
        for c in rj["conditions"]:
            s.add(RuleCondition(rule_id=rule.id, field=c["field"], operator=c["operator"], value_json=c["value"], unit=c.get("unit")))
        for v in rj["values"]:
            rv = RuleValue(rule_id=rule.id, value_key=v["value_key"], value_type=v["value_type"], num_min=v.get("num_min"),
                           num_max=v.get("num_max"), num_exact=v.get("num_exact"), enum_value=v.get("enum_value"),
                           formula=v.get("formula"), unit=v.get("unit"), condition_note=v.get("condition_note"))
            s.add(rv)
            s.flush()
            _add_evidence(s, rule.id, rv.id, v["evidence"], provisions, role="value")
        for ev in rj.get("exceptions_mentioned", []):
            _add_evidence(s, rule.id, None, ev, provisions, role="exception")
        outcome.rule_ids.append(rule.id)

    if range_errors:
        run.status = outcome.status = "range_error"
    run.validation = {"errors": [], "warnings": warnings, "range_errors": range_errors, "evidence_spans": spans}
    outcome.warnings = warnings
    outcome.errors = range_errors
    s.flush()
    return outcome


def _add_evidence(s: Session, rule_id, rule_value_id, ev: dict, provisions: dict[str, str], *, role: str) -> None:
    text = provisions[ev["provision_id"]]
    span = locate(ev["quote"], text)
    assert span is not None  # validate_extraction 가 이미 확인
    s.add(RuleEvidence(rule_id=rule_id, rule_value_id=rule_value_id, provision_id=uuid.UUID(ev["provision_id"]),
                       char_start=span[0], char_end=span[1], quoted_text=text[span[0]:span[1]], role=role))


def supersede_on_approval(s: Session, rule: Rule, today) -> None:
    """새 버전 규칙 승인 시 이전 규칙의 effective_to 를 채우고, 시행일이 지났으면 superseded 로 바꾼다."""
    if not rule.supersedes_rule_id:
        return
    old = s.get(Rule, rule.supersedes_rule_id)
    if old is None or not rule.effective_from:
        return
    old.effective_to = rule.effective_from - timedelta(days=1)
    if rule.effective_from <= today:
        old.review_status = "superseded"
