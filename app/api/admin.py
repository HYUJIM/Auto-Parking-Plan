"""
관리자 API (검수 화면용, 인증 필수: X-Admin-Token) — 설계서 10장.

  GET  /admin/extractions?status=          검수 대기 추출 결과
  GET  /admin/rules?status=                 규칙 목록 (기본 auto_checked = 검수 대기)
  GET  /admin/rules/{id}                    원문 하이라이트 + 추출 규칙 + 원장 초안
  POST /admin/rules/{id}/approve|reject|edit
  POST /admin/ledgers/{id}/facts/{fact_key} 원장 사실 수정 (must_mention, key_terms)
  POST /admin/ledgers/{id}/dismissals       원문 후보 제외 (사유 필수)
  POST /admin/ledgers/{id}/approve
  POST /admin/documents/{id}/refetch        원문 재수집 (국가법령정보 Open API, LAWGO_OC 필요)
  POST /admin/extractions/run               문서 버전 AI 추출 실행 (LLM 필요)
  POST /admin/zones                         L4 특화구역 수동 등록 (고시번호, 일자, 경계 또는 PNU 목록)
  GET  /admin/verifications/{report_id}     사후 검증 보고서 원문
  POST /admin/explanations/purge            설명 캐시 삭제 (재생성)
"""
from __future__ import annotations

import hashlib
import hmac
import json
import uuid
from datetime import date
from typing import Any

from fastapi import APIRouter, Depends, Header, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete, select

from app.api.errors import ApiError
from app.db.models import (
    DocumentVersion,
    Explanation,
    ExtractionRun,
    FactLedger,
    LedgerDismissal,
    LedgerFact,
    Provision,
    Rule,
    SourceDocument,
    VerificationReport,
    ZoneOverlay,
)
from app.db.types import utcnow
from app.explain.ledger import _provision_texts, _refresh, approve_ledger, ledger_to_dict
from app.legal.ingest import ensure_jurisdiction, ingest_bundle, link_delegations
from app.review.approval import ApprovalError, approve_rule, edit_rule, log_action, reject_rule


def require_admin(request: Request, x_admin_token: str | None = Header(default=None)) -> str:
    token = request.app.state.container.settings.admin_token
    if not token:
        raise ApiError("ADMIN_DISABLED")
    if not x_admin_token or not hmac.compare_digest(x_admin_token, token):
        raise ApiError("UNAUTHORIZED")
    return "admin"


router = APIRouter(prefix="/admin", tags=["admin"], dependencies=[Depends(require_admin)])


def _c(request: Request):  # noqa: ANN202
    return request.app.state.container


def _rule_summary(r: Rule) -> dict[str, Any]:
    return {"id": str(r.id), "rule_key": r.rule_key, "category": r.category, "layer": r.layer, "title": r.title,
            "jurisdiction_code": r.jurisdiction_code, "review_status": r.review_status, "confidence": r.confidence,
            "effective_from": r.effective_from.isoformat() if r.effective_from else None,
            "effective_to": r.effective_to.isoformat() if r.effective_to else None,
            "approved_by": r.approved_by, "approved_at": r.approved_at.isoformat() if r.approved_at else None}


@router.get("/extractions")
def list_extractions(request: Request, status: str | None = None, limit: int = 100) -> dict[str, Any]:
    with _c(request).db.session() as s:
        q = select(ExtractionRun).where(ExtractionRun.task == "rule_extraction").order_by(ExtractionRun.created_at.desc()).limit(limit)
        if status:
            q = q.where(ExtractionRun.status == status)
        runs = s.scalars(q).all()
        out = []
        for run in runs:
            rules = s.scalars(select(Rule).where(Rule.extraction_run_id == run.id)).all()
            out.append({"id": str(run.id), "version_id": str(run.version_id) if run.version_id else None, "status": run.status,
                        "model": run.model, "prompt_version": run.prompt_version, "created_at": run.created_at.isoformat(),
                        "validation": run.validation, "rules": [_rule_summary(r) for r in rules]})
        return {"count": len(out), "items": out}


