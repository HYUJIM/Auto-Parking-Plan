"""app.legal.lawgo_drf 시험 — 네트워크 없음.

drf_error_auth.xml 은 실제 DRF 응답(OC=test, 2026-11-06)이다.
drf_*_constructed.xml 은 가이드 필드 목록·활용방법 그림을 근거로 만든 표본이다(실제 응답 아님, 파일 머리 참고).
"""
from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from app.legal import lawgo_html as H
from app.legal.lawgo_drf import (
    DrfAuthError,
    DrfDocHint,
    DrfError,
    LawGoDrfClient,
    parse_law_xml,
    parse_ordin_xml,
    parse_search_xml,
    parse_xml,
)

FIX = Path(__file__).resolve().parents[1] / "fixtures" / "lawgo"


def raw(name: str) -> bytes:
    return (FIX / name).read_bytes()


def test_real_auth_failure_response_raises() -> None:
    with pytest.raises(DrfAuthError, match="사용자 정보 검증에 실패하였습니다"):
        parse_xml(raw("drf_error_auth.xml"))


def test_html_instead_of_xml_raises() -> None:
    with pytest.raises(DrfError):
        parse_xml(b"<html><body>error</body>")


def test_parse_law_xml_header_and_articles() -> None:
    b = parse_law_xml(raw("drf_law_constructed.xml"), mst="284013",
                      meta=DrfDocHint(key="kr.law.nkpa", layer="L1_NATIONAL", jurisdiction_code="00"))
    assert b.document.key == "kr.law.nkpa" and b.document.external_id == "009294"
    assert b.document.official_title == "국토의 계획 및 이용에 관한 법률" and b.document.short_title == "국토계획법"
    assert b.document.source_type == "law"
    v = b.version
    assert (v.promulgation_number, v.promulgation_date, v.effective_from) == ("법률 제21447호", "2026-03-05", "2026-07-01")
    assert v.external_version_id == "284013" and v.raw_format == "xml"
    assert v.v_version_header == "confirmed" and v.v_current_version == "needs_review"
    assert v.source_url == "https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=284013&efYd=20260701"
    # 전문(장 제목)·서식은 건너뛴다
    assert [(a.kind, a.label, a.path) for a in b.articles] == [("article", "제77조", "art77"), ("annex", "별표 1의2", "annex1-2")]


def test_law_xml_text_matches_html_rendering() -> None:
    """항·호·목으로 나뉜 XML 을 이어 붙인 결과가 HTML 수집기의 text 와 같은 모양인지 (첫 항은 제목 줄에 붙음)."""
    b = parse_law_xml(raw("drf_law_constructed.xml"))
    art = b.articles[0]
    html = H.parse_body((FIX / "law_body_nkpa_excerpt.html").read_bytes().decode("utf-8"), "law").article("제77조")
    assert html is not None
    html_lines = html.text.split("\n")
    assert html_lines[-1] == "[전문개정 2009. 2. 6.]"  # 표본에서 뺀 줄 (DRF 에서의 위치 미확인)
    assert art.text == "\n".join(html_lines[:-1])
    assert art.heading == "용도지역의 건폐율"
    assert art.effective_from is None  # 조문시행일자 == 문서 시행일자


def test_law_xml_annex_cdata_blocks_become_lines() -> None:
    b = parse_law_xml(raw("drf_law_constructed.xml"))
    annex = b.articles[1]
    assert annex.text == "[구조 시험용 1줄]\n[구조 시험용 2줄]"  # 빈 CDATA 구획은 버림
    assert annex.effective_from == "2027-01-01"


def test_parse_ordin_xml_matches_html_header_and_text() -> None:
    b = parse_ordin_xml(raw("drf_ordin_constructed.xml"),
                        meta=DrfDocHint(key="daegu.ord.urban_planning", jurisdiction_code="27"))
    html = H.parse_body((FIX / "ordin_body_urban_planning_excerpt.html").read_bytes().decode("utf-8"), "ordin")
    assert b.document.external_id == "2152107" and b.version.external_version_id == "2128915"
    assert b.document.layer == "L3_LOCAL" and b.document.jurisdiction_code == "27"
    # XML 필드로 구성한 공포번호가 HTML 표제 표기와 같아야 한다
    assert b.version.promulgation_number == html.header.promulgation_number == "대구광역시조례 제6487호"
    assert b.version.effective_from == "2026-05-11" and b.version.promulgation_date == "2026-05-11"
    assert [a.label for a in b.articles] == ["제75조", "제80조"]  # 조문여부 N 은 건너뜀
    for a in b.articles:
        h = html.article(a.label)
        assert h is not None and a.text == h.text and a.heading == h.heading
    assert b.version.source_url == "https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=2128915"


def test_parse_search_xml() -> None:
    hits = parse_search_xml(raw("drf_search_constructed.xml"))
    assert len(hits) == 1
    assert hits[0]["id"] == "1" and hits[0]["법령ID"] == "009294" and hits[0]["법령일련번호"] == "284013"
    assert hits[0]["법령명한글"] == "국토의 계획 및 이용에 관한 법률"


def test_client_builds_official_query_and_saves_raw(tmp_path: Path) -> None:
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        if req.url.path.endswith("lawSearch.do"):
            return httpx.Response(200, content=raw("drf_search_constructed.xml"))
        return httpx.Response(200, content=raw("drf_law_constructed.xml"))

    with LawGoDrfClient("my-oc", transport=httpx.MockTransport(handler)) as c:
        hits = c.search("law", "국토의 계획 및 이용에 관한 법률")
        b = c.get_document("eflaw", mst="284013", ef_yd="20260701", meta=DrfDocHint(key="kr.law.nkpa"),
                           raw_dir=tmp_path / "raw")
        with pytest.raises(ValueError):
            c.get_document("eflaw", mst="284013")  # eflaw + MST 에는 efYd 필요
        with pytest.raises(ValueError):
            c.get_document("law")
    assert hits[0]["법령ID"] == "009294"
    q0, q1 = seen[0].url.params, seen[1].url.params
    assert str(seen[0].url).startswith("http://www.law.go.kr/DRF/lawSearch.do")
    assert (q0["OC"], q0["target"], q0["type"], q0["query"]) == ("my-oc", "law", "XML", "국토의 계획 및 이용에 관한 법률")
    assert seen[1].url.path == "/DRF/lawService.do"
    assert (q1["OC"], q1["target"], q1["MST"], q1["efYd"], q1["type"]) == ("my-oc", "eflaw", "284013", "20260701", "XML")
    assert "ID" not in q1
    assert seen[1].headers["user-agent"] == "APP-legal-collector/0.1 (dev)"
    assert b.document.key == "kr.law.nkpa" and b.version.raw_file is not None
    saved = tmp_path / b.version.raw_file
    assert saved.read_bytes() == raw("drf_law_constructed.xml")


def test_client_auth_failure_propagates() -> None:
    transport = httpx.MockTransport(lambda req: httpx.Response(200, content=raw("drf_error_auth.xml")))
    with LawGoDrfClient("test", transport=transport) as c:
        with pytest.raises(DrfAuthError):
            c.get_document("law", id="009294")
        with pytest.raises(DrfAuthError):
            c.search("ordin", "대구")


def test_client_requires_oc() -> None:
    with pytest.raises(ValueError):
        LawGoDrfClient("")
