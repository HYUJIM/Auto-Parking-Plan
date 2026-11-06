"""
외부 공공 API 클라이언트가 돌려주는 공통 자료형 (계약).

원칙
  * None = 미확인. 규제 없음·0·허용으로 해석하지 않는다.
  * 모든 결과는 출처(source)와 조회 시각(fetched_at, timezone-aware)을 가진다.
  * 좌표는 항상 EPSG:4326 [경도, 위도] GeoJSON.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Literal

DesignationKind = Literal["zoning", "district", "zone", "other"]
Conflict = Literal["included", "partially_included", "adjacent", "unknown"]


class UpstreamError(Exception):
    """외부 API 장애·시간 초과·인증 오류. API 계층에서 UPSTREAM_UNAVAILABLE(503) 또는 PARCEL_PARTIAL 로 바뀐다."""

    def __init__(self, service: str, message: str, *, retriable: bool = True, status_code: int | None = None):
        super().__init__(f"[{service}] {message}")
        self.service = service
        self.message = message
        self.retriable = retriable
        self.status_code = status_code


@dataclass
class AddressCandidate:
    """도로명주소 검색 API 결과 1건."""

    road_address: str | None
    jibun_address: str
    pnu: str  # 19자리: 법정동코드10 + 산여부1(1=일반, 2=산) + 본번4 + 부번4
    adm_code: str  # 법정동코드 10자리
    sido: str
    sigungu: str | None
    emd: str | None
    zip_code: str | None = None
    building_name: str | None = None
    source: str = "juso.go.kr"
    fetched_at: datetime | None = None


@dataclass
class CadastralParcel:
    """연속지적도(필지 경계) 조회 결과."""

    pnu: str
    jibun: str | None  # '123-4대' 등 원천 표기
    address: str | None
    geometry: dict[str, Any] | None  # GeoJSON MultiPolygon, EPSG:4326
    official_land_price: int | None  # 개별공시지가(원/㎡), 원천에 있으면
    source: str
    fetched_at: datetime
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class LandCharacteristics:
    """토지특성/토지임야 정보: 면적·지목·도로접면 등."""

    pnu: str
    land_area_m2: float | None
    land_category: str | None  # 지목명 '대', '전', '잡종지' ...
    road_side: str | None  # 도로접면 '세로한면(가)' 등 원천 표기
    official_land_price: int | None
    source: str
    fetched_at: datetime
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class LandUseDesignation:
    """토지이용계획 속성 1건 (용도지역·지구·구역 등)."""

    kind: DesignationKind
    name: str  # '제2종일반주거지역'
    code: str | None  # 'UQA122'
    conflict: Conflict
    basis_law: str | None  # 원천이 표기한 근거 법령명
    source: str
    fetched_at: datetime
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class BuildingInfo:
    """건축물대장 표제부 1건 (선택 정보)."""

    main_use: str | None
    gross_floor_area: float | None
    building_area: float | None
    approval_date: str | None  # 'YYYY-MM-DD'
    source: str
    fetched_at: datetime
    raw: dict[str, Any] = field(default_factory=dict)
