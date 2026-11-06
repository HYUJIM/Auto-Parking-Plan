"""
국가법령정보 공동활용 Open API(DRF) 클라이언트 + XML 파서.

    client = LawGoDrfClient(oc="<신청한 OC>")
    hits = client.search("law", "국토의 계획 및 이용에 관한 법률")
    bundle = client.get_document("eflaw", mst="284013", ef_yd="20260701",
                                 meta=DrfDocHint(key="kr.law.nkpa", layer="L1_NATIONAL"))

요청 규격 (open.law.go.kr 'OPEN API 활용가이드', 2026-11-06 열람)
- 목록: GET {base}/lawSearch.do?OC=&target=law|eflaw|ordin&type=XML&query=…
- 본문: GET {base}/lawService.do?OC=&target=law&type=XML&ID=|MST=
        target=eflaw 는 MST 와 efYd(필수, ID 로 조회할 때는 생략)
        target=ordin 은 ID=|MST=
- OC 는 등록된 서버 IP/도메인에서만 통과한다. 실패하면
  <Response><result>사용자 정보 검증에 실패하였습니다.</result><msg>…</msg></Response> (실제 응답, tests/fixtures/lawgo 참고)

XML 요소 이름의 근거
- 법령(law/eflaw): 가이드의 '출력 결과 필드' 목록과 'OPEN API 활용방법' 화면의 본문조회 결과 그림
  (<법령 법령키>/<기본정보>/<조문>/<조문단위 조문키>/<조문내용> …, <별표>/<별표단위 별표키>/<별표내용> …).
  항·호·목은 필드 목록(항번호·항내용·호번호·호내용·목번호·목내용)에 따라 <항>/<호>/<목> 중첩으로 가정한다.
- 자치법규(ordin): 필드 목록만 있고 중첩 구조 그림이 없다. 그래서 요소 위치에 기대지 않고
  '조문번호와 조내용을 자식으로 가진 요소 = 조 하나'로 찾는다.
- 실제 응답 표본은 OC 가 없어 받지 못했다. 테스트 표본(tests/fixtures/lawgo/drf_*_constructed.xml)은
  위 근거로 만든 것이며 파일 머리에 그렇게 적혀 있다. 실제 OC 를 받으면 표본을 실제 응답으로 바꿀 것.

text 규칙은 lawgo_html 과 같게 맞춘다
- 조 하나 = 조문내용 + 항내용 + 호내용 + 목내용을 문서 순서대로 줄 단위로 잇는다.
- 조문내용이 '제N조(제목)' 한 줄뿐이면 첫 항 내용을 같은 줄에 한 칸 띄워 붙인다
  (law.go.kr 화면 표시 '제77조(용도지역의 건폐율) ① …' 와 같아지도록).
- 연속한 CDATA 구획(]]><![CDATA[)은 줄바꿈으로 본다. 각 줄 앞뒤 공백 제거, 빈 줄 제거.
"""
from __future__ import annotations

import datetime as dt
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlencode

import httpx

import app.external._http  # noqa: F401  (httpx 요청 로그에서 OC 값을 가리는 필터 설치)
from app.legal.parsed import ArticleText, DocumentMeta, Layer, SourceBundle, SourceType, VerifyState, VersionMeta

DRF_BASE_URL = "https://www.law.go.kr/DRF"  # http 도 되지만 OC 가 평문으로 가므로 https
USER_AGENT = "APP-legal-collector/0.1 (dev)"
KST = dt.timezone(dt.timedelta(hours=9))
DrfTarget = Literal["law", "eflaw", "ordin"]

# 가이드의 자치법규종류 코드 (ordinInfoGuide)
ORDIN_KIND_NAMES = {
    "C0001": "조례",
    "C0002": "규칙",
    "C0003": "훈령",
    "C0004": "예규",
    "C0006": "기타",
    "C0010": "고시",
    "C0011": "의회규칙",
}


class DrfError(RuntimeError):
    pass


class DrfAuthError(DrfError):
    """OC 검증 실패 (미등록 IP/도메인 등)."""


@dataclass
class DrfDocHint:
    """XML 에 없는 저장소 쪽 메타데이터."""

    key: str | None = None
    layer: Layer | None = None
    source_type: SourceType | None = None
    jurisdiction_code: str | None = None
    parent_key: str | None = None
    homepage_url: str | None = None
    aliases: dict[str, str] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# XML 유틸
