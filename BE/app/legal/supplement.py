"""
원문 보충 자료 (설계서 11장 리스크: '별표가 이미지·복잡한 표로 제공됨 → 수작업 입력 허용, 근거로 원문 링크').

law.go.kr 화면에 텍스트로 없는 부분을 같은 문서 버전에 '보충 조문'으로 붙인다. 새 버전을 만들지 않는다.

  * pdf_annex   : 별표의 공식 PDF 첨부파일. 텍스트 층을 결정적으로 추출(pypdf)하고 공백만 정규화한다 → 원문 그대로.
  * image_table : 조문 안의 표 이미지. 사람(또는 AI 보조)이 칸 단위로 옮겨 적은 전사본. 원본 이미지 URL·sha256·전사자·
                  대조자(verified_by)를 table_json 에 남긴다. verified_by 가 비어 있으면 '사람 대조 전' 이며,
                  이 전사본을 근거로 한 규칙은 검수자가 이미지와 대조한 뒤에만 승인해야 한다.

형식 (data/legal_sources/supplements/*.json):
{
  "format": "app-legal-supplement@1",
  "document_key": "kr.rule.parking", "effective_from": "2026-07-27", "external_version_id": "288421",
  "items": [
    {"kind": "pdf_annex", "label": "별표 5", "path": "annex5", "heading": "...", "source_url": "...", "raw_file": "raw/x.pdf",
     "raw_sha256": "...", "text": "<정규화된 PDF 텍스트>"},
    {"kind": "image_table", "parent_path": "art3/para1/item2", "path": "art3/para1/item2/tbl33758907", "label": "표",
     "source_url": "...flSeq=33758907", "raw_file": "raw/x.gif", "raw_sha256": "...",
     "transcription": {"transcribed_by": "...", "transcribed_at": "...", "verified_by": null, "note": "..."},
     "rows": [["구분", "너비", "길이"], ["경형", "2.0미터 이상", "3.6미터 이상"], ...]}
  ]
}
"""
from __future__ import annotations

import hashlib
import io
import json
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import DocumentVersion, Provision, SourceDocument
from app.legal.structure import flatten, parse_article
from app.storage.blob import BlobStore

SUPPLEMENT_FORMAT = "app-legal-supplement@1"
ROW_SEP = " | "


class SupplementError(Exception):
    pass


# ── PDF 별표 텍스트 (결정적) ───────────────────────────────────
def pdf_text(data: bytes) -> str:
    from pypdf import PdfReader  # noqa: PLC0415

    reader = PdfReader(io.BytesIO(data))
    return "".join((p.extract_text() or "") for p in reader.pages)


def normalize_annex_text(raw: str) -> str:
    """공백만 바꾼다: 머리말 뒤·호(1.)·목(가.) 앞에 줄바꿈, 줄 끝 공백 제거. 글자는 바꾸지 않는다."""
    t = raw.replace("\r\n", "\n").replace("\r", "\n").replace(" ", " ")
    # 목: 두 칸 이상 공백(+줄바꿈)으로 구분됨 → 임시 표지
    t = re.sub(r"\n?[ \t]{2,}(?=[가-힣]\.\s)", "\x00", t)
    # 나머지 줄바꿈은 PDF 화면 줄넘김이다. pypdf 는 원래 띄어쓰기가 있던 곳은 공백을 남기므로(예: '건축물의 \n층수')
    # 줄바꿈만 지우면 원문 문장이 복원된다 (예: '초등학\n교' → '초등학교'). pypdf 6.10(줄바꿈 없음)·6.21(줄바꿈 있음) 모두 같은 결과.
    t = t.replace("\n", "").replace("\x00", "\n")
    t = re.sub(r"(<개정[^>]*>|<신설[^>]*>)", r"\1\n", t, count=1)
    t = re.sub(r"(?<=[^\s\d.])(?=\d{1,2}\.\s)", "\n", t)  # 호: 앞 글자에 붙어 있음 ('…관련)1. ')
    lines = [ln.rstrip() for ln in t.split("\n")]
    return "\n".join(ln for ln in lines if ln.strip())


def row_text(cells: list[str]) -> str:
    return ROW_SEP.join(c.strip() for c in cells)


@dataclass
class SupplementResult:
    version_id: str
    added: int
    skipped: int


