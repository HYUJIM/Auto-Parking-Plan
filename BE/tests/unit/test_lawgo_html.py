"""app.legal.lawgo_html 파서 시험 — 저장해 둔 law.go.kr 원문 페이지(tests/fixtures/lawgo)만 사용, 네트워크 없음."""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

import httpx
import pytest

from app.legal import lawgo_html as L
from app.legal.parsed import SourceBundle

FIX = Path(__file__).resolve().parents[1] / "fixtures" / "lawgo"
DATA = Path(__file__).resolve().parents[2] / "data" / "legal_sources"


def read(name: str) -> str:
    return (FIX / name).read_bytes().decode("utf-8")


@pytest.fixture(scope="module")
def law_body() -> L.ParsedBody:
    return L.parse_body(read("law_body_nkpa_excerpt.html"), "law")


@pytest.fixture(scope="module")
def decree_body() -> L.ParsedBody:
    return L.parse_body(read("law_body_decree_excerpt.html"), "law")


@pytest.fixture(scope="module")
def ordin_body() -> L.ParsedBody:
    return L.parse_body(read("ordin_body_urban_planning_excerpt.html"), "ordin")


# --- 기본 도구 -----------------------------------------------------------------


def test_normalize_text_only_allowed_changes() -> None:
    raw = "가 나  \r\n다\t \n\n\n\n\n라\n\n마"
    assert L.normalize_text(raw) == "가 나\n다\n\n라\n\n마"


@pytest.mark.parametrize(
    ("label", "path"),
    [("제84조", "art84"), ("제84조의2", "art84-2"), ("별표 5", "annex5"), ("별표 5의2", "annex5-2")],
)
def test_label_to_path(label: str, path: str) -> None:
    assert L.label_to_path(label) == path


def test_parse_header_line_law_and_ordinance() -> None:
    h = L.parse_header_line("[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]")
    assert h == {
        "header_line": "[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]",
        "effective_from": "2026-07-01",
        "promulgation_number": "법률 제21447호",
        "promulgation_date": "2026-03-05",
        "revision_kind": "타법개정",
    }
    h = L.parse_header_line("\n [시행 2026. 11. 12.] [대구광역시조례 제6551호, 2026. 10. 30., 일부개정] ")
    assert h["effective_from"] == "2026-11-12"
    assert h["promulgation_number"] == "대구광역시조례 제6551호"
    assert h["promulgation_date"] == "2026-10-30"


# --- 법령 본문 -----------------------------------------------------------------


def test_law_header_and_hidden_values(law_body: L.ParsedBody) -> None:
    h = law_body.header
    assert h.official_title == "국토의 계획 및 이용에 관한 법률"
    assert h.short_title == "국토계획법"
    assert (h.effective_from, h.promulgation_number, h.promulgation_date) == ("2026-07-01", "법률 제21447호", "2026-03-05")
    assert law_body.seq == "284013" and law_body.external_id == "009294"
    assert L.header_consistency(h, law_body.hidden) == []


def test_law_article_text_verbatim_structure(law_body: L.ParsedBody) -> None:
    art = law_body.article("제77조")
    assert art is not None
    assert (art.label, art.path, art.heading, art.anchor) == ("제77조", "art77", "용도지역의 건폐율", "J77:0")
    lines = art.text.split("\n")
    # 첫 줄 = 조 제목 + ①항 (화면 그대로), 개정 주석 포함
    assert lines[0].startswith("제77조(용도지역의 건폐율) ① 제36조에 따라 지정된 용도지역에서 건폐율의 최대한도는")
    assert lines[0].endswith("<개정 2011. 4. 14., 2013. 7. 16., 2015. 8. 11.>")
    assert lines[1:6] == ["1. 도시지역", "가. 주거지역: 70퍼센트 이하", "나. 상업지역: 90퍼센트 이하",
                          "다. 공업지역: 70퍼센트 이하", "라. 녹지지역: 20퍼센트 이하"]
    assert "⑤ 삭제 <2021. 1. 12.>" in lines
    assert lines[-1] == "[전문개정 2009. 2. 6.]"
    # HTML 공백 정리 결과: 줄 앞뒤 공백·연속 공백·숨은 요소 없음
    assert all(ln == ln.strip() and "  " not in ln for ln in lines)
    assert "javascript" not in art.text and "체크박스" not in art.text
    assert art.warnings == [] and art.images == []


