"""합성 대지별 배치 결과: 모든 대안의 불변식 + 대수의 타당성."""

from __future__ import annotations

import pytest
from shapely.geometry import MultiPolygon, box

from app.parking.engine import simulate_detailed
from app.parking.types import SimulationParams, StallSize
from tests.parking._helpers import (
    ALL_ANGLES,
    L_SHAPE,
    PENTAGON,
    assert_layout_valid,
    local_to_geojson,
    make_dims,
    rect,
    rotated_rect,
    rough_upper_bound,
    to_local,
)

DIMS = make_dims()

SITES = {
    "rect_20x40": rect(20, 40),
    "rect_50x60": rect(50, 60),
    "rotated_33": rotated_rect(30, 45, 33),
    "l_shape": L_SHAPE,
    "pentagon": PENTAGON,
    "strip_8x60": rect(8, 60),
}


def _run(site_local, dims=DIMS, **kw):
    params = SimulationParams(**kw)
    res, geoms = simulate_detailed(local_to_geojson(site_local), dims, params)
    assert len(res.alternatives) == len(geoms)
    return res, geoms, params


@pytest.fixture(scope="module")
def per_angle_results():
    """(대지, 각도) → (result, geoms, params). 각도 하나씩 따로 돌린다."""
    out = {}
    for name, poly in SITES.items():
        for angle in ALL_ANGLES:
            out[(name, angle)] = _run(poly, angles=[angle], max_alternatives=3)
    return out


@pytest.mark.parametrize("name", list(SITES))
@pytest.mark.parametrize("angle", ALL_ANGLES)
def test_invariants_every_alternative(per_angle_results, name, angle):
    res, geoms, params = per_angle_results[(name, angle)]
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert alt.parking_angle == angle
        assert_layout_valid(g, alt, DIMS, params)


@pytest.mark.parametrize("name", list(SITES))
def test_invariants_all_angles_default_params(name):
    res, geoms, params = _run(SITES[name])
    assert res.alternatives, res.constraint_reasons
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, DIMS, params)


def test_rect_50x60_counts_are_sensible(per_angle_results):
    res, geoms, _ = per_angle_results[("rect_50x60", "PERPENDICULAR_90")]
    best = res.alternatives[0]
    usable = best.metrics["usable_area_m2"]
    assert abs(usable - 49 * 59) < 0.01
    ub = rough_upper_bound(usable, "PERPENDICULAR_90", DIMS)
    assert best.stall_count > 60
    assert 0.8 * ub <= best.stall_count <= ub
    assert 45.0 <= best.metrics["efficiency_percent"] <= 65.0
    assert 20.0 <= best.metrics["area_per_stall_m2"] <= 30.0
    for angle in ALL_ANGLES:
        r = per_angle_results[("rect_50x60", angle)][0]
        n = r.alternatives[0].stall_count
        assert 0.6 * rough_upper_bound(usable, angle, DIMS) <= n <= rough_upper_bound(usable, angle, DIMS)
    # 직각주차가 가장 효율적
    counts = {a: per_angle_results[("rect_50x60", a)][0].alternatives[0].stall_count for a in ALL_ANGLES}
    assert counts["PERPENDICULAR_90"] == max(counts.values())


def test_rect_20x40_counts_are_sensible(per_angle_results):
    res = per_angle_results[("rect_20x40", "PERPENDICULAR_90")][0]
    best = res.alternatives[0]
    # 19 m 폭 → 양측 직각 모듈(5+6+5) 1개, 39 m 길이 → 열당 15대
    assert best.stall_count >= 28
    assert best.stall_count <= rough_upper_bound(best.metrics["usable_area_m2"], "PERPENDICULAR_90", DIMS)
    # 차로는 긴 변 방향(대략 남북)
    assert abs(best.orientation_deg - 90) < 2
    for angle in ALL_ANGLES:
        assert per_angle_results[("rect_20x40", angle)][0].alternatives[0].stall_count > 10


def test_rotation_invariance():
    """같은 직사각형을 33° 돌려도 대수는 (거의) 같고, 차로는 대지 변과 평행."""
    base = _run(rect(30, 45), angles=["PERPENDICULAR_90"])[0].alternatives[0]
    rot = _run(rotated_rect(30, 45, 33), angles=["PERPENDICULAR_90"])[0].alternatives[0]
    assert abs(base.stall_count - rot.stall_count) <= 1
    d = rot.orientation_deg % 90
    assert min(abs(d - 33), abs(d - 33 + 90), abs(d - 33 - 90)) < 0.01


