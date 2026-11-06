"""
사실 원장 (설계서 9장).

1) build_rule_ledger(): 규칙 하나가 근거로 삼는 조문에서 결정적 추출기로 후보를 '전부' 뽑아
   근거 인용 구간 안의 후보는 사실(fact)로, 밖의 후보는 사유와 함께 제외(dismissal)한 '초안'을 만든다.
   → 사람이 검수 화면에서 확인·수정 후 승인한다 (approve_ledger). 처리되지 않은 후보가 있으면 승인 불가.
2) compose_item_ledger(): 검토 항목(review_item) 하나에 쓰인 규칙들의 '승인된' 원장을 합치고
   엔진 사실(STATUS, 용도지역, 용도 묶음)을 더해 설명 생성·검증의 정답지로 쓴다.
   합성 원장은 사람 승인 원장 + 결정적 엔진 사실로만 이루어지므로 구성 원장이 모두 승인됐을 때만 승인 상태가 된다.
"""
from __future__ import annotations

import hashlib
import json
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import FactLedger, LedgerDismissal, LedgerFact, Provision, Rule
from app.verification.fact_ledger import check_completeness, extract_candidates, ledger_hash

AUTO_REVIEWER = "auto-draft"
TRUE_EXCEPTION_RE = re.compile(r"다만|그러하지\s*아니하")
STATUS_LABEL = {"REVIEWABLE": "검토 가능", "CONDITIONAL": "조건부 검토", "MANUAL_CHECK": "별도 확인 필요"}
LEDGER_NS = uuid.UUID("6f1d3c2e-9a51-4c7e-8a0b-1f2e3d4c5b6a")
# 값이 많은 카테고리는 대표 값만 '반드시 설명'으로 둔다 (나머지는 설명해도 되는 사실). 검수자가 바꿀 수 있다.
MUST_MENTION_VALUE_KEYS = {
    "PARKING_STALL_SIZE": re.compile(r"(^|\.)(general|extended)\."),
    "DRIVE_AISLE_WIDTH": re.compile(r"(perpendicular|parallel)"),
}


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def _short_kind(kind: str) -> str:
    return {"QUANTITY": "Q", "PERIOD": "P", "DATE": "D", "REFERENCE": "REF", "SUBJECT": "SUBJ",
            "EXCEPTION": "EXC", "MODALITY": "MOD", "CONDITION": "COND", "STATUS": "STATUS"}[kind]


def _fact_dict(f: LedgerFact) -> dict:
    d = {"fact_id": f.fact_key, "kind": f.kind, "origin": f.origin,
         "provision_id": str(f.provision_id) if f.provision_id else None, "quote": f.quote,
         "must_mention": f.must_mention, "candidate_ids": list(f.candidate_ids or [])}
    if f.canonical is not None:
        d["canonical"] = f.canonical
    if f.modality:
        d["modality"] = f.modality
    if f.name:
        d["name"] = f.name
    if f.status_value:
        d["value"] = f.status_value
    if f.key_terms:
        d["key_terms"] = f.key_terms
    return d


def ledger_to_dict(led: FactLedger) -> dict:
    return {
        "schema_version": "fact_ledger@1.0",
        "ledger_id": str(led.id),
        "rule_ids": [str(x) for x in led.rule_ids],
        "provision_ids": [str(x) for x in led.provision_ids],
        "facts": [_fact_dict(f) for f in sorted(led.facts, key=lambda f: f.fact_key)],
        "dismissed": [{"candidate_id": d.candidate_id, "reason": d.reason, "reviewer": d.reviewer}
                      for d in sorted(led.dismissals, key=lambda d: d.candidate_id)],
        "review_status": led.review_status,
    }


def _refresh(s: Session, led: FactLedger, provisions: dict[str, str]) -> None:
    led_dict = ledger_to_dict(led)
    gaps = check_completeness(provisions, led_dict)
    led.completeness_gaps = gaps
    led.gap_count = len(gaps)
    led.ledger_json = led_dict
    led.ledger_hash = ledger_hash(led_dict)


def _provision_texts(s: Session, ids) -> dict[str, str]:
    out = {}
    for pid in ids:
        p = s.get(Provision, uuid.UUID(str(pid)))
        if p:
            out[str(p.id)] = p.text
    return out


