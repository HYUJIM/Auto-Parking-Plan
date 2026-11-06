"""
기록된 추출 결과(seed) 적재.

data/seed/extractions/*.json 은 rule_extraction@1.0 형식의 추출 결과를 보관한 것이다 (현재 저장소의 파일은 LLM 이 아니라
규칙형 추출기 scripts/build_seed_extractions.py 의 출력이며, extraction_run.model 에 그대로 기록된다).
조문 id 는 적재 시점에 정해지므로 '@<path>' 자리표시자로 쓰고, 여기서 실제 provision uuid 로 바꾼 뒤
실시간 LLM 출력과 똑같은 기계 검증(store_extraction)을 통과시킨다. 승인은 사람이 한다 (--approve-as 는 검수자 이름 기록).

형식:
{
  "format": "app-seed-extraction@1",
  "document_key": "kr.decree.nkpa",
  "effective_from": "2026-07-01",        # 대상 버전 (생략 시 최신)
  "model": "claude-opus-5-5 (dev session)",
  "prompt_version": "rule-extract@v1",
  "extraction": { ... rule_extraction@1.0, provision_id = "@art84/para1/item4", document_version_id = "@version" ... }
}
"""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import DocumentVersion, Provision, SourceDocument
from app.extraction.store import StoreOutcome, store_extraction

SEED_FORMAT = "app-seed-extraction@1"


class SeedError(Exception):
    pass


def _version(s: Session, doc_key: str, effective_from: str | None) -> DocumentVersion:
    doc = s.scalar(select(SourceDocument).where(SourceDocument.key == doc_key))
    if doc is None:
        raise SeedError(f"문서 없음: {doc_key} (원문 번들을 먼저 적재하세요)")
    vs = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == doc.id)).all()
    if effective_from:
        v = next((v for v in vs if v.effective_from == date.fromisoformat(effective_from)), None)
    else:
        v = max(vs, key=lambda v: (v.effective_from or date.min, v.fetched_at), default=None)
    if v is None:
        raise SeedError(f"버전 없음: {doc_key} {effective_from}")
    return v


def resolve(s: Session, version: DocumentVersion, extraction: dict[str, Any]) -> dict[str, Any]:
    paths = {p.path: str(p.id) for p in s.scalars(select(Provision).where(Provision.version_id == version.id)).all()}

    def walk(x):
        if isinstance(x, dict):
            return {k: walk(v) for k, v in x.items()}
        if isinstance(x, list):
            return [walk(v) for v in x]
        if isinstance(x, str) and x.startswith("@"):
            if x == "@version":
                return str(version.id)
            if x[1:] not in paths:
                raise SeedError(f"조문 path 없음: {x} (버전 {version.id})")
            return paths[x[1:]]
        return x

    return walk(extraction)


def load_seed(s: Session, seed: dict[str, Any]) -> StoreOutcome:
    if seed.get("format") != SEED_FORMAT:
        raise SeedError(f"알 수 없는 seed 형식: {seed.get('format')}")
    ver = _version(s, seed["document_key"], seed.get("effective_from"))
    output = resolve(s, ver, seed["extraction"])
    return store_extraction(s, version=ver, output=output, model=seed.get("model", "unknown"),
                            prompt_version=seed.get("prompt_version", "rule-extract@v1"))


def load_seed_file(s: Session, path: Path) -> StoreOutcome:
    return load_seed(s, json.loads(Path(path).read_text(encoding="utf-8")))
