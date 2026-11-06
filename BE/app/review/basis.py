"""
근거 트리 (설계서 7장 8단계, 기능정의서 12장): 적용 규칙의 rule_evidence → provision 에서 출발해
delegation 을 거꾸로 올라가며 L1 법률까지 노드를 만든다. 상위 → 하위 순서로 정렬한다.
"""
from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Delegation, DocumentVersion, Provision, Rule, SourceDocument
from app.legal.structure import article_label_for
from app.security.sanitize import safe_url

LAYER_ORDER = {"L1_NATIONAL": 0, "L2_LAND_USE": 1, "L3_LOCAL": 2, "L4_SPECIAL_ZONE": 3}
TYPE_ORDER = {"law": 0, "decree": 1, "rule": 2, "ordinance": 3, "local_rule": 4, "notice": 5, "plan": 6}


def _root_label(s: Session, p: Provision) -> tuple[Provision, str]:
    root = p
    while root.parent_id is not None:
        root = s.get(Provision, root.parent_id)
    return root, root.label


def provision_label(s: Session, p: Provision) -> str:
    root, base = _root_label(s, p)
    path = "/".join(seg for seg in p.path.split("~")[0].split("/") if not seg.startswith(("tbl", "row")))
    return article_label_for(path, base.replace(" ", "") if root.kind == "annex" else base)


def _transcription_table(s: Session, p: Provision) -> Provision | None:
    cur = p
    while cur is not None:
        if isinstance(cur.table_json, dict) and cur.table_json.get("supplement") == "image_table":
            return cur
        cur = s.get(Provision, cur.parent_id) if cur.parent_id else None
    return None


def _verbatim_prefix(text: str, limit: int = 400) -> str:
    t = text.strip()
    if len(t) <= limit:
        return t
    cut = t[:limit]
    for mark in ("다. ", "다.\n", ". "):
        i = cut.rfind(mark)
        if i > 40:
            return cut[: i + len(mark.rstrip())]
    return cut


def node_for(s: Session, p: Provision, *, quote: str | None, rule_id: uuid.UUID | None) -> dict:
    ver = s.get(DocumentVersion, p.version_id)
    doc = s.get(SourceDocument, ver.document_id)
    label = provision_label(s, p)
    url = ver.source_url or doc.homepage_url
    table = _transcription_table(s, p)
    if table is not None:
        # 원문 화면에 텍스트가 없어 표 이미지를 옮겨 적은 전사본임을 표시하고, 원본 이미지로 연결한다
        who = (table.table_json.get("transcription") or {}).get("verified_by") or "사람 대조 전"
        label = f"{provision_label(s, table)} [표 이미지 전사본 · 대조: {who}]"
        url = table.table_json.get("source_url") or url
    return {
        "layer": doc.layer,
        "document_title": doc.official_title,
        "article_label": label,
        "effective_from": ver.effective_from.isoformat() if ver.effective_from else None,
        "quote": (quote if quote is not None else _verbatim_prefix(p.text))[:400],
        "url": safe_url(url),  # 화면 링크 → http(s) 만 (javascript: 등 저장형 XSS 차단)
        "rule_id": str(rule_id) if rule_id else None,
        "_sort": (LAYER_ORDER[doc.layer], TYPE_ORDER.get(doc.source_type, 9)),
        "_pid": str(p.id),
    }


def _same_path_in_effective_version(s: Session, p: Provision, on: date) -> Provision:
    """위임 엣지가 가리키는 상위 조문이 기준일에 시행 중인 버전이 아니면, 같은 path 의 시행 중 버전 조문으로 바꾼다."""
    ver = s.get(DocumentVersion, p.version_id)
    if ver.effective_from and ver.effective_from <= on and (ver.effective_to is None or ver.effective_to >= on):
        return p
    versions = s.scalars(select(DocumentVersion).where(DocumentVersion.document_id == ver.document_id)).all()
    cur = next((v for v in versions if v.effective_from and v.effective_from <= on and (v.effective_to is None or v.effective_to >= on)), None)
    if cur is None:
        return p
    return s.scalar(select(Provision).where(Provision.version_id == cur.id, Provision.path == p.path)) or p


HEADLINE_VALUE_KEYS = ("non_parallel.general.width", "parallel.general.width", "perpendicular_90.one_gate", "ratio")


def _headline_evidence(rule: Rule):  # noqa: ANN202
    """근거 트리에 보일 대표 인용: 화면 기준값(일반형·직각주차·비율)의 근거를 우선한다."""
    by_value = {v.id: v.value_key for v in rule.values}
    vals = [e for e in rule.evidence if e.role == "value"]
    for key in HEADLINE_VALUE_KEYS:
        hit = next((e for e in vals if by_value.get(e.rule_value_id) == key), None)
        if hit is not None:
            return hit
    return vals[0] if vals else None


def basis_for_rules(s: Session, rules: list[Rule], on: date, *, max_depth: int = 4) -> list[dict]:
    nodes: dict[str, dict] = {}
    for rule in rules:
        ev = _headline_evidence(rule)
        if ev is None:
            continue
        start = s.get(Provision, ev.provision_id)
        nodes.setdefault(str(start.id), node_for(s, start, quote=ev.quoted_text, rule_id=rule.id))
        frontier = [start]
        for _ in range(max_depth):
            nxt = []
            for p in frontier:
                chain = [p]
                while chain[-1].parent_id is not None:
                    chain.append(s.get(Provision, chain[-1].parent_id))
                ids = [c.id for c in chain]
                edges = s.scalars(select(Delegation).where(Delegation.to_provision_id.in_(ids),
                                                           Delegation.review_status.in_(("auto_checked", "approved")))).all()
                for e in edges:
                    up = _same_path_in_effective_version(s, s.get(Provision, e.from_provision_id), on)
                    if str(up.id) not in nodes:
                        nodes[str(up.id)] = node_for(s, up, quote=None, rule_id=None)
                        nxt.append(up)
            frontier = nxt
            if not frontier:
                break
    ordered = sorted(nodes.values(), key=lambda n: n["_sort"])
    for n in ordered:
        n.pop("_sort", None)
        n.pop("_pid", None)
    return ordered
