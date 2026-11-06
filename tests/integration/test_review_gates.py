"""사람 검수 관문: 원장 승인 없이는 AI 설명 불가, 표 이미지 전사본은 대조 확인 없이 승인 불가, 조건이 붙은 용도는 조건부."""
from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import select

from app.db.models import FactLedger, Rule
from app.explain.ledger import approve_ledger
from app.extraction.seed import load_seed_file
from app.legal.ingest import ingest_bundle, link_delegations, load_bundle_file
from app.legal.supplement import ingest_supplement, load_supplement_file
from app.review.approval import ApprovalError, approve_rule
from app.review.basis import basis_for_rules
from app.storage.blob import LocalBlobStore

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "data" / "legal_sources"


@pytest.fixture
def parking_rules(db, tmp_path):
    blobs = LocalBlobStore(tmp_path / "blobs")
    with db.session() as s:
        for f in ("kr.law.parking__2026-08-28.json", "kr.rule.parking__2026-07-27.json"):
            ingest_bundle(s, load_bundle_file(SRC / f), blobs, base_dir=SRC)
        ingest_supplement(s, load_supplement_file(SRC / "supplements" / "kr.rule.parking__2026-07-27__tables.json"), blobs, base_dir=SRC)
        link_delegations(s)
        out = load_seed_file(s, ROOT / "data" / "seed" / "extractions" / "03_kr.rule.parking__dimensions.json")
        assert out.status == "ok"
        return out.rule_ids


def test_transcription_requires_confirmation(db, parking_rules):
    with db.session() as s:
        stall = next(s.get(Rule, r) for r in parking_rules if s.get(Rule, r).category == "PARKING_STALL_SIZE")
        with pytest.raises(ApprovalError, match="전사본"):
            approve_rule(s, stall.id, reviewer="검수자")
    with db.session() as s:
        r = approve_rule(s, stall.id, reviewer="검수자", confirm_transcription=True, acknowledge_warnings=True)
        assert r.review_status == "approved"
        nodes = basis_for_rules(s, [r], r.effective_from)
        lowest = [n for n in nodes if "전사본" in (n["article_label"] or "")]
        assert lowest and "대조: 검수자" in lowest[0]["article_label"] and "flSeq=" in lowest[0]["url"]


def test_rule_approval_leaves_ledger_draft_until_reviewer_approves(db, parking_rules):
    with db.session() as s:
        rid = parking_rules[-1]  # 차로 폭 (전사본 포함)
        approve_rule(s, rid, reviewer="검수자", confirm_transcription=True, acknowledge_warnings=True)
        led = next(x for x in s.scalars(select(FactLedger).where(FactLedger.kind == "rule")).all() if x.rule_ids == [str(rid)])
        assert led.review_status == "draft"
        approve_ledger(s, led, "검수자")
        assert led.review_status == "approved"
        assert all(d.reviewer != "auto-draft" for d in led.dismissals)
