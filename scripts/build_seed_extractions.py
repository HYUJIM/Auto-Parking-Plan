"""
수집한 원문 번들에서 규칙 추출 결과(rule_extraction@1.0)를 만든다 → data/seed/extractions/*.json

이 스크립트는 '규칙형 추출기'다 (LLM 키 없이 개발·검증하기 위한 대체 경로). 결과는 실시간 LLM 출력과
똑같이 기계 검증(근거 인용 원문 일치, 숫자 일치, 상위 범위)을 통과해야 저장되고, 사람이 승인해야 검토에 쓰인다.
extraction_run.model 에 'scripted:build_seed_extractions@v1' 로 남는다.

대상
  * 건폐율·용적률: 국토계획법 시행령 제84조①·제85조① (전국 범위), 대구광역시 도시계획 조례 제75조·제80조① (지역 값)
  * 건축 가능 용도: 시행령 별표 4·5·6·17 (공식 PDF 텍스트) + 조례 제33·34③·35·46조
  * 주차구획·차로 폭: 주차장법 시행규칙 제3조①(표 전사본), 제6조①3나2)(표 전사본)·제6조①4 (출입구 너비)

실행: python scripts/build_seed_extractions.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.console import ensure_utf8_stdio  # noqa: E402
from app.legal.parsed import SourceBundle  # noqa: E402
from app.legal.structure import flatten, parse_article  # noqa: E402
from app.legal.supplement import row_text  # noqa: E402
from app.verification.ko_quantity import parse  # noqa: E402

SRC = ROOT / "data" / "legal_sources"
OUT = ROOT / "data" / "seed" / "extractions"
MODEL = "scripted:build_seed_extractions@v1"
ZONE_CODES = {
    "제1종전용주거지역": "R1E", "제2종전용주거지역": "R2E", "제1종일반주거지역": "R1G", "제2종일반주거지역": "R2G",
    "제3종일반주거지역": "R3G", "준주거지역": "RQ", "중심상업지역": "CC", "일반상업지역": "CG", "근린상업지역": "CN",
    "유통상업지역": "CD", "전용공업지역": "IE", "일반공업지역": "IG", "준공업지역": "IQ", "보전녹지지역": "GC",
    "생산녹지지역": "GP", "자연녹지지역": "GN", "보전관리지역": "MC", "생산관리지역": "MP", "계획관리지역": "MPL",
    "농림지역": "AG", "자연환경보전지역": "NC",
}
ZONE_LINE = re.compile(r"^\s*\d+\.\s*(?P<zone>\S+지역)\s*:\s*(?P<rest>.+)$")


def provisions_of(bundle_file: str) -> tuple[SourceBundle, dict[str, str]]:
    b = SourceBundle.from_json(json.loads((SRC / bundle_file).read_text(encoding="utf-8")))
    paths: dict[str, str] = {}
    for a in b.articles:
        for row in flatten(parse_article(a.label, a.path, a.heading, a.text, kind="annex" if a.kind == "annex" else "article"), a.text):
            paths[row["path"]] = row["text"]
    return b, paths


def supplement_provisions(supp_file: str) -> dict[str, str]:
    supp = json.loads((SRC / "supplements" / supp_file).read_text(encoding="utf-8"))
    paths: dict[str, str] = {}
    for it in supp["items"]:
        if it["kind"] == "pdf_annex":
            for row in flatten(parse_article(it["label"], it["path"], it.get("heading"), it["text"], kind="annex"), it["text"]):
                paths[row["path"]] = row["text"]
        else:
            for i, r in enumerate(it["rows"][1:], start=1):
                paths[f"{it['path']}/row{i}"] = row_text(r)
    return paths


def exception_quote(text: str) -> str | None:
    """'(다만, …)' 또는 '다만, …' 구간을 원문 그대로."""
    i = text.find("(다만")
    if i >= 0:
        depth, j = 0, i
        while j < len(text):
            depth += {"(": 1, ")": -1}.get(text[j], 0)
            if depth == 0:
                return text[i:j + 1]
            j += 1
        return text[i:].split("\n")[0]
    i = text.find("다만")
    if i >= 0:
        seg = text[i:].split("\n")[0]
        k = seg.find("다.")
        return seg[: k + 2] if k > 0 else seg
    return None


def ratio_rules(paths: dict[str, str], prefix: str, category: str, *, national: bool) -> list[dict]:
    out = []
    abbr = "BCR" if category == "BUILDING_COVERAGE" else "FAR"
    label = "건폐율" if abbr == "BCR" else "용적률"
    for path, text in sorted(paths.items()):
        if not re.fullmatch(re.escape(prefix) + r"/item\d+", path):
            continue
        first = text.split("\n")[0]
        m = ZONE_LINE.match(first)
        if not m or m.group("zone") not in ZONE_CODES:
            continue
        zone = m.group("zone")
        quote = first[m.start("zone"):].strip()
        # 단서 '(다만…' 이 같은 줄에 붙어 있으면 값 근거 인용에서는 뺀다
        quote_main = quote.split("(다만")[0].rstrip()
        qs = [q for q in parse(quote_main) if q.kind == "QUANTITY" and q.dim == "ratio"]
        lo = next((q.value for q in qs if q.comparator == "GE"), None)
        hi = next((q.value for q in qs if q.comparator == "LE"), None)
        if hi is None:
            continue
        val = {"value_key": "ratio", "unit": "%", "evidence": {"provision_id": f"@{path}", "quote": quote_main}}
        if national and lo is not None:
            val.update(value_type="range", num_min=lo, num_max=hi)
        else:
            val.update(value_type="max", num_max=hi)
        rule = {"rule_key": f"{abbr}.ZONE.{ZONE_CODES[zone]}", "category": category,
                "title": f"{zone} {label} {'범위(국가 법령)' if national else '(조례)'}", "automation_level": "auto",
                "conditions": [{"field": "zoning", "operator": "eq", "value": zone}], "values": [val], "confidence": "high"}
        exc = exception_quote(text)
        if exc:
            rule["exceptions_mentioned"] = [{"provision_id": f"@{path}", "quote": exc}]
        out.append(rule)
    return out


USE_HEAD = re.compile(r"「건축법\s*시행령」\s*별표\s*1?\s*제\d+호(?:의\d+)?(?:[가-힣]목)?의\s*")


def use_entry(text: str) -> tuple[str, str, str | None] | None:
    """목·호 한 줄 → (근거 인용, 용도명, 조건 메모). 용도명은 인용 안에 그대로 있어야 한다."""
    first = re.sub(r"\s*<[^>]*>\s*$", "", text.split("\n")[0]).strip()
    body = re.sub(r"^\s*(?:\d+(?:의\d+)?|[가-힣](?:의\d+)?)\.\s*", "", first)
    m = USE_HEAD.search(body)
    if m:
        phrase = body[m.end():]
        quote_start = m.start()
    elif "별표 1 제" in body and "「건축법" in body:
        # '…별표 1 제21호마목부터 사목까지의 규정에 따른 시설 …' 처럼 호 번호 뒤에 바로 용도명이 오지 않는 경우
        k = body.find("별표 1 ") + len("별표 1 ")
        phrase = body[k:]
        quote_start = body.find("「건축법")
    else:
        k = body.rfind("의 ")
        if k < 0:
            return None
        phrase = body[k + 2:]
        quote_start = 0
        # 예: '제33조제9호 및 제9호의2의 공장', '별표 4 제2호차목 및 카목의 공장' → 다른 조문이 범위를 정하므로 그 문구를 조건으로 남긴다
        name0 = re.split(r"[\(\[（［]", phrase)[0].strip()
        return body[:k + 2 + len(name0)], name0, body[:300]
    phrase = phrase.strip()
    name = re.split(r"[\(\[（［]", phrase)[0].strip()
    name = re.sub(r"(?:으로서|로서)\s.*$", "", name).strip()
    if len(name) > 40:
        name = name.split(" 중 ")[0].strip()
    if not name:
        return None
    end = body.find(name, quote_start) + len(name)
    quote = body[quote_start:end]
    note = phrase if phrase != name else None
    return quote, name, (note[:300] if note else None)


def use_rules_from_annex(paths: dict[str, str], annex: str, zone: str) -> dict:
    values, exc = [], []
    head1 = paths.get(f"{annex}/item1", "").split("\n")[0]
    for path, text in sorted(paths.items(), key=lambda kv: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", kv[0])]):
        m = re.fullmatch(re.escape(annex) + r"/item(\d)/sub(\d+)(?:-\d+)?", path)
        if not m:
            continue
        e = use_entry(text)
        if e is None:
            continue
        quote, name, note = e
        sec = m.group(1)
        v = {"value_key": f"use:{sec}.{m.group(2)}", "enum_value": name, "evidence": {"provision_id": f"@{path}", "quote": quote}}
        if sec == "1":
            v.update(value_type="enum_allowed", condition_note=note)
        else:
            v.update(value_type="enum_conditional",
                     condition_note=("도시·군계획조례가 정하는 바에 따라 건축할 수 있는 건축물" + (f" — {note}" if note else ""))[:300])
        values.append(v)
    q = exception_quote(head1)
    if q:
        exc.append({"provision_id": f"@{annex}/item1", "quote": q})
    rule = {"rule_key": f"USE.ZONE.{ZONE_CODES[zone]}", "category": "ZONING_USE", "title": f"{zone} 안에서 건축할 수 있는 건축물 (영 {annex.replace('annex', '별표 ')})",
            "automation_level": "auto", "conditions": [{"field": "zoning", "operator": "eq", "value": zone}],
            "values": values, "confidence": "high"}
    if exc:
        rule["exceptions_mentioned"] = exc
    return rule


def use_rules_from_ordinance(paths: dict[str, str], base: str, zone: str) -> dict:
    values = []
    for path, text in sorted(paths.items(), key=lambda kv: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", kv[0])]):
        m = re.fullmatch(re.escape(base) + r"/item(\d+)(?:-(\d+))?", path)
        if not m:
            continue
        e = use_entry(text)
        if e is None:
            continue
        quote, name, note = e
        values.append({"value_key": f"use:{m.group(1)}" + (f"_{m.group(2)}" if m.group(2) else ""), "value_type": "enum_allowed",
                       "enum_value": name, "condition_note": note, "evidence": {"provision_id": f"@{path}", "quote": quote}})
    exc = []
    for v in values:
        path = v["evidence"]["provision_id"][1:]
        q = exception_quote(paths[path])
        if q:  # 호 안의 목에 단서('다만, …')가 있으면 규칙 단서로 기록 → 엔진이 조건부로 처리
            exc.append({"provision_id": f"@{path}", "quote": q})
    rule = {"rule_key": f"USE.ZONE.{ZONE_CODES[zone]}", "category": "ZONING_USE", "title": f"{zone} 안에서 건축할 수 있는 건축물 (조례)",
            "automation_level": "auto", "conditions": [{"field": "zoning", "operator": "eq", "value": zone}],
            "values": values, "confidence": "high"}
    if exc:
        rule["exceptions_mentioned"] = exc
    return rule


STALL_TYPES = {"경형": "compact", "일반형": "general", "확장형": "extended", "장애인전용": "disabled", "이륜자동차전용": "two_wheel",
               "이륜자동차 전용": "two_wheel", "보도와 차도의 구분이 없는 주거지역의 도로": "residential_road"}
AISLE_TYPES = {"평행주차": "parallel_0", "직각주차": "perpendicular_90", "60도 대향주차": "angle_60", "45도 대향주차": "angle_45",
               "교차주차": "cross"}


def num_of(cell: str) -> float:
    return float(re.match(r"\d+(?:\.\d+)?", cell).group(0))


def parking_rules(supp_paths: dict[str, str], rule_paths: dict[str, str]) -> list[dict]:
    rules = []
    for form, tbl, title in (("parallel", "art3/para1/item1/tbl24335428", "주차단위구획 (평행주차형식)"),
                             ("non_parallel", "art3/para1/item2/tbl33758907", "주차단위구획 (평행주차형식 외)")):
        values = []
        for path, text in sorted(supp_paths.items()):
            if not path.startswith(tbl + "/row"):
                continue
            cells = text.split(" | ")
            typ = STALL_TYPES[cells[0]]
            for dim, cell in (("width", cells[1]), ("length", cells[2])):
                values.append({"value_key": f"{form}.{typ}.{dim}", "value_type": "min", "num_min": num_of(cell), "unit": "m",
                               "condition_note": None, "evidence": {"provision_id": f"@{path}", "quote": text}})
        rules.append({"rule_key": f"PARK.STALL.{form.upper()}", "category": "PARKING_STALL_SIZE", "title": title,
                      "automation_level": "auto", "conditions": [], "values": values, "confidence": "high"})
    values = []
    for path, text in sorted(supp_paths.items()):
        if not path.startswith("art6/para1/item3/sub2/tbl24335458/row"):
            continue
        cells = text.split(" | ")
        ang = AISLE_TYPES[cells[0]]
        values.append({"value_key": f"{ang}.two_plus_gates", "value_type": "min", "num_min": num_of(cells[1]), "unit": "m",
                       "evidence": {"provision_id": f"@{path}", "quote": text}})
        values.append({"value_key": f"{ang}.one_gate", "value_type": "min", "num_min": num_of(cells[2]), "unit": "m",
                       "evidence": {"provision_id": f"@{path}", "quote": text}})
    gate = rule_paths["art6/para1/item4"]
    q1 = "노외주차장의 출입구 너비는 3.5미터 이상"
    q2 = "주차대수 규모가 50대 이상인 경우에는 출구와 입구를 분리하거나 너비 5.5미터 이상의 출입구를 설치"
    assert q1 in gate and q2 in gate, "제6조①4 원문이 예상과 다름 — 다시 확인"
    values.append({"value_key": "gate.width_min", "value_type": "min", "num_min": 3.5, "unit": "m",
                   "evidence": {"provision_id": "@art6/para1/item4", "quote": q1}})
    values.append({"value_key": "gate.width_50plus", "value_type": "min", "num_min": 5.5, "unit": "m",
                   "condition_note": "주차대수 50대 이상: 출구·입구 분리 또는 너비 5.5미터 이상 출입구",
                   "evidence": {"provision_id": "@art6/para1/item4", "quote": q2}})
    rules.append({"rule_key": "PARK.AISLE.OFFSTREET", "category": "DRIVE_AISLE_WIDTH", "title": "노외주차장 차로 너비·출입구 너비",
                  "automation_level": "auto", "conditions": [], "values": values, "confidence": "high"})
    return rules


def seed(doc_key: str, effective_from: str, rules: list[dict], note: str) -> dict:
    return {"format": "app-seed-extraction@1", "document_key": doc_key, "effective_from": effective_from, "model": MODEL,
            "prompt_version": "seed-script@v1", "note": note,
            "extraction": {"schema_version": "rule_extraction@1.0", "document_version_id": "@version", "rules": rules,
                           "delegations": [], "unresolved": []}}


def main() -> int:
    ensure_utf8_stdio()
    OUT.mkdir(parents=True, exist_ok=True)
    _, decree = provisions_of("kr.decree.nkpa__2026-09-18.json")
    _, ordin = provisions_of("daegu.ord.urban_planning__2026-05-11.json")
    _, prule = provisions_of("kr.rule.parking__2026-07-27.json")
    annex = supplement_provisions("kr.decree.nkpa__2026-09-18__annexes.json")
    tables = supplement_provisions("kr.rule.parking__2026-07-27__tables.json")

    files = {
        "01_kr.decree.nkpa__ratios.json": seed("kr.decree.nkpa", "2026-09-18",
            ratio_rules(decree, "art84/para1", "BUILDING_COVERAGE", national=True) + ratio_rules(decree, "art85/para1", "FLOOR_AREA_RATIO", national=True),
            "시행령 제84조①·제85조① 각 호 (용도지역별 건폐율 상한·용적률 범위)"),
        "02_kr.decree.nkpa__uses.json": seed("kr.decree.nkpa", "2026-09-18",
            [use_rules_from_annex(annex, "annex4", "제1종일반주거지역"), use_rules_from_annex(annex, "annex5", "제2종일반주거지역"),
             use_rules_from_annex(annex, "annex6", "제3종일반주거지역"), use_rules_from_annex(annex, "annex17", "자연녹지지역")],
            "시행령 별표 4·5·6·17 (공식 PDF 텍스트) 제1호=건축할 수 있는 건축물, 제2호=조례로 정하는 건축물"),
        "03_kr.rule.parking__dimensions.json": seed("kr.rule.parking", "2026-07-27", parking_rules(tables, prule),
            "주차장법 시행규칙 제3조①(표 이미지 전사본), 제6조①3나2)(표 이미지 전사본), 제6조①4. 전사본은 사람 대조 후 승인"),
        "10_daegu.ord.urban_planning__ratios.json": seed("daegu.ord.urban_planning", "2026-05-11",
            ratio_rules(ordin, "art75", "BUILDING_COVERAGE", national=False) + ratio_rules(ordin, "art80/para1", "FLOOR_AREA_RATIO", national=False),
            "대구광역시 도시계획 조례 제75조·제80조① 각 호"),
        "11_daegu.ord.urban_planning__uses.json": seed("daegu.ord.urban_planning", "2026-05-11",
            [use_rules_from_ordinance(ordin, "art33", "제1종일반주거지역"), use_rules_from_ordinance(ordin, "art34/para3", "제2종일반주거지역"),
             use_rules_from_ordinance(ordin, "art35", "제3종일반주거지역"), use_rules_from_ordinance(ordin, "art46", "자연녹지지역")],
            "대구광역시 도시계획 조례 제33조·제34조③·제35조·제46조 (영 별표 제2호 위임분)"),
    }
    for name, data in files.items():
        (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
        rules = data["extraction"]["rules"]
        print(f"{name}: 규칙 {len(rules)}개, 값 {sum(len(r['values']) for r in rules)}개")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