@router.get("/rules")
def list_rules(request: Request, status: str = "auto_checked", category: str | None = None) -> dict[str, Any]:
    with _c(request).db.session() as s:
        q = select(Rule).where(Rule.review_status == status).order_by(Rule.category, Rule.rule_key)
        if category:
            q = q.where(Rule.category == category)
        return {"items": [_rule_summary(r) for r in s.scalars(q).all()]}


@router.get("/rules/{rule_id}")
def rule_detail(request: Request, rule_id: uuid.UUID) -> dict[str, Any]:
    with _c(request).db.session() as s:
        r = s.get(Rule, rule_id)
        if r is None:
            raise ApiError("RULE_NOT_FOUND")
        from app.review.approval import transcription_table  # noqa: PLC0415

        ev = []
        for e in r.evidence:
            p = s.get(Provision, e.provision_id)
            t = transcription_table(s, p)
            ev.append({"role": e.role, "rule_value_id": str(e.rule_value_id) if e.rule_value_id else None,
                       "provision_id": str(p.id), "label": p.label, "path": p.path, "text": p.text, "table_json": p.table_json,
                       "transcription": ({"source_url": t.table_json.get("source_url"), **(t.table_json.get("transcription") or {})}
                                         if t is not None else None),
                       "highlight": {"char_start": e.char_start, "char_end": e.char_end, "quoted_text": e.quoted_text}})
        ledgers = [ledger_to_dict(led) | {"gap_count": led.gap_count, "completeness_gaps": led.completeness_gaps}
                   for led in s.scalars(select(FactLedger).where(FactLedger.kind == "rule")).all()
                   if [str(x) for x in led.rule_ids] == [str(r.id)]]
        run = s.get(ExtractionRun, r.extraction_run_id) if r.extraction_run_id else None
        return {**_rule_summary(r),
                "conditions": [{"field": c.field, "operator": c.operator, "value": c.value_json} for c in r.conditions],
                "values": [{"id": str(v.id), "value_key": v.value_key, "value_type": v.value_type, "num_min": v.num_min,
                            "num_max": v.num_max, "num_exact": v.num_exact, "enum_value": v.enum_value, "unit": v.unit,
                            "formula": v.formula, "condition_note": v.condition_note} for v in r.values],
                "evidence": ev, "ledgers": ledgers,
                "extraction": {"id": str(run.id), "model": run.model, "status": run.status, "validation": run.validation} if run else None}


class ReviewAction(BaseModel):
    reviewer: str = Field(..., min_length=2, max_length=80)
    comment: str | None = Field(default=None, max_length=1000)


class ApproveAction(ReviewAction):
    approve_ledger: bool = False  # 원장을 확인했으면 true (기본은 규칙만 승인, 원장은 /admin/ledgers/{id}/approve)
    confirm_transcription: bool = False  # 표 이미지 전사본을 원본 이미지와 대조했음
    acknowledge_warnings: bool = False  # 경고(단서 미기재 등)를 검수자가 확인했음


class EditAction(ReviewAction):
    values: list[dict[str, Any]]


@router.post("/rules/{rule_id}/approve")
def approve(request: Request, rule_id: uuid.UUID, body: ApproveAction) -> dict[str, Any]:
    with _c(request).db.session() as s:
        try:
            r = approve_rule(s, rule_id, reviewer=body.reviewer, comment=body.comment, approve_ledger_too=body.approve_ledger,
                             acknowledge_warnings=body.acknowledge_warnings, confirm_transcription=body.confirm_transcription)
        except (ApprovalError, ValueError) as e:
            raise ApiError("CONFLICT", str(e)) from e
        return _rule_summary(r)


@router.post("/rules/{rule_id}/reject")
def reject(request: Request, rule_id: uuid.UUID, body: ReviewAction) -> dict[str, Any]:
    with _c(request).db.session() as s:
        try:
            return _rule_summary(reject_rule(s, rule_id, reviewer=body.reviewer, comment=body.comment))
        except ApprovalError as e:
            raise ApiError("CONFLICT", str(e)) from e


