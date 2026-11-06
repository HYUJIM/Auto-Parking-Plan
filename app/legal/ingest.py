"""
원문 적재기: SourceBundle → source_document / document_version / provision (+ 파서 기반 위임 관계).

수집 원칙 (설계서 4장)
  * 원문은 받은 그대로 객체 저장소에 두고 sha256 으로 변경을 감지한다.
  * 해시가 바뀌면 새 document_version 을 만든다. 그 버전의 규칙은 다시 추출·검수 대기가 된다.
  * 시행일이 미래인 버전도 저장하되 effective_from 으로 걸러 검토에 쓰지 않는다.
  * 같은 문서의 버전들은 시행일 순으로 effective_to(다음 버전 시행 전날)를 채운다.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Delegation, DocumentVersion, Jurisdiction, Provision, ReviewLog, Rule, SourceDocument
from app.legal.parsed import SourceBundle
from app.legal.structure import flatten, parse_article
from app.storage.blob import BlobStore

log = logging.getLogger(__name__)

CONTENT_TYPES = {"xml": "application/xml", "html": "text/html; charset=utf-8", "pdf": "application/pdf", "json": "application/json"}


@dataclass
class IngestResult:
    document_id: uuid.UUID
    version_id: uuid.UUID
    created: bool  # 새 버전(시행일·공포번호가 다른 버전)을 만들었는지
    provisions: int  # 추가한 조문 수
    text_mismatch: list[str] = field(default_factory=list)  # 같은 버전을 다시 받았는데 본문이 다른 조문 path
    text_confirmed: bool = False  # 다른 출처(예: Open API XML)로 받은 본문이 기존 본문과 일치


def _d(s: str | None) -> date | None:
    return date.fromisoformat(s) if s else None


def load_bundle_file(path: Path) -> SourceBundle:
    return SourceBundle.from_json(json.loads(Path(path).read_text(encoding="utf-8")))


def _raw_bytes(bundle: SourceBundle, base_dir: Path | None) -> bytes:
    if bundle.version.raw_file and base_dir is not None:
        p = base_dir / Path(*bundle.version.raw_file.split("/"))
        if p.exists():
            return p.read_bytes()
    # 원문 파일이 없으면 조문 텍스트 자체를 정규 JSON 으로 해시·보관
    return json.dumps([a.__dict__ for a in bundle.articles], ensure_ascii=False, sort_keys=True).encode("utf-8")


def ensure_jurisdiction(s: Session, code: str, name: str | None = None) -> Jurisdiction:
    j = s.get(Jurisdiction, code)
    if j:
        return j
    level = 0 if code == "00" else (1 if len(code) == 2 else 2)
    parent = None if level == 0 else ("00" if level == 1 else code[:2])
    if parent and not s.get(Jurisdiction, parent):
        ensure_jurisdiction(s, parent)
    j = Jurisdiction(code=code, name=name or code, level=level, parent_code=parent, is_supported=False)
    s.add(j)
    s.flush()
    return j


def ingest_bundle(s: Session, bundle: SourceBundle, blobs: BlobStore, *, base_dir: Path | None = None) -> IngestResult:
    dm, vm = bundle.document, bundle.version
    ensure_jurisdiction(s, dm.jurisdiction_code)
    doc = s.scalar(select(SourceDocument).where(SourceDocument.key == dm.key))
    parent_id = None
    if dm.parent_key:
        parent = s.scalar(select(SourceDocument).where(SourceDocument.key == dm.parent_key))
        parent_id = parent.id if parent else None
    if doc is None:
        doc = SourceDocument(key=dm.key, layer=dm.layer, source_type=dm.source_type, official_title=dm.official_title,
                             short_title=dm.short_title, jurisdiction_code=dm.jurisdiction_code,
                             external_system=dm.external_system, external_id=dm.external_id,
                             parent_document_id=parent_id, homepage_url=dm.homepage_url, aliases=dm.aliases or {})
        s.add(doc)
        s.flush()
    else:
        doc.official_title, doc.short_title, doc.aliases = dm.official_title, dm.short_title, dm.aliases or doc.aliases
        doc.parent_document_id = parent_id or doc.parent_document_id

    raw = _raw_bytes(bundle, base_dir)
    sha = hashlib.sha256(raw).hexdigest()
    existing = s.scalar(select(DocumentVersion).where(DocumentVersion.document_id == doc.id, DocumentVersion.raw_sha256 == sha))
    if existing is not None:
        return IngestResult(doc.id, existing.id, False, 0)

    # 같은 버전(시행일 + 공포번호, 또는 외부 버전 id)을 다른 출처·형식으로 다시 받은 경우: 새 버전을 만들지 않고 본문을 대조한다.
    # (예: 개발용 HTML 로 적재한 버전을 매일 국가법령정보 Open API XML 로 재수집)
    same = _same_version(s, doc.id, vm)
    if same is not None:
        return _reconcile(s, doc, same, bundle, raw, sha, blobs)

    eff = vm.effective_from or "unknown"
    key = f"law/{dm.key}/{eff}/{sha[:16]}.{vm.raw_format}"
    obj_key = blobs.put(key, raw, CONTENT_TYPES.get(vm.raw_format, "application/octet-stream"))
    ver = DocumentVersion(
        document_id=doc.id, promulgation_number=vm.promulgation_number, promulgation_date=_d(vm.promulgation_date),
        effective_from=_d(vm.effective_from), effective_to=_d(vm.effective_to), external_version_id=vm.external_version_id,
        raw_object_key=obj_key, raw_format=vm.raw_format, raw_sha256=sha, source_url=vm.source_url,
        fetched_at=datetime.fromisoformat(vm.fetched_at), v_source_access=vm.v_source_access,
        v_version_header=vm.v_version_header, v_text_match=vm.v_text_match, v_current_version=vm.v_current_version,
        current_checked_on=_d(vm.current_checked_on), note=vm.note, is_example=bundle.is_example)
    s.add(ver)
    s.flush()
    n = _add_articles(s, ver, bundle.articles, start_order=0)
    had_rules = s.scalar(select(Rule.id).join(DocumentVersion, Rule.version_id == DocumentVersion.id)
                         .where(DocumentVersion.document_id == doc.id, Rule.review_status == "approved").limit(1)) is not None
    recompute_effective_to(s, doc.id)
    if had_rules:
        # 설계서 4장: 해시(버전)가 바뀌면 그 버전의 규칙은 다시 추출·검수 대기
        s.add(ReviewLog(target_type="document_version", target_id=ver.id, action="request_reextract", reviewer="system",
                        comment=f"{dm.official_title} 새 버전({vm.effective_from} 시행, {vm.promulgation_number}) — 규칙 재추출·검수 필요"))
    return IngestResult(doc.id, ver.id, True, n)


def _norm_text(t: str) -> str:
    return re.sub(r"\s+", "", t or "")


def _same_version(s: Session, document_id: uuid.UUID, vm) -> DocumentVersion | None:  # noqa: ANN001
    if not vm.effective_from:
        return None
    cands = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == document_id,
                                                    DocumentVersion.effective_from == _d(vm.effective_from))).all()
    for v in cands:
        if vm.promulgation_number and v.promulgation_number and _norm_text(vm.promulgation_number) == _norm_text(v.promulgation_number):
            return v
        if vm.external_version_id and v.external_version_id and vm.external_version_id == v.external_version_id:
            return v
    return None


def _add_articles(s: Session, ver: DocumentVersion, articles, *, start_order: int) -> int:  # noqa: ANN001
    n, order = 0, start_order
    for art in articles:
        tree = parse_article(art.label, art.path, art.heading, art.text, kind="annex" if art.kind == "annex" else
                             ("addendum" if art.kind == "addendum" else "article"))
        tree.table_json = art.table_json
        by_path: dict[str, Provision] = {}
        for row in flatten(tree, art.text):
            order += 1
            p = Provision(version_id=ver.id, parent_id=by_path[row["parent_path"]].id if row["parent_path"] else None,
                          kind=row["kind"], label=row["label"], path=row["path"], heading=row["heading"],
                          text=row["text"], table_json=row["table_json"], sort_order=order)
            s.add(p)
            s.flush()
            by_path[row["path"]] = p
            n += 1
    return n


def _reconcile(s: Session, doc: SourceDocument, ver: DocumentVersion, bundle: SourceBundle, raw: bytes, sha: str,
               blobs: BlobStore) -> IngestResult:
    roots = {p.path: p for p in s.scalars(select(Provision).where(Provision.version_id == ver.id, Provision.parent_id.is_(None))).all()}
    mismatch, compared, new_articles = [], 0, []
    for art in bundle.articles:
        cur = roots.get(art.path)
        if cur is None:
            new_articles.append(art)
            continue
        compared += 1
        if _norm_text(art.text.replace("[[IMAGE", "")) != _norm_text(cur.text.replace("[[IMAGE", "")):
            mismatch.append(art.path)
    blobs.put(f"law/{doc.key}/{bundle.version.effective_from}/alt-{sha[:16]}.{bundle.version.raw_format}", raw,
              CONTENT_TYPES.get(bundle.version.raw_format, "application/octet-stream"))
    order = max((p.sort_order for p in s.scalars(select(Provision).where(Provision.version_id == ver.id)).all()), default=0)
    added = _add_articles(s, ver, new_articles, start_order=order) if not mismatch else 0
    src = bundle.version.source_url
    if mismatch:
        ver.v_text_match = "needs_review"
        ver.note = ((ver.note or "") + f" | 재수집 본문 불일치({bundle.version.raw_format}): {', '.join(mismatch[:20])}")[:4000]
        s.add(ReviewLog(target_type="document_version", target_id=ver.id, action="comment", reviewer="system",
                        diff_json={"mismatch_paths": mismatch, "source_url": src},
                        comment="같은 버전을 다시 받았는데 본문이 다름 — 검수자가 원문을 확인하고 필요하면 재추출"))
    elif compared and bundle.version.raw_format != ver.raw_format:
        ver.v_text_match = "confirmed"
        ver.note = ((ver.note or "") + f" | {bundle.version.raw_format} 원문과 본문 일치 확인 ({compared}개 조)")[:4000]
    s.flush()
    return IngestResult(doc.id, ver.id, False, added, text_mismatch=mismatch,
                        text_confirmed=bool(compared and not mismatch and bundle.version.raw_format != ver.raw_format))


def recompute_effective_to(s: Session, document_id: uuid.UUID) -> None:
    """버전 시행일 순으로 effective_to 를 채우고, 그 버전 규칙의 effective_to 도 맞춘다."""
    versions = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == document_id)).all()
    dated = sorted((v for v in versions if v.effective_from), key=lambda v: v.effective_from)
    for cur, nxt in zip(dated, dated[1:] + [None], strict=True):
        if nxt is not None and nxt.effective_from <= cur.effective_from:
            continue  # 같은 시행일 버전은 만들지 않지만, 혹시 있으면 시행 기간을 뒤집지 않는다
        cur.effective_to = (nxt.effective_from - timedelta(days=1)) if nxt else None
        for r in s.scalars(select(Rule).where(Rule.version_id == cur.id)).all():
            r.effective_to = cur.effective_to


# ── 파서 기반 위임 관계: 하위 조문의 '법 제77조', '영 제84조' 같은 참조 → 상위 조문 ──
REF_RE = re.compile(r"(?:「([^」]{2,60})」|(같은\s*법\s*시행령|같은\s*법|법|영|시행령|규칙|시행규칙))\s*제\s*(\d+)\s*조(?:\s*의\s*(\d+))?(?:\s*제\s*(\d+)\s*항)?")
DELEGATING_WORDS = re.compile(r"조례로\s*정|대통령령으로\s*정|국토교통부령으로\s*정|부령으로\s*정|규칙으로\s*정")


def _resolve_alias(s: Session, doc: SourceDocument, word: str | None, title: str | None) -> SourceDocument | None:
    if title:
        return s.scalar(select(SourceDocument).where(SourceDocument.official_title == title.strip()))
    word = re.sub(r"\s+", "", word or "")
    aliases = {re.sub(r"\s+", "", k): v for k, v in (doc.aliases or {}).items()}
    if word in aliases:
        return s.scalar(select(SourceDocument).where(SourceDocument.key == aliases[word]))
    if word == "법" and doc.parent_document_id:  # 시행령·시행규칙의 '법' = 모법
        return s.get(SourceDocument, doc.parent_document_id)
    if word in ("영", "시행령", "같은법시행령") and doc.parent_document_id:
        parent = s.get(SourceDocument, doc.parent_document_id)
        return s.scalar(select(SourceDocument).where(SourceDocument.parent_document_id == parent.id,
                                                     SourceDocument.source_type == "decree")) if parent else None
    return None


def _version_at(s: Session, document_id: uuid.UUID, on: date | None) -> DocumentVersion | None:
    vs = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == document_id)).all()
    dated = sorted((v for v in vs if v.effective_from), key=lambda v: v.effective_from)
    if on:
        cands = [v for v in dated if v.effective_from <= on]
        if cands:
            return cands[-1]
    return dated[-1] if dated else (vs[0] if vs else None)


ANNEX_REF_RE = re.compile(r"(같은\s*법\s*시행령|법|영|시행령|규칙|시행규칙)\s*별표\s*(\d+)(?:\s*의\s*(\d+))?")
SELF_ARTICLE_REF_RE = re.compile(r"\(\s*제\s*(\d+)\s*조(?:\s*의\s*(\d+))?(?:\s*제\s*(\d+)\s*항)?[^)]{0,30}관련\s*\)")


def _target(s: Session, version_id, path: str, para: str | None = None) -> Provision | None:
    t = None
    if para:
        t = s.scalar(select(Provision).where(Provision.version_id == version_id, Provision.path == f"{path}/para{para}"))
    return t or s.scalar(select(Provision).where(Provision.version_id == version_id, Provision.path == path))


def _edge(s: Session, upper: Provision, lower: Provision, evidence: str) -> int:
    relation = "delegates" if DELEGATING_WORDS.search(upper.text) else "implements"
    exists = s.scalar(select(Delegation).where(Delegation.from_provision_id == upper.id,
                                               Delegation.to_provision_id == lower.id, Delegation.relation == relation))
    if exists or upper.id == lower.id:
        return 0
    s.add(Delegation(from_provision_id=upper.id, to_provision_id=lower.id, relation=relation,
                     evidence_text=evidence[:300], created_by="parser", review_status="auto_checked"))
    return 1


def link_delegations(s: Session) -> int:
    """모든 버전의 조문에서 상위 법령 참조를 찾아 delegation 엣지를 만든다 (created_by='parser', 결정적).

      * '법 제77조제1항', '영 제84조제1항', '「…」 제N조'  → 그 조문(항)
      * '영 별표 5', '시행규칙 별표 1'                  → 그 별표
      * 별표 머리의 '(제71조제1항제4호관련)'             → 같은 문서의 그 조문 (별표는 조문을 구체화)
    """
    created = 0
    provisions = s.scalars(select(Provision).where(Provision.kind.in_(("article", "paragraph", "annex")))).all()
    for p in provisions:
        ver = s.get(DocumentVersion, p.version_id)
        doc = s.get(SourceDocument, ver.document_id)
        scan = p.text if p.kind != "article" else p.text.split("\n", 1)[0]
        for m in REF_RE.finditer(scan):
            title, word, art, art_sub, para = m.groups()
            target_doc = _resolve_alias(s, doc, word, title)
            if target_doc is None or target_doc.id == doc.id:
                continue
            tv = _version_at(s, target_doc.id, ver.effective_from)
            target = _target(s, tv.id, f"art{art}" + (f"-{art_sub}" if art_sub else ""), para) if tv else None
            if target is not None:
                created += _edge(s, target, p, m.group(0))
        for m in ANNEX_REF_RE.finditer(scan):
            word, num, sub = m.groups()
            target_doc = _resolve_alias(s, doc, word, None)
            if target_doc is None or target_doc.id == doc.id:
                continue
            tv = _version_at(s, target_doc.id, ver.effective_from)
            target = _target(s, tv.id, f"annex{num}" + (f"-{sub}" if sub else "")) if tv else None
            if target is not None:
                created += _edge(s, target, p, m.group(0))
        if p.kind == "annex" and p.parent_id is None:
            m = SELF_ARTICLE_REF_RE.search(p.text[:400])
            if m:
                art, art_sub, para = m.groups()
                target = _target(s, p.version_id, f"art{art}" + (f"-{art_sub}" if art_sub else ""), para)
                if target is not None:
                    created += _edge(s, target, p, m.group(0))
    s.flush()
    return created
