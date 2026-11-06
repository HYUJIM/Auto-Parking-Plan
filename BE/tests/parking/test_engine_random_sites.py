"""고정 시드 무작위 대지(볼록·별모양·곡선 경계·홈 파인 사각형)에서 모든 불변식 확인."""

from __future__ import annotations

import math
import random

import pytest
from shapely import affinity
from shapely.geometry import Polygon, box

from app.parking.engine import simulate_detailed
from app.parking.types import SimulationParams
from tests.parking._helpers import assert_layout_valid, local_point_lnglat, local_to_geojson, make_dims

DIMS = make_dims()


def _random_site(rng: random.Random) -> Polygon:
    kind = rng.choice(["star", "convex", "curvy", "notched"])
    radius = rng.uniform(15, 55)
    if kind == "notched":
        w, h = rng.uniform(15, 80), rng.uniform(15, 80)
        p = affinity.rotate(box(0, 0, w, h), rng.uniform(0, 180), origin=(0, 0))
        return p.difference(affinity.rotate(box(0, 0, w * 0.4, h * 0.4), rng.uniform(0, 180), origin=(0, 0)))
    n = rng.randint(40, 70) if kind == "curvy" else rng.randint(4, 12)
    angs = sorted(rng.uniform(0, 2 * math.pi) for _ in range(n))
    pts = []
    for a in angs:
        if kind == "curvy":
            r = radius * (0.8 + 0.2 * math.sin(3 * a))  # 짧은 변이 많은 곡선 경계
        elif kind == "star":
            r = radius * rng.uniform(0.55, 1.0)
        else:
            r = radius
        pts.append((r * math.cos(a), r * math.sin(a)))
    p = Polygon(pts)
    return p.convex_hull if kind == "convex" else p.buffer(0)


def _cases(seed: int, n: int):
    rng = random.Random(seed)
    out = []
    while len(out) < n:
        poly = _random_site(rng)
        if poly.is_empty or poly.geom_type != "Polygon" or poly.area < 300:
            continue
        kw: dict = {"setback_m": rng.choice([0.0, 0.5, 1.0])}
        if rng.random() < 0.5:
            b = poly.bounds
            kw["gate_point"] = local_point_lnglat(rng.uniform(b[0] - 20, b[2] + 20), rng.uniform(b[1] - 20, b[3] + 20))
        if rng.random() < 0.3:
            kw["gate_count"] = 2
        out.append((poly, kw))
    return out


@pytest.mark.parametrize("idx,case", list(enumerate(_cases(20261106, 10))))
def test_random_site_invariants(idx, case):
    poly, kw = case
    params = SimulationParams(**kw)
    res, geoms = simulate_detailed(local_to_geojson(poly), DIMS, params)
    assert res.site_area_m2 is not None and res.site_area_m2 > 0
    if poly.area > 1500:
        assert res.alternatives, res.constraint_reasons
    for alt, g in zip(res.alternatives, geoms, strict=True):
        assert_layout_valid(g, alt, DIMS, params)
