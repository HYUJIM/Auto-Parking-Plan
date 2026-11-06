"""
검토 요청 처리: POST /v1/reviews → 202 {review_id} → (백그라운드) 엔진 실행 → GET /v1/reviews/{id}.

진행 단계 (기능정의서 19장 '검토 중 화면'): 대지정보 조회 → 용도지역 확인 → 건축 법규 검토 → 주차 기준 확인 → 쉬운 설명 확인
결과는 review_result.schema.json 으로 검증한 뒤 저장한다 (계약 위반이면 저장하지 않고 failed).
"""
from __future__ import annotations

import json
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from functools import lru_cache
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from sqlalchemy import select

from app.db.models import Parcel, ReviewItem, ReviewRequest
from app.db.session import Database
from app.db.types import utcnow
from app.explain.ledger import compose_item_ledger
from app.explain.service import ExplainContext, ExplanationService, basis_only
from app.parcels.service import ParcelService
from app.review.engine import DISCLAIMER, ENGINE_VERSION, ReviewEngine

log = logging.getLogger(__name__)
SCHEMA_DIR = Path(__file__).resolve().parent.parent / "resources" / "schemas"
STEPS = [("parcel", "대지정보 조회"), ("zoning", "용도지역 확인"), ("building", "건축 법규 검토"),
         ("parking", "주차 기준 확인"), ("explain", "쉬운 설명 확인")]


@lru_cache
def review_result_validator() -> Draft202012Validator:
    reg = Registry()
    for p in SCHEMA_DIR.glob("*.schema.json"):
        doc = json.loads(p.read_text(encoding="utf-8"))
        reg = reg.with_resource(doc["$id"], Resource.from_contents(doc))
    schema = json.loads((SCHEMA_DIR / "review_result.schema.json").read_text(encoding="utf-8"))
    return Draft202012Validator(schema, registry=reg, format_checker=Draft202012Validator.FORMAT_CHECKER)


def today_kst(tz: str = "Asia/Seoul") -> date:
    return datetime.now(ZoneInfo(tz)).date()


class ReviewNotFound(Exception):
    pass