def test_l_shape_and_pentagon_use_area(per_angle_results):
    for name in ("l_shape", "pentagon"):
        res = per_angle_results[(name, "PERPENDICULAR_90")][0]
        best = res.alternatives[0]
        assert best.metrics["efficiency_percent"] > 35
        for angle in ALL_ANGLES:
            assert per_angle_results[(name, angle)][0].alternatives, (name, angle)


def test_narrow_strip_only_parallel():
    res, geoms, params = _run(rect(8, 60))
    assert "LIMIT_SITE_WIDTH_NARROW" in res.constraint_reasons
    for a in ("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45"):
        assert f"LIMIT_SITE_WIDTH_NARROW:{a}" in res.constraint_reasons
    assert "LIMIT_SITE_WIDTH_NARROW:PARALLEL_0" not in res.constraint_reasons
    assert res.alternatives and {a.parking_angle for a in res.alternatives} == {"PARALLEL_0"}
    best = res.alternatives[0]
    assert best.is_recommended and best.stall_count >= 8  # 59 m / 6 m → 9대 (편측)
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, DIMS, params)


def test_tiny_site_no_layout():
    res, geoms, _ = _run(rect(6, 6))
    assert res.alternatives == [] and geoms == []
    assert "NO_FEASIBLE_LAYOUT" in res.constraint_reasons
    assert "LIMIT_SITE_WIDTH_NARROW" in res.constraint_reasons
    assert abs(res.site_area_m2 - 36.0) < 0.01


def test_multipolygon_uses_largest_part():
    big, small = box(0, 0, 30, 40), box(50, 0, 60, 10)
    res, geoms, params = _run(MultiPolygon([big, small]))
    assert "MULTIPART_SITE_LARGEST_USED" in res.constraint_reasons
    assert abs(res.site_area_m2 - 1300.0) < 0.01  # 전체 면적
    assert res.alternatives
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert "MULTIPART_SITE_LARGEST_USED" in alt.constraint_reasons
        assert abs(to_local(g.site).area - big.area) < 1e-6
        assert all(to_local(s).within(big.buffer(1e-6)) for s in g.stalls)
        assert_layout_valid(g, alt, DIMS, params)


def test_custom_dimensions_are_honoured():
    """엔진에 치수가 박혀 있지 않음: 전혀 다른 치수를 넣으면 그대로 그려진다."""
    dims = make_dims(
        stall_non_parallel={"general": StallSize(3.3, 5.6)},
        stall_parallel={"general": StallSize(2.3, 7.1)},
        aisle_width={
            "PERPENDICULAR_90": {"one_gate": 7.3, "two_plus_gates": 7.0},
            "ANGLE_60": {"one_gate": 6.1, "two_plus_gates": 5.0},
            "ANGLE_45": {"one_gate": 5.7, "two_plus_gates": 4.1},
            "PARALLEL_0": {"one_gate": 4.4, "two_plus_gates": 3.1},
        },
        gate_width_min_m=8.0,
    )
    res, geoms, params = _run(rect(50, 60), dims=dims, max_alternatives=4)
    assert len(res.alternatives) == 4
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, dims, params)
        assert g.driveway_width_m == 8.0  # max(차로 폭, 출입구 최소 너비)
        assert alt.layers["gate"]["properties"]["width_m"] == 8.0


@pytest.mark.parametrize("stall_type", ["general", "extended"])
def test_stall_type_and_two_gates(stall_type):
    res, geoms, params = _run(rect(50, 60), stall_type=stall_type, gate_count=2, max_alternatives=4)
    if stall_type == "extended":
        assert "DIMENSION_MISSING:PARALLEL_0" in res.constraint_reasons
        assert all(a.parking_angle != "PARALLEL_0" for a in res.alternatives)
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert alt.stall_type == stall_type
        assert alt.metrics["aisle_width_m"] == DIMS.aisle_width[alt.parking_angle]["two_plus_gates"]
        assert_layout_valid(g, alt, DIMS, params)
