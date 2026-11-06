"""
mock 서버 예시 필지 데이터셋 로더 (mockserver/fixtures/parcels.json).

모든 레코드는 is_example=true 인 가상 필지다. 법정동코드·법정동명만 실제 값이고
지번(9001~9201)·경계·도로명('…예시로')·건물·지정 내용은 지어낸 것이다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

from shapely.geometry import Point, shape
from shapely.geometry.base import BaseGeometry

__all__ = ["MockParcel", "MockDataset", "fixtures_path", "load_dataset", "load_parcels"]

_FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
_TOKEN_SPLIT_RE = re.compile(r"[\s,()]+")


def fixtures_path() -> Path:
    """예시 필지 JSON 경로 (mockserver/fixtures/parcels.json)."""
    return _FIXTURES_DIR / "parcels.json"


def _tokens(text: str) -> list[str]:
    return [t for t in _TOKEN_SPLIT_RE.split(text) if t]


@dataclass(frozen=True)
class MockParcel:
    record: dict[str, Any]
    geom: BaseGeometry = field(compare=False)

    # 자주 쓰는 속성
    @property
    def id(self) -> str:
        return self.record["id"]

    @property
    def pnu(self) -> str:
        return self.record["pnu"]

    @property
    def road_address(self) -> str:
        return self.record["road_address"]

    @property
    def jibun_address(self) -> str:
        return self.record["jibun_address"]

    @property
    def behavior(self) -> dict[str, str]:
        return self.record.get("behavior") or {}

    @property
    def is_suseong(self) -> bool:
        return self.pnu.startswith("27260")

    def matches(self, keyword: str) -> bool:
        """도로명주소 또는 지번주소에 검색어가 들어 있는지.

        글자 토큰은 부분 문자열로, 숫자가 든 토큰(건물번호·지번)은 토큰 단위로 비교한다
        ('범어예시로 12' 가 '범어예시로 120' 에 걸리지 않도록). '9002' 는 '9002-3' 에도 걸린다.
        """
        kw_tokens = _tokens(keyword)
        if not kw_tokens:
            return False
        for hay in (f"{self.road_address} ({self.record['emd']})", self.jibun_address):
            hay_tokens = _tokens(hay)
            ok = True
            for t in kw_tokens:
                if any(ch.isdigit() for ch in t):
                    if not any(h == t or h.startswith(t + "-") for h in hay_tokens):
                        ok = False
                        break
                elif t not in hay:
                    ok = False
                    break
            if ok:
                return True
        return False

    def summary(self) -> dict[str, Any]:
        r = self.record
        return {
            "id": r["id"],
            "pnu": r["pnu"],
            "is_example": r.get("is_example", True),
            "road_address": r["road_address"],
            "jibun_address": r["jibun_address"],
            "sido": r["sido"],
            "sigungu": r["sigungu"],
            "emd": r["emd"],
            "zone": r["zone"],
            "area_m2": r["land"]["area_m2"],
            "land_category": r["land"]["category"],
            "designations": [d["name"] for d in r["designations"]],
            "has_buildings": bool(r["buildings"]),
            "supported_region": self.is_suseong,
            "behavior": dict(self.behavior),
            "tags": list(r.get("tags") or []),
            "centroid": [round(self.geom.centroid.x, 7), round(self.geom.centroid.y, 7)],
        }


@dataclass
class MockDataset:
    meta: dict[str, Any]
    parcels: list[MockParcel]

    def __post_init__(self) -> None:
        self._by_pnu = {p.pnu: p for p in self.parcels}

    def by_pnu(self, pnu: str) -> MockParcel | None:
        return self._by_pnu.get(pnu)

    def by_pnu_prefix(self, prefix: str) -> list[MockParcel]:
        return [p for p in self.parcels if p.pnu.startswith(prefix)]

    def at_point(self, lng: float, lat: float) -> list[MockParcel]:
        pt = Point(lng, lat)
        return [p for p in self.parcels if p.geom.covers(pt)]

    def intersecting(self, geom: BaseGeometry) -> list[MockParcel]:
        return [p for p in self.parcels if p.geom.intersects(geom)]

    def search(self, keyword: str) -> list[MockParcel]:
        return [p for p in self.parcels if p.matches(keyword)]

    def region_names(self) -> set[str]:
        names: set[str] = set()
        for p in self.parcels:
            names.update({p.record["sido"], p.record["sigungu"]})
        return names


@lru_cache(maxsize=4)
def _load(path_str: str) -> MockDataset:
    doc = json.loads(Path(path_str).read_text(encoding="utf-8"))
    parcels = [MockParcel(record=rec, geom=shape(rec["geometry"])) for rec in doc["parcels"]]
    return MockDataset(meta=doc.get("_meta") or {}, parcels=parcels)


def load_dataset(path: str | Path | None = None) -> MockDataset:
    """데이터셋 로드(경로별 캐시)."""
    return _load(str(Path(path) if path else fixtures_path()))


def load_parcels(path: str | Path | None = None) -> list[dict[str, Any]]:
    """통합 시험용 요약 목록: pnu, 주소, 용도지역(zone), 면적, 특수 동작(behavior), tags 등."""
    return [p.summary() for p in load_dataset(path).parcels]
