"""입력 처리·제약 사유·출입구·대안 선택·결정성·성능."""

from __future__ import annotations

import dataclasses
import json
import time

import pytest
from shapely.geometry import Point

from app.parking.engine import simulate, simulate_detailed
from app.parking.types import ParkingDimensions, SimulationParams, SimulationResult, StallSize
from tests.parking._helpers import (
    ALL_ANGLES,
    IRREGULAR_HEX,
    PENTAGON,
    X0,
    Y0,
    assert_layout_valid,
    local_point_lnglat,
    local_to_geojson,
    make_dims,
    rect,
    scaled_to_area,
)

DIMS = make_dims()


def _dump(res: SimulationResult) -> str:
    return json.dumps(dataclasses.asdict(res), sort_keys=True, ensure_ascii=False)


# --- 입력 기하 -------------------------------------------------------------
@pytest.mark.parametrize(
    "bad",
    [
        {"type": "Point", "coordinates": [128.63, 35.85]},
        {"type": "Polygon", "coordinates": []},
        {"type": "Polygon", "coordinates": [[[128.63, 35.85], [128.63, 35.85], [128.63, 35.85], [128.63, 35.85]]]},
        {
            "type": "Polygon",
            "coordinates": [[[35.85, 128.63], [35.851, 128.63], [35.851, 128.631], [35.85, 128.63]]],
        },  # 위경도 뒤바뀜
        {"foo": "bar"},
    ],
)
def test_invalid_site_geometry(bad):
    res = simulate(bad, DIMS, SimulationParams())
    assert res.alternatives == []
    assert res.constraint_reasons == ["SITE_GEOMETRY_INVALID"]
    assert res.site_area_m2 is None
    assert res.crs_internal == "EPSG:5179"


def test_feature_input_and_default_params():
    feat = {"type": "Feature", "geometry": local_to_geojson(rect(20, 40)), "properties": {"pnu": "x"}}
    res = simulate(feat, DIMS)
    assert res.alternatives and abs(res.site_area_m2 - 800.0) < 0.01


def test_self_intersecting_site_is_repaired_or_rejected():
    # 나비넥타이 모양 → make_valid 로 두 삼각형 → 큰 쪽 사용
    lng0, lat0 = local_point_lnglat(0, 0)
    lng1, lat1 = local_point_lnglat(60, 60)
    bow = {"type": "Polygon", "coordinates": [[[lng0, lat0], [lng1, lat1], [lng1, lat0], [lng0, lat1], [lng0, lat0]]]}
    res = simulate(bow, DIMS, SimulationParams())
    assert "SITE_GEOMETRY_REPAIRED" in res.constraint_reasons
    assert "SITE_GEOMETRY_INVALID" not in res.constraint_reasons


# --- 치수 누락 -------------------------------------------------------------
def test_missing_dimension_for_one_angle():
    aisles = dict(DIMS.aisle_width)
    del aisles["ANGLE_45"]
    dims = make_dims(aisle_width=aisles)
    res, geoms = simulate_detailed(local_to_geojson(rect(50, 60)), dims, SimulationParams(max_alternatives=4))
    assert "DIMENSION_MISSING:ANGLE_45" in res.constraint_reasons
    assert all(a.parking_angle != "ANGLE_45" for a in res.alternatives)
    assert {a.parking_angle for a in res.alternatives} == {"PERPENDICULAR_90", "ANGLE_60", "PARALLEL_0"}
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, dims, SimulationParams(max_alternatives=4))


def test_missing_gate_key_and_stall_type():
    dims = make_dims(aisle_width={"PERPENDICULAR_90": {"one_gate": 6.0}})  # two_plus_gates 없음
    res = simulate(local_to_geojson(rect(50, 60)), dims, SimulationParams(gate_count=2))
    assert res.alternatives == []
    for a in ALL_ANGLES:
        assert f"DIMENSION_MISSING:{a}" in res.constraint_reasons
    assert "NO_FEASIBLE_LAYOUT" in res.constraint_reasons

    res = simulate(local_to_geojson(rect(50, 60)), DIMS, SimulationParams(stall_type="disabled"))
    assert res.alternatives == [] and "NO_FEASIBLE_LAYOUT" in res.constraint_reasons
    assert all(f"DIMENSION_MISSING:{a}" in res.constraint_reasons for a in ALL_ANGLES)


