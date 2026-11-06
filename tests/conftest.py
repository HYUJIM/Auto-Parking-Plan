"""공통 테스트 도구: 메모리 DB, 가짜 외부 API, 예시(가짜 문구) 법규 데이터."""
from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

from app.config import Settings
from app.db.models import Jurisdiction
from app.db.session import Database
from app.external.types import CadastralParcel, LandCharacteristics, LandUseDesignation, UpstreamError
from app.legal.parsed import ArticleText, DocumentMeta, SourceBundle, VersionMeta

NOW = datetime(2026, 11, 6, 3, 0, tzinfo=UTC)


def square(lng: float, lat: float, dx: float = 0.0004, dy: float = 0.0003) -> dict:
    return {"type": "MultiPolygon", "coordinates": [[[[lng, lat], [lng + dx, lat], [lng + dx, lat + dy], [lng, lat + dy], [lng, lat]]]]}


class StubVWorld:
    """VWorldClient 와 같은 메서드 이름을 가진 테스트용 가짜."""

    def __init__(self, parcels: dict[str, dict]):
        self.parcels = parcels

    def get_parcel(self, pnu):
        p = self.parcels.get(pnu)
        if p is None:
            return None
        return CadastralParcel(pnu=pnu, jibun=p.get("jibun"), address=p["address"], geometry=p["geometry"],
                               official_land_price=None, source="stub:cadastral", fetched_at=NOW, raw={"is_example": True})

    def get_parcel_at(self, lng, lat):
        return None

    def get_land_use(self, pnu):
        p = self.parcels[pnu]
        if p.get("land_use_error"):
            raise UpstreamError("vworld:getLandUseAttr", "server error")
        return [LandUseDesignation(kind=k, name=n, code=None, conflict=c, basis_law=None, source="stub:landuse", fetched_at=NOW)
                for k, n, c in p["designations"]]

    def get_land_characteristics(self, pnu):
        p = self.parcels[pnu]
        return LandCharacteristics(pnu=pnu, land_area_m2=p.get("area"), land_category=p.get("category", "대"), road_side=None,
                                   official_land_price=None, source="stub:landchar", fetched_at=NOW)


class StubJuso:
    def __init__(self, cands):
        self.cands = cands

    def search(self, keyword, *, page=1, per_page=10):
        return [c for c in self.cands if keyword in c.jibun_address or keyword in (c.road_address or "")]


EX_NOTE = "[예시 조문 — 실제 법령 원문 아님] "


def example_bundles() -> list[SourceBundle]:
    """구조 시험용 가짜 문구 (실제 법령 원문 아님). 국가 법령(L1) 1개 + 조례(L3) 1개 + 주차 기준(L1) 1개."""
    fetched = "2026-11-06T09:00:00+09:00"
    decree = SourceBundle(
        document=DocumentMeta(key="ex.decree", layer="L1_NATIONAL", source_type="decree", official_title="예시 시행령",
                              jurisdiction_code="00", external_system="example", external_id="ex-decree"),
        version=VersionMeta(promulgation_number="예시 제1호", promulgation_date="2026-01-01", effective_from="2026-01-01",
                            effective_to=None, external_version_id=None, source_url="https://example.invalid/decree",
                            fetched_at=fetched, raw_format="json", raw_file=None),
        articles=[ArticleText(kind="article", label="제84조", path="art84", heading="용도지역안에서의 건폐율",
                              text=EX_NOTE + "제84조(용도지역안에서의 건폐율) ① 건폐율은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                   "1. 제1종일반주거지역 : 60퍼센트 이하\n2. 제2종일반주거지역 : 60퍼센트 이하"),
                  ArticleText(kind="article", label="제85조", path="art85", heading="용도지역 안에서의 용적률",
                              text=EX_NOTE + "제85조(용도지역 안에서의 용적률) ① 용적률은 다음 각 호의 범위에서 조례로 정하는 비율을 초과해서는 안 된다.\n"
                                   "1. 제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하\n2. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하")],
        is_example=True)
    ordin = SourceBundle(
        document=DocumentMeta(key="ex.ordin", layer="L3_LOCAL", source_type="ordinance", official_title="예시 도시계획 조례",
                              jurisdiction_code="27", external_system="example", external_id="ex-ordin",
                              aliases={"영": "ex.decree"}),
        version=VersionMeta(promulgation_number="예시 조례 제1호", promulgation_date="2026-02-01", effective_from="2026-02-01",
                            effective_to=None, external_version_id=None, source_url="https://example.invalid/ordin",
                            fetched_at=fetched, raw_format="json", raw_file=None),
        articles=[ArticleText(kind="article", label="제50조", path="art50", heading="건폐율",
                              text=EX_NOTE + "제50조(건폐율) ① 영 제84조제1항에 따라 용도지역별 건폐율은 다음 각 호와 같다.\n"
                                   "1. 제2종일반주거지역 : 60퍼센트 이하"),
                  ArticleText(kind="article", label="제51조", path="art51", heading="용적률",
                              text=EX_NOTE + "제51조(용적률) ① 영 제85조제1항에 따라 용도지역별 용적률은 다음 각 호와 같다.\n"
                                   "1. 제2종일반주거지역 : 220퍼센트 이하")],
        is_example=True)
    parking = SourceBundle(
        document=DocumentMeta(key="ex.rule", layer="L1_NATIONAL", source_type="rule", official_title="예시 시행규칙",
                              jurisdiction_code="00", external_system="example", external_id="ex-rule"),
        version=VersionMeta(promulgation_number="예시 규칙 제1호", promulgation_date="2026-01-01", effective_from="2026-01-01",
                            effective_to=None, external_version_id=None, source_url="https://example.invalid/rule",
                            fetched_at=fetched, raw_format="json", raw_file=None),
        articles=[ArticleText(kind="article", label="제3조", path="art3", heading="주차구획",
                              text=EX_NOTE + "제3조(주차구획) ① 주차단위구획은 다음 각 호와 같다.\n"
                                   "1. 평행주차형식의 경우: 일반형 너비 2.0미터 이상, 길이 6.0미터 이상\n"
                                   "2. 평행주차형식 외의 경우: 일반형 너비 2.5미터 이상, 길이 5.0미터 이상"),
                  ArticleText(kind="article", label="제6조", path="art6", heading="차로",
                              text=EX_NOTE + "제6조(차로) ① 차로의 너비는 다음 각 호와 같다.\n"
                                   "1. 직각주차: 출입구가 2개 이상인 경우 6.0미터, 출입구가 1개인 경우 6.0미터\n"
                                   "2. 평행주차: 출입구가 2개 이상인 경우 3.3미터, 출입구가 1개인 경우 5.0미터\n"
                                   "3. 출입구의 너비는 3.5미터 이상으로 하여야 한다.")],
        is_example=True)
    return [decree, ordin, parking]


@pytest.fixture
def db() -> Database:
    d = Database("sqlite://")
    d.create_all()
    with d.session() as s:
        s.add(Jurisdiction(code="00", name="전국", level=0, parent_code=None, is_supported=True))
        s.add(Jurisdiction(code="27", name="대구광역시", level=1, parent_code="00", is_supported=False))
        s.add(Jurisdiction(code="27260", name="대구광역시 수성구", level=2, parent_code="27", is_supported=True))
    return d


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(database_url="sqlite://", blob_local_dir=tmp_path / "blobs", llm_provider="fake", review_executor="inline",
                    admin_token="test-admin-token", app_env="test")