# ---------------------------------------------------------------------------

_CDATA_JOIN_RE = re.compile(rb"\]\]>\s*<!\[CDATA\[")


def _prepare_xml(raw: bytes) -> bytes:
    # 연속한 CDATA 구획 경계 → 줄바꿈 (ElementTree 는 구획 경계를 잃어버리므로 미리 표시)
    return _CDATA_JOIN_RE.sub(b"\n", raw)


def _lines(s: str | None) -> list[str]:
    if not s:
        return []
    s = s.replace("\r\n", "\n").replace("\r", "\n").replace(" ", " ")
    return [ln.strip() for ln in s.split("\n") if ln.strip()]


def _text(el: ET.Element | None) -> str | None:
    if el is None:
        return None
    t = "".join(el.itertext()).strip()
    return t or None


def _child_text(el: ET.Element, tag: str) -> str | None:
    return _text(el.find(tag))


def _first(root: ET.Element, tag: str) -> ET.Element | None:
    return next(root.iter(tag), None)


def _first_text(root: ET.Element, tag: str) -> str | None:
    return _text(_first(root, tag))


def _ymd(s: str | None) -> str | None:
    if not s:
        return None
    d = re.sub(r"\D", "", s)
    if len(d) != 8 or d == "99991231":
        return None
    return f"{d[:4]}-{d[4:6]}-{d[6:]}"


def _int_or_none(s: str | None) -> int | None:
    if s is None:
        return None
    d = re.sub(r"\D", "", s)
    return int(d) if d else None


def check_error(root: ET.Element) -> None:
    """DRF 오류 응답이면 예외."""
    if root.tag == "Response":
        result = _child_text(root, "result") or ""
        msg = _child_text(root, "msg") or ""
        if "검증" in result or "인증" in result:
            raise DrfAuthError(f"{result} {msg}".strip())
        raise DrfError(f"{result} {msg}".strip() or "DRF error response")


def parse_xml(raw: bytes) -> ET.Element:
    try:
        root = ET.fromstring(_prepare_xml(raw))
    except ET.ParseError as e:
        head = raw[:200].decode("utf-8", "replace")
        raise DrfError(f"XML 이 아님 (HTML 오류 화면일 수 있음): {head!r}") from e
    check_error(root)
    return root


def _label(jo: int, branch: int | None, prefix: str = "제", unit: str = "조") -> str:
    return f"{prefix}{jo}{unit}" + (f"의{branch}" if branch else "")


def _path(kind: str, n: int, branch: int | None) -> str:
    return f"{kind}{n}" + (f"-{branch}" if branch else "")


_TITLE_ONLY_RE = re.compile(r"^제\d+조(?:의\d+)?(?:\s*\(.*\))?$")


# ---------------------------------------------------------------------------
# 법령 (target=law / eflaw)
# ---------------------------------------------------------------------------


def _law_article_text(unit: ET.Element) -> str:
    """조문단위 → 조 전체 텍스트 (조문내용 + 항/호/목)."""
    head = _lines(_child_text(unit, "조문내용"))
    body: list[str] = []

    def walk_ho(ho: ET.Element) -> None:
        body.extend(_lines(_child_text(ho, "호내용")))
        for mok in ho.findall("목"):
            body.extend(_lines(_child_text(mok, "목내용")))

    for hang in unit.findall("항"):
        body.extend(_lines(_child_text(hang, "항내용")))
        for ho in hang.findall("호"):
            walk_ho(ho)
    # 항 없이 호가 바로 오는 경우도 받아 둔다
    for ho in unit.findall("호"):
        walk_ho(ho)

    if len(head) == 1 and _TITLE_ONLY_RE.match(head[0]) and body:
        lines = [f"{head[0]} {body[0]}"] + body[1:]
    else:
        lines = head + body
    return "\n".join(lines)