def build_rule_ledger(s: Session, rule: Rule) -> FactLedger:
    """규칙 하나의 원장 초안을 만든다 (draft). 기존 초안이 있으면 대체한다."""
    for old in s.scalars(select(FactLedger).where(FactLedger.kind == "rule", FactLedger.review_status == "draft")).all():
        if [str(x) for x in old.rule_ids] == [str(rule.id)]:
            s.delete(old)
    s.flush()

    spans: dict[str, list[tuple[int, int, str, str | None]]] = {}
    value_keys = {v.id: v.value_key for v in rule.values}
    for ev in rule.evidence:
        spans.setdefault(str(ev.provision_id), []).append((ev.char_start, ev.char_end, ev.role, value_keys.get(ev.rule_value_id)))
    provisions = _provision_texts(s, spans.keys())
    zoning = {_norm(c.value_json) for c in rule.conditions if c.field == "zoning" and isinstance(c.value_json, str)}
    mention_re = MUST_MENTION_VALUE_KEYS.get(rule.category)

    led = FactLedger(id=uuid.uuid4(), kind="rule", rule_ids=[str(rule.id)], provision_ids=sorted(provisions.keys()),
                     review_status="draft", ledger_hash="0" * 64, ledger_json={})
    s.add(led)
    s.flush()
    counters: dict[str, int] = {}
    used_keys: set[str] = set()

    def new_key(kind: str) -> str:
        counters[kind] = counters.get(kind, 0) + 1
        return f"{_short_kind(kind)}{counters[kind]}"

    for pid, text in provisions.items():
        pspans = spans[pid]
        for c in extract_candidates(pid, text):
            a, b = c["char_start"], c["char_end"]
            inside = [sp for sp in pspans if sp[0] <= a and b <= sp[1]]
            overlap = [sp for sp in pspans if a < sp[1] and sp[0] < b]
            kind = c["kind"]
            fact = None
            if kind in ("QUANTITY", "PERIOD", "DATE"):
                if inside:
                    st, en, role, vkey = inside[0]
                    must = True if mention_re is None else bool(vkey and mention_re.search(vkey)) or role == "exception"
                    fact = dict(kind=kind, quote=text[st:en], canonical=c["canonical"], must_mention=must)
            elif kind == "REFERENCE":
                name = c["quote"].strip("「」 ")
                fact = dict(kind=kind, quote=c["quote"], name=name, must_mention=False)
            elif kind == "SUBJECT":
                if _norm(c["name"]) in zoning:
                    fact = dict(kind=kind, quote=c["quote"], name=c["name"], must_mention=True)
                elif inside or overlap:
                    fact = dict(kind=kind, quote=c["quote"], name=c["name"], must_mention=False)
            elif kind in ("EXCEPTION", "MODALITY"):
                if overlap:
                    must = True
                    if kind == "EXCEPTION" and not TRUE_EXCEPTION_RE.search(c["quote"]) and not any(sp[2] == "exception" for sp in overlap):
                        # '(…를 제외한다)' 같은 용도 범위 한정은 단서가 아니다 → 인용은 허용, 필수 설명은 아님 (검수자가 바꿀 수 있음)
                        must = False
                    fact = dict(kind=kind, quote=c["quote"], modality=c.get("modality"), must_mention=must)
            if fact is None:
                reason = ("이 규칙의 적용 대상 용도지역이 아님" if kind == "SUBJECT" else "규칙 근거 인용 구간 밖의 원문 후보")
                s.add(LedgerDismissal(ledger_id=led.id, candidate_id=c["candidate_id"], reason=f"{reason} (자동 초안)",
                                      reviewer=AUTO_REVIEWER))
                continue
            key = new_key(kind)
            used_keys.add(key)
            s.add(LedgerFact(ledger_id=led.id, fact_key=key, kind=fact["kind"], origin="provision",
                             provision_id=uuid.UUID(pid), quote=fact["quote"], canonical=fact.get("canonical"),
                             modality=fact.get("modality"), name=fact.get("name"), key_terms=[],
                             must_mention=fact["must_mention"], candidate_ids=[c["candidate_id"]]))
    s.flush()
    s.refresh(led)
    _refresh(s, led, provisions)
    s.flush()
    return led