def test_empty_dimensions_and_bad_values():
    res = simulate(local_to_geojson(rect(50, 60)), ParkingDimensions(), SimulationParams())
    assert res.alternatives == [] and "NO_FEASIBLE_LAYOUT" in res.constraint_reasons
    dims = make_dims(stall_non_parallel={"general": StallSize(0.0, 5.0)})  # 0 폭 = 없는 값
    res = simulate(local_to_geojson(rect(50, 60)), dims, SimulationParams(angles=["PERPENDICULAR_90"]))
    assert res.constraint_reasons[:1] == ["DIMENSION_MISSING:PERPENDICULAR_90"]


def test_missing_gate_width_uses_aisle_width():
    dims = make_dims(gate_width_min_m=None)
    params = SimulationParams(angles=["PARALLEL_0"], gate_count=2)
    res, geoms = simulate_detailed(local_to_geojson(rect(30, 40)), dims, params)
    alt, g = res.alternatives[0], geoms[0]
    assert g.driveway_width_m == dims.aisle_width["PARALLEL_0"]["two_plus_gates"]
    assert_layout_valid(g, alt, dims, params)


# --- 출입구 ---------------------------------------------------------------
def test_gate_snapped_when_far():
    lng, lat = local_point_lnglat(25, -30)  # 대지(50×60) 남쪽 30 m 밖
    params = SimulationParams(gate_point=(lng, lat))
    res, geoms = simulate_detailed(local_to_geojson(rect(50, 60)), DIMS, params)
    assert "GATE_SNAPPED" in res.constraint_reasons
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert "GATE_SNAPPED" in alt.constraint_reasons
        assert g.gate_point.distance(Point(X0 + 25, Y0)) < 0.5  # 남쪽 변의 최근접점
        assert_layout_valid(g, alt, DIMS, params)


def test_gate_near_boundary_not_flagged():
    lng, lat = local_point_lnglat(53, 30)  # 동쪽 변에서 3 m 밖
    params = SimulationParams(gate_point=(lng, lat))
    res, geoms = simulate_detailed(local_to_geojson(rect(50, 60)), DIMS, params)
    assert "GATE_SNAPPED" not in res.constraint_reasons
    assert res.alternatives
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert g.gate_point.distance(Point(X0 + 50, Y0 + 30)) < g.driveway_width_m
        assert_layout_valid(g, alt, DIMS, params)


def test_gate_at_corner_is_moved_along_edge():
    lng, lat = local_point_lnglat(0.2, 0.1)  # 모서리 바로 옆 → 개구부가 변 안에 들도록 이동
    params = SimulationParams(gate_point=(lng, lat))
    res, geoms = simulate_detailed(local_to_geojson(rect(50, 60)), DIMS, params)
    assert res.alternatives and "GATE_SNAPPED" not in res.constraint_reasons
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, DIMS, params)


def test_unreachable_gate_falls_back_to_auto_gate():
    """좁은 목(2 m)으로만 이어진 작은 땅 쪽에 출입구를 지정 → 진입로 불가 → 자동 출입구 + 표시."""
    from shapely.geometry import box
    from shapely.ops import unary_union

    dumbbell = unary_union([box(0, 0, 40, 40), box(39, 19, 51, 21), box(50, 15, 60, 25)])
    params = SimulationParams(gate_point=local_point_lnglat(60, 20))
    res, geoms = simulate_detailed(local_to_geojson(dumbbell), DIMS, params)
    assert res.alternatives
    assert "GATE_UNREACHABLE" in res.constraint_reasons
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert "GATE_UNREACHABLE" in alt.constraint_reasons
        assert "GATE_SNAPPED" in alt.constraint_reasons  # 실제 출입구가 지정점에서 15 m 이상 떨어짐
        assert g.gate_point.x - X0 <= 40.5  # 큰 땅 쪽 경계
        assert_layout_valid(g, alt, DIMS, params)


def test_invalid_gate_point_falls_back_to_auto():
    res = simulate(local_to_geojson(rect(50, 60)), DIMS, SimulationParams(gate_point=(float("nan"), 35.0)))
    assert "GATE_POINT_INVALID" in res.constraint_reasons and res.alternatives


