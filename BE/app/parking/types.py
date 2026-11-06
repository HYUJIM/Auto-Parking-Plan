"""
주차장 배치 시뮬레이션 자료형 (계약).

치수(주차구획·차로 폭)는 반드시 검토 엔진이 '승인된 규칙'에서 꺼내 넘긴다.
이 모듈은 DB·법규를 모르며, 넘겨받은 치수만으로 기하 배치를 계산한다.
좌표 입출력은 EPSG:4326 GeoJSON, 내부 계산은 EPSG:5179(UTM-K, m).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

ParkingAngle = Literal["PARALLEL_0", "ANGLE_45", "ANGLE_60", "PERPENDICULAR_90"]
StallType = Literal["general", "extended", "compact", "disabled"]


@dataclass(frozen=True)
class StallSize:
    width_m: float
    length_m: float


@dataclass
class ParkingDimensions:
    """승인 규칙에서 온 법정 최소 치수. 없는 값은 None (해당 형식은 배치하지 않음)."""

    # 평행주차 외 형식(직각·60도·45도)의 구획
    stall_non_parallel: dict[StallType, StallSize] = field(default_factory=dict)
    # 평행주차 형식의 구획
    stall_parallel: dict[StallType, StallSize] = field(default_factory=dict)
    # 차로 폭: {angle: {"one_gate": m, "two_plus_gates": m}}
    aisle_width: dict[ParkingAngle, dict[str, float]] = field(default_factory=dict)
    gate_width_min_m: float | None = None  # 출입구 최소 너비
    # 각 치수의 근거 규칙 id (응답 근거 표시용)
    rule_ids: list[str] = field(default_factory=list)


@dataclass
class SimulationParams:
    angles: list[ParkingAngle] = field(default_factory=lambda: ["PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0"])
    stall_type: StallType = "general"
    setback_m: float = 0.5  # 대지 경계에서 띄울 거리 (사용자 보정 가능)
    gate_point: tuple[float, float] | None = None  # [lng, lat]; None 이면 자동 선택
    gate_count: Literal[1, 2] = 1  # 출입구 수 → 차로 폭 기준 선택
    max_alternatives: int = 3
    orientation_step_deg: float = 5.0


@dataclass
class Alternative:
    alt_code: str  # 'ALT_A' ...
    parking_angle: ParkingAngle
    stall_type: StallType
    orientation_deg: float
    stall_count: int
    layers: dict[str, Any]  # {"stalls": FeatureCollection, "aisles": FeatureCollection, "gate": Feature, "usable_area": Feature}
    metrics: dict[str, Any]  # site_area_m2, usable_area_m2, parking_area_m2, efficiency_percent, area_per_stall_m2, aisle_width_m, stall_width_m, stall_length_m
    constraint_reasons: list[str] = field(default_factory=list)
    is_recommended: bool = False


@dataclass
class SimulationResult:
    alternatives: list[Alternative]
    constraint_reasons: list[str]  # 전체 차원의 제약 (LIMIT_SITE_WIDTH_NARROW, NO_FEASIBLE_LAYOUT, ...)
    site_area_m2: float | None
    crs_internal: str = "EPSG:5179"
