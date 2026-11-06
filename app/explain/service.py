"""
쉬운 설명 생성·검증·캐시 (설계서 8·9장).

흐름: 캐시 키 → displayable_explanation 뷰에 있으면 재사용
      → 없으면 AI 작성(explanation_writer) → 사후 검증(결정적 1–7 + 분리된 판정 모델 8)
      → 실패 시 반려 사유를 넣어 재생성(최대 2회) → 그래도 실패하면 원장 인용 템플릿(같은 검증 1–7)
      → 템플릿도 실패하면 설명 없이 근거 원문만 (basis_only)
검증을 통과하지 않은 문장은 어떤 경우에도 화면 응답에 들어가지 않는다.
"""
from __future__ import annotations

import hashlib
import json
import logging
import uuid
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.db.models import Explanation, ExtractionRun, FactLedger, Provision, VerificationReport
from app.llm.base import LLMClient, LLMError
from app.verification.ko_quantity import normalize_ref, parse, zone_names
from app.verification.post_verifier import _q_matches, verify

log = logging.getLogger(__name__)

RESOURCES = Path(__file__).resolve().parent.parent / "resources"
WRITER_PROMPT_VERSION = "explanation-writer@v2"
JUDGE_PROMPT_VERSION = "semantic-judge@v1"
VERIFIER_VERSION = "post_verifier@1.0+app0.1"
KIND_LABEL = {"PERIOD": "기간", "QUANTITY": "기준", "DATE": "날짜", "EXCEPTION": "예외", "MODALITY": "의무·제한",
              "SUBJECT": "적용 대상", "REFERENCE": "근거", "CONDITION": "검토 결과"}


@lru_cache
def _schema(name: str) -> dict:
    return json.loads((RESOURCES / "schemas" / name).read_text(encoding="utf-8"))


@lru_cache
def _prompt(name: str) -> str:
    return (RESOURCES / "prompts" / name).read_text(encoding="utf-8")


def cache_key(*, category: str, rule_ids: list[str], ledger_hash: str, status: str, audience: str) -> str:
    raw = "|".join([category, ",".join(sorted(rule_ids)), ledger_hash, status, audience, WRITER_PROMPT_VERSION])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def render_fallback_grouped(ledger: dict, category: str, audience: str) -> dict:
    """원장 인용만으로 만든 템플릿 설명. 같은 인용문을 쓰는 사실은 한 문장으로 묶는다."""
    facts = ledger["facts"]
    groups: dict[str, list[dict]] = {}
    for f in facts:
        if f.get("must_mention") and f["kind"] != "STATUS":
            groups.setdefault(f["quote"], []).append(f)
    sentences = []
    for n, (quote, fs) in enumerate(groups.items(), start=1):
        qs = parse(quote)
        also = [g["fact_id"] for g in facts if g not in fs and g.get("canonical") and any(_q_matches(q, g["canonical"]) for q in qs)]
        also += [g["fact_id"] for g in facts if g not in fs and g["kind"] == "SUBJECT" and normalize_ref(g.get("name", "")) in zone_names(quote)]
        ids = list(dict.fromkeys([f["fact_id"] for f in fs] + also))
        sentences.append({"sid": f"t{n}", "role": "meaning", "text": f"{KIND_LABEL.get(fs[0]['kind'], '원문')}: “{quote}”"[:200],
                          "fact_ids": ids})
    st = next((f for f in facts if f["kind"] == "STATUS"), None)
    if st and st["value"] != "REVIEWABLE":
        sentences.append({"sid": "t_caution", "role": "caution", "text": "추가 조건에 따라 결과가 달라질 수 있어 확인이 필요합니다.",
                          "fact_ids": [st["fact_id"]]})
    if not sentences and st:
        sentences.append({"sid": "t_status", "role": "summary", "text": st["quote"], "fact_ids": [st["fact_id"]]})
    return {"schema_version": "explanation@2.0", "category": category, "audience": audience,
            "source": "template_fallback", "sentences": sentences, "glossary": []}


@dataclass
class ExplainContext:
    category: str
    title: str
    status: str
    reason_code: str | None
    criteria_text: str | None
    audience: str
    rule_ids: list[str]


