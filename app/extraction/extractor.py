"""
오프라인 배치: 조문 → 규칙 JSON (AI) → 기계 검증 → draft/auto_checked 저장 (설계서 6장 2–4단계, 재추출 6단계).

대상 선별(2단계): MVP 카테고리 키워드 + 이미 연결된 위임 조문.
AI 는 조문 묶음(조 본문 + 하위 항·호·목)을 받아 rule_extraction@1.0 형식으로만 답한다.
기계 검증 반려 시 반려 사유를 붙여 최대 2회 재추출한다.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import DocumentVersion, Provision, SourceDocument
from app.extraction.store import PROMPT_VERSION, StoreOutcome, store_extraction
from app.llm.base import LLMClient, LLMError

log = logging.getLogger(__name__)
RES = Path(__file__).resolve().parent.parent / "resources"

CATEGORY_KEYWORDS = {
    "BUILDING_COVERAGE": ["건폐율"],
    "FLOOR_AREA_RATIO": ["용적률"],
    "ZONING_USE": ["건축할 수 있는 건축물", "건축제한", "건축할 수 없는 건축물"],
    "PARKING_STALL_SIZE": ["주차구획", "주차단위구획"],
    "DRIVE_AISLE_WIDTH": ["차로", "출입구"],
    "PARKING_REQUIREMENT": ["부설주차장의 설치기준", "설치기준", "주차대수"],
}


@lru_cache
def _prompt() -> str:
    return (RES / "prompts" / "rule_extraction_prompt.md").read_text(encoding="utf-8")


@lru_cache
def _schema() -> dict:
    return json.loads((RES / "schemas" / "rule_extraction.schema.json").read_text(encoding="utf-8"))


@dataclass
class Bundle:
    root: Provision
    members: list[Provision]
    categories: list[str]


def select_bundles(s: Session, version: DocumentVersion, categories: list[str] | None = None) -> list[Bundle]:
    cats = categories or list(CATEGORY_KEYWORDS)
    roots = s.scalars(select(Provision).where(Provision.version_id == version.id, Provision.parent_id.is_(None))
                      .order_by(Provision.sort_order)).all()
    out = []
    for r in roots:
        hay = (r.heading or "") + "\n" + r.text
        hit = [c for c in cats if any(k in hay for k in CATEGORY_KEYWORDS[c])]
        if not hit:
            continue
        members = s.scalars(select(Provision).where(Provision.version_id == version.id, Provision.path.like(f"{r.path}%"))
                            .order_by(Provision.sort_order)).all()
        out.append(Bundle(r, list(members), hit))
    return out


def extract_version(s: Session, version: DocumentVersion, llm: LLMClient, *, categories: list[str] | None = None,
                    max_retries: int = 2) -> list[StoreOutcome]:
    doc = s.get(SourceDocument, version.document_id)
    outcomes = []
    for b in select_bundles(s, version, categories):
        payload = {
            "document_version_id": str(version.id),
            "document": {"title": doc.official_title, "layer": doc.layer, "jurisdiction_code": doc.jurisdiction_code,
                         "effective_from": version.effective_from.isoformat() if version.effective_from else None},
            "categories": b.categories,
            "provisions": [{"provision_id": str(p.id), "label": p.label, "path": p.path, "text": p.text} for p in b.members],
        }
        feedback: list[str] = []
        for attempt in range(max_retries + 1):
            body = dict(payload)
            if feedback:
                body["rejection_feedback"] = "[기계 검증 반려 사유]\n" + "\n".join(f"- {x}" for x in feedback)
            try:
                res = llm.generate_json(task="rule_extraction", system=_prompt(), user=json.dumps(body, ensure_ascii=False),
                                        schema=_schema(), max_tokens=16000)
            except LLMError as e:
                log.warning("추출 실패 %s %s: %s", doc.key, b.root.label, e)
                break
            out = store_extraction(s, version=version, output=res.data, model=f"{llm.provider}:{res.model}",
                                   provision_ids=[p.id for p in b.members], prompt_version=PROMPT_VERSION)
            outcomes.append(out)
            if out.status in ("ok", "range_error"):
                break
            feedback = out.errors
            log.info("재추출 %d/%d (%s): %s", attempt + 1, max_retries, b.root.label, feedback[:3])
    return outcomes