def test_decree_rates_for_second_class_general_residential(decree_body: L.ParsedBody) -> None:
    a84, a85 = decree_body.article("제84조"), decree_body.article("제85조")
    assert a84 is not None and a85 is not None
    assert a84.heading == "용도지역안에서의 건폐율"
    assert a85.heading == "용도지역 안에서의 용적률"
    assert "4. 제2종일반주거지역 : 60퍼센트 이하" in a84.text.split("\n")
    assert "4. 제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하" in a85.text.split("\n")
    # 원문에 공백이 없는 곳은 없는 그대로 ('①법')
    assert a84.text.startswith("제84조(용도지역안에서의 건폐율) ①법 제77조제1항 및 제2항에 따른 건폐율은")


def test_decree_article71_points_to_annexes(decree_body: L.ParsedBody) -> None:
    a71 = decree_body.article("제71조")
    assert a71 is not None
    lines = a71.text.split("\n")
    assert "3. 제1종일반주거지역안에서 건축할 수 있는 건축물 : 별표 4에 규정된 건축물" in lines
    assert "4. 제2종일반주거지역안에서 건축할 수 있는 건축물 : 별표 5에 규정된 건축물" in lines
    assert "5. 제3종일반주거지역안에서 건축할 수 있는 건축물 : 별표 6에 규정된 건축물" in lines
    ref = decree_body.annex("별표 5")
    assert ref is not None
    assert ref.byl_seq == "18442157" and ref.path == "annex5"
    assert ref.title_line == "[별표 5] 제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)"
    assert ref.heading == "제2종일반주거지역안에서 건축할 수 있는 건축물(제71조제1항제4호관련)"
    assert any("flSeq=168871301" in x for x in ref.file_links)


def test_article_images_become_marker_lines_without_alt_text() -> None:
    body = L.parse_body(read("law_body_rule_parking_excerpt.html"), "law")
    art = body.article("제3조")
    assert art is not None and art.heading == "주차장의 주차구획"
    lines = art.text.split("\n")
    i = lines.index("1. 평행주차형식의 경우")
    assert lines[i + 1] == "[[IMAGE flSeq=24335428]]"
    assert lines[i + 2] == "2. 평행주차형식 외의 경우"
    assert lines[i + 3] == "[[IMAGE flSeq=33758907]]"
    assert "경형 1.7미터 이상" not in art.text and "구분 너비 길이" not in art.text  # 대체텍스트는 text 에 넣지 않는다
    assert [im["marker"] for im in art.images] == ["[[IMAGE flSeq=24335428]]", "[[IMAGE flSeq=33758907]]"]
    assert art.images[0]["alt"].startswith("구분 너비 길이")
    assert all(L.IMAGE_MARKER_RE.match(im["marker"]) for im in art.images)


# --- 자치법규 본문 -------------------------------------------------------------


def test_ordinance_header_and_articles(ordin_body: L.ParsedBody) -> None:
    h = ordin_body.header
    assert h.official_title == "대구광역시 도시계획 조례"
    assert (h.effective_from, h.promulgation_number, h.promulgation_date, h.revision_kind) == (
        "2026-05-11", "대구광역시조례 제6487호", "2026-05-11", "일부개정")
    assert ordin_body.seq == "2128915" and ordin_body.external_id == "2152107"
    assert L.header_consistency(h, ordin_body.hidden) == []
    a75, a80 = ordin_body.article("제75조"), ordin_body.article("제80조")
    assert a75 is not None and a80 is not None
    assert a75.text.split("\n")[0] == "제75조(용도지역 안에서의 건폐율) 영 제84조제1항에 따라 용도지역 안에서의 건폐율은 다음 각 호에서 정한 비율로 한다."
    assert "4. 제2종일반주거지역 : 60퍼센트 이하" in a75.text.split("\n")
    assert "19. 계획관리지역 : 40퍼센트 이하 <개정 2014.5.20,, 2017.4.10.>" in a75.text.split("\n")  # 원문 오기 그대로
    assert "4. 제2종일반주거지역 : 220퍼센트 이하" in a80.text.split("\n")


def test_ordinance_alias_definitions_and_links(ordin_body: L.ParsedBody) -> None:
    defs = L.extract_alias_definitions(ordin_body.articles)
    assert ("법", "국토의 계획 및 이용에 관한 법률", "제2조") in defs
    assert ("영", "국토의 계획 및 이용에 관한 법률 시행령", "제3조") in defs
    assert ("규칙", "국토의 계획 및 이용에 관한 법률 시행규칙", "제30조") in defs
    links = L.extract_alias_links(read("ordin_body_urban_planning_excerpt.html"))
    assert links["법"] == ["009294"] and links["영"] == ["009419"]


