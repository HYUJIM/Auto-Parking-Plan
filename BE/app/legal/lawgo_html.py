"""
국가법령정보센터(www.law.go.kr) 공개 HTML 페이지용 개발 수집기.

DRF Open API(인증값 OC 필요)를 쓰기 전까지 개발 데이터를 만들기 위한 수집기다.
페이지는 받은 그대로(raw bytes) 보관하고, 결정적 파서(bs4 + 정규식)로
버전 표제와 선택한 조문·별표만 뽑아 SourceBundle 을 만든다. AI 는 쓰지 않는다.

law.go.kr 화면 구성 (2026-11 확인)
- 법령 이름 주소   : /법령/<법령명>, /자치법규/<자치법규명>  → iframe src 에 현행 lsiSeq·efYd / ordinSeq
- 법령 본문        : POST /LSW/lsInfoR.do   (lsiSeq, efYd, chrClsCd=010202, urlMode=lsInfoP, ancYnChk=0)
- 자치법규 본문    : GET  /LSW/ordinInfoR.do (ordinSeq, ordinId, chrClsCd=010202, gubun=ELIS)
- 법령 연혁 목록   : GET  /LSW/lsHstListR.do?lsId=...
- 자치법규 연혁    : GET  /LSW/ordinHstListR.do?ordinId=...
- 법령 별표 내용   : GET  /LSW/lsBylContentsInfoR.do?bylSeq=...&chrClsCd=010202
- 자치법규 별표    : GET  /LSW/ordinBylContentsInfoR.do?bylSeq=...&gubun=ELIS
- 사람이 보는 주소 : /LSW/lsInfoP.do?lsiSeq=..&efYd=..  /  /LSW/ordinInfoP.do?ordinSeq=..

본문 HTML 구조: 조마다 <a name="J{조}:{가지}"> 다음에 <div class="pgroup"> … <div class="lawcon">
안에 항·호·목이 <p> 한 줄씩 들어 있다. 조 제목은 <span class="bl"><label>제77조(용도지역의 건폐율)</label>.

텍스트 정규화 (원문 대비 바꾸는 것은 이것뿐)
- HTML 표시 규칙에 따른 공백 처리: 태그 사이 줄바꿈·들여쓰기 등 연속 공백은 한 칸, 문단(<p>) 앞뒤 공백 제거
- <p> 하나 = 한 줄, <br> = 줄바꿈, 빈 문단은 버림
- \\r\\n → \\n, NBSP → 공백, 줄 끝 공백 제거, 3줄 이상 연속 빈 줄 → 빈 줄 1줄
- 〈개정 …〉/<개정 …>, [전문개정 …] 같은 연혁 주석은 화면에 보이는 그대로 남긴다.
"""
from __future__ import annotations

import copy
import datetime as dt
import re
import time
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any, Literal
from urllib.parse import parse_qs, quote, urlencode, urlsplit

import httpx
from bs4 import BeautifulSoup, Comment, NavigableString, Tag

from app.legal.parsed import ArticleText, DocumentMeta, SourceBundle, VerifyState, VersionMeta

BASE_URL = "https://www.law.go.kr"
USER_AGENT = "APP-legal-collector/0.1 (dev)"
KST = dt.timezone(dt.timedelta(hours=9))
DEFAULT_MIN_INTERVAL = 1.2  # 요청 사이 최소 간격(초). law.go.kr 에 부담을 주지 않기 위해 1초 이상.
BUNDLE_NOTE = "선별 조문만 수집 (개발용 HTML 수집기)"
KO_CHR_CLS = "010202"  # 한글 본문

DocKind = Literal["law", "ordin"]


# ---------------------------------------------------------------------------
# 텍스트 정규화
# ---------------------------------------------------------------------------

_BR_MARK = ""  # <br> 자리 표시 (사용자 영역 문자, 원문에 나오지 않음)
_HTML_WS_RE = re.compile(r"[ \t\n\r\f]+")  # HTML 이 한 칸으로 줄이는 공백 (NBSP 제외)