def approve_ledger(s: Session, led: FactLedger, reviewer: str) -> FactLedger:
    provisions = _provision_texts(s, led.provision_ids)
    s.refresh(led)
    _refresh(s, led, provisions)
    if led.gap_count:
        raise ValueError(f"원장에 처리되지 않은 원문 후보 {led.gap_count}개가 있어 승인할 수 없습니다")
    # 같은 규칙의 이전 승인 원장은 superseded
    for old in s.scalars(select(FactLedger).where(FactLedger.kind == "rule", FactLedger.review_status == "approved")).all():
        if old.id != led.id and [str(x) for x in old.rule_ids] == [str(x) for x in led.rule_ids]:
            old.review_status = "superseded"
            # 이 규칙 원장으로 만든 합성 원장도 무효 → displayable_explanation 에서 빠진다
            for comp in s.scalars(select(FactLedger).where(FactLedger.kind == "composite", FactLedger.review_status == "approved")).all():
                if str(led.rule_ids[0]) in [str(x) for x in comp.rule_ids]:
                    comp.review_status = "superseded"
    # 자동 초안이 낸 제외 사유는 승인하는 검수자가 확인한 것으로 기록한다 (사유 없는·검수자 없는 제외 불가)
    for d in led.dismissals:
        if d.reviewer == AUTO_REVIEWER:
            d.reviewer = f"{reviewer} (자동 초안 확인)"
    led.review_status = "approved"
    led.approved_by = reviewer
    led.approved_at = datetime.now(UTC)
    _refresh(s, led, provisions)
    s.flush()
    return led


def approved_rule_ledger(s: Session, rule_id) -> FactLedger | None:
    cands = s.scalars(select(FactLedger).where(FactLedger.kind == "rule", FactLedger.review_status == "approved")
                      .order_by(FactLedger.approved_at.desc())).all()
    return next((led for led in cands if [str(x) for x in led.rule_ids] == [str(rule_id)]), None)


@dataclass
class EngineFact:
    kind: str  # SUBJECT | CONDITION
    quote: str
    must_mention: bool = True
    name: str | None = None
    key_terms: list | None = None


def compose_item_ledger(s: Session, *, parts: list[tuple[Rule, bool]], status: str,
                        engine_facts: list[EngineFact] | None = None) -> FactLedger | None:
    """검토 항목용 합성 원장. parts = [(규칙, 이 항목의 '적용 값' 규칙인지)].

    구성 규칙 중 하나라도 승인된 원장이 없으면 None (→ 설명 없이 근거 원문만 표시).
    """
    facts: list[dict] = []
    dismissed: list[dict] = []
    provision_ids: list[str] = []
    rule_ids: list[str] = []
    for idx, (rule, primary) in enumerate(parts):
        led = approved_rule_ledger(s, rule.id)
        if led is None:
            return None
        d = ledger_to_dict(led)
        tag = f"{rule.layer[:2]}r{idx + 1}"
        rule_ids.append(str(rule.id))
        provision_ids += [p for p in d["provision_ids"] if p not in provision_ids]
        for f in d["facts"]:
            f = dict(f)
            f["fact_id"] = f"{tag}.{f['fact_id']}"
            f["must_mention"] = bool(f["must_mention"] and primary)
            facts.append(f)
        dismissed += [x for x in d["dismissed"] if x["candidate_id"] not in {y["candidate_id"] for y in dismissed}]
    for i, ef in enumerate(engine_facts or []):
        f = {"fact_id": f"E.{_short_kind(ef.kind)}{i + 1}", "kind": ef.kind, "origin": "engine", "provision_id": None,
             "quote": ef.quote, "must_mention": ef.must_mention, "candidate_ids": []}
        if ef.name:
            f["name"] = ef.name
        if ef.key_terms:
            f["key_terms"] = ef.key_terms
        facts.append(f)
    facts.append({"fact_id": "E.STATUS", "kind": "STATUS", "origin": "engine", "provision_id": None,
                  "quote": f"엔진 판정: {STATUS_LABEL[status]}", "value": status, "must_mention": False, "candidate_ids": []})

    content = {"rule_ids": rule_ids, "provision_ids": provision_ids, "facts": facts, "dismissed": dismissed}
    content_key = hashlib.sha256(json.dumps(content, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
    led_id = uuid.uuid5(LEDGER_NS, content_key)
    existing = s.get(FactLedger, led_id)
    if existing is not None and existing.review_status == "approved":
        return existing
    led_dict = {"schema_version": "fact_ledger@1.0", "ledger_id": str(led_id), "rule_ids": rule_ids,
                "provision_ids": provision_ids, "facts": facts, "dismissed": dismissed, "review_status": "approved"}
    gaps = check_completeness(_provision_texts(s, provision_ids), led_dict)
    led = existing or FactLedger(id=led_id, kind="composite")
    led.rule_ids, led.provision_ids, led.ledger_json = rule_ids, provision_ids, led_dict
    led.completeness_gaps, led.gap_count, led.status_value = gaps, len(gaps), status
    led.review_status = "approved" if not gaps else "draft"
    led.approved_by = "composite-of-approved-ledgers" if not gaps else None
    led.approved_at = datetime.now(UTC) if not gaps else None
    led.ledger_hash = ledger_hash(led_dict)
    if existing is None:
        s.add(led)
    s.flush()
    return led if not gaps else None