@router.post("/rules/{rule_id}/edit")
def edit(request: Request, rule_id: uuid.UUID, body: EditAction) -> dict[str, Any]:
    with _c(request).db.session() as s:
        try:
            return _rule_summary(edit_rule(s, rule_id, reviewer=body.reviewer, values=body.values, comment=body.comment))
        except ApprovalError as e:
            raise ApiError("CONFLICT", str(e)) from e


class FactPatch(ReviewAction):
    must_mention: bool | None = None
    key_terms: list[Any] | None = None


@router.post("/ledgers/{ledger_id}/facts/{fact_key}")
def patch_fact(request: Request, ledger_id: uuid.UUID, fact_key: str, body: FactPatch) -> dict[str, Any]:
    with _c(request).db.session() as s:
        led = s.get(FactLedger, ledger_id)
        if led is None or led.review_status != "draft":
            raise ApiError("CONFLICT", "초안(draft) 원장만 수정할 수 있습니다")
        f = s.scalar(select(LedgerFact).where(LedgerFact.ledger_id == ledger_id, LedgerFact.fact_key == fact_key))
        if f is None:
            raise ApiError("CONFLICT", "사실 없음")
        if body.must_mention is not None:
            f.must_mention = body.must_mention
        if body.key_terms is not None:
            f.key_terms = body.key_terms
        s.flush()
        s.refresh(led)
        _refresh(s, led, _provision_texts(s, led.provision_ids))
        log_action(s, target_type="fact_ledger", target_id=led.id, action="edit", reviewer=body.reviewer,
                   diff={"fact_key": fact_key, "must_mention": body.must_mention, "key_terms": body.key_terms}, comment=body.comment)
        return ledger_to_dict(led) | {"gap_count": led.gap_count}


class Dismissal(ReviewAction):
    candidate_id: str
    reason: str = Field(..., min_length=2, max_length=300)


@router.post("/ledgers/{ledger_id}/dismissals")
def dismiss(request: Request, ledger_id: uuid.UUID, body: Dismissal) -> dict[str, Any]:
    with _c(request).db.session() as s:
        led = s.get(FactLedger, ledger_id)
        if led is None or led.review_status != "draft":
            raise ApiError("CONFLICT", "초안(draft) 원장만 수정할 수 있습니다")
        s.merge(LedgerDismissal(ledger_id=ledger_id, candidate_id=body.candidate_id, reason=body.reason, reviewer=body.reviewer))
        s.flush()
        s.refresh(led)
        _refresh(s, led, _provision_texts(s, led.provision_ids))
        return ledger_to_dict(led) | {"gap_count": led.gap_count}


@router.post("/ledgers/{ledger_id}/approve")
def approve_led(request: Request, ledger_id: uuid.UUID, body: ReviewAction) -> dict[str, Any]:
    with _c(request).db.session() as s:
        led = s.get(FactLedger, ledger_id)
        if led is None:
            raise ApiError("CONFLICT", "원장 없음")
        try:
            approve_ledger(s, led, body.reviewer)
        except ValueError as e:
            raise ApiError("CONFLICT", str(e)) from e
        log_action(s, target_type="fact_ledger", target_id=led.id, action="approve", reviewer=body.reviewer, comment=body.comment)
        return ledger_to_dict(led)


@router.post("/documents/{document_id}/refetch")
def refetch(request: Request, document_id: uuid.UUID, body: ReviewAction) -> dict[str, Any]:
    c = _c(request)
    if not c.settings.lawgo_oc:
        raise ApiError("CONFLICT", "LAWGO_OC(국가법령정보 공동활용 Open API 인증값)가 설정되지 않았습니다")
    from app.legal.lawgo_drf import LawGoDrfClient  # noqa: PLC0415

    with c.db.session() as s:
        doc = s.get(SourceDocument, document_id)
        if doc is None:
            raise ApiError("CONFLICT", "문서 없음")
        import tempfile  # noqa: PLC0415
        from pathlib import Path  # noqa: PLC0415

        client = LawGoDrfClient(c.settings.lawgo_oc, base_url=c.settings.lawgo_base_url)
        target = "ordin" if doc.source_type in ("ordinance", "local_rule") else "law"
        with tempfile.TemporaryDirectory(prefix="lawgo-") as tmp:
            bundle = client.get_document(target, id=doc.external_id, raw_dir=Path(tmp) / "raw")
            bundle.document.key = doc.key
            res = ingest_bundle(s, bundle, c.blobs, base_dir=Path(tmp))
        link_delegations(s)
        log_action(s, target_type="document_version", target_id=res.version_id, action="request_reextract" if res.created else "comment",
                   reviewer=body.reviewer, comment="원문 재수집" + (" — 새 버전" if res.created else
                                                                     (" — 본문 불일치" if res.text_mismatch else " — 같은 버전")))
        return {"document_id": str(doc.id), "version_id": str(res.version_id), "new_version": res.created,
                "provisions_added": res.provisions, "text_mismatch": res.text_mismatch, "text_confirmed": res.text_confirmed}


