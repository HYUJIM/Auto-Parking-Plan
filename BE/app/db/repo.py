"""자주 쓰는 조회. active_rules_at 은 schema.sql 의 같은 이름 함수와 같은 의미 (이식 가능한 파이썬 구현)."""
from __future__ import annotations

import hashlib
from datetime import date

from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.db.models import DocumentVersion, Jurisdiction, Rule, SourceDocument


def active_rules_at(s: Session, on: date, *, jurisdiction_codes: list[str] | None = None,
                    categories: list[str] | None = None) -> list[Rule]:
    """기준일에 시행 중이고 승인된 규칙만. 시행일이 NULL 인 규칙은 검토에 쓰지 않는다."""
    q = select(Rule).where(
        Rule.review_status == "approved",
        Rule.effective_from.is_not(None),
        Rule.effective_from <= on,
        or_(Rule.effective_to.is_(None), Rule.effective_to >= on),
    )
    if jurisdiction_codes:
        q = q.where(Rule.jurisdiction_code.in_(jurisdiction_codes))
    if categories:
        q = q.where(Rule.category.in_(categories))
    return list(s.scalars(q.order_by(Rule.rule_key, Rule.effective_from)).all())


def ruleset_hash(rules: list[Rule]) -> str:
    """사용한 approved 규칙 집합의 해시 (재현성). 규칙 id·키·버전·승인 시각으로 만든다."""
    parts = sorted(f"{r.id}|{r.rule_key}|{r.version_id}|{r.approved_at.isoformat() if r.approved_at else ''}" for r in rules)
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def jurisdiction_chain(code: str) -> list[str]:
    """시·군·구 → 시·도 → 전국."""
    chain = [code]
    if len(code) >= 5:
        chain.append(code[:2])
    if code != "00":
        chain.append("00")
    return list(dict.fromkeys(chain))


def is_supported(s: Session, code: str) -> bool:
    j = s.get(Jurisdiction, code)
    return bool(j and j.is_supported)


def pending_amendments(s: Session, on: date, jurisdiction_codes: list[str]) -> list[tuple[SourceDocument, DocumentVersion]]:
    """기준일 이후 시행 예정인 버전 (PENDING_AMENDMENT 안내용)."""
    q = (select(SourceDocument, DocumentVersion).join(DocumentVersion, DocumentVersion.document_id == SourceDocument.id)
         .where(and_(SourceDocument.jurisdiction_code.in_(jurisdiction_codes), DocumentVersion.effective_from > on))
         .order_by(DocumentVersion.effective_from))
    return [tuple(r) for r in s.execute(q).all()]


def versions_in_force_without_rules(s: Session, on: date, jurisdiction_codes: list[str]) -> list[tuple[SourceDocument, DocumentVersion]]:
    """기준일에 시행 중인 버전에 승인 규칙이 없는데, 같은 문서의 이전 버전에는 승인 규칙이 있던 경우 (개정 후 재검수 대기)."""
    out = []
    docs = s.scalars(select(SourceDocument).where(SourceDocument.jurisdiction_code.in_(jurisdiction_codes))).all()
    for d in docs:
        vs = sorted((v for v in d.versions if v.effective_from), key=lambda v: v.effective_from)
        cur = [v for v in vs if v.effective_from <= on and (v.effective_to is None or v.effective_to >= on)]
        if not cur:
            continue
        cur_v = cur[-1]
        has_cur = s.scalar(select(Rule.id).where(Rule.version_id == cur_v.id, Rule.review_status == "approved").limit(1))
        older = [v.id for v in vs if v.effective_from < cur_v.effective_from]
        had_old = older and s.scalar(select(Rule.id).where(Rule.version_id.in_(older), Rule.review_status.in_(("approved", "superseded"))).limit(1))
        if not has_cur and had_old:
            out.append((d, cur_v))
    return out
