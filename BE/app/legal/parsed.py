"""
법규 원문 수집 결과의 공통 자료형 (계약).

수집기(국가법령정보 Open API XML, 또는 개발용 law.go.kr HTML)는 원문을 받아
SourceBundle 하나를 만든다. 적재기(app.legal.ingest)는 SourceBundle 을 받아
source_document / document_version / provision 행을 만든다.

조문 구조(조·항·호·목)는 AI 가 아니라 결정적 파서(app.legal.structure)가 만든다.
수집기는 '조 단위 원문 전체(text)'만 정확히 넘기면 되고, 하위 분해는 선택이다.

JSON 직렬화 형식: data/legal_sources/*.json  (format = "app-legal-source@1")
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal

Layer = Literal["L1_NATIONAL", "L2_LAND_USE", "L3_LOCAL", "L4_SPECIAL_ZONE"]
SourceType = Literal["law", "decree", "rule", "ordinance", "local_rule", "notice", "plan"]
VerifyState = Literal["not_checked", "confirmed", "needs_review"]
BUNDLE_FORMAT = "app-legal-source@1"


@dataclass
class DocumentMeta:
    key: str  # 저장소 내부 키 (예: 'kr.decree.nkpa' ) — 다른 번들이 parent_key 로 참조
    layer: Layer
    source_type: SourceType
    official_title: str  # 공식 제명
    jurisdiction_code: str  # '00' 전국, '27' 대구광역시, '27260' 수성구
    external_system: str  # 'law.go.kr'
    external_id: str | None  # 법령ID / 자치법규ID
    short_title: str | None = None
    parent_key: str | None = None  # 시행령 → 법률
    homepage_url: str | None = None
    # 조례 본문의 '법', '영', '규칙' 같은 약칭이 가리키는 문서 key (위임 관계 해석용)
    aliases: dict[str, str] = field(default_factory=dict)


@dataclass
class VersionMeta:
    promulgation_number: str | None  # '대통령령 제35000호'
    promulgation_date: str | None  # 'YYYY-MM-DD'
    effective_from: str | None  # 시행일. 미확인이면 None (다운로드일로 대체 금지)
    effective_to: str | None
    external_version_id: str | None  # MST / lsiSeq / ordinSeq
    source_url: str
    fetched_at: str  # ISO8601 with timezone
    raw_format: Literal["xml", "html", "pdf", "json"]
    raw_file: str | None  # data/legal_sources/ 기준 상대경로 (원문 그대로 보관한 파일)
    v_source_access: VerifyState = "not_checked"
    v_version_header: VerifyState = "not_checked"
    v_text_match: VerifyState = "not_checked"
    v_current_version: VerifyState = "not_checked"
    current_checked_on: str | None = None
    note: str | None = None


@dataclass
class ArticleText:
    """조(article) 또는 별표(annex) 하나의 원문.

    kind='article': label='제84조', heading='용도지역안에서의 건폐율', text=조 전체 원문(항·호 포함, 원문 그대로)
    kind='annex'  : label='별표 5', heading='제2종일반주거지역안에서 건축할 수 있는 건축물', text=별표 전체 텍스트
    path 는 버전 간 같은 조문을 맞추는 키: 'art84', 'art84-2'(제84조의2), 'annex5', 'annex5-2'
    """

    kind: Literal["article", "annex", "addendum"]
    label: str
    path: str
    heading: str | None
    text: str
    table_json: Any | None = None  # 별표가 표이면 [[셀,...],...] (선택)
    effective_from: str | None = None  # 조문 단위 시행일이 문서와 다를 때만


@dataclass
class SourceBundle:
    document: DocumentMeta
    version: VersionMeta
    articles: list[ArticleText]
    format: str = BUNDLE_FORMAT
    is_example: bool = False  # 구조 시험용 가짜 문구면 True

    def to_json(self) -> dict[str, Any]:
        return asdict(self)

    @staticmethod
    def from_json(d: dict[str, Any]) -> SourceBundle:
        if d.get("format") != BUNDLE_FORMAT:
            raise ValueError(f"unknown bundle format: {d.get('format')}")
        return SourceBundle(
            document=DocumentMeta(**d["document"]),
            version=VersionMeta(**d["version"]),
            articles=[ArticleText(**a) for a in d["articles"]],
            is_example=bool(d.get("is_example", False)),
        )