def test_ordinance_annex_title_parsing(ordin_body: L.ParsedBody) -> None:
    ref = ordin_body.annex("별표 2")
    assert ref is not None
    assert ref.title_line == "별표_2_상업지역 안에서 주거복합건축물의 용도 비율 및 용적률"
    assert ref.heading == "상업지역 안에서 주거복합건축물의 용도 비율 및 용적률"
    assert ref.byl_seq == "22307003"


# --- 별표 내용 / 연혁 / 이름 주소 ---------------------------------------------


def test_annex_contents_attachment_only() -> None:
    c = L.parse_annex_contents(read("law_annex_contents_18442157.html"))
    assert not c.has_text and c.text is None and c.table_json is None
    assert c.file_seqs["hanFlSeq"] == "168871299" and c.file_seqs["pdfFlSeq"] == "168871301"
    assert c.availability.startswith("attachment_only:") and "HWP" in c.availability and "PDF" in c.availability
    o = L.parse_annex_contents(read("ordin_annex_contents_21279951.html"))
    assert not o.has_text and o.availability == "attachment_only:HWP/문서뷰어(이미지 변환)"


def test_annex_contents_html_table_path() -> None:
    # 합성 표 (법령 문장 아님): HTML 표로 제공되는 별표가 있을 때의 처리만 확인
    html = "<html><body><table><tr><th>A</th><th>B</th></tr><tr><td>x <br/>y</td><td> z </td></tr></table></body></html>"
    c = L.parse_annex_contents(html)
    assert c.has_text and c.table_json == [["A", "B"], ["x\ny", "z"]]


def test_ordinance_history_in_force_and_pending() -> None:
    hist = L.parse_history(read("ordin_history_building.html"), "ordin")
    assert hist[0].seq == "2181437" and hist[0].site_current_flag is True
    assert hist[0].effective_from == "2026-11-12" and hist[0].promulgation_number == "조례 제6551호"
    cur = L.select_in_force(hist, "2026-11-06")
    assert cur is not None and (cur.seq, cur.effective_from) == ("2173139", "2026-09-30")
    assert [e.seq for e in L.pending_after(hist, "2026-11-06")] == ["2181437"]
    later = L.select_in_force(hist, "2026-11-12")
    assert later is not None and later.seq == "2181437"


def test_law_history_same_seq_with_staged_effective_dates() -> None:
    hist = L.parse_history(read("law_history_decree_building_excerpt.html"), "law")
    rows = [(e.seq, e.effective_from) for e in hist if e.seq == "290833"]
    assert rows == [("290833", "2027-04-07"), ("290833", "2026-10-06")]
    cur = L.select_in_force(hist, "2026-11-06")
    assert cur is not None and (cur.seq, cur.effective_from, cur.site_current_flag) == ("290833", "2026-10-06", True)
    assert ("290833", "2027-04-07") in [(e.seq, e.effective_from) for e in L.pending_after(hist, "2026-11-06")]


def test_name_resolver() -> None:
    q = L.parse_name_resolver(read("name_resolver_decree_nkpa.html"))
    assert q["lsiSeq"] == "288857" and q["efYd"] == "20260918"


# --- 번들 ---------------------------------------------------------------------


def _decree_doc() -> L.DocumentMeta:
    return L.DocumentMeta(
        key="kr.decree.nkpa", layer="L1_NATIONAL", source_type="decree",
        official_title="국토의 계획 및 이용에 관한 법률 시행령", jurisdiction_code="00",
        external_system="law.go.kr", external_id="009419", parent_key="kr.law.nkpa",
    )