class ExtractRun(ReviewAction):
    version_id: uuid.UUID
    categories: list[str] | None = None


@router.post("/extractions/run")
def run_extraction(request: Request, body: ExtractRun) -> dict[str, Any]:
    c = _c(request)
    if c.llms.extractor is None:
        raise ApiError("CONFLICT", "추출용 LLM 이 설정되지 않았습니다 (LLM_PROVIDER / API 키)")
    from app.extraction.extractor import extract_version  # noqa: PLC0415

    with c.db.session() as s:
        ver = s.get(DocumentVersion, body.version_id)
        if ver is None:
            raise ApiError("CONFLICT", "버전 없음")
        outs = extract_version(s, ver, c.llms.extractor, categories=body.categories)
        return {"runs": [{"run_id": str(o.run_id), "status": o.status, "rule_ids": [str(x) for x in o.rule_ids],
                          "errors": o.errors, "warnings": o.warnings} for o in outs]}


class ZoneRegistration(ReviewAction):
    zone_type: str = Field(..., pattern=r"^(district_unit_plan|rnd_special_zone|free_economic_zone|heritage|industrial_complex|other)$")
    name: str = Field(..., min_length=2, max_length=200)
    notice_title: str = Field(..., min_length=2, max_length=300)
    notice_number: str | None = None
    notice_date: date | None = None
    notice_url: str | None = None
    jurisdiction_code: str = Field(..., pattern=r"^(00|\d{2}|\d{5})$")
    geometry: dict[str, Any] | None = None
    pnu_list: list[str] = Field(default_factory=list)
    effective_from: date | None = None
    effective_to: date | None = None


@router.post("/zones", status_code=201)
def register_zone(request: Request, body: ZoneRegistration) -> dict[str, Any]:
    c = _c(request)
    if not body.geometry and not body.pnu_list:
        raise ApiError("INVALID_REQUEST", "경계(geometry) 또는 PNU 목록 중 하나는 필요합니다")
    with c.db.session() as s:
        ensure_jurisdiction(s, body.jurisdiction_code)
        ext_id = body.notice_number or f"manual-{uuid.uuid4().hex[:12]}"
        doc = s.scalar(select(SourceDocument).where(SourceDocument.external_system == "manual", SourceDocument.external_id == ext_id))
        if doc is None:
            doc = SourceDocument(key=f"l4.{ext_id}", layer="L4_SPECIAL_ZONE", source_type="notice", official_title=body.notice_title,
                                 jurisdiction_code=body.jurisdiction_code, external_system="manual", external_id=ext_id,
                                 homepage_url=body.notice_url)
            s.add(doc)
            s.flush()
        raw = json.dumps(body.model_dump(mode="json"), ensure_ascii=False, sort_keys=True).encode("utf-8")
        sha = hashlib.sha256(raw).hexdigest()
        obj = c.blobs.put(f"l4/{doc.id}/{sha[:16]}.json", raw, "application/json")  # 고시번호는 경로에 넣지 않음 (윈도우 금지 문자)
        if not s.scalar(select(DocumentVersion).where(DocumentVersion.document_id == doc.id, DocumentVersion.raw_sha256 == sha)):
            s.add(DocumentVersion(document_id=doc.id, promulgation_number=body.notice_number, promulgation_date=body.notice_date,
                                  effective_from=body.effective_from or body.notice_date, raw_object_key=obj, raw_format="json",
                                  raw_sha256=sha, source_url=body.notice_url or "manual://registration", fetched_at=utcnow(),
                                  v_source_access="confirmed" if body.notice_url else "not_checked",
                                  note=f"관리자 수작업 등록 ({body.reviewer})"))
        z = ZoneOverlay(document_id=doc.id, zone_type=body.zone_type, name=body.name, geom=body.geometry, pnu_list=body.pnu_list,
                        effective_from=body.effective_from or body.notice_date, effective_to=body.effective_to)
        s.add(z)
        s.flush()
        log_action(s, target_type="document_version", target_id=doc.id, action="comment", reviewer=body.reviewer,
                   comment=f"L4 특화구역 등록: {body.name}")
        return {"zone_id": str(z.id), "document_id": str(doc.id)}