def parse_law_xml(
    raw: bytes,
    *,
    source_url: str | None = None,
    fetched_at: str | None = None,
    raw_file: str | None = None,
    mst: str | None = None,
    meta: DrfDocHint | None = None,
) -> SourceBundle:
    """lawService.do?target=law|eflaw&type=XML 응답 → SourceBundle (전 조문·별표)."""
    meta = meta or DrfDocHint()
    root = parse_xml(raw)
    info = root.find("기본정보")
    if info is None:
        info = root
    law_id = _child_text(info, "법령ID")
    title = _child_text(info, "법령명_한글") or _child_text(info, "법령명한글")
    short = _child_text(info, "법령명약칭")
    kind_name = _child_text(info, "법종구분")
    anc_no = _child_text(info, "공포번호")
    anc_yd = _ymd(_child_text(info, "공포일자"))
    ef_yd = _ymd(_child_text(info, "시행일자"))
    prom_no = f"{kind_name} 제{int(anc_no)}호" if (kind_name and anc_no and anc_no.isdigit()) else None

    articles: list[ArticleText] = []
    for unit in root.iter("조문단위"):
        if (_child_text(unit, "조문여부") or "조문") != "조문":
            continue  # 편·장·절 제목('전문')
        jo = _int_or_none(_child_text(unit, "조문번호"))
        if jo is None:
            continue
        br = _int_or_none(_child_text(unit, "조문가지번호")) or None
        art_ef = _ymd(_child_text(unit, "조문시행일자"))
        articles.append(
            ArticleText(
                kind="article",
                label=_label(jo, br),
                path=_path("art", jo, br),
                heading=_child_text(unit, "조문제목"),
                text=_law_article_text(unit),
                effective_from=art_ef if (art_ef and art_ef != ef_yd) else None,
            )
        )
    for unit in root.iter("별표단위"):
        if (_child_text(unit, "별표구분") or "별표") != "별표":
            continue  # 서식 제외
        no = _int_or_none(_child_text(unit, "별표번호"))
        if no is None:
            continue
        br = _int_or_none(_child_text(unit, "별표가지번호")) or None
        content = "\n".join(_lines(_child_text(unit, "별표내용")))
        if not content:
            continue  # 내용 없는 별표(파일 전용)는 넣지 않는다
        bef = _ymd(_child_text(unit, "별표시행일자"))
        articles.append(
            ArticleText(
                kind="annex",
                label=f"별표 {no}" + (f"의{br}" if br else ""),
                path=_path("annex", no, br),
                heading=_child_text(unit, "별표제목"),
                text=content,
                effective_from=bef if (bef and bef != ef_yd) else None,
            )
        )

    v_header: VerifyState = "confirmed" if (prom_no and anc_yd and ef_yd) else "needs_review"
    if source_url is None:
        q = {"lsiSeq": mst} if mst else {"lsId": law_id or ""}
        if mst and ef_yd:
            q["efYd"] = ef_yd.replace("-", "")
        source_url = f"https://www.law.go.kr/LSW/lsInfoP.do?{urlencode(q)}"
    doc = DocumentMeta(
        key=meta.key or f"law.go.kr.law.{law_id}",
        layer=meta.layer or "L1_NATIONAL",
        source_type=meta.source_type or _law_source_type(kind_name),
        official_title=title or "",
        jurisdiction_code=meta.jurisdiction_code or "00",
        external_system="law.go.kr",
        external_id=law_id,
        short_title=short,
        parent_key=meta.parent_key,
        homepage_url=meta.homepage_url,
        aliases=dict(meta.aliases),
    )
    version = VersionMeta(
        promulgation_number=prom_no,
        promulgation_date=anc_yd,
        effective_from=ef_yd,
        effective_to=None,
        external_version_id=mst,
        source_url=source_url,
        fetched_at=fetched_at or dt.datetime.now(KST).replace(microsecond=0).isoformat(),
        raw_format="xml",
        raw_file=raw_file,
        v_source_access="confirmed",
        v_version_header=v_header,
        v_text_match="not_checked",
        v_current_version="needs_review",
        note="DRF Open API XML (공포번호 = 법종구분 + 공포번호 로 구성)",
    )
    return SourceBundle(document=doc, version=version, articles=articles, is_example=False)


def _law_source_type(kind_name: str | None) -> SourceType:
    k = kind_name or ""
    if k == "법률":
        return "law"
    if k == "대통령령":
        return "decree"
    if k.endswith("령") or "규칙" in k:  # 총리령·부령 = 시행규칙
        return "rule"
    return "law"


# ---------------------------------------------------------------------------
# 자치법규 (target=ordin)
# ---------------------------------------------------------------------------