def test_auto_gate_costs_few_stalls():
    """자동 출입구는 구획 손실이 거의 없는 곳(차로 끝)을 고른다."""
    res, geoms = simulate_detailed(local_to_geojson(rect(20, 40)), DIMS, SimulationParams(angles=["PERPENDICULAR_90"]))
    # 19 m 폭 → 양측 1모듈, 39 m / 2.5 = 15대 × 2열
    assert res.alternatives[0].stall_count == 30


# --- 대안 ------------------------------------------------------------------
def test_alternatives_ordering_and_recommendation():
    res = simulate(local_to_geojson(PENTAGON), DIMS, SimulationParams(max_alternatives=3))
    alts = res.alternatives
    assert [a.alt_code for a in alts] == ["ALT_A", "ALT_B", "ALT_C"]
    assert len({a.parking_angle for a in alts}) == 3  # 서로 다른 형식 우선
    counts = [a.stall_count for a in alts]
    assert counts == sorted(counts, reverse=True)
    assert [a.is_recommended for a in alts] == [True, False, False]


def test_alternatives_same_angle_use_different_orientations():
    res = simulate(
        local_to_geojson(rect(50, 60)), DIMS, SimulationParams(angles=["PERPENDICULAR_90"], max_alternatives=3)
    )
    alts = res.alternatives
    assert 2 <= len(alts) <= 3
    oris = [a.orientation_deg for a in alts]
    for i in range(len(oris)):
        for j in range(i + 1, len(oris)):
            d = abs(oris[i] - oris[j]) % 180
            assert min(d, 180 - d) >= 10
    assert alts[0].is_recommended and sum(a.is_recommended for a in alts) == 1


def test_max_alternatives_respected():
    res = simulate(local_to_geojson(rect(50, 60)), DIMS, SimulationParams(max_alternatives=1))
    assert len(res.alternatives) == 1 and res.alternatives[0].parking_angle == "PERPENDICULAR_90"
    res = simulate(local_to_geojson(rect(50, 60)), DIMS, SimulationParams(max_alternatives=10))
    assert len(res.alternatives) >= 4


def test_setback_changes_usable_area():
    a = simulate(
        local_to_geojson(rect(50, 60)), DIMS, SimulationParams(setback_m=0.0, max_alternatives=1)
    ).alternatives[0]
    b = simulate(
        local_to_geojson(rect(50, 60)), DIMS, SimulationParams(setback_m=2.0, max_alternatives=1)
    ).alternatives[0]
    assert abs(a.metrics["usable_area_m2"] - 3000) < 0.01
    assert abs(b.metrics["usable_area_m2"] - 46 * 56) < 0.01
    assert a.stall_count >= b.stall_count


def test_setback_zero_still_valid():
    params = SimulationParams(setback_m=0.0)
    res, geoms = simulate_detailed(local_to_geojson(PENTAGON), DIMS, params)
    assert res.alternatives
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, DIMS, params)


# --- 결정성·성능 -----------------------------------------------------------
def test_deterministic():
    site = local_to_geojson(PENTAGON)
    params = SimulationParams(gate_point=local_point_lnglat(70, 30))
    a = _dump(simulate(site, DIMS, params))
    b = _dump(simulate(site, DIMS, params))
    assert a == b
    # 입력 객체를 바꾸지 않는다
    site2 = json.loads(json.dumps(site))
    before = json.dumps(site2)
    simulate(site2, DIMS, params)
    assert json.dumps(site2) == before


def test_result_is_json_serialisable():
    res = simulate(local_to_geojson(rect(50, 60)), DIMS, SimulationParams())
    json.dumps(dataclasses.asdict(res))


def test_performance_irregular_3000m2():
    site = local_to_geojson(scaled_to_area(IRREGULAR_HEX, 3000.0))
    simulate(local_to_geojson(rect(10, 10)), DIMS, SimulationParams())  # 변환기 초기화 제외
    t0 = time.perf_counter()
    res = simulate(site, DIMS, SimulationParams())
    dt = time.perf_counter() - t0
    assert res.alternatives
    assert dt < 5.0, f"simulate took {dt:.2f}s (target < 2 s on a laptop)"