def _version(s: Session, key: str, effective_from: str, external_version_id: str | None) -> DocumentVersion:
    doc = s.scalar(select(SourceDocument).where(SourceDocument.key == key))
    if doc is None:
        raise SupplementError(f"문서 없음: {key}")
    q = select(DocumentVersion).where(DocumentVersion.document_id == doc.id,
                                      DocumentVersion.effective_from == date.fromisoformat(effective_from))
    vs = s.scalars(q).all()
    if external_version_id:
        vs = [v for v in vs if v.external_version_id == external_version_id] or vs
    if not vs:
        raise SupplementError(f"버전 없음: {key} {effective_from} (원문 번들을 먼저 적재)")
    return vs[0]


def ingest_supplement(s: Session, supp: dict[str, Any], blobs: BlobStore, *, base_dir: Path | None = None) -> SupplementResult:
    if supp.get("format") != SUPPLEMENT_FORMAT:
        raise SupplementError(f"알 수 없는 형식: {supp.get('format')}")
    ver = _version(s, supp["document_key"], supp["effective_from"], supp.get("external_version_id"))
    existing = {p.path: p for p in s.scalars(select(Provision).where(Provision.version_id == ver.id)).all()}
    order = max((p.sort_order for p in existing.values()), default=0)
    added = skipped = 0
    notes = []
    for it in supp["items"]:
        raw_key = None
        if it.get("raw_file") and base_dir is not None:
            rp = base_dir / Path(*it["raw_file"].split("/"))
            if rp.exists():
                data = rp.read_bytes()
                sha = hashlib.sha256(data).hexdigest()
                if it.get("raw_sha256") and it["raw_sha256"] != sha:
                    raise SupplementError(f"원본 해시 불일치: {it['raw_file']}")
                ext = rp.suffix.lstrip(".") or "bin"
                raw_key = blobs.put(f"law/{supp['document_key']}/{supp['effective_from']}/supp-{sha[:16]}.{ext}", data,
                                    "application/pdf" if ext == "pdf" else f"image/{ext}")
        if it["path"] in existing:
            skipped += 1
            continue
        if it["kind"] == "pdf_annex":
            tree = parse_article(it["label"], it["path"], it.get("heading"), it["text"], kind="annex")
            tree.table_json = {"supplement": "pdf_annex", "source_url": it["source_url"], "raw_sha256": it.get("raw_sha256"),
                               "raw_object_key": raw_key}
            by_path: dict[str, Provision] = {}
            for row in flatten(tree, it["text"]):
                order += 1
                p = Provision(version_id=ver.id, parent_id=by_path[row["parent_path"]].id if row["parent_path"] else None,
                              kind=row["kind"], label=row["label"], path=row["path"], heading=row["heading"], text=row["text"],
                              table_json=row["table_json"], sort_order=order)
                s.add(p)
                s.flush()
                by_path[row["path"]] = p
                added += 1
            notes.append(f"{it['label']}: 공식 PDF 텍스트 ({it['source_url']})")
        elif it["kind"] == "image_table":
            parent = existing.get(it["parent_path"])
            if parent is None:
                raise SupplementError(f"상위 조문 없음: {it['parent_path']}")
            rows = it["rows"]
            meta = {"supplement": "image_table", "source_url": it["source_url"], "raw_sha256": it.get("raw_sha256"),
                    "raw_object_key": raw_key, "transcription": it["transcription"], "header": rows[0], "rows": rows[1:]}
            order += 1
            table = Provision(version_id=ver.id, parent_id=parent.id, kind="annex", label=it.get("label", "표"), path=it["path"],
                              heading="표 이미지 전사본", text="\n".join(row_text(r) for r in rows), table_json=meta, sort_order=order)
            s.add(table)
            s.flush()
            added += 1
            for i, r in enumerate(rows[1:], start=1):
                order += 1
                s.add(Provision(version_id=ver.id, parent_id=table.id, kind="annex_row", label=f"{i}행", path=f"{it['path']}/row{i}",
                                heading=None, text=row_text(r), table_json={"cells": r, "header": rows[0]}, sort_order=order))
                added += 1
            who = it["transcription"].get("verified_by") or "사람 대조 전"
            notes.append(f"{it['path']}: 표 이미지 전사본 ({it['source_url']}, 대조: {who})")
    if notes:
        ver.note = ((ver.note or "") + " | 보충: " + "; ".join(notes))[:4000]
    s.flush()
    return SupplementResult(str(ver.id), added, skipped)


def load_supplement_file(path: Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))
