"""
사실 원장(Fact Ledger) — 사후 검증의 '정답지'

원장은 규칙 하나가 근거로 삼는 원문 조문에서 '설명에 반드시 들어가야 할 사실'을
원자 단위로 적어 둔 목록이다. AI 답변은 이 원장과만 대조된다.

1) extract_candidates(): 원문에서 기계적으로 후보를 뽑는다 (숫자·기간·날짜·비교어·예외·의무/금지 문장).
2) 사람이 검수 화면에서 후보를 원장 사실(fact)로 승격하거나, 사유를 적어 제외(dismissal)한다.
3) check_completeness(): 원문의 모든 후보가 원장에 들어갔거나 사유와 함께 제외됐는지 확인한다.
   → 원장 자체가 원문을 빠뜨리는 것을 막는다 (원문 누락 방지의 1단계).

사실(fact) 형식은 schemas/fact_ledger.schema.json 참고.
"""
from __future__ import annotations

import hashlib
import json

from app.verification.ko_quantity import EXCEPTION_RE, modality, parse, split_sentences, zone_names

NUMERIC_KINDS = {"PERIOD", "QUANTITY", "DATE"}


def _cid(provision_id: str, kind: str, start: int, end: int) -> str:
    return hashlib.sha1(f"{provision_id}|{kind}|{start}|{end}".encode()).hexdigest()[:12]


def extract_candidates(provision_id: str, text: str) -> list[dict]:
    """원문에서 사실 후보를 결정적으로 추출 (AI 미사용)."""
    out = []
    for q in parse(text):
        if q.kind in NUMERIC_KINDS or q.kind == "REFERENCE":
            out.append({
                "candidate_id": _cid(provision_id, q.kind, q.start, q.end),
                "provision_id": provision_id, "kind": q.kind, "quote": q.raw,
                "char_start": q.start, "char_end": q.end,
                "canonical": {"dim": q.dim, "value": q.value, "unit": q.unit, "comparator": q.comparator},
            })
    for start, sent in split_sentences(text):
        mod = modality(sent)
        if EXCEPTION_RE.search(sent):
            out.append({"candidate_id": _cid(provision_id, "EXCEPTION", start, start + len(sent)),
                        "provision_id": provision_id, "kind": "EXCEPTION", "quote": sent,
                        "char_start": start, "char_end": start + len(sent), "modality": mod})
        elif mod:
            out.append({"candidate_id": _cid(provision_id, "MODALITY", start, start + len(sent)),
                        "provision_id": provision_id, "kind": "MODALITY", "quote": sent,
                        "char_start": start, "char_end": start + len(sent), "modality": mod})
        for z in zone_names(sent):
            i = text.find(z, start) if z in text else start
            out.append({"candidate_id": _cid(provision_id, "SUBJECT:" + z, start, start + len(sent)),
                        "provision_id": provision_id, "kind": "SUBJECT", "quote": z,
                        "char_start": i, "char_end": i + len(z), "name": z})
    # 같은 SUBJECT 이름은 한 번만
    seen, uniq = set(), []
    for c in out:
        k = (c["kind"], c.get("name")) if c["kind"] == "SUBJECT" else c["candidate_id"]
        if k not in seen:
            seen.add(k)
            uniq.append(c)
    return uniq


def check_completeness(provisions: dict[str, str], ledger: dict) -> list[dict]:
    """원문 후보 중 원장에 반영도, 제외 사유도 없는 것을 돌려준다. 빈 목록이어야 원장을 승인할 수 있다."""
    covered = {cid for f in ledger["facts"] for cid in f.get("candidate_ids", [])}
    dismissed = {d["candidate_id"] for d in ledger.get("dismissed", []) if d.get("reason")}
    gaps = []
    for pid, text in provisions.items():
        if pid not in ledger.get("provision_ids", []):
            continue
        for c in extract_candidates(pid, text):
            if c["candidate_id"] not in covered | dismissed:
                gaps.append({"candidate_id": c["candidate_id"], "kind": c["kind"], "quote": c["quote"],
                             "provision_id": pid, "message": "원장에 반영되지도, 사유와 함께 제외되지도 않은 원문 사실"})
    return gaps


def ledger_hash(ledger: dict) -> str:
    return hashlib.sha256(json.dumps(ledger, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