@router.get("/verifications/{report_id}")
def verification(request: Request, report_id: uuid.UUID) -> dict[str, Any]:
    with _c(request).db.session() as s:
        r = s.get(VerificationReport, report_id)
        if r is None:
            raise ApiError("CONFLICT", "보고서 없음", status=404)
        return {"id": str(r.id), "explanation_id": str(r.explanation_id) if r.explanation_id else None,
                "ledger_id": str(r.ledger_id), "attempt_no": r.attempt_no, "candidate_source": r.candidate_source,
                "verdict": r.verdict, "error_codes": r.error_codes, "verifier_version": r.verifier_version,
                "judge_model": r.judge_model, "report": r.report_json, "created_at": r.created_at.isoformat()}


@router.get("/alerts")
def alerts(request: Request, limit: int = 100) -> dict[str, Any]:
    """검수·관리자 알림: 범위 이탈(OUT_OF_RANGE), 템플릿까지 실패한 설명, 재수집 본문 불일치·재추출 요청, 실패한 검토."""
    from app.db.models import ReviewItem, ReviewLog, ReviewRequest  # noqa: PLC0415

    with _c(request).db.session() as s:
        out = []
        for it in s.scalars(select(ReviewItem).where(ReviewItem.reason_code == "OUT_OF_RANGE").limit(limit)).all():
            out.append({"type": "OUT_OF_RANGE", "review_id": str(it.request_id), "category": it.category, "rule_ids": it.applied_rule_ids})
        for e in s.scalars(select(Explanation).where(Explanation.display_source == "basis_only").limit(limit)).all():
            out.append({"type": "EXPLANATION_ALL_FAILED", "explanation_id": str(e.id), "category": e.category, "audience": e.audience,
                        "ledger_id": str(e.ledger_id) if e.ledger_id else None})
        for lg in s.scalars(select(ReviewLog).where(ReviewLog.reviewer == "system").order_by(ReviewLog.created_at.desc()).limit(limit)).all():
            out.append({"type": "SOURCE_" + ("REEXTRACT" if lg.action == "request_reextract" else "TEXT_MISMATCH"),
                        "document_version_id": str(lg.target_id), "comment": lg.comment, "at": lg.created_at.isoformat()})
        for r in s.scalars(select(ReviewRequest).where(ReviewRequest.status == "failed").limit(limit)).all():
            out.append({"type": "REVIEW_FAILED", "review_id": str(r.id), "error": r.error})
        return {"count": len(out), "items": out}


class Purge(ReviewAction):
    only_basis_only: bool = True


@router.post("/explanations/purge")
def purge(request: Request, body: Purge) -> dict[str, Any]:
    with _c(request).db.session() as s:
        q = select(Explanation.id)
        if body.only_basis_only:
            q = q.where(Explanation.display_source == "basis_only")
        ids = list(s.scalars(q).all())
        for eid in ids:
            e = s.get(Explanation, eid)
            e.passed_report_id = None
            e.display_source = "basis_only"
        s.flush()
        s.execute(delete(VerificationReport).where(VerificationReport.explanation_id.in_(ids)))
        s.execute(delete(Explanation).where(Explanation.id.in_(ids)))
        return {"purged": len(ids)}