class ExplanationService:
    def __init__(self, *, writer: LLMClient | None, judge: LLMClient | None, max_attempts: int = 3):
        self.writer = writer
        self.judge = judge
        self.max_attempts = max_attempts

    # ── 공개 ───────────────────────────────────────────────
    def explain(self, s: Session, ctx: ExplainContext, ledger_row: FactLedger | None) -> dict[str, Any]:
        if ledger_row is None or ledger_row.review_status != "approved":
            return basis_only()
        ledger = ledger_row.ledger_json
        key = cache_key(category=ctx.category, rule_ids=ctx.rule_ids, ledger_hash=ledger_row.ledger_hash,
                        status=ctx.status, audience=ctx.audience)
        cached = self._cached(s, key)
        if cached is not None:
            return cached
        existing = s.scalar(select(Explanation).where(Explanation.cache_key == key))
        if existing is not None and existing.display_source == "basis_only":
            return basis_only()  # 이전에 모든 경로가 실패한 조합 (관리자가 재생성 요청 전까지 재사용)
        return self._generate(s, ctx, ledger_row, ledger, key)

    # ── 내부 ───────────────────────────────────────────────
    def _cached(self, s: Session, key: str) -> dict | None:
        row = s.execute(text("SELECT id FROM displayable_explanation WHERE cache_key = :k"), {"k": key}).first()
        if row is None:
            return None
        exp = s.get(Explanation, uuid.UUID(str(row[0])) if not isinstance(row[0], uuid.UUID) else row[0])
        if exp is None:  # SQLite 는 uuid 를 hex 문자열로 저장
            exp = s.scalar(select(Explanation).where(Explanation.cache_key == key))
        rep = s.get(VerificationReport, exp.passed_report_id)
        return _display(exp.display_source, exp.explanation_json, rep, s.get(FactLedger, exp.ledger_id).ledger_json)

    def _provisions(self, s: Session, ledger: dict) -> dict[str, str]:
        out = {}
        for pid in ledger["provision_ids"]:
            p = s.get(Provision, uuid.UUID(pid))
            if p:
                out[pid] = p.text
        return out

    def _generate(self, s: Session, ctx: ExplainContext, ledger_row: FactLedger, ledger: dict, key: str) -> dict:
        provisions = self._provisions(s, ledger)
        attempts: list[tuple[str, dict, dict, uuid.UUID | None]] = []  # (source, explanation, report, run_id)
        passed: tuple[str, dict, dict] | None = None
        feedback: list[str] = []
        judge_fn = self._judge_fn()
        if self.writer is not None:
            for _ in range(self.max_attempts):
                try:
                    exp, run_id = self._write(s, ctx, ledger, feedback)
                except LLMError as e:
                    log.warning("설명 작성 실패 (%s): %s", ctx.category, e)
                    break
                rep = verify(exp, ledger, provisions=provisions, judge=judge_fn)
                attempts.append(("ai", exp, rep, run_id))
                if rep["verdict"] == "PASS":
                    passed = ("ai_verified", exp, rep)
                    break
                feedback = rep["feedback_for_regeneration"]
        if passed is None:
            fb = render_fallback_grouped(ledger, ctx.category, ctx.audience)
            rep = verify(fb, ledger, provisions=provisions)  # 템플릿은 원문 인용이므로 판정 모델 없이 1–7
            attempts.append(("template_fallback", fb, rep, None))
            if rep["verdict"] == "PASS":
                passed = ("template_fallback", fb, rep)

        try:
            with s.begin_nested():
                exp_row = Explanation(cache_key=key, category=ctx.category, audience=ctx.audience, explanation_json=None,
                                      ledger_id=ledger_row.id, display_source="basis_only")
                s.add(exp_row)
                s.flush()
                report_rows = []
                for n, (src, _exp, rep, run_id) in enumerate(attempts, start=1):
                    rr = VerificationReport(explanation_id=exp_row.id, ledger_id=ledger_row.id, attempt_no=n, candidate_source=src,
                                            verdict=rep["verdict"], error_codes=sorted({e["code"] for e in rep["errors"]}),
                                            report_json=rep, verifier_version=VERIFIER_VERSION,
                                            judge_model=(self.judge.model if (self.judge and src == "ai") else None),
                                            ledger_hash=rep["ledger_hash"], explanation_hash=rep["explanation_hash"])
                    s.add(rr)
                    report_rows.append(rr)
                    if run_id is not None:
                        exp_row.extraction_run_id = run_id
                s.flush()
                if passed is not None:
                    src, exp, rep = passed
                    pass_row = next(r for r, a in zip(report_rows, attempts, strict=True) if a[2] is rep)
                    exp_row.display_source = src
                    exp_row.explanation_json = exp
                    exp_row.passed_report_id = pass_row.id
                    s.flush()
                if ledger_row.ledger_hash != (rep_hash := attempts[-1][2]["ledger_hash"]):
                    log.error("원장 해시 불일치: %s != %s", ledger_row.ledger_hash, rep_hash)
        except IntegrityError:
            # 다른 검토가 같은 캐시 키를 먼저 저장함 → 그 결과를 쓴다
            cached = self._cached(s, key)
            return cached if cached is not None else basis_only()
        if passed is None:
            log.error("설명 검증 실패: 템플릿도 통과하지 못함 (category=%s, ledger=%s) — 관리자 확인 필요", ctx.category, ledger_row.id)
            return basis_only()
        src, exp, rep = passed
        return _display(src, exp, pass_row, ledger)

    def _write(self, s: Session, ctx: ExplainContext, ledger: dict, feedback: list[str]) -> tuple[dict, uuid.UUID]:
        facts = [{k: v for k, v in f.items() if k != "candidate_ids"} for f in ledger["facts"]]
        payload = {
            "category": ctx.category, "audience": ctx.audience,
            "review_item": {"category": ctx.category, "title": ctx.title, "status": ctx.status,
                            "reason_code": ctx.reason_code, "criteria_text": ctx.criteria_text},
            "facts": facts,
        }
        if feedback:
            payload["rejection_feedback"] = "[사후 검증 반려 사유]\n" + "\n".join(f"- {x}" for x in feedback)
        user = json.dumps(payload, ensure_ascii=False)
        system = _prompt("explanation_prompt.md")
        run = ExtractionRun(version_id=None, provision_ids=ledger["provision_ids"], task="explanation",
                            model=f"{self.writer.provider}:{self.writer.model}", prompt_version=WRITER_PROMPT_VERSION,
                            schema_version="explanation.schema.json@2.0",
                            input_sha256=hashlib.sha256(user.encode("utf-8")).hexdigest(), status="failed")
        s.add(run)
        s.flush()
        try:
            res = self.writer.generate_json(task="explanation_writer", system=system, user=user,
                                            schema=_schema("explanation.schema.json"), max_tokens=4000)
        except LLMError as e:
            run.validation = {"error": str(e)}
            raise
        exp = res.data
        exp.setdefault("schema_version", "explanation@2.0")
        exp.setdefault("category", ctx.category)
        exp.setdefault("audience", ctx.audience)
        exp["source"] = "ai"
        run.output_json, run.status, run.model = exp, "ok", f"{self.writer.provider}:{res.model}"
        return exp, run.id

    def _judge_fn(self):
        judge = self.judge
        schema = _schema("judge_verdict.schema.json")
        system = _prompt("judge_prompt.md")

        def fn(facts: list[dict], sentences: list[dict]) -> dict:
            if judge is None:
                raise LLMError("판정 모델이 설정되지 않았습니다")
            user = json.dumps({"facts": facts, "sentences": [{k: s[k] for k in ("sid", "text", "fact_ids")} for s in sentences]},
                              ensure_ascii=False)
            return judge.generate_json(task="semantic_judge", system=system, user=user, schema=schema, max_tokens=4000).data

        return fn


def basis_only() -> dict:
    return {"display_source": "basis_only", "content": None,
            "verification": {"verdict": "NOT_AVAILABLE", "report_id": None, "checked_at": None,
                             "must_mention_total": 0, "must_mention_covered": 0}}


def _display(source: str, exp: dict, rep_row: VerificationReport, ledger: dict) -> dict:
    total = sum(1 for f in ledger["facts"] if f.get("must_mention") and f["kind"] != "STATUS")
    covered = sum(1 for c in rep_row.report_json.get("coverage", []) if c["status"] == "COVERED")
    return {"display_source": source, "content": exp,
            "verification": {"verdict": "PASS", "report_id": str(rep_row.id), "checked_at": rep_row.report_json["checked_at"],
                             "must_mention_total": total, "must_mention_covered": covered}}