def _ordin_units(root: ET.Element) -> list[ET.Element]:
    """'조문번호'와 '조내용'(또는 '조제목')을 자식으로 가진 요소 = 조 하나."""
    out = []
    for el in root.iter():
        tags = {c.tag for c in el}
        if "조문번호" in tags and ("조내용" in tags or "조제목" in tags):
            out.append(el)
    return out


def _ordin_jo_no(s: str | None) -> tuple[int | None, int | None]:
    if not s:
        return None, None
    d = re.sub(r"\D", "", s)
    if not d:
        return None, None
    if len(d) == 6:  # 조번호 4자리 + 가지번호 2자리 (법령 JO 형식과 같다고 가정)
        return int(d[:4]), (int(d[4:]) or None)
    return int(d), None


def parse_ordin_xml(
    raw: bytes,
    *,
    source_url: str | None = None,
    fetched_at: str | None = None,
    raw_file: str | None = None,
    meta: DrfDocHint | None = None,
) -> SourceBundle:
    """lawService.do?target=ordin&type=XML 응답 → SourceBundle."""
    meta = meta or DrfDocHint()
    root = parse_xml(raw)
    ordin_id = _first_text(root, "자치법규ID")
    ordin_seq = _first_text(root, "자치법규일련번호")
    title = _first_text(root, "자치법규명")
    anc_no = _first_text(root, "공포번호")
    anc_yd = _ymd(_first_text(root, "공포일자"))
    ef_yd = _ymd(_first_text(root, "시행일자"))
    kind = _first_text(root, "자치법규종류")
    org = _first_text(root, "지자체기관명")
    kind_name = ORDIN_KIND_NAMES.get(kind or "", kind)
    prom_no = None
    if org and kind_name and anc_no and re.fullmatch(r"\d+", anc_no):
        prom_no = f"{org.replace(' ', '')}{kind_name} 제{int(anc_no)}호"

    articles: list[ArticleText] = []
    for unit in _ordin_units(root):
        yn = _child_text(unit, "조문여부")
        if yn is not None and yn not in ("Y", "조문"):
            continue  # 편·장·절
        jo, br = _ordin_jo_no(_child_text(unit, "조문번호"))
        if jo is None:
            continue
        articles.append(
            ArticleText(
                kind="article",
                label=_label(jo, br),
                path=_path("art", jo, br),
                heading=_child_text(unit, "조제목"),
                text="\n".join(_lines(_child_text(unit, "조내용"))),
            )
        )
    for unit in root.iter():
        tags = {c.tag for c in unit}
        if not ("별표번호" in tags and "별표내용" in tags):
            continue
        if (_child_text(unit, "별표구분") or "별표") != "별표":
            continue
        no = _int_or_none(_child_text(unit, "별표번호"))
        content = "\n".join(_lines(_child_text(unit, "별표내용")))
        if no is None or not content:
            continue
        br = _int_or_none(_child_text(unit, "별표가지번호")) or None
        articles.append(
            ArticleText(
                kind="annex",
                label=f"별표 {no}" + (f"의{br}" if br else ""),
                path=_path("annex", no, br),
                heading=_child_text(unit, "별표제목"),
                text=content,
            )
        )

    v_header: VerifyState = "confirmed" if (prom_no and anc_yd and ef_yd) else "needs_review"
    if source_url is None:
        source_url = (
            f"https://www.law.go.kr/LSW/ordinInfoP.do?{urlencode({'ordinSeq': ordin_seq})}"
            if ordin_seq
            else f"https://www.law.go.kr/LSW/ordinInfoP.do?{urlencode({'ordinId': ordin_id or ''})}"
        )
    doc = DocumentMeta(
        key=meta.key or f"law.go.kr.ordin.{ordin_id}",
        layer=meta.layer or "L3_LOCAL",
        source_type=meta.source_type or "ordinance",
        official_title=title or "",
        jurisdiction_code=meta.jurisdiction_code or "unknown",
        external_system="law.go.kr",
        external_id=ordin_id,
        short_title=None,
        parent_key=meta.parent_key,
        homepage_url=meta.homepage_url,
        aliases=dict(meta.aliases),
    )
    version = VersionMeta(
        promulgation_number=prom_no,
        promulgation_date=anc_yd,
        effective_from=ef_yd,
        effective_to=None,
        external_version_id=ordin_seq,
        source_url=source_url,
        fetched_at=fetched_at or dt.datetime.now(KST).replace(microsecond=0).isoformat(),
        raw_format="xml",
        raw_file=raw_file,
        v_source_access="confirmed",
        v_version_header=v_header,
        v_text_match="not_checked",
        v_current_version="needs_review",
        note="DRF Open API XML (공포번호 = 지자체기관명 + 자치법규종류 + 공포번호 로 구성)",
    )
    return SourceBundle(document=doc, version=version, articles=articles, is_example=False)


