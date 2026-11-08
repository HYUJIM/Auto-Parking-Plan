"""
파이썬판(BE/app/legal) 결과를 자바판 대조 시험용 기대값으로 만든다 (legal 모듈의 LegalParityTest 가 읽는다).

실행 (저장소 루트에서):
    BE/.venv/bin/python BE-spring/legal/src/test/resources/parity/gen_parity.py

출력 (이 파일과 같은 폴더):
    structure_parity.json : BE/data/legal_sources/*.json 번들(+ supplements 의 pdf_annex)의 모든 조문을
                            app.legal.structure.parse_article → flatten 한 행 (text 는 sha256 앞 16자리와 코드포인트 길이)
                            + article_label_for 결과
    html_parity.json      : BE/data/legal_sources/raw/*.html 과 BE/tests/fixtures/lawgo/*.html 을
                            app.legal.lawgo_html 파서로 읽은 결과 (긴 text 는 sha256 앞 16자리와 코드포인트 길이)
원문 파일이 바뀌면 파일 sha256 이 달라지므로 자바 시험이 '다시 만들라'고 알린다.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[5]  # .../AutoParkingPlan
BE = REPO / "BE"
sys.path.insert(0, str(BE))

from app.legal import lawgo_html as L  # noqa: E402
from app.legal.parsed import SourceBundle  # noqa: E402
from app.legal.structure import article_label_for, flatten, parse_article  # noqa: E402

DATA = BE / "data" / "legal_sources"
FIX = BE / "tests" / "fixtures" / "lawgo"
AS_OF = "2026-11-06"


def h(s: str | None) -> list | None:
    """긴 문자열 → [sha256 앞 16자리, 코드포인트 길이]."""
    if s is None:
        return None
    return [hashlib.sha256(s.encode("utf-8")).hexdigest()[:16], len(s)]


def file_sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def rows_of(label: str, path: str, heading: str | None, text: str, kind: str) -> list:
    out = []
    base = label.replace(" ", "") if kind == "annex" else label
    for r in flatten(parse_article(label, path, heading, text, kind=kind), text):
        lp = "/".join(seg for seg in r["path"].split("~")[0].split("/") if not seg.startswith(("tbl", "row")))
        out.append([r["kind"], r["label"], r["path"], r["heading"], h(r["text"]), r["parent_path"], article_label_for(lp, base)])
    return out


def structure() -> dict:
    out: dict = {"bundles": {}, "supplements": {}}
    for p in sorted(DATA.glob("*.json")):
        b = SourceBundle.from_json(json.loads(p.read_text(encoding="utf-8")))
        arts = []
        for a in b.articles:
            kind = "annex" if a.kind == "annex" else ("addendum" if a.kind == "addendum" else "article")
            arts.append({"path": a.path, "kind": kind, "rows": rows_of(a.label, a.path, a.heading, a.text, kind)})
        out["bundles"][p.name] = {"sha256": file_sha(p), "articles": arts}
    for p in sorted((DATA / "supplements").glob("*.json")):
        supp = json.loads(p.read_text(encoding="utf-8"))
        items = []
        for it in supp["items"]:
            if it["kind"] == "pdf_annex":
                items.append({"path": it["path"], "rows": rows_of(it["label"], it["path"], it.get("heading"), it["text"], "annex")})
        out["supplements"][p.name] = {"sha256": file_sha(p), "items": items}
    return out


def body_json(b: L.ParsedBody) -> dict:
    hd = b.header
    return {
        "header": [hd.official_title, hd.short_title, hd.header_line, hd.effective_from, hd.promulgation_number,
                   hd.promulgation_date, hd.revision_kind],
        "hidden": b.hidden,
        "consistency": L.header_consistency(hd, b.hidden),
        "external_id": b.external_id,
        "seq": b.seq,
        "articles": [{"label": a.label, "path": a.path, "heading": a.heading, "text": h(a.text), "anchor": a.anchor,
                      "warnings": a.warnings, "images": [[i["marker"], i["src"], h(i["alt"])] for i in a.images]}
                     for a in b.articles],
        "annexes": [[r.byl_seq, r.title_line, r.label, r.path, r.heading, r.file_links] for r in b.annexes],
    }


def html_case(name: str, html: str) -> dict:
    if "lsInfoR-" in name or name.startswith("law_body_"):
        return {"type": "body", "kind": "law", "result": body_json(L.parse_body(html, "law"))}
    if "ordinInfoR-" in name or name.startswith("ordin_body_"):
        b = L.parse_body(html, "ordin")
        return {"type": "body", "kind": "ordin", "result": body_json(b),
                "alias_links": L.extract_alias_links(html),
                "alias_links_rule": L.extract_alias_links(html, aliases=("법", "영", "규칙", "시행규칙")),
                "alias_definitions": [list(x) for x in L.extract_alias_definitions(b.articles)]}
    if name.endswith("__history.html") or "_history_" in name:
        kind = "ordin" if (".ord." in name or name.startswith("ordin_")) else "law"
        hist = L.parse_history(html, kind)
        cur = L.select_in_force(hist, AS_OF)
        return {"type": "history", "kind": kind,
                "entries": [[e.seq, h(e.text), e.effective_from, e.promulgation_date, e.promulgation_number, e.revision_kind,
                             e.site_current_flag] for e in hist],
                "in_force": [cur.seq, cur.effective_from] if cur else None,
                "pending": [[e.seq, e.effective_from] for e in L.pending_after(hist, AS_OF)]}
    if "__annex-" in name or "_annex_contents_" in name:
        c = L.parse_annex_contents(html)
        return {"type": "annex", "has_text": c.has_text, "text": h(c.text), "table_json": c.table_json,
                "viewer_iframe": c.viewer_iframe, "content_images": c.content_images, "file_seqs": c.file_seqs,
                "availability": c.availability}
    if name.startswith("name_resolver_"):
        return {"type": "resolver", "query": L.parse_name_resolver(html)}
    return {"type": "skip"}


def html() -> dict:
    out: dict = {"raw": {}, "fixtures": {}}
    for p in sorted((DATA / "raw").glob("*.html")):
        case = html_case(p.name, p.read_bytes().decode("utf-8"))
        if case["type"] != "skip":
            out["raw"][p.name] = {"sha256": file_sha(p), **case}
    for p in sorted(FIX.glob("*.html")):
        out["fixtures"][p.name] = {"sha256": file_sha(p), **html_case(p.name, p.read_bytes().decode("utf-8"))}
    return out


def dump(name: str, obj: dict) -> None:
    # 최상위 항목(파일) 하나 = 한 줄 (diff 를 보기 쉽게, 크기는 작게)
    lines = []
    for group, entries in obj.items():
        body = ",\n".join(f"  {json.dumps(k, ensure_ascii=False)}: {json.dumps(v, ensure_ascii=False, separators=(',', ':'))}"
                          for k, v in entries.items())
        lines.append(f" {json.dumps(group)}: {{\n{body}\n }}")
    (HERE / name).write_text("{\n" + ",\n".join(lines) + "\n}\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    dump("structure_parity.json", structure())
    dump("html_parity.json", html())
    print("ok")