class ReviewService:
    def __init__(self, db: Database, parcels: ParcelService, explainer: ExplanationService, *,
                 executor: str = "thread", workers: int = 4, timezone: str = "Asia/Seoul"):
        self.db = db
        self.parcels = parcels
        self.explainer = explainer
        self.executor_mode = executor
        self.timezone = timezone
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="review") if executor == "thread" else None

    def shutdown(self) -> None:
        if self._pool:
            self._pool.shutdown(wait=False, cancel_futures=True)

    # ── 제출 ───────────────────────────────────────────────
    def submit(self, *, pnu: str, as_of: date | None, audience: str, inputs: dict[str, Any] | None = None) -> uuid.UUID:
        with self.db.session() as s:
            self.parcels.get_parcel(s, pnu)  # 필지 확정 (없으면 ParcelNotFound / UpstreamUnavailable)
            req = ReviewRequest(pnu=pnu, as_of_date=as_of or today_kst(self.timezone), audience=audience, inputs=inputs or {},
                                engine_version=ENGINE_VERSION, status="queued",
                                progress=[{"step": k, "label": lab, "state": "pending"} for k, lab in STEPS])
            s.add(req)
            s.flush()
            rid = req.id
        if self.executor_mode == "inline":
            self.run(rid)
        elif self.executor_mode == "thread":
            self._pool.submit(self._run_safe, rid)
        # worker 모드: app.worker 프로세스가 queued 요청을 가져간다
        return rid

    def _run_safe(self, rid: uuid.UUID) -> None:
        try:
            self.run(rid)
        except Exception:  # noqa: BLE001
            log.exception("검토 실행 실패 %s", rid)

    # ── 실행 ───────────────────────────────────────────────
    def _progress(self, rid: uuid.UUID, step: str, state: str) -> None:
        with self.db.session() as s:
            req = s.get(ReviewRequest, rid)
            req.progress = [dict(p, state=state) if p["step"] == step else p for p in req.progress]

    def run(self, rid: uuid.UUID) -> None:
        with self.db.session() as s:
            req = s.get(ReviewRequest, rid)
            if req is None or req.status not in ("queued", "running"):
                return
            req.status = "running"
        try:
            self._run(rid)
        except Exception as e:  # noqa: BLE001
            log.exception("review %s failed", rid)
            with self.db.session() as s:
                req = s.get(ReviewRequest, rid)
                req.status, req.error, req.finished_at = "failed", {"code": "INTERNAL_ERROR", "message": type(e).__name__}, utcnow()

    def _run(self, rid: uuid.UUID) -> None:
        self._progress(rid, "parcel", "running")
        with self.db.session() as s:
            req = s.get(ReviewRequest, rid)
            parcel = s.get(Parcel, req.pnu)
            snap = self.parcels.snapshot(s, parcel)
            as_of, audience, inputs = req.as_of_date, req.audience, dict(req.inputs or {})
        self._progress(rid, "parcel", "done")
        self._progress(rid, "zoning", "done" if snap.designations is not None else "failed")

        with self.db.session() as s:
            self._progress(rid, "building", "running")
            out = ReviewEngine(s, as_of=as_of, inputs=inputs).run(snap)
            self._progress(rid, "building", "done")
            self._progress(rid, "parking", "done")
            self._progress(rid, "explain", "running")
            items_json = []
            for it in out.items:
                led = None
                if it.rule_parts:
                    led = compose_item_ledger(s, parts=it.rule_parts, status=it.status, engine_facts=it.engine_facts)
                ctx = ExplainContext(category=it.category, title=it.title, status=it.status, reason_code=it.reason_code,
                                     criteria_text=it.applied.get("criteria_text"), audience=audience, rule_ids=it.applied_rule_ids)
                try:
                    explanation = self.explainer.explain(s, ctx, led) if led is not None else basis_only()
                except Exception:  # noqa: BLE001  (설명 실패가 검토 결과를 막지 않는다)
                    log.exception("설명 생성 실패: %s", it.category)
                    explanation = basis_only()
                items_json.append({"category": it.category, "title": it.title, "status": it.status, "reason_code": it.reason_code,
                                   "applied": it.applied, "basis": it.basis, "explanation": explanation})
            result = {
                "schema_version": "review_result@1.0",
                "review_id": str(rid),
                "as_of_date": as_of.isoformat(),
                "engine_version": ENGINE_VERSION,
                "ruleset_hash": out.ruleset_hash,
                "parcel": snap.to_result_parcel(),
                "items": items_json,
                "manual_checks": out.manual_checks,
                "data_notices": out.data_notices,
                "disclaimer": DISCLAIMER,
            }
            errors = sorted(review_result_validator().iter_errors(result), key=lambda e: list(e.absolute_path))
            req = s.get(ReviewRequest, rid)
            if errors:
                msgs = [f"{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in errors[:10]]
                log.error("review_result 계약 위반: %s", msgs)
                req.status, req.error = "failed", {"code": "RESULT_SCHEMA_VIOLATION", "message": "; ".join(msgs)[:2000]}
                req.finished_at = utcnow()
                return
            for it, ij in zip(out.items, items_json, strict=True):
                s.add(ReviewItem(request_id=rid, category=it.category, status=it.status, applied_rule_ids=it.applied_rule_ids,
                                 result_json=ij, reason_code=it.reason_code, sort_order=it.sort_order))
            req.ruleset_hash = out.ruleset_hash
            req.result_json = result
            partial = any(n["code"] in ("PARCEL_PARTIAL", "DESIGNATION_UNAVAILABLE") for n in out.data_notices)
            req.status = "partial" if partial else "done"
            req.progress = [dict(p, state="done") if p["state"] in ("running", "pending") else p for p in req.progress]
            req.finished_at = utcnow()

    # ── 조회 ───────────────────────────────────────────────
    def get(self, rid: uuid.UUID) -> dict[str, Any]:
        with self.db.session() as s:
            req = s.get(ReviewRequest, rid)
            if req is None:
                raise ReviewNotFound(str(rid))
            if req.status in ("done", "partial") and req.result_json:
                return {"state": req.status, "result": self._only_displayable(s, req.result_json)}
            return {"state": req.status, "progress": {"review_id": str(rid), "status": req.status, "steps": req.progress,
                                                      "error": req.error}}

    @staticmethod
    def _only_displayable(s, result: dict[str, Any]) -> dict[str, Any]:
        """저장된 결과라도, 화면에는 지금 displayable_explanation 뷰에 있는 설명만 내보낸다 (원장이 바뀌면 이전 검증 무효)."""
        from sqlalchemy import text as sql  # noqa: PLC0415

        ids = [i["explanation"]["verification"]["report_id"] for i in result.get("items", [])
               if i["explanation"]["display_source"] != "basis_only"]
        if not ids:
            return result
        rows = s.execute(sql("SELECT passed_report_id FROM displayable_explanation")).all()
        ok = {str(uuid.UUID(str(r[0]))) for r in rows if r[0] is not None}
        if all(i in ok for i in ids):
            return result
        out = json.loads(json.dumps(result))
        for it in out["items"]:
            ex = it["explanation"]
            if ex["display_source"] != "basis_only" and ex["verification"]["report_id"] not in ok:
                it["explanation"] = basis_only()
        return out

    def claim_next(self) -> uuid.UUID | None:
        """워커 모드: queued 요청 하나를 running 으로 가져간다.

        조건부 UPDATE(status='queued' 인 경우만)로 원자적으로 가져가므로 워커가 여럿이어도(SQLite 포함) 같은 요청을 두 번 돌리지 않는다.
        """
        from sqlalchemy import update  # noqa: PLC0415

        for _ in range(5):
            with self.db.session() as s:
                rid = s.scalar(select(ReviewRequest.id).where(ReviewRequest.status == "queued")
                               .order_by(ReviewRequest.created_at).limit(1))
                if rid is None:
                    return None
                n = s.execute(update(ReviewRequest).where(ReviewRequest.id == rid, ReviewRequest.status == "queued")
                              .values(status="running")).rowcount
                if n == 1:
                    return rid
        return None

    def requeue(self, rid: uuid.UUID) -> None:
        with self.db.session() as s:
            req = s.get(ReviewRequest, rid)
            if req is not None and req.status == "running":
                req.status = "queued"

    def reclaim_stale(self, older_than_s: int = 900) -> int:
        """워커가 죽어 'running' 으로 남은 오래된 요청을 다시 대기열로 (워커 시작 시 호출)."""
        from datetime import timedelta  # noqa: PLC0415

        cutoff = utcnow() - timedelta(seconds=older_than_s)
        with self.db.session() as s:
            rows = s.scalars(select(ReviewRequest).where(ReviewRequest.status == "running", ReviewRequest.created_at < cutoff)).all()
            for r in rows:
                r.status = "queued"
            return len(rows)

    def run_claimed(self, rid: uuid.UUID) -> None:
        try:
            self._run(rid)
        except Exception as e:  # noqa: BLE001
            log.exception("review %s failed", rid)
            with self.db.session() as s:
                req = s.get(ReviewRequest, rid)
                req.status, req.error, req.finished_at = "failed", {"code": "INTERNAL_ERROR", "message": type(e).__name__}, utcnow()