def normalize_text(text: str) -> str:
    """번들 text 에 허용된 정규화만 적용한다."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace(" ", " ")
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    # 빈 줄 3줄 이상(= 줄바꿈 4개 이상) → 빈 줄 1줄
    text = re.sub(r"\n{4,}", "\n\n", text)
    return text.strip("\n")


def image_marker(img: Tag) -> str:
    """본문 안 내용 이미지(표·그림) 자리에 넣는 표시 줄. 이미지 내용(대체텍스트 포함)은 text 에 넣지 않는다."""
    src = img.get("src") or ""
    m = re.search(r"flSeq=(\d+)", src)
    return f"[[IMAGE flSeq={m.group(1)}]]" if m else f"[[IMAGE src={src}]]"


IMAGE_MARKER_RE = re.compile(r"^\[\[IMAGE (?:flSeq=\d+|src=[^\]]*)\]\]$")


def _visible_copy(el: Tag, *, mark_images: bool = True) -> Tag:
    """화면에 보이지 않는 요소를 지운 사본. 내용 이미지는 독립된 줄의 [[IMAGE …]] 표시로 바꾼다."""
    el = copy.copy(el)
    for c in el.find_all(string=lambda s: isinstance(s, Comment)):
        c.extract()
    for t in el.find_all(["script", "style", "input", "textarea", "select", "button", "noscript"]):
        t.decompose()
    for t in el.find_all(style=re.compile(r"display\s*:\s*none", re.I)):
        t.decompose()
    for img in el.find_all("img"):
        if mark_images and not _is_ui_image(img):
            img.replace_with(NavigableString(_BR_MARK + image_marker(img) + _BR_MARK))
        else:
            img.decompose()
    for br in el.find_all("br"):
        br.replace_with(NavigableString(_BR_MARK))
    return el


def _element_lines(el: Tag, *, mark_images: bool = True) -> list[str]:
    """한 요소(<p> 등)의 표시 텍스트를 줄 목록으로. HTML 공백 규칙 적용."""
    raw = _visible_copy(el, mark_images=mark_images).get_text()
    out = []
    for part in raw.split(_BR_MARK):
        s = _HTML_WS_RE.sub(" ", part).strip(" \t\n\r\f")
        out.append(s)
    # 이미지 표시 줄 앞뒤에 생긴 빈 조각은 버린다 (원문의 빈 줄이 아님)
    out = [
        x
        for i, x in enumerate(out)
        if x.strip()
        or not (
            (i > 0 and IMAGE_MARKER_RE.match(out[i - 1])) or (i + 1 < len(out) and IMAGE_MARKER_RE.match(out[i + 1]))
        )
    ]
    # 문단 끝/처음의 빈 조각은 버린다 (<br> 로 끝나는 문단 등)
    while out and not out[-1].strip():
        out.pop()
    while out and not out[0].strip():
        out.pop(0)
    return out


def _is_ui_image(img: Tag) -> bool:
    src = (img.get("src") or "").lower()
    alt = img.get("alt") or ""
    return (
        "/images/button/" in src
        or "images/button/" in src
        or "images/common/btn_" in src
        or "버튼" in alt
        or alt in {"연혁", "위임행정규칙", "펼침", "접기", "별표"}
    )


# ---------------------------------------------------------------------------
# 라벨 / 날짜
# ---------------------------------------------------------------------------

_ART_LABEL_RE = re.compile(r"^(제\d+조(?:의\d+)?)(?:\s*\((.*)\))?\s*$", re.S)
_ANNEX_LABEL_RE = re.compile(r"^별표\s*(\d+)(?:\s*의\s*(\d+))?$")


def label_to_path(label: str) -> str:
    """'제84조'→'art84', '제84조의2'→'art84-2', '별표 5'→'annex5', '별표 5의2'→'annex5-2'."""
    m = re.fullmatch(r"제(\d+)조(?:의(\d+))?", label.strip())
    if m:
        return f"art{int(m.group(1))}" + (f"-{int(m.group(2))}" if m.group(2) else "")
    m = _ANNEX_LABEL_RE.fullmatch(label.strip())
    if m:
        return f"annex{int(m.group(1))}" + (f"-{int(m.group(2))}" if m.group(2) else "")
    raise ValueError(f"unknown label: {label!r}")


_KDATE_RE = re.compile(r"(\d{4})\s*\.\s*(\d{1,2})\s*\.\s*(\d{1,2})\s*\.?")


def parse_kdate(s: str) -> str | None:
    """'2026. 7. 1.' / '2026.7.1' → '2026-07-01'."""
    m = _KDATE_RE.search(s)
    if not m:
        return None
    y, mo, d = (int(x) for x in m.groups())
    return dt.date(y, mo, d).isoformat()


def ymd8_to_iso(s: str | None) -> str | None:
    if not s or not re.fullmatch(r"\d{8}", s):
        return None
    if s == "99991231":
        return None
    return f"{s[:4]}-{s[4:6]}-{s[6:]}"


# ---------------------------------------------------------------------------
# 버전 표제
# ---------------------------------------------------------------------------

_EF_RE = re.compile(r"\[\s*시행\s*([0-9.\s]+?)\s*\]")
_ANC_RE = re.compile(r"\[\s*([^\[\],]*?제\s*[0-9-]+\s*호)\s*,\s*([0-9.\s]+?)\s*,\s*([^\]]*?)\s*\]")


@dataclass
class VersionHeader:
    official_title: str | None
    short_title: str | None
    header_line: str | None  # 화면 표제 원문: '[시행 2026. 7. 1.] [법률 제21447호, 2026. 3. 5., 타법개정]'
    effective_from: str | None
    promulgation_number: str | None  # '법률 제21447호' (표제에 보이는 그대로)
    promulgation_date: str | None
    revision_kind: str | None  # '일부개정', '타법개정' …

    @property
    def complete(self) -> bool:
        return bool(self.effective_from and self.promulgation_number and self.promulgation_date)


def parse_header_line(line: str) -> dict[str, str | None]:
    line = _HTML_WS_RE.sub(" ", line.replace(" ", " ")).strip()
    ef = _EF_RE.search(line)
    anc = _ANC_RE.search(line)
    return {
        "header_line": line or None,
        "effective_from": parse_kdate(ef.group(1)) if ef else None,
        "promulgation_number": _HTML_WS_RE.sub(" ", anc.group(1)).strip() if anc else None,
        "promulgation_date": parse_kdate(anc.group(2)) if anc else None,
        "revision_kind": anc.group(3).strip() if anc and anc.group(3).strip() else None,
    }


def _parse_law_header(soup: BeautifulSoup) -> VersionHeader:
    h2 = soup.find("h2")
    title = short = None
    if h2 is not None:
        h2c = _visible_copy(h2, mark_images=False)
        span = h2c.find("span")
        if span is not None:
            st = _HTML_WS_RE.sub(" ", span.get_text()).strip()
            m = re.search(r"약칭\s*:\s*([^)]+?)\s*\)", st)
            short = m.group(1).strip() if m else None
            span.decompose()
        title = _HTML_WS_RE.sub(" ", h2c.get_text()).strip() or None
    sub = soup.find("div", class_="ct_sub")
    line = sub.get_text(" ") if sub is not None else ""
    return VersionHeader(official_title=title, short_title=short, **parse_header_line(line))


def _parse_ordin_header(soup: BeautifulSoup) -> VersionHeader:
    h2 = soup.find("h2")
    title = _HTML_WS_RE.sub(" ", h2.get_text()).strip() if h2 is not None else None
    sub = soup.find("div", class_="subtit1")
    line = sub.get_text(" ") if sub is not None else ""
    return VersionHeader(official_title=title or None, short_title=None, **parse_header_line(line))


def _hidden_inputs(soup: BeautifulSoup) -> dict[str, str]:
    out: dict[str, str] = {}
    for inp in soup.find_all("input", attrs={"type": "hidden"}):
        key = inp.get("id") or inp.get("name")
        if key and key not in out:
            out[key] = inp.get("value") or ""
    return out


def header_consistency(header: VersionHeader, hidden: dict[str, str]) -> list[str]:
    """표제와 페이지 숨은 값(ancYd/ancNo/efYd)이 서로 맞는지. 문제 목록을 돌려준다."""
    problems: list[str] = []
    if not header.complete:
        problems.append("표제에서 시행일·공포번호·공포일 중 일부를 읽지 못함")
        return problems
    anc_yd = ymd8_to_iso(hidden.get("ancYd"))
    if anc_yd and anc_yd != header.promulgation_date:
        problems.append(f"공포일 불일치: 표제 {header.promulgation_date} / ancYd {anc_yd}")
    ef_yd = ymd8_to_iso(hidden.get("efYd"))
    if ef_yd and ef_yd != header.effective_from:
        problems.append(f"시행일 불일치: 표제 {header.effective_from} / efYd {ef_yd}")
    anc_no = hidden.get("ancNo")
    if anc_no and header.promulgation_number:
        m = re.search(r"제\s*([0-9-]+)\s*호", header.promulgation_number)
        if not m or m.group(1).lstrip("0") != anc_no.lstrip("0"):
            problems.append(f"공포번호 불일치: 표제 {header.promulgation_number} / ancNo {anc_no}")
    return problems


# ---------------------------------------------------------------------------
# 조문 / 별표 목록
# ---------------------------------------------------------------------------

_ART_ANCHOR_RE = re.compile(r"^J(\d+):(\d+)$")


@dataclass
class ParsedArticle:
    label: str  # '제84조', '제84조의2'
    path: str  # 'art84', 'art84-2'
    heading: str | None
    text: str  # 조 전체 (조 제목 줄 + 항·호·목), 정규화 후
    anchor: str  # 'J84:0'
    warnings: list[str] = field(default_factory=list)
    images: list[dict[str, str]] = field(default_factory=list)  # 본문 내용 이미지 {'marker','src','alt'} (alt 는 사이트 대체텍스트 원문)

    def to_article_text(self) -> ArticleText:
        return ArticleText(kind="article", label=self.label, path=self.path, heading=self.heading, text=self.text)


def _article_lines(lawcon: Tag) -> tuple[list[str], list[str], list[dict[str, str]]]:
    lines: list[str] = []
    warnings: list[str] = []
    images: list[dict[str, str]] = []
    for child in lawcon.children:
        if isinstance(child, Comment):
            continue
        if isinstance(child, NavigableString):
            s = _HTML_WS_RE.sub(" ", str(child)).strip()
            if s:
                lines.append(s)
            continue
        if not isinstance(child, Tag):
            continue
        if child.name in ("script", "style", "input"):
            continue
        style = child.get("style") or ""
        if re.search(r"display\s*:\s*none", style, re.I):
            continue
        if child.name == "table" or child.find("table") is not None:
            warnings.append("조문 안에 표(table)가 있음 — 표 셀은 ' | ' 로 이어 붙임")
            tables = [child] if child.name == "table" else child.find_all("table")
            for tbl in tables:
                for row in table_rows(tbl):
                    lines.append(" | ".join(row))
            continue
        imgs = [child] if child.name == "img" else child.find_all("img")
        for img in imgs:
            if not _is_ui_image(img):
                mk = image_marker(img)
                images.append({"marker": mk, "src": img.get("src") or "", "alt": img.get("alt") or ""})
                warnings.append(f"조문 안에 내용 이미지가 있음 ({mk}) — 이미지 내용은 text 에 없고 그 자리에 표시 줄만 넣음")
        if child.name == "img":
            if not _is_ui_image(child):
                lines.append(image_marker(child))
            continue
        lines.extend(ln for ln in _element_lines(child) if ln.strip())
    return lines, warnings, images


def _parse_articles(soup: BeautifulSoup) -> list[ParsedArticle]:
    out: list[ParsedArticle] = []
    for a in soup.find_all("a", attrs={"name": _ART_ANCHOR_RE}):
        anchor = a["name"]
        block = a.find_next_sibling()
        if not isinstance(block, Tag) or "pgroup" not in (block.get("class") or []):
            continue
        lawcon = block.find("div", class_="lawcon")
        if lawcon is None:
            continue
        lab = lawcon.find("label") or lawcon.find("span", class_="bl")
        if lab is None:
            continue
        label_line = _HTML_WS_RE.sub(" ", lab.get_text()).strip()
        m = _ART_LABEL_RE.match(label_line)
        if not m:
            continue
        label, heading = m.group(1), (m.group(2).strip() if m.group(2) is not None else None)
        path = label_to_path(label)
        am = _ART_ANCHOR_RE.match(anchor)
        expect = f"art{int(am.group(1))}" + (f"-{int(am.group(2))}" if int(am.group(2)) else "")
        lines, warnings, images = _article_lines(lawcon)
        if expect != path:
            warnings.append(f"앵커 {anchor} 와 라벨 {label} 의 조번호가 다름")
        out.append(
            ParsedArticle(
                label=label,
                path=path,
                heading=heading or None,
                text=normalize_text("\n".join(lines)),
                anchor=anchor,
                warnings=warnings,
                images=images,
            )
        )
    return out


@dataclass
class AnnexRef:
    """본문 하단 별표·서식 목록의 한 항목 (제목과 첨부 파일 링크)."""

    byl_seq: str
    title_line: str  # 화면에 보이는 그대로: '[별표 5] 제2종일반주거지역안에서 …(제71조제1항제4호관련)'
    label: str | None  # '별표 5' (별표가 아니면 None)
    path: str | None  # 'annex5'
    heading: str | None
    file_links: list[str] = field(default_factory=list)  # flDownload.do?… (상대경로 그대로)


_LAW_ANNEX_TITLE_RE = re.compile(r"^\[\s*(별표\s*\d+(?:\s*의\s*\d+)?)\s*\]\s*(.*)$")
_ORDIN_ANNEX_TITLE_RE = re.compile(r"^(별표)\s*_?\s*(\d+(?:\s*의\s*\d+)?)\s*_\s*(.*)$")


def _parse_annex_title(title: str) -> tuple[str | None, str | None]:
    m = _LAW_ANNEX_TITLE_RE.match(title)
    if m:
        label = re.sub(r"\s+", " ", m.group(1)).replace(" 의 ", "의").replace(" 의", "의")
        label = re.sub(r"^별표\s*", "별표 ", label)
        return label, m.group(2).strip() or None
    m = _ORDIN_ANNEX_TITLE_RE.match(title)
    if m:
        num = re.sub(r"\s+", "", m.group(2))
        return f"별표 {num}", m.group(3).strip() or None
    return None, None


def _parse_annex_refs(soup: BeautifulSoup) -> list[AnnexRef]:
    refs: dict[str, AnnexRef] = {}
    for a in soup.find_all("a", onclick=re.compile(r"bylInfoDiv\('\d+'\)")):
        seq = re.search(r"bylInfoDiv\('(\d+)'\)", a["onclick"]).group(1)
        title = _HTML_WS_RE.sub(" ", _visible_copy(a, mark_images=False).get_text()).strip()
        if not title:
            continue
        if seq in refs:
            continue
        label, heading = _parse_annex_title(title)
        links: list[str] = []
        container = a.find_parent("li")
        if container is not None:
            for fl in container.find_all("a", href=re.compile(r"flDownload\.do")):
                href = fl["href"]
                if href not in links:
                    links.append(href)
        refs[seq] = AnnexRef(
            byl_seq=seq,
            title_line=title,
            label=label,
            path=label_to_path(label) if label else None,
            heading=heading,
            file_links=links,
        )
    return list(refs.values())


@dataclass
class ParsedBody:
    kind: DocKind
    header: VersionHeader
    hidden: dict[str, str]
    articles: list[ParsedArticle]
    annexes: list[AnnexRef]

    def article(self, label: str) -> ParsedArticle | None:
        return next((a for a in self.articles if a.label == label), None)

    def annex(self, label: str) -> AnnexRef | None:
        return next((a for a in self.annexes if a.label == label), None)

    @property
    def external_id(self) -> str | None:
        return self.hidden.get("lsId") if self.kind == "law" else self.hidden.get("ordinId")

    @property
    def seq(self) -> str | None:
        return self.hidden.get("lsiSeq") if self.kind == "law" else self.hidden.get("ordinSeq")


def parse_body(html: str, kind: DocKind) -> ParsedBody:
    """lsInfoR.do / ordinInfoR.do 응답 HTML 을 파싱한다."""
    soup = BeautifulSoup(html, "html.parser")
    header = _parse_law_header(soup) if kind == "law" else _parse_ordin_header(soup)
    return ParsedBody(
        kind=kind,
        header=header,
        hidden=_hidden_inputs(soup),
        articles=_parse_articles(soup),
        annexes=_parse_annex_refs(soup),
    )


# ---------------------------------------------------------------------------
# 별표 내용
# ---------------------------------------------------------------------------


def table_rows(tbl: Tag) -> list[list[str]]:
    rows: list[list[str]] = []
    for tr in tbl.find_all("tr"):
        if tr.find_parent("table") is not tbl:
            continue
        cells = []
        for cell in tr.find_all(["td", "th"]):
            if cell.find_parent("tr") is not tr:
                continue
            cells.append(normalize_text("\n".join(_element_lines(cell))).strip())
        if cells:
            rows.append(cells)
    return rows


@dataclass
class AnnexContent:
    byl_seq: str | None
    has_text: bool  # law.go.kr 이 HTML 텍스트(표)로 제공하는지
    text: str | None
    table_json: list[list[str]] | None
    viewer_iframe: str | None  # 문서 뷰어(이미지 변환) 주소
    content_images: list[str]  # 별표 본문 이미지(flDownload.do?flSeq=…)
    file_seqs: dict[str, str]  # {'hanFlSeq': …, 'pdfFlSeq': …}

    @property
    def availability(self) -> str:
        if self.has_text:
            return "html_text"
        kinds = []
        if self.file_seqs.get("hanFlSeq") and self.file_seqs["hanFlSeq"] != "0":
            kinds.append("HWP")
        if self.file_seqs.get("pdfFlSeq") and self.file_seqs["pdfFlSeq"] != "0":
            kinds.append("PDF")
        if self.content_images:
            kinds.append("이미지")
        if self.viewer_iframe:
            kinds.append("문서뷰어(이미지 변환)")
        return "attachment_only:" + "/".join(kinds) if kinds else "unknown"


def parse_annex_contents(html: str) -> AnnexContent:
    """lsBylContentsInfoR.do / ordinBylContentsInfoR.do 응답."""
    soup = BeautifulSoup(html, "html.parser")
    hidden = _hidden_inputs(soup)
    iframe = soup.find("iframe")
    imgs = [i.get("src") for i in soup.find_all("img") if "본문이미지" in (i.get("alt") or "")]
    tables = [t for t in soup.find_all("table") if t.find_parent("table") is None]
    table_json = None
    text = None
    if tables:
        rows: list[list[str]] = []
        for t in tables:
            rows.extend(table_rows(t))
        if any(any(c.strip() for c in r) for r in rows):
            table_json = rows
            text = normalize_text("\n".join("\t".join(r) for r in rows))
    return AnnexContent(
        byl_seq=None,
        has_text=text is not None,
        text=text,
        table_json=table_json,
        viewer_iframe=iframe.get("src") if iframe is not None else None,
        content_images=[s for s in imgs if s],
        file_seqs={k: v for k, v in hidden.items() if k.endswith("FlSeq")},
    )


# ---------------------------------------------------------------------------
# 연혁 / 이름 주소
# ---------------------------------------------------------------------------


@dataclass
class HistoryEntry:
    seq: str  # lsiSeq / ordinSeq (법령은 같은 lsiSeq 가 시행일별로 여러 번 나올 수 있음)
    text: str  # 목록에 보이는 그대로 (NBSP 는 공백으로)
    effective_from: str | None
    promulgation_date: str | None
    promulgation_number: str | None  # 목록 표기 그대로 ('조례 제6551호' 처럼 본문 표제와 다를 수 있음)
    revision_kind: str | None
    site_current_flag: bool | None  # 사이트가 표시한 현행 여부 (자치법규는 '최신 공포본'이라 시행 예정일 수 있음)


_LAW_HST_RE = re.compile(r"lsViewLsHst2\(\s*'(\d+)'\s*,\s*'(\d*)'\s*,\s*'(\d*)'\s*,\s*'(\d*)'\s*,\s*'([YN]?)'")
_ORDIN_HST_RE = re.compile(r"ordinViewOrdinHst\(\s*'(\d+)'\s*,\s*'([YN]?)'\s*\)")


def parse_history(html: str, kind: DocKind) -> list[HistoryEntry]:
    soup = BeautifulSoup(html, "html.parser")
    out: list[HistoryEntry] = []
    seen: set[tuple[str, str | None]] = set()
    pat = _LAW_HST_RE if kind == "law" else _ORDIN_HST_RE
    for a in soup.find_all("a", onclick=pat):
        m = pat.search(a["onclick"])
        seq = m.group(1)
        text = _HTML_WS_RE.sub(" ", a.get_text().replace("\u00a0", " ")).strip()
        h = parse_header_line(text)
        # 법령은 한 공포본(lsiSeq)이 시행일별로 여러 줄 나올 수 있다(부분 시행). (일련번호, 시행일)로 구분한다.
        ef = ymd8_to_iso(m.group(4)) if kind == "law" else h["effective_from"]
        ident = (seq, ef or h["effective_from"])
        if ident in seen:
            continue
        seen.add(ident)
        flag = m.group(5) if kind == "law" else m.group(2)
        out.append(
            HistoryEntry(
                seq=seq,
                text=text,
                effective_from=ef or h["effective_from"],
                promulgation_date=h["promulgation_date"],
                promulgation_number=h["promulgation_number"],
                revision_kind=h["revision_kind"],
                site_current_flag=(flag == "Y") if flag else None,
            )
        )
    return out


def select_in_force(entries: Iterable[HistoryEntry], as_of: str) -> HistoryEntry | None:
    """기준일(as_of, 'YYYY-MM-DD')에 시행 중인 버전 = 시행일 ≤ 기준일 중 시행일이 가장 늦은 것."""
    cands = [e for e in entries if e.effective_from and e.effective_from <= as_of]
    if not cands:
        return None
    # 같은 시행일이 여럿이면 사이트 현행 표시 → 공포일 → 일련번호 순으로 늦은 것
    return max(
        cands,
        key=lambda e: (e.effective_from, bool(e.site_current_flag), e.promulgation_date or "", int(e.seq)),
    )


def pending_after(entries: Iterable[HistoryEntry], as_of: str) -> list[HistoryEntry]:
    """기준일 뒤에 시행되는 버전(시행 예정)."""
    return sorted(
        (e for e in entries if e.effective_from and e.effective_from > as_of),
        key=lambda e: (e.effective_from, int(e.seq)),
    )


def parse_name_resolver(html: str) -> dict[str, str]:
    """/법령/<이름>, /자치법규/<이름> 응답의 iframe src 질의값."""
    soup = BeautifulSoup(html, "html.parser")
    iframe = soup.find("iframe", src=re.compile(r"(lsInfoP|ordinInfoP)\.do"))
    if iframe is None:
        return {}
    q = parse_qs(urlsplit(iframe["src"]).query)
    return {k: v[0] for k, v in q.items() if v}


# ---------------------------------------------------------------------------
# 약칭 근거 (조례 본문의 '법', '영', '규칙')
# ---------------------------------------------------------------------------

_ALIAS_DEF_RE = re.compile(r"「([^」]+)」\s*\(\s*이하\s*[“\"]([^”\"]+)[”\"]\s*(?:이)?라\s*한다\s*\)")


def extract_alias_definitions(articles: Iterable[ParsedArticle]) -> list[tuple[str, str, str]]:
    """'「국토의 계획 및 이용에 관한 법률」(이하 “법”이라 한다)' → [('법', '국토의 계획 및 이용에 관한 법률', '제2조')]."""
    out: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    for art in articles:
        for m in _ALIAS_DEF_RE.finditer(art.text):
            title, alias = m.group(1).strip(), m.group(2).strip()
            if alias in seen:
                continue
            seen.add(alias)
            out.append((alias, title, art.label))
    return out


def extract_alias_links(html: str, aliases: Iterable[str] = ("법", "영", "규칙")) -> dict[str, list[str]]:
    """자치법규 본문의 약칭 링크가 가리키는 법령ID(lsId). fncOrdinLawPop('<lsId>','010101','012601',…)."""
    wanted = set(aliases)
    soup = BeautifulSoup(html, "html.parser")
    out: dict[str, list[str]] = {}
    for a in soup.find_all("a", onclick=re.compile(r"fncOrdinLawPop\(")):
        txt = _HTML_WS_RE.sub(" ", a.get_text()).strip()
        if txt not in wanted:
            continue
        m = re.search(r"fncOrdinLawPop\('(\d+)'\s*,\s*'(\d+)'", a["onclick"])
        if not m or m.group(2) != "010101":
            continue
        ids = out.setdefault(txt, [])
        if m.group(1) not in ids:
            ids.append(m.group(1))
    return out


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def now_kst_iso() -> str:
    return dt.datetime.now(KST).replace(microsecond=0).isoformat()


@dataclass
class FetchedPage:
    url: str  # 요청 주소 (GET 은 질의 포함, POST 는 본문을 질의 형태로 덧붙인 기록용 주소)
    method: str
    params: dict[str, str]
    status: int
    content: bytes  # 받은 그대로
    fetched_at: str

    @property
    def text(self) -> str:
        return self.content.decode("utf-8")


class LawGoHtmlClient:
    """law.go.kr 공개 페이지 수집 클라이언트. 요청은 순차, 요청 사이 min_interval 초 이상 쉰다."""

    def __init__(
        self,
        *,
        base_url: str = BASE_URL,
        min_interval: float = DEFAULT_MIN_INTERVAL,
        timeout: float = 60.0,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if min_interval < 1.0 and transport is None:
            raise ValueError("min_interval must be >= 1.0 s for law.go.kr")
        self.base_url = base_url.rstrip("/")
        self.min_interval = min_interval
        self._last = 0.0
        self._http = httpx.Client(
            headers={"User-Agent": USER_AGENT, "Accept-Language": "ko-KR,ko;q=0.9"},
            timeout=timeout,
            follow_redirects=True,
            transport=transport,
        )

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> LawGoHtmlClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def _wait(self) -> None:
        delta = time.monotonic() - self._last
        if delta < self.min_interval:
            time.sleep(self.min_interval - delta)

    def request(self, method: str, path: str, params: dict[str, str] | None = None) -> FetchedPage:
        params = dict(params or {})
        url = self.base_url + path
        self._wait()
        try:
            if method == "POST":
                resp = self._http.post(url, data=params)
            else:
                resp = self._http.get(url, params=params)
        finally:
            self._last = time.monotonic()
        resp.raise_for_status()
        record_url = url + ("?" + urlencode(params) if params else "")
        return FetchedPage(
            url=record_url,
            method=method,
            params=params,
            status=resp.status_code,
            content=resp.content,
            fetched_at=now_kst_iso(),
        )

    # --- 개별 화면 ---
    def resolve_by_name(self, kind: DocKind, name: str) -> tuple[FetchedPage, dict[str, str]]:
        prefix = "법령" if kind == "law" else "자치법규"
        page = self.request("GET", "/" + quote(f"{prefix}/{name.replace(' ', '')}"))
        return page, parse_name_resolver(page.text)

    def law_history(self, ls_id: str) -> FetchedPage:
        return self.request("GET", "/LSW/lsHstListR.do", {"lsId": ls_id})

    def ordin_history(self, ordin_id: str) -> FetchedPage:
        return self.request("GET", "/LSW/ordinHstListR.do", {"ordinId": ordin_id})

    def law_body(self, lsi_seq: str, ef_yd: str | None = None) -> FetchedPage:
        params = {"lsiSeq": lsi_seq, "chrClsCd": KO_CHR_CLS, "urlMode": "lsInfoP", "ancYnChk": "0"}
        if ef_yd:
            params["efYd"] = ef_yd
        return self.request("POST", "/LSW/lsInfoR.do", params)

    def ordin_body(self, ordin_seq: str, ordin_id: str | None = None) -> FetchedPage:
        params = {"ordinSeq": ordin_seq, "chrClsCd": KO_CHR_CLS, "gubun": "ELIS"}
        if ordin_id:
            params["ordinId"] = ordin_id
        return self.request("GET", "/LSW/ordinInfoR.do", params)

    def law_annex(self, byl_seq: str) -> FetchedPage:
        return self.request("GET", "/LSW/lsBylContentsInfoR.do", {"bylSeq": byl_seq, "chrClsCd": KO_CHR_CLS})

    def ordin_annex(self, byl_seq: str) -> FetchedPage:
        return self.request("GET", "/LSW/ordinBylContentsInfoR.do", {"bylSeq": byl_seq, "gubun": "ELIS"})


def human_url(kind: DocKind, seq: str, ef_yd: str | None = None) -> str:
    """사람이 브라우저로 여는 버전 고정 주소."""
    if kind == "law":
        q = {"lsiSeq": seq, "chrClsCd": KO_CHR_CLS, "urlMode": "lsInfoP"}
        if ef_yd:
            q["efYd"] = ef_yd
        q["ancYnChk"] = "0"
        return f"{BASE_URL}/LSW/lsInfoP.do?{urlencode(q)}"
    return f"{BASE_URL}/LSW/ordinInfoP.do?{urlencode({'ordinSeq': seq, 'chrClsCd': KO_CHR_CLS, 'gubun': 'ELIS'})}"


def homepage_url(kind: DocKind, name: str) -> str:
    """현행본으로 연결되는 법령 이름 주소 (버전 고정 아님)."""
    prefix = "법령" if kind == "law" else "자치법규"
    return f"{BASE_URL}/{prefix}/{name.replace(' ', '')}"


# ---------------------------------------------------------------------------
# SourceBundle 만들기
# ---------------------------------------------------------------------------


@dataclass
class Missing:
    label: str
    reason: str


@dataclass
class AnnexFetch:
    ref: AnnexRef
    content: AnnexContent
    raw_file: str | None


def build_bundle(
    document: DocumentMeta,
    body: ParsedBody,
    *,
    source_url: str,
    fetched_at: str,
    raw_file: str | None,
    article_labels: Iterable[str] = (),
    annexes: Iterable[AnnexFetch] = (),
    expect_headings: dict[str, str] | None = None,
    in_force_on: str | None = None,
    current_checked_on: str | None = None,
    note: str = BUNDLE_NOTE,
) -> tuple[SourceBundle, list[Missing], list[str]]:
    """파싱 결과 → SourceBundle. (bundle, 누락 목록, 경고 목록)

    in_force_on: 연혁 목록으로 이 버전이 current_checked_on 기준 시행 중임을 확인했으면 그 날짜.
    expect_headings: {'제75조': '건폐율'} 처럼 조 제목에 들어 있어야 할 말 (번호가 바뀌었을 때 잘못 고르지 않도록).
    """
    missing: list[Missing] = []
    warnings: list[str] = []
    articles: list[ArticleText] = []
    expect_headings = expect_headings or {}

    for label in article_labels:
        art = body.article(label)
        if art is None:
            missing.append(Missing(label, "본문에서 해당 조를 찾지 못함"))
            continue
        want = expect_headings.get(label)
        if want and (art.heading is None or want not in art.heading):
            missing.append(Missing(label, f"조 제목이 예상과 다름 (예상 포함어 {want!r}, 실제 {art.heading!r}) — 번호 재확인 필요"))
            continue
        warnings.extend(f"{label}: {w}" for w in art.warnings)
        articles.append(art.to_article_text())

    for af in annexes:
        ref, content = af.ref, af.content
        label = ref.label or ref.title_line
        if not content.has_text:
            missing.append(
                Missing(label, f"별표가 첨부파일로만 제공됨 ({content.availability}) — 전사하지 않음. 제목: {ref.title_line}")
            )
            continue
        articles.append(
            ArticleText(
                kind="annex",
                label=ref.label or ref.title_line,
                path=ref.path or f"annex-{ref.byl_seq}",
                heading=ref.heading,
                text=content.text or "",
                table_json=content.table_json,
            )
        )

    h = body.header
    problems = header_consistency(h, body.hidden)
    v_header: VerifyState = "confirmed" if h.complete and not problems else "needs_review"
    warnings.extend(f"표제: {p}" for p in problems)

    v_current: VerifyState = "needs_review"
    checked_on = None
    if in_force_on and h.effective_from and h.effective_from <= in_force_on:
        v_current = "confirmed"
        checked_on = current_checked_on or in_force_on

    version = VersionMeta(
        promulgation_number=h.promulgation_number,
        promulgation_date=h.promulgation_date,
        effective_from=h.effective_from,
        effective_to=None,
        external_version_id=body.seq,
        source_url=source_url,
        fetched_at=fetched_at,
        raw_format="html",
        raw_file=raw_file,
        v_source_access="confirmed",
        v_version_header=v_header,
        v_text_match="not_checked",
        v_current_version=v_current,
        current_checked_on=checked_on,
        note=note,
    )
    bundle = SourceBundle(document=document, version=version, articles=articles, is_example=False)
    return bundle, missing, warnings


def bundle_filename(key: str, effective_from: str | None) -> str:
    return f"{key}__{effective_from or 'unknown'}.json"


def bundle_json(bundle: SourceBundle) -> dict[str, Any]:
    return bundle.to_json()
