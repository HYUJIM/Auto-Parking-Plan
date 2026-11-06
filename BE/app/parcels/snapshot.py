"""검토 엔진 입력: 필지 스냅샷 (외부 API 캐시에서 만든 불변 값)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class ParcelSnapshot:
    pnu: str
    road_address: str | None
    jibun_address: str
    jurisdiction_code: str  # 시·군·구 5자리 (PNU 앞 5자리)
    sido: str
    sigungu: str | None
    supported: bool
    land_area_m2: float | None
    land_category: str | None
    designations: list[dict[str, Any]] | None  # None = 조회 실패/미확인, [] = 지정 없음
    geometry: dict[str, Any] | None
    centroid: list[float] | None
    road_side: str | None = None
    official_land_price: int | None = None
    sources: list[dict[str, Any]] = field(default_factory=list)
    notices: list[dict[str, str]] = field(default_factory=list)  # PARCEL_PARTIAL, DESIGNATION_UNAVAILABLE
    buildings: list[dict[str, Any]] = field(default_factory=list)
    is_example: bool = False

    def to_result_parcel(self) -> dict[str, Any]:
        """review_result.schema 의 parcel 객체."""
        return {
            "pnu": self.pnu,
            "road_address": self.road_address,
            "jibun_address": self.jibun_address,
            "jurisdiction": {"sido": self.sido, "sigungu": self.sigungu, "supported": self.supported},
            "land_area_m2": self.land_area_m2,
            "land_category": self.land_category,
            "designations": None if self.designations is None else [
                {"kind": d["kind"], "name": d["name"], "conflict": d.get("conflict") or "unknown"} for d in self.designations],
            "location": {"centroid": self.centroid, "geometry": self.geometry},
            "sources": self.sources,
        }