# ---------------------------------------------------------------------------
# 목록 (lawSearch.do)
# ---------------------------------------------------------------------------


def parse_search_xml(raw: bytes) -> list[dict[str, Any]]:
    """목록 응답의 결과 항목들. 자식 요소를 가진 루트 직계 요소 하나 = 결과 하나 (요소명 그대로 dict)."""
    root = parse_xml(raw)
    out: list[dict[str, Any]] = []
    for item in root:
        if len(item) == 0:
            continue
        rec: dict[str, Any] = {k: v for k, v in item.attrib.items()}
        for c in item:
            rec[c.tag] = _text(c)
        out.append(rec)
    return out


# ---------------------------------------------------------------------------
# 클라이언트
# ---------------------------------------------------------------------------


class LawGoDrfClient:
    def __init__(
        self,
        oc: str,
        *,
        base_url: str = DRF_BASE_URL,
        timeout: float = 10.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not oc:
            raise ValueError("OC (Open API 인증값) 가 필요합니다")
        self.oc = oc
        self.base_url = base_url.rstrip("/")
        self._http = httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=timeout, transport=transport)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> LawGoDrfClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def _get(self, endpoint: str, params: dict[str, str]) -> tuple[bytes, str]:
        q = {"OC": self.oc, "type": "XML", **{k: v for k, v in params.items() if v is not None}}
        # httpx 의 예외 메시지에는 요청 URL 전체(= OC 인증값)가 들어간다 → 로그·응답으로 새지 않게 URL 없는 오류로 바꾼다
        try:
            resp = self._http.get(f"{self.base_url}/{endpoint}", params=q)
        except httpx.HTTPError as e:
            raise DrfError(f"{endpoint} 요청 실패 ({type(e).__name__})") from None
        if resp.is_error:
            raise DrfError(f"{endpoint} HTTP {resp.status_code}")
        return resp.content, dt.datetime.now(KST).replace(microsecond=0).isoformat()

    def search(self, target: str, query: str, **params: str) -> list[dict[str, Any]]:
        raw, _ = self._get("lawSearch.do", {"target": target, "query": query, **params})
        return parse_search_xml(raw)

    def fetch_document_xml(
        self, target: DrfTarget, id: str | None = None, mst: str | None = None, ef_yd: str | None = None
    ) -> tuple[bytes, str]:
        if not id and not mst:
            raise ValueError("id 또는 mst 중 하나는 필요합니다")
        params: dict[str, str] = {"target": target}
        if id:
            params["ID"] = id
        if mst:
            params["MST"] = mst
        if target == "eflaw" and mst and not id:
            if not ef_yd:
                raise ValueError("target=eflaw 를 MST 로 조회할 때는 ef_yd(YYYYMMDD)가 필요합니다")
            params["efYd"] = ef_yd
        return self._get("lawService.do", params)

    def get_document(
        self,
        target: DrfTarget,
        id: str | None = None,
        mst: str | None = None,
        ef_yd: str | None = None,
        *,
        meta: DrfDocHint | None = None,
        raw_dir: Path | None = None,
    ) -> SourceBundle:
        raw, fetched_at = self.fetch_document_xml(target, id=id, mst=mst, ef_yd=ef_yd)
        raw_file = None
        if raw_dir is not None:
            raw_dir.mkdir(parents=True, exist_ok=True)
            name = f"drf_{target}_{id or ''}_{mst or ''}_{ef_yd or ''}.xml".replace("__", "_")
            (raw_dir / name).write_bytes(raw)
            raw_file = f"{raw_dir.name}/{name}"
        if target == "ordin":
            return parse_ordin_xml(raw, fetched_at=fetched_at, raw_file=raw_file, meta=meta)
        return parse_law_xml(raw, fetched_at=fetched_at, raw_file=raw_file, mst=mst, meta=meta)
