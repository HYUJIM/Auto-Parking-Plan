"""재수집(국가법령정보 Open API) 버전 처리 회귀 테스트 — 감사 지적: 같은 버전을 다른 형식으로 다시 받으면 새 버전이 생기고
승인 규칙의 시행 기간이 꺼지던 문제."""
from __future__ import annotations

import copy
from pathlib import Path

from sqlalchemy import select

from app.db.models import DocumentVersion, ReviewLog, Rule
from app.extraction.seed import load_seed_file
from app.legal.ingest import ingest_bundle, link_delegations, load_bundle_file
from app.review.approval import approve_rule
from app.storage.blob import LocalBlobStore

SRC = Path(__file__).resolve().parents[2] / "data" / "legal_sources"
SEEDS = Path(__file__).resolve().parents[2] / "data" / "seed" / "extractions"


def _setup(db, tmp_path):
    blobs = LocalBlobStore(tmp_path / "blobs")
    with db.session() as s:
        for f in ("kr.law.nkpa__2026-07-01.json", "kr.decree.nkpa__2026-09-18.json", "daegu.ord.urban_planning__2026-05-11.json"):
            ingest_bundle(s, load_bundle_file(SRC / f), blobs, base_dir=SRC)
        link_delegations(s)
    for f in ("01_kr.decree.nkpa__ratios.json", "10_daegu.ord.urban_planning__ratios.json"):
        with db.session() as s:
            out = load_seed_file(s, SEEDS / f)
            for rid in out.rule_ids:
                approve_rule(s, rid, reviewer="검수자(테스트)", approve_ledger_too=True, acknowledge_warnings=True)
    return blobs


def _drf_like(bundle):
    """같은 버전을 Open API XML 로 받은 것처럼: 원문 파일 없음, 형식 xml, 본문 동일."""
    b = copy.deepcopy(bundle)
    b.version.raw_format, b.version.raw_file, b.version.source_url = "xml", None, "http://www.law.go.kr/DRF/lawService.do?target=ordin"
    return b


def _rule(s, key):
    return s.scalar(select(Rule).where(Rule.rule_key == key, Rule.layer == "L3_LOCAL"))


def test_same_version_other_format_is_reconciled_not_duplicated(db, tmp_path):
    blobs = _setup(db, tmp_path)
    b = load_bundle_file(SRC / "daegu.ord.urban_planning__2026-05-11.json")
    with db.session() as s:
        res = ingest_bundle(s, _drf_like(b), blobs)
        assert res.created is False and res.text_confirmed is True and not res.text_mismatch
        vers = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == res.document_id)).all()
        assert len(vers) == 1 and vers[0].v_text_match == "confirmed"
        r = _rule(s, "FAR.ZONE.R2G")
        assert r.review_status == "approved" and r.effective_to is None


def test_same_version_changed_text_flags_needs_review(db, tmp_path):
    blobs = _setup(db, tmp_path)
    b = _drf_like(load_bundle_file(SRC / "daegu.ord.urban_planning__2026-05-11.json"))
    art = next(a for a in b.articles if a.label == "제80조")
    art.text = art.text.replace("220퍼센트", "230퍼센트")
    with db.session() as s:
        res = ingest_bundle(s, b, blobs)
        assert res.created is False and res.text_mismatch == ["art80"]
        ver = s.get(DocumentVersion, res.version_id)
        assert ver.v_text_match == "needs_review"
        assert s.scalar(select(ReviewLog).where(ReviewLog.target_id == ver.id)) is not None
        assert _rule(s, "FAR.ZONE.R2G").effective_to is None  # 승인 규칙을 조용히 끄지 않는다


def test_genuinely_new_version_requests_reextraction(db, tmp_path):
    blobs = _setup(db, tmp_path)
    b = _drf_like(load_bundle_file(SRC / "daegu.ord.urban_planning__2026-05-11.json"))
    b.version.effective_from, b.version.promulgation_number = "2027-01-01", "대구광역시조례 제9999호(테스트)"
    with db.session() as s:
        res = ingest_bundle(s, b, blobs)
        assert res.created is True
        log = s.scalar(select(ReviewLog).where(ReviewLog.target_id == res.version_id, ReviewLog.action == "request_reextract"))
        assert log is not None
        r = _rule(s, "FAR.ZONE.R2G")
        assert str(r.effective_to) == "2026-12-31" and r.effective_to > r.effective_from
