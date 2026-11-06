"""사람 검수 (설계서 6장 5단계): 규칙 승인·반려·수정. 모든 행위는 review_log 에 남긴다."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import ExtractionRun, FactLedger, Provision, ReviewLog, Rule
from app.explain.ledger import approve_ledger, build_rule_ledger
from app.extraction.store import supersede_on_approval, upper_range
from app.review.service import today_kst
from app.verification.ai_output import _numbers


class ApprovalError(Exception):
    pass


def log_action(s: Session, *, target_type: str, target_id, action: str, reviewer: str, diff=None, comment=None) -> None:  # noqa: ANN001
    s.add(ReviewLog(target_type=target_type, target_id=target_id, action=action, reviewer=reviewer, diff_json=diff, comment=comment))


def draft_ledger_for(s: Session, rule: Rule) -> FactLedger:
    return build_rule_ledger(s, rule)


def approve_rule(s: Session, rule_id: uuid.UUID, *, reviewer: str, comment: str | None = None,
                 approve_ledger_too: bool = False, acknowledge_warnings: bool = False,
                 confirm_transcription: bool = False) -> Rule:
    """사람 승인. auto_checked 규칙, 또는 경고만 있는 draft 규칙(검수자가 경고를 확인한 경우)만 승인할 수 있다."""
    rule = s.get(Rule, rule_id)
    if rule is None:
        raise ApprovalError("규칙 없음")
    run = s.get(ExtractionRun, rule.extraction_run_id) if rule.extraction_run_id else None
    if rule.review_status == "draft":
        if not acknowledge_warnings or run is None or run.status not in ("ok",):
            raise ApprovalError("경고가 있는 draft 규칙입니다. 경고를 확인했다면 acknowledge_warnings=true 로 승인하세요"
                                f" (추출 상태 {run.status if run else '없음'})")
    elif rule.review_status != "auto_checked":
        raise ApprovalError(f"기계 검증을 통과한(auto_checked) 규칙만 승인할 수 있습니다 (현재 {rule.review_status})")
    if rule.effective_from is None:
        raise ApprovalError("시행일이 확인되지 않은 버전의 규칙은 승인할 수 없습니다")
    # 승인 시점 상위 범위 재검사 (설계서 6장: L3 값이 연결된 L1 범위를 벗어나면 range_error)
    if rule.layer == "L3_LOCAL" and rule.category in ("BUILDING_COVERAGE", "FLOOR_AREA_RATIO"):
        zoning = next(("".join(str(c.value_json).split()) for c in rule.conditions if c.field == "zoning"), None)
        lo, hi, _ = upper_range(s, category=rule.category, zoning=zoning, effective_on=rule.effective_from)
        val = next((v.num_max if v.num_max is not None else v.num_exact for v in rule.values if v.value_key == "ratio"), None)
        if lo is None and hi is None and not acknowledge_warnings:
            raise ApprovalError("상위(L1) 규칙이 승인되지 않아 범위 검사를 할 수 없습니다. 국가 법령 규칙을 먼저 승인하세요")
        if val is not None and ((hi is not None and val > hi) or (lo is not None and val < lo)):
            raise ApprovalError(f"range_error: 조례 값 {val} 이 상위 법령 범위({lo}~{hi})를 벗어납니다")
    # 표 이미지 전사본을 근거로 한 규칙: 검수자가 원본 이미지와 대조했음을 확인해야 승인 (원문이 진실이다)
    unverified = unverified_transcriptions(s, rule)
    if unverified:
        if not confirm_transcription:
            raise ApprovalError("근거가 사람 대조 전의 표 이미지 전사본입니다. 원본 이미지와 대조한 뒤 confirm_transcription=true 로 승인하세요: "
                                + ", ".join(t.table_json.get("source_url", t.path) for t in unverified))
        for t in unverified:
            meta = dict(t.table_json)
            meta["transcription"] = {**meta.get("transcription", {}), "verified_by": reviewer,
                                     "verified_at": datetime.now(UTC).isoformat(timespec="seconds")}
            t.table_json = meta
            log_action(s, target_type="document_version", target_id=t.version_id, action="comment", reviewer=reviewer,
                       comment=f"표 이미지 전사본 대조 확인: {meta.get('source_url')}")
    rule.review_status = "approved"
    rule.approved_by = reviewer
    rule.approved_at = datetime.now(UTC)
    supersede_on_approval(s, rule, today_kst())
    log_action(s, target_type="rule", target_id=rule.id, action="approve", reviewer=reviewer, comment=comment)
    # 사실 원장 초안은 항상 만든다 (검수자가 같은 화면에서 확인). 원장 승인은 별도 행위이며, 검수자가 명시적으로 요청할 때만 함께 승인한다.
    led = build_rule_ledger(s, rule)
    if approve_ledger_too:
        approve_ledger(s, led, reviewer)
        log_action(s, target_type="fact_ledger", target_id=led.id, action="approve", reviewer=reviewer,
                   comment="규칙 검수와 함께 원장 확인·승인 (자동 초안의 사실·제외 사유를 검수자가 확인)")
    s.flush()
    return rule


def transcription_table(s: Session, p: Provision) -> Provision | None:
    """조문이 표 이미지 전사본(또는 그 행)이면 전사 표 조문을 돌려준다."""
    cur = p
    while cur is not None:
        if isinstance(cur.table_json, dict) and cur.table_json.get("supplement") == "image_table":
            return cur
        cur = s.get(Provision, cur.parent_id) if cur.parent_id else None
    return None


def unverified_transcriptions(s: Session, rule: Rule) -> list[Provision]:
    out = {}
    for ev in rule.evidence:
        t = transcription_table(s, s.get(Provision, ev.provision_id))
        if t is not None and not (t.table_json.get("transcription") or {}).get("verified_by"):
            out[t.id] = t
    return list(out.values())


def reject_rule(s: Session, rule_id: uuid.UUID, *, reviewer: str, comment: str | None = None) -> Rule:
    rule = s.get(Rule, rule_id)
    if rule is None:
        raise ApprovalError("규칙 없음")
    rule.review_status = "rejected"
    log_action(s, target_type="rule", target_id=rule.id, action="reject", reviewer=reviewer, comment=comment)
    return rule


def edit_rule(s: Session, rule_id: uuid.UUID, *, reviewer: str, values: list[dict[str, Any]], comment: str | None = None) -> Rule:
    """검수자가 값을 고친다. 고친 숫자도 근거 인용문 안에 있어야 한다 (원문이 진실이다)."""
    rule = s.get(Rule, rule_id)
    if rule is None:
        raise ApprovalError("규칙 없음")
    if rule.review_status == "approved":
        raise ApprovalError("승인된 규칙은 수정할 수 없습니다. 재추출 또는 새 규칙으로 처리하세요")
    by_key = {v.value_key: v for v in rule.values}
    diff = []
    for patch in values:
        v = by_key.get(patch.get("value_key"))
        if v is None:
            raise ApprovalError(f"없는 value_key: {patch.get('value_key')}")
        ev = next((e for e in rule.evidence if e.rule_value_id == v.id), None)
        nums = _numbers(ev.quoted_text) if ev else set()
        before = {k: getattr(v, k) for k in ("num_min", "num_max", "num_exact", "enum_value", "unit", "condition_note")}
        for k in ("num_min", "num_max", "num_exact"):
            if k in patch and patch[k] is not None and float(patch[k]) not in nums:
                raise ApprovalError(f"{v.value_key}.{k}={patch[k]}: 근거 인용문에 이 숫자가 없습니다")
        for k in ("num_min", "num_max", "num_exact", "enum_value", "unit", "condition_note"):
            if k in patch:
                setattr(v, k, patch[k])
        diff.append({"value_key": v.value_key, "before": before, "after": {k: getattr(v, k) for k in before}})
    rule.review_status = "auto_checked"
    log_action(s, target_type="rule", target_id=rule.id, action="edit", reviewer=reviewer, diff={"values": diff}, comment=comment)
    s.flush()
    return rule


def pending_rules(s: Session, status: str = "auto_checked") -> list[Rule]:
    return list(s.scalars(select(Rule).where(Rule.review_status == status).order_by(Rule.created_at)).all())