def test_build_bundle_selection_missing_and_verification(decree_body: L.ParsedBody) -> None:
    annex = L.AnnexFetch(
        ref=decree_body.annex("별표 5"),  # type: ignore[arg-type]
        content=L.parse_annex_contents(read("law_annex_contents_18442157.html")),
        raw_file="raw/x.html",
    )
    bundle, missing, warnings = L.build_bundle(
        _decree_doc(), decree_body,
        source_url=L.human_url("law", "288857", "20260918"), fetched_at="2026-11-06T12:00:00+09:00",
        raw_file="raw/kr.decree.nkpa.html",
        article_labels=["제84조", "제85조", "제99조", "제71조"],
        annexes=[annex],
        expect_headings={"제84조": "건폐율", "제85조": "용적률", "제71조": "용적률"},
        in_force_on="2026-11-06",
    )
    assert [a.label for a in bundle.articles] == ["제84조", "제85조"]
    reasons = {m.label: m.reason for m in missing}
    assert "찾지 못함" in reasons["제99조"]
    assert "예상과 다름" in reasons["제71조"]
    assert "첨부파일로만" in reasons["별표 5"]
    v = bundle.version
    assert (v.promulgation_number, v.promulgation_date, v.effective_from) == ("대통령령 제36587호", "2026-08-18", "2026-09-18")
    assert (v.v_source_access, v.v_version_header, v.v_text_match, v.v_current_version) == (
        "confirmed", "confirmed", "not_checked", "confirmed")
    assert v.current_checked_on == "2026-11-06" and v.raw_format == "html" and v.external_version_id == "288857"
    assert v.note == L.BUNDLE_NOTE and bundle.is_example is False
    assert v.source_url == "https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857&chrClsCd=010202&urlMode=lsInfoP&efYd=20260918&ancYnChk=0"
    # 직렬화 왕복
    d = json.loads(json.dumps(bundle.to_json(), ensure_ascii=False))
    assert SourceBundle.from_json(d) == bundle
    assert warnings == []


def test_build_bundle_not_in_force_is_needs_review(decree_body: L.ParsedBody) -> None:
    bundle, _, _ = L.build_bundle(
        _decree_doc(), decree_body, source_url="u", fetched_at="t", raw_file=None, in_force_on=None,
    )
    assert bundle.version.v_current_version == "needs_review" and bundle.version.current_checked_on is None
    assert bundle.articles == []


# --- HTTP 클라이언트 (모의 전송) -------------------------------------------------


def test_client_requests_are_polite_and_identified() -> None:
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200, content=b"<html></html>")

    with L.LawGoHtmlClient(min_interval=0.2, transport=httpx.MockTransport(handler)) as c:
        t0 = time.monotonic()
        c.law_body("288857", "20260918")
        c.ordin_body("2128915", "2152107")
        c.law_history("009419")
        elapsed = time.monotonic() - t0
    assert elapsed >= 0.4  # 요청 사이 간격
    ua = seen[0].headers["user-agent"]
    assert ua == "APP-legal-collector/0.1 (dev)" and "@" not in ua
    assert seen[0].method == "POST" and seen[0].url.path == "/LSW/lsInfoR.do"
    assert b"lsiSeq=288857" in seen[0].content and b"efYd=20260918" in seen[0].content
    assert seen[1].method == "GET" and seen[1].url.params["ordinSeq"] == "2128915"
    assert seen[2].url.params["lsId"] == "009419"


def test_client_refuses_fast_interval_for_real_site() -> None:
    with pytest.raises(ValueError):
        L.LawGoHtmlClient(min_interval=0.5)


# --- 저장된 번들 ↔ 원문 페이지 재파싱 일치 -----------------------------------------

BUNDLES = sorted(DATA.glob("*.json")) if DATA.exists() else []
ALLOWED_KEYS = {
    "kr.law.nkpa", "kr.decree.nkpa", "kr.law.parking", "kr.decree.parking", "kr.rule.parking", "kr.decree.building",
    "daegu.ord.urban_planning", "daegu.ord.parking", "daegu.ord.building", "suseong.ord.parking",
}


@pytest.mark.skipif(not BUNDLES, reason="data/legal_sources 번들 없음")
@pytest.mark.parametrize("path", BUNDLES, ids=[p.name for p in BUNDLES])
def test_saved_bundle_traces_back_to_raw_page(path: Path) -> None:
    d = json.loads(path.read_text(encoding="utf-8"))
    b = SourceBundle.from_json(d)
    assert b.document.key in ALLOWED_KEYS and b.is_example is False
    assert path.name == L.bundle_filename(b.document.key, b.version.effective_from)
    assert b.version.raw_file and (DATA / b.version.raw_file).exists()
    kind = "ordin" if ".ord." in b.document.key else "law"
    body = L.parse_body((DATA / b.version.raw_file).read_bytes().decode("utf-8"), kind)  # type: ignore[arg-type]
    assert body.header.effective_from == b.version.effective_from
    assert body.header.promulgation_number == b.version.promulgation_number
    assert body.seq == b.version.external_version_id
    for art in b.articles:
        if art.kind == "article":
            again = body.article(art.label)
            assert again is not None and again.text == art.text and again.heading == art.heading
    for alias, key in b.document.aliases.items():
        assert key in ALLOWED_KEYS, alias
    if b.document.parent_key:
        assert b.document.parent_key in ALLOWED_KEYS
    assert not re.search(r"\n{4,}", "\n".join(a.text for a in b.articles))
