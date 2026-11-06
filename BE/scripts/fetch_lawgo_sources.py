"""
law.go.kr 공개 HTML 에서 법규 원문을 받아 data/legal_sources/ 에 SourceBundle(JSON)로 저장한다.

    .venv/bin/python scripts/fetch_lawgo_sources.py                 # 전체
    .venv/bin/python scripts/fetch_lawgo_sources.py --only kr.law.nkpa kr.decree.nkpa
    .venv/bin/python scripts/fetch_lawgo_sources.py --as-of 2026-11-06

- 대상 목록(TARGETS)은 선언형이다. 조문 번호는 받은 페이지에서 확인한 번호이며,
  expect_headings 로 조 제목을 함께 확인해 번호가 바뀌면 '누락'으로 기록한다(잘못된 조를 담지 않는다).
- 버전은 연혁 목록에서 '시행일 ≤ 기준일' 중 가장 늦은 것을 고른다. 시행 예정 버전은 include_pending 일 때만 따로 저장.
- 원문 페이지는 받은 바이트 그대로 raw/ 에 저장, MANIFEST.md 는 매번 다시 쓴다(재실행 가능).
- 요청은 순차, 요청 사이 1.2초 이상 (LawGoHtmlClient).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import traceback
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from bs4 import BeautifulSoup  # noqa: E402

from app.legal.lawgo_html import (  # noqa: E402
    KST,
    USER_AGENT,
    AnnexFetch,
    FetchedPage,
    HistoryEntry,
    LawGoHtmlClient,
    Missing,
    ParsedBody,
    build_bundle,
    bundle_filename,
    extract_alias_definitions,
    extract_alias_links,
    homepage_url,
    human_url,
    now_kst_iso,
    parse_annex_contents,
    parse_body,
    parse_history,
    pending_after,
    select_in_force,
)
from app.legal.parsed import DocumentMeta, SourceBundle  # noqa: E402

OUT_DIR = ROOT / "data" / "legal_sources"
RAW_DIR = OUT_DIR / "raw"
MANIFEST = OUT_DIR / "MANIFEST.md"


@dataclass
class Target:
    key: str
    kind: str  # 'law' | 'ordin'
    name: str  # 공식 제명 (법령 이름 주소로 현행본을 찾는 데 사용)
    layer: str
    source_type: str
    jurisdiction_code: str
    priority: str
    purpose: str
    parent_key: str | None = None
    articles: list[str] = field(default_factory=list)
    expect_headings: dict[str, str] = field(default_factory=dict)
    annexes: list[str] = field(default_factory=list)
    expect_annex_headings: dict[str, str] = field(default_factory=dict)
    header_only: bool = False
    include_pending: bool = False
    resolve_aliases: bool = False


# 조례 약칭이 가리키는 '공식 제명' → 문서 key. 번들이 없는 문서는 넣지 않는다(존재하지 않는 key 를 만들지 않음).
TITLE_TO_KEY = {
    "국토의 계획 및 이용에 관한 법률": "kr.law.nkpa",
    "국토의 계획 및 이용에 관한 법률 시행령": "kr.decree.nkpa",
    "주차장법": "kr.law.parking",
    "주차장법 시행령": "kr.decree.parking",
    "주차장법 시행규칙": "kr.rule.parking",
    "건축법 시행령": "kr.decree.building",
}

TARGETS: list[Target] = [
    # ---------------- P1 건폐율·용적률 ----------------
    Target(
        key="kr.law.nkpa", kind="law", name="국토의 계획 및 이용에 관한 법률",
        layer="L1_NATIONAL", source_type="law", jurisdiction_code="00", priority="P1·P3",
        purpose="용도지역 건폐율·용적률 상한(제77조·제78조), 건축제한 위임(제76조)",
        articles=["제76조", "제77조", "제78조"],
        expect_headings={"제76조": "건축", "제77조": "건폐율", "제78조": "용적률"},
    ),
    Target(
        key="kr.decree.nkpa", kind="law", name="국토의 계획 및 이용에 관한 법률 시행령",
        layer="L1_NATIONAL", source_type="decree", jurisdiction_code="00", priority="P1·P3",
        parent_key="kr.law.nkpa",
        purpose="세분 용도지역 건폐율·용적률 범위(제84조·제85조), 용도지역 건축제한(제71조·별표)",
        articles=["제71조", "제84조", "제85조"],
        expect_headings={"제71조": "건축제한", "제84조": "건폐율", "제85조": "용적률"},
        annexes=["별표 4", "별표 5", "별표 6", "별표 7", "별표 9", "별표 17"],
        expect_annex_headings={
            "별표 4": "제1종일반주거지역", "별표 5": "제2종일반주거지역", "별표 6": "제3종일반주거지역",
            "별표 7": "준주거지역", "별표 9": "일반상업지역", "별표 17": "자연녹지지역",
        },
    ),
    Target(
        key="daegu.ord.urban_planning", kind="ordin", name="대구광역시 도시계획 조례",
        layer="L3_LOCAL", source_type="ordinance", jurisdiction_code="27", priority="P1·P3",
        purpose="대구 용도지역 건폐율(제75조)·용적률(제80조), 용도지역별 건축제한(제33~36·38·46조), 약칭 정의(제2·3·30조)",
        articles=["제1조", "제2조", "제3조", "제30조", "제33조", "제34조", "제35조", "제36조", "제38조", "제46조",
                  "제75조", "제80조"],
        expect_headings={
            "제1조": "목적", "제2조": "기본방향", "제3조": "자문단", "제30조": "이행보증금",
            "제33조": "제1종일반주거지역", "제34조": "제2종일반주거지역", "제35조": "제3종일반주거지역",
            "제36조": "준주거지역", "제38조": "일반상업지역", "제46조": "자연녹지지역",
            "제75조": "건폐율", "제80조": "용적률",
        },
        resolve_aliases=True,
    ),
    # ---------------- P2 주차 구획·구조 ----------------
    Target(
        key="kr.law.parking", kind="law", name="주차장법",
        layer="L1_NATIONAL", source_type="law", jurisdiction_code="00", priority="P2",
        purpose="주차장 설비기준 위임(제6조), 부설주차장 설치·지정(제19조)",
        articles=["제6조", "제19조"],
        expect_headings={"제6조": "설비기준", "제19조": "부설주차장"},
    ),
    Target(
        key="kr.rule.parking", kind="law", name="주차장법 시행규칙",
        layer="L1_NATIONAL", source_type="rule", jurisdiction_code="00", priority="P2",
        parent_key="kr.law.parking",
        purpose="주차구획(제3조), 노외주차장 구조·설비(제6조), 부설주차장 구조·설비(제11조)",
        articles=["제3조", "제6조", "제11조"],
        expect_headings={"제3조": "주차구획", "제6조": "노외주차장", "제11조": "부설주차장"},
    ),
    # ---------------- P4 주차 설치기준·버전 ----------------
    Target(
        key="kr.decree.parking", kind="law", name="주차장법 시행령",
        layer="L1_NATIONAL", source_type="decree", jurisdiction_code="00", priority="P4",
        parent_key="kr.law.parking",
        purpose="부설주차장 설치기준(제6조, 별표 1)",
        articles=["제6조"],
        expect_headings={"제6조": "부설주차장"},
        annexes=["별표 1"],
        expect_annex_headings={"별표 1": "부설주차장"},
    ),
    Target(
        key="daegu.ord.parking", kind="ordin", name="대구광역시 주차장 설치 및 관리 조례",
        layer="L3_LOCAL", source_type="ordinance", jurisdiction_code="27", priority="P4",
        purpose="대구 부설주차장 설치기준(제15조, 별표 2), 약칭 정의(제1조의2)",
        articles=["제1조", "제1조의2", "제15조"],
        expect_headings={"제1조": "목적", "제1조의2": "실태조사", "제15조": "부설주차장"},
        annexes=["별표 2"],
        expect_annex_headings={"별표 2": "부설주차장"},
        resolve_aliases=True,
    ),
    Target(
        key="suseong.ord.parking", kind="ordin", name="대구광역시 수성구 주차장 설치 및 관리 조례",
        layer="L3_LOCAL", source_type="ordinance", jurisdiction_code="27260", priority="P4",
        purpose="수성구 주차장 조례 존재·버전 표제 확인 (조문 미수집)",
        header_only=True,
    ),
    Target(
        key="daegu.ord.building", kind="ordin", name="대구광역시 건축 조례",
        layer="L3_LOCAL", source_type="ordinance", jurisdiction_code="27", priority="P4",
        purpose="버전 표제만: 기준일 시행본 + 시행 예정본(PENDING_AMENDMENT 시연용)",
        header_only=True, include_pending=True,
    ),
    Target(
        key="kr.decree.building", kind="law", name="건축법 시행령",
        layer="L1_NATIONAL", source_type="decree", jurisdiction_code="00", priority="P4",
        parent_key=None,  # 건축법(법률) 번들이 없으므로 비워 둔다
        purpose="버전 표제 + 별표 1(용도별 건축물의 종류) 제공 형태 확인",
        header_only=True,
        annexes=["별표 1"],
        expect_annex_headings={"별표 1": "용도별 건축물의 종류"},
    ),
]


# ---------------------------------------------------------------------------


@dataclass
class VersionResult:
    target: Target
    bundle: SourceBundle | None
    bundle_file: str | None
    role: str  # 'in_force' | 'pending'
    body: ParsedBody | None
    raw_files: list[str]
    missing: list[Missing]
    warnings: list[str]
    alias_evidence: list[str] = field(default_factory=list)
    annex_notes: list[str] = field(default_factory=list)
    images: list[tuple[str, dict[str, str]]] = field(default_factory=list)  # (조 라벨, {'marker','src','alt'})


@dataclass
class TargetResult:
    target: Target
    versions: list[VersionResult]
    history: list[HistoryEntry]
    history_url: str | None
    resolver_url: str | None
    error: str | None = None
    notes: list[str] = field(default_factory=list)


def _write_raw(name: str, page: FetchedPage) -> str:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    path = RAW_DIR / name
    path.write_bytes(page.content)  # 받은 그대로
    return f"raw/{name}"


def _fetch_body(client: LawGoHtmlClient, t: Target, seq: str, ef_yd: str | None, ext_id: str | None) -> FetchedPage:
    if t.kind == "law":
        return client.law_body(seq, ef_yd)
    return client.ordin_body(seq, ext_id)


def _resolve_aliases(t: Target, body: ParsedBody, html: str, lsid_to_key: dict[str, str]) -> tuple[dict[str, str], list[str]]:
    aliases: dict[str, str] = {}
    evidence: list[str] = []
    links = extract_alias_links(html, aliases=("법", "영", "규칙", "시행규칙"))
    for alias, title, label in extract_alias_definitions(body.articles):
        if alias not in ("법", "영", "규칙", "시행규칙"):
            continue
        key = TITLE_TO_KEY.get(title)
        linked = links.get(alias, [])
        linked_keys = sorted({lsid_to_key[i] for i in linked if i in lsid_to_key})
        line = f"'{alias}' = 「{title}」 ({label}에서 정의); 본문 링크 법령ID {linked or '없음'}"
        if key is None:
            evidence.append(line + " → 이 저장소에 번들이 없어 aliases 에 넣지 않음")
            continue
        if linked_keys and linked_keys != [key]:
            evidence.append(line + f" → 경고: 링크 대상 {linked_keys} 와 제명 대상 {key} 가 다름, aliases 에 넣지 않음")
            continue
        aliases[alias] = key
        evidence.append(line + f" → aliases['{alias}'] = '{key}'")
    return aliases, evidence


def collect_target(client: LawGoHtmlClient, t: Target, as_of: str, lsid_to_key: dict[str, str]) -> TargetResult:
    res = TargetResult(target=t, versions=[], history=[], history_url=None, resolver_url=None)
    resolver_page, rq = client.resolve_by_name(t.kind, t.name)  # type: ignore[arg-type]
    res.resolver_url = resolver_page.url
    seq0 = rq.get("lsiSeq") if t.kind == "law" else rq.get("ordinSeq")
    if not seq0:
        raise RuntimeError(f"이름 주소에서 일련번호를 찾지 못함: {resolver_page.url}")
    ef0 = rq.get("efYd")
    page0 = _fetch_body(client, t, seq0, ef0, None)
    body0 = parse_body(page0.text, t.kind)  # type: ignore[arg-type]
    ext_id = body0.external_id
    if not ext_id:
        raise RuntimeError("본문에서 법령ID/자치법규ID 를 찾지 못함")
    if t.kind == "law":
        lsid_to_key[ext_id] = t.key

    hist_page = client.law_history(ext_id) if t.kind == "law" else client.ordin_history(ext_id)
    res.history_url = hist_page.url
    _write_raw(f"{t.key}__history.html", hist_page)
    res.history = parse_history(hist_page.text, t.kind)  # type: ignore[arg-type]
    in_force = select_in_force(res.history, as_of)
    if in_force is None:
        raise RuntimeError("연혁 목록에서 기준일 시행본을 고르지 못함")
    flagged = [e for e in res.history if e.site_current_flag]
    if t.kind == "law" and flagged and (flagged[0].seq, flagged[0].effective_from) != (in_force.seq, in_force.effective_from):
        res.notes.append(f"주의: 사이트 '현행' 표시({flagged[0].seq})와 시행일 기준 선택({in_force.seq})이 다름")
    if t.kind == "ordin" and flagged and flagged[0].seq != in_force.seq:
        res.notes.append(
            f"사이트가 '현행'으로 표시한 버전({flagged[0].seq}: {flagged[0].text})은 기준일 {as_of} 현재 시행 전 — "
            f"시행일 기준으로 {in_force.seq} 를 시행본으로 사용"
        )

    wanted: list[tuple[HistoryEntry, str]] = [(in_force, "in_force")]
    if t.include_pending:
        wanted += [(e, "pending") for e in pending_after(res.history, as_of)]

    # 법령은 (lsiSeq, 시행일) 이 한 버전, 자치법규는 ordinSeq 가 한 버전
    def page_key(seq: str, ef_yd8: str | None) -> tuple[str, str | None]:
        return (seq, ef_yd8) if t.kind == "law" else (seq, None)

    pages: dict[tuple[str, str | None], tuple[FetchedPage, ParsedBody]] = {page_key(seq0, ef0): (page0, body0)}
    for entry, role in wanted:
        ef = entry.effective_from.replace("-", "") if (t.kind == "law" and entry.effective_from) else None
        k = page_key(entry.seq, ef)
        if k in pages:
            page, body = pages[k]
        else:
            page = _fetch_body(client, t, entry.seq, ef, ext_id)
            body = parse_body(page.text, t.kind)  # type: ignore[arg-type]
            pages[k] = (page, body)
        res.versions.append(_make_version(client, t, role, entry, page, body, as_of, lsid_to_key))
    return res


def _make_version(
    client: LawGoHtmlClient,
    t: Target,
    role: str,
    entry: HistoryEntry,
    page: FetchedPage,
    body: ParsedBody,
    as_of: str,
    lsid_to_key: dict[str, str],
) -> VersionResult:
    h = body.header
    eff = h.effective_from or "unknown"
    endpoint = "lsInfoR" if t.kind == "law" else "ordinInfoR"
    raw_files = [_write_raw(f"{t.key}__{eff}__{endpoint}-{body.seq}.html", page)]
    warnings: list[str] = []
    if body.seq != entry.seq:
        warnings.append(f"요청한 일련번호 {entry.seq} 와 본문 일련번호 {body.seq} 가 다름")
    if h.effective_from != entry.effective_from:
        warnings.append(f"연혁 목록 시행일 {entry.effective_from} 와 본문 표제 시행일 {h.effective_from} 가 다름")

    annex_fetches: list[AnnexFetch] = []
    annex_missing: list[Missing] = []
    annex_notes: list[str] = []
    if role == "in_force":
        for label in t.annexes:
            ref = body.annex(label)
            if ref is None:
                annex_missing.append(Missing(label, "본문 별표 목록에서 찾지 못함"))
                continue
            want = t.expect_annex_headings.get(label)
            if want and (not ref.heading or want not in ref.heading):
                annex_missing.append(Missing(label, f"별표 제목이 예상과 다름 (예상 포함어 {want!r}, 실제 {ref.title_line!r})"))
                continue
            apage = client.law_annex(ref.byl_seq) if t.kind == "law" else client.ordin_annex(ref.byl_seq)
            araw = _write_raw(f"{t.key}__{eff}__annex-{ref.byl_seq}.html", apage)
            raw_files.append(araw)
            content = parse_annex_contents(apage.text)
            annex_fetches.append(AnnexFetch(ref=ref, content=content, raw_file=araw))
            annex_notes.append(
                f"{label}: 「{ref.title_line}」 bylSeq={ref.byl_seq}, 제공 형태 {content.availability}, "
                f"첨부 {', '.join(ref.file_links) or '없음'}"
            )

    aliases: dict[str, str] = {}
    alias_evidence: list[str] = []
    if t.resolve_aliases:
        aliases, alias_evidence = _resolve_aliases(t, body, page.text, lsid_to_key)

    doc = DocumentMeta(
        key=t.key,
        layer=t.layer,  # type: ignore[arg-type]
        source_type=t.source_type,  # type: ignore[arg-type]
        official_title=h.official_title or t.name,
        jurisdiction_code=t.jurisdiction_code,
        external_system="law.go.kr",
        external_id=body.external_id,
        short_title=h.short_title,
        parent_key=t.parent_key,
        homepage_url=homepage_url(t.kind, t.name),  # type: ignore[arg-type]
        aliases=aliases,
    )
    note = "선별 조문만 수집 (개발용 HTML 수집기)"
    labels = [] if (t.header_only or role == "pending") else t.articles
    images: list[tuple[str, dict[str, str]]] = []
    for label in labels:
        art = body.article(label)
        if art is not None:
            images.extend((label, im) for im in art.images)
    if images:
        img_labels = sorted({lb for lb, _ in images}, key=labels.index)
        note += (
            f" — {'·'.join(img_labels)}에 본문 표 이미지가 있음: text 의 [[IMAGE flSeq=…]] 줄이 이미지 자리이며 "
            "이미지 내용(표 수치)은 수집하지 않음"
        )
    if t.header_only:
        note += " — 이 번들은 버전 표제만 담음(조문 없음)"
    if role == "pending":
        note += f" — 시행 예정 버전: 기준일 {as_of} 현재 시행 전"
    ef_yd = h.effective_from.replace("-", "") if (t.kind == "law" and h.effective_from) else None
    bundle, missing, bwarn = build_bundle(
        doc,
        body,
        source_url=human_url(t.kind, body.seq or entry.seq, ef_yd),  # type: ignore[arg-type]
        fetched_at=page.fetched_at,
        raw_file=raw_files[0],
        article_labels=labels,
        annexes=annex_fetches,
        expect_headings=t.expect_headings,
        in_force_on=as_of if role == "in_force" else None,
        current_checked_on=as_of,
        note=note,
    )
    missing = annex_missing + missing
    fname = bundle_filename(t.key, h.effective_from)
    with (OUT_DIR / fname).open("w", encoding="utf-8", newline="\n") as f:
        json.dump(bundle.to_json(), f, ensure_ascii=False, indent=2)
        f.write("\n")
    return VersionResult(
        target=t,
        bundle=bundle,
        bundle_file=fname,
        role=role,
        body=body,
        raw_files=raw_files,
        missing=missing,
        warnings=warnings + bwarn,
        alias_evidence=alias_evidence,
        annex_notes=annex_notes,
        images=images,
    )


# ---------------------------------------------------------------------------
# 법정동코드 확인 (행정표준코드관리시스템)
# ---------------------------------------------------------------------------

REGION_CHECKS = [("2700000000", "대구광역시", "27"), ("2726000000", "대구광역시 수성구", "27260")]


@dataclass
class RegionCheck:
    code: str
    expected_name: str
    prefix: str
    found_name: str | None
    ok: bool
    raw_file: str | None
    url: str
    fetched_at: str | None


def verify_region_codes() -> list[RegionCheck]:
    out: list[RegionCheck] = []
    with LawGoHtmlClient(base_url="https://www.code.go.kr") as cc:
        for code, name, prefix in REGION_CHECKS:
            params = {
                "cPage": "1", "pageSize": "10", "disuseAt": "0", "codeseId": "00002", "searchOk": "0",
                "sidoCd": "*", "sggCd": "*", "umdCd": "*", "riCd": "*", "regionCd": code, "locataddNm": "",
            }
            try:
                page = cc.request("POST", "/stdcode/regCodeL.do", params)
            except Exception as e:  # noqa: BLE001
                out.append(RegionCheck(code, name, prefix, None, False, None, "https://www.code.go.kr/stdcode/regCodeL.do", None))
                print(f"[region] {code}: {e}", file=sys.stderr)
                continue
            raw = _write_raw(f"code.go.kr__regCodeL__{code}.html", page)
            soup = BeautifulSoup(page.text, "html.parser")
            found = None
            for td in soup.find_all("td", class_="table_left"):
                if td.get_text(strip=True) == code:
                    nx = td.find_next_sibling("td")
                    found = nx.get_text(strip=True) if nx else None
                    break
            out.append(RegionCheck(code, name, prefix, found, found == name, raw, page.url, page.fetched_at))
    return out


# ---------------------------------------------------------------------------
# MANIFEST
# ---------------------------------------------------------------------------


def _md(s: str | None) -> str:
    return (s or "—").replace("|", "\\|").replace("\n", " ")


def write_manifest(results: list[TargetResult], regions: list[RegionCheck], as_of: str, started: str) -> None:
    L: list[str] = []
    L.append("# 법규 원문 수집 기록 (law.go.kr 공개 HTML, 개발용)")
    L.append("")
    L.append(f"- 생성: `scripts/fetch_lawgo_sources.py` · 실행 시작 {started} · 검토 기준일 **{as_of}**")
    L.append(f"- 수집기: `app/legal/lawgo_html.py` (bs4 + 정규식, AI 미사용) · User-Agent `{USER_AGENT}` · 순차 요청, 요청 간 1.2초 이상")
    L.append("- 형식: `app-legal-source@1` (`app/legal/parsed.py`) · 원문 페이지는 받은 바이트 그대로 `raw/` 에 보관")
    L.append("- DRF Open API(`/DRF/lawService.do`)는 OC 인증값이 없어 사용하지 않음(OC=test → '사용자 정보 검증에 실패하였습니다.'). "
             "클라이언트·파서는 `app/legal/lawgo_drf.py` 에 있으나 실제 응답 표본이 없어 가이드 필드 목록으로 만든 표본으로만 시험함.")
    L.append("")
    L.append("## 텍스트 규칙")
    L.append("")
    L.append("- `text` 는 조 전체: 첫 줄이 화면 그대로의 `제N조(제목)` + ①항 본문이고, 이어서 항·호·목이 원문 순서대로 한 줄씩 (화면의 `<p>` 1개 = 1줄).")
    L.append("- 정규화는 다음만 적용: HTML 표시 규칙의 공백 처리(태그 사이 연속 공백 → 한 칸, 줄 앞뒤 공백 제거), `<br>`→줄바꿈, 빈 문단 제거, `\\r\\n`→`\\n`, NBSP→공백, 줄 끝 공백 제거, 빈 줄 3줄 이상 → 1줄.")
    L.append("- **개정 연혁 주석은 포함한다.** `<개정 2011. 4. 14., …>`, `<신설 …>`, `[전문개정 …]`, `(개정 2008. 12. 30 조례 제4006호)` 등 화면에 보이는 주석을 그대로 남겼다. "
             "규칙 추출 시 주석을 떼려면 파서에서 별도로 처리해야 한다.")
    L.append("- 링크·약칭(「」, ㆍ, ·, ①②, `1.`, `가.`)은 원문 문자 그대로. 화면 아이콘·숨은 요소(display:none)·체크박스는 제외.")
    L.append("- 조문 안에 표가 **이미지**로 실린 곳은 그 자리에 `[[IMAGE flSeq=…]]` 한 줄을 넣었다(원문에 없는 유일한 추가 줄). "
             "이미지 내용은 text 에 없다 — 아래 '누락(본문 이미지)' 참고. 구조 파서는 `^\\[\\[IMAGE ` 줄을 '내용 미수집' 표시로 다뤄야 한다.")
    L.append("- 별표가 HWP/PDF/이미지(문서뷰어 이미지 변환)로만 제공되면 전사하지 않고 아래 '누락'에 기록했다. law.go.kr 에 HTML 텍스트판이 없었다.")
    L.append("- `v_current_version=confirmed` 는 연혁 목록에서 '시행일 ≤ 기준일 중 가장 늦은 버전'이고 본문 표제 시행일도 기준일 이전일 때만. "
             "`v_version_header=confirmed` 는 표제에서 시행일·공포번호·공포일을 모두 읽었고 페이지 숨은 값(ancYd/ancNo/efYd)과 일치할 때만.")
    L.append("")
    L.append("## 번들 목록")
    L.append("")
    L.append("| 우선 | key | 제명 | 화면 표제 | 시행일 | 일련번호 | 기준일 시행본 | 조문/별표 수 | 파일 |")
    L.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in results:
        for v in r.versions:
            b = v.bundle
            if b is None:
                continue
            L.append(
                f"| {r.target.priority} | `{r.target.key}` | {_md(b.document.official_title)} | {_md(v.body.header.header_line if v.body else None)} "
                f"| {_md(b.version.effective_from)} | {_md(b.version.external_version_id)} "
                f"| {'예 (confirmed)' if b.version.v_current_version == 'confirmed' else ('아니오 — 시행 예정' if v.role == 'pending' else 'needs_review')} "
                f"| {len(b.articles)} | `{v.bundle_file}` |"
            )
        if r.error:
            L.append(f"| {r.target.priority} | `{r.target.key}` | {_md(r.target.name)} | 수집 실패 | — | — | — | 0 | — |")
    L.append("")
    L.append("## 문서별 상세")
    for r in results:
        t = r.target
        L.append("")
        L.append(f"### `{t.key}` — {t.name}")
        L.append("")
        L.append(f"- 목적: {t.purpose}")
        L.append(f"- 이름 주소: {r.resolver_url or '—'}")
        L.append(f"- 연혁 목록: {r.history_url or '—'} (raw: `raw/{t.key}__history.html`)")
        if r.error:
            L.append(f"- **수집 실패**: {r.error}")
            continue
        for n in r.notes:
            L.append(f"- {n}")
        pend = pending_after(r.history, as_of)
        if pend:
            L.append(f"- 연혁 목록상 기준일({as_of}) 뒤에 시행되는 버전:")
            for e in pend:
                L.append(f"  - `{e.seq}` {e.text}")
        for v in r.versions:
            b = v.bundle
            if b is None:
                continue
            L.append(f"- **{'시행본' if v.role == 'in_force' else '시행 예정본'}** `{v.bundle_file}`")
            L.append(f"  - 표제: {v.body.header.header_line if v.body else '—'}")
            L.append(f"  - source_url: {b.version.source_url}")
            L.append(f"  - raw: {', '.join(f'`{x}`' for x in v.raw_files)}")
            L.append(f"  - 검증: source_access={b.version.v_source_access}, version_header={b.version.v_version_header}, "
                     f"current_version={b.version.v_current_version}, text_match={b.version.v_text_match}")
            if b.articles:
                L.append(f"  - 수집: {', '.join(f'{a.label}({a.heading})' if a.heading else a.label for a in b.articles)}")
            if b.document.aliases or v.alias_evidence:
                L.append(f"  - aliases: `{json.dumps(b.document.aliases, ensure_ascii=False)}`")
                for ev in v.alias_evidence:
                    L.append(f"    - {ev}")
            for an in v.annex_notes:
                L.append(f"  - 별표 확인: {an}")
            for m in v.missing:
                L.append(f"  - **누락** {m.label}: {m.reason}")
            for label, im in v.images:
                L.append(
                    f"  - **누락(본문 이미지)** {label} `{im['marker']}` (https://www.law.go.kr{im['src']}): "
                    "표가 이미지로만 실려 있어 text 에 없음. 참고로 law.go.kr 이 붙인 대체텍스트(alt) 원문은 아래와 같다 — "
                    "이미지 원본과 대조하지 않았고 오기가 있을 수 있으므로(예: '3.3.미터') 규칙 근거로 쓰지 말 것."
                )
                L.append("")
                L.append("    ```text")
                for ln in im["alt"].replace("\r\n", "\n").split("\n"):
                    if ln.strip():
                        L.append("    " + ln.rstrip())
                L.append("    ```")
                L.append("")
            for w in v.warnings:
                L.append(f"  - 경고: {w}")
    L.append("")
    L.append("## 법정동코드 확인")
    L.append("")
    L.append("출처: 행정표준코드관리시스템(행정안전부) 법정동코드목록조회 `https://www.code.go.kr/stdcode/regCodeL.do` (폐지구분 '현존', 법정동코드 검색)")
    L.append("")
    L.append("| 법정동코드 | 조회된 법정동명 | 기대 | 접두 | 결과 | raw |")
    L.append("| --- | --- | --- | --- | --- | --- |")
    for c in regions:
        L.append(f"| {c.code} | {_md(c.found_name)} | {c.expected_name} | `{c.prefix}` | {'일치' if c.ok else '불일치/실패'} | `{c.raw_file or '—'}` |")
    L.append("")
    L.append("## 아직 없는 것")
    L.append("")
    L.append("- 모든 별표 본문(시행령 별표 4·5·6·7·9·17, 주차장법 시행령 별표 1, 대구 주차장 조례 별표 2, 건축법 시행령 별표 1): 첨부파일 전용 → 미전사. "
             "DRF Open API(`<별표내용>`) 또는 공식 HWP/PDF 를 별도 검수 절차로 처리해야 함.")
    L.append("- 주차장법 시행규칙 제3조(주차단위구획 너비·길이), 제6조(노외주차장 차로 너비), 제11조⑤1(소규모 부설주차장 차로 너비)의 **표 이미지 내용**: "
             "text 에는 `[[IMAGE flSeq=…]]` 자리 표시만 있다. 위 대체텍스트는 참고용이며 원본 이미지와 대조한 값이 아니다.")
    L.append("- 대구광역시 도시계획 조례는 용도지역별 건축제한을 별표가 아니라 조문(제31조~제50조의2)으로 둔다. 1·2·3종일반주거(제33~35조), 준주거(제36조), "
             "일반상업(제38조), 자연녹지(제46조)만 수집했다.")
    L.append("- 수성구 주차장 조례·대구 건축 조례·건축법 시행령: 버전 표제만(조문 없음).")
    L.append("- `v_text_match`: 다른 경로(DRF XML 등)와 대조하지 않았으므로 전부 `not_checked`.")
    L.append("- 부칙·조문별 시행일: 수집하지 않음. 시행 예정 개정이 있는 문서는 위 '기준일 뒤에 시행되는 버전' 참고.")
    L.append("")
    MANIFEST.write_text("\n".join(L), encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--as-of", default=dt.datetime.now(KST).date().isoformat(), help="검토 기준일 YYYY-MM-DD (기본: 오늘 KST)")
    ap.add_argument("--only", nargs="*", help="이 key 만 수집 (MANIFEST 는 이번 실행분으로 다시 씀)")
    ap.add_argument("--skip-region", action="store_true", help="법정동코드 확인 생략")
    args = ap.parse_args(argv)
    # 윈도우 콘솔(cp949 등)에서 출력 문자 때문에 멈추지 않도록
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="backslashreplace")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    started = now_kst_iso()
    targets = [t for t in TARGETS if not args.only or t.key in args.only]
    results: list[TargetResult] = []
    lsid_to_key: dict[str, str] = {}
    with LawGoHtmlClient() as client:
        for t in targets:
            print(f"[{t.key}] {t.name}", flush=True)
            try:
                r = collect_target(client, t, args.as_of, lsid_to_key)
            except Exception as e:  # noqa: BLE001
                traceback.print_exc()
                r = TargetResult(target=t, versions=[], history=[], history_url=None, resolver_url=None, error=repr(e))
            for v in r.versions:
                if v.bundle:
                    print(f"   -> {v.bundle_file}: {len(v.bundle.articles)} items, missing {len(v.missing)}", flush=True)
            results.append(r)
    regions = [] if args.skip_region else verify_region_codes()
    write_manifest(results, regions, args.as_of, started)
    print(f"MANIFEST: {MANIFEST}")
    return 1 if any(r.error for r in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
