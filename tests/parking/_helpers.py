"""주차 배치 테스트 공용: 합성 대지(대구 부근)와 배치 불변식 검사.

주의: 아래 치수는 '테스트 입력'일 뿐이다. 엔진은 법정 치수를 ParkingDimensions 로만 받는다.
"""

from __future__ import annotations

import math
from typing import Any

import shapely
from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box, mapping, shape
from shapely.geometry.base import BaseGeometry

from app.parking import geo
from app.parking.engine import LayoutGeometry
from app.parking.types import Alternative, ParkingDimensions, SimulationParams, StallSize

# 대구 부근 기준점 (EPSG:5179 로 환산해 m 단위로 대지를 만든 뒤 EPSG:4326 으로 되돌린다)
ORIGIN_LNG, ORIGIN_LAT = 128.63, 35.85
X0, Y0 = geo.lnglat_to_utmk(ORIGIN_LNG, ORIGIN_LAT)

TOL = 1e-6  # m, EPSG:5179 내부 기하 허용오차
ALL_ANGLES = ["PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0"]
THETA = {"PERPENDICULAR_90": 90.0, "ANGLE_60": 60.0, "ANGLE_45": 45.0, "PARALLEL_0": 0.0}


def make_dims(**overrides: Any) -> ParkingDimensions:
    """테스트 입력 치수 (법규 값 아님)."""
    kw: dict[str, Any] = dict(
        stall_non_parallel={"general": StallSize(2.5, 5.0), "extended": StallSize(2.6, 5.2)},
        stall_parallel={"general": StallSize(2.0, 6.0)},
        aisle_width={
            "PERPENDICULAR_90": {"one_gate": 6.0, "two_plus_gates": 6.0},
            "ANGLE_60": {"one_gate": 5.5, "two_plus_gates": 4.5},
            "ANGLE_45": {"one_gate": 5.0, "two_plus_gates": 3.5},
            "PARALLEL_0": {"one_gate": 5.0, "two_plus_gates": 3.3},
        },
        gate_width_min_m=3.5,
        rule_ids=["TEST-RULE"],
    )
    kw.update(overrides)
    return ParkingDimensions(**kw)


def local_to_geojson(geom_local: BaseGeometry) -> dict:
    """기준점 기준 m 좌표 다각형 → EPSG:4326 GeoJSON geometry."""
    return mapping(geo.to_wgs84(affinity.translate(geom_local, X0, Y0)))


def local_point_lnglat(x: float, y: float) -> tuple[float, float]:
    return geo.utmk_to_lnglat(X0 + x, Y0 + y)


def to_local(geom_abs: BaseGeometry) -> BaseGeometry:
    return affinity.translate(geom_abs, -X0, -Y0)


# --- 합성 대지 (m) --------------------------------------------------------
def rect(w: float, h: float) -> Polygon:
    return box(0, 0, w, h)


def rotated_rect(w: float, h: float, deg: float) -> Polygon:
    return affinity.rotate(box(0, 0, w, h), deg, origin=(0, 0))


L_SHAPE = Polygon([(0, 0), (50, 0), (50, 20), (20, 20), (20, 45), (0, 45)])
PENTAGON = Polygon([(0, 0), (60, -5), (70, 30), (30, 55), (-5, 35)])
IRREGULAR_HEX = Polygon([(0, 0), (62, -4), (70, 25), (48, 52), (12, 47), (-6, 20)])


def scaled_to_area(poly: Polygon, area: float) -> Polygon:
    f = math.sqrt(area / poly.area)
    return affinity.scale(poly, f, f, origin=(0, 0))


# --- 불변식 ---------------------------------------------------------------
def _corners(poly: Polygon) -> list[tuple[float, float]]:
    pts = list(poly.exterior.coords)[:-1]
    assert len(pts) == 4, f"stall must have 4 corners, got {len(pts)}"
    return pts


def _dist(a, b) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _check_stall_shape(stall: Polygon, edge: LineString, angle: str, w: float, l_: float) -> None:
    pts = _corners(stall)
    e0, e1 = edge.coords[0], edge.coords[1]
    idx = [min(range(4), key=lambda i: _dist(pts[i], e)) for e in (e0, e1)]
    assert _dist(pts[idx[0]], e0) < TOL and _dist(pts[idx[1]], e1) < TOL, "access edge must be a stall edge"
    assert (idx[1] - idx[0]) % 4 in (1, 3), "access edge endpoints must be adjacent corners"
    a = edge.length
    # 접근변에 인접한 다른 변
    j = idx[0]
    k = (j - 1) % 4 if (j + 1) % 4 == idx[1] else (j + 1) % 4
    side = (pts[k][0] - pts[j][0], pts[k][1] - pts[j][1])
    acc = (e1[0] - e0[0], e1[1] - e0[1])
    b = math.hypot(*side)
    cosang = abs(acc[0] * side[0] + acc[1] * side[1]) / (a * b)
    alpha = math.degrees(math.acos(min(1.0, cosang)))
    # 평행사변형 (마주 보는 변 길이 같음)
    lens = [_dist(pts[i], pts[(i + 1) % 4]) for i in range(4)]
    assert abs(lens[0] - lens[2]) < TOL and abs(lens[1] - lens[3]) < TOL
    theta = THETA[angle]
    if angle == "PERPENDICULAR_90":
        assert abs(alpha - 90) < 1e-6 and abs(a - w) < TOL and abs(b - l_) < TOL
        assert a < b  # 짧은 변이 접근변
    elif angle == "PARALLEL_0":
        assert abs(alpha - 90) < 1e-6 and abs(a - l_) < TOL and abs(b - w) < TOL
        assert a > b  # 긴 변이 접근변
    else:
        r = math.radians(theta)
        assert abs(alpha - theta) < 1e-6
        assert abs(a - w / math.sin(r)) < TOL  # 차로 방향 간격 = W / sinθ
        assert abs(a * math.sin(r) - w) < TOL  # 측변 사이 수직거리 = 법정 폭
        assert abs(b * math.sin(r) - (l_ * math.sin(r) + w * math.cos(r))) < TOL  # 모듈 깊이
        assert b - w / math.tan(r) >= l_ - TOL  # 측변 = L + W·cotθ → W×L 직사각형을 품음
        assert a < b


def assert_layout_valid(g: LayoutGeometry, alt: Alternative, dims: ParkingDimensions, params: SimulationParams) -> None:
    """반환된 대안 하나의 모든 불변식."""
    angle = alt.parking_angle
    assert g.parking_angle == angle and len(g.stalls) == alt.stall_count > 0
    size = (dims.stall_parallel if angle == "PARALLEL_0" else dims.stall_non_parallel)[params.stall_type]
    w, l_ = size.width_m, size.length_m
    key = "one_gate" if params.gate_count == 1 else "two_plus_gates"
    req_aisle = dims.aisle_width[angle][key]
    assert g.stall_width_m == w and g.stall_length_m == l_ and g.aisle_width_m == req_aisle

    # 면적 계산 정밀도를 위해 기준점 기준 지역 좌표(m)로 옮겨 검사 (EPSG:5179 와 같은 축척)
    site, usable = to_local(g.site), to_local(g.usable)
    stalls = [to_local(s) for s in g.stalls]
    edges = [to_local(e) for e in g.access_edges]
    aisles = [(k, to_local(p), wd) for k, p, wd in g.aisles]
    union = to_local(g.aisle_union)
    gp = to_local(g.gate_point)
    opening = to_local(g.gate_opening)

    usable_tol = usable.buffer(TOL, join_style="mitre")
    assert g.aisle_union.geom_type == "Polygon", "aisle network must be ONE connected polygon"
    assert abs(union.area - shapely.union_all([a[1] for a in aisles]).area) < 1e-6
    assert site.buffer(TOL, join_style="mitre").covers(union), "aisles must stay inside the site"

    tree = shapely.STRtree(stalls)
    for i, (stall, edge) in enumerate(zip(stalls, edges, strict=True)):
        # 1) 사용 가능 영역 안
        assert usable_tol.covers(stall), f"stall {i} outside usable area"
        # 2) 구획끼리 겹치지 않음
        for j in tree.query(stall).tolist():
            if j != i:
                assert stall.intersection(stalls[j]).area < TOL, f"stalls {i},{j} overlap"
        # 3) 차로와 겹치지 않음
        assert stall.intersection(union).area < TOL, f"stall {i} overlaps aisle"
        # 4) 접근변이 차로 경계에 전부 닿음
        assert union.buffer(TOL).covers(edge), f"stall {i} access edge not on aisle boundary"
        # 5) 법정 치수 그대로 (축소 없음)
        _check_stall_shape(stall, edge, angle, w, l_)

    # 6) 차로 폭
    for kind, poly, width in aisles:
        assert width >= req_aisle - TOL
        if kind in ("aisle", "spine"):
            mrr = poly.minimum_rotated_rectangle
            xy = list(mrr.exterior.coords)
            sides = sorted([_dist(xy[0], xy[1]), _dist(xy[1], xy[2])])
            assert abs(poly.area - mrr.area) < 1e-6, f"{kind} must be a rectangle"
            assert sides[0] >= req_aisle - TOL, f"{kind} narrower than required"
        else:
            assert kind == "driveway"
            if dims.gate_width_min_m:
                assert width >= dims.gate_width_min_m - TOL
    # 출입구 바로 안쪽에도 차로 폭(지름) 원이 들어감 = 진입로 폭이 모자라지 않음
    # (곡선·꺾인 경계에서 출입구 구간의 안쪽 볼록 허용치 0.3 m 만큼 여유)
    core = union.buffer(-(req_aisle / 2 - 1e-3))
    assert not core.is_empty and core.distance(gp) <= req_aisle / 2 + 0.35, "driveway narrower than aisle at the gate"

    # 7) 출입구: 경계 위의 점 + 개구부, 차로망이 닿음
    assert site.exterior.distance(gp) < TOL, "gate must be on the site boundary"
    assert union.distance(gp) < TOL, "aisle network must touch the gate"
    assert site.exterior.buffer(TOL).covers(opening), "gate opening must lie on the boundary"
    assert union.buffer(1e-5).covers(opening), "aisle network must reach the full gate opening"
    if dims.gate_width_min_m:
        assert opening.length >= dims.gate_width_min_m - TOL

    assert_output_layers(alt, g, w, l_, req_aisle, params)


def assert_output_layers(
    alt: Alternative, g: LayoutGeometry, w: float, l_: float, req_aisle: float, params: SimulationParams
) -> None:
    layers = alt.layers
    assert {"stalls", "aisles", "gate", "usable_area"} <= set(layers)
    feats = layers["stalls"]["features"]
    assert layers["stalls"]["type"] == "FeatureCollection" and len(feats) == alt.stall_count
    for i, f in enumerate(feats):
        p = f["properties"]
        assert p["id"] == f"P-{i + 1:03d}"
        assert p["stall_type"] == params.stall_type and p["angle"] == alt.parking_angle
        assert p["width_m"] == round(w, 2) and p["length_m"] == round(l_, 2)
        ring = f["geometry"]["coordinates"][0]
        assert f["geometry"]["type"] == "Polygon" and ring[0] == ring[-1]
        for lng, lat in ring:
            assert round(lng, 7) == lng and round(lat, 7) == lat
            assert 124 < lng < 132 and 33 < lat < 39  # [lng, lat] 순서
        assert Polygon(ring).exterior.is_ccw  # RFC 7946
    # 4326 출력 ↔ 내부 기하 (반올림 1e-7° ≈ 1cm)
    for f, s in zip(feats[:5], g.stalls[:5], strict=True):
        back = geo.to_utmk(shape(f["geometry"]))
        assert back.centroid.distance(s.centroid) < 0.05
        assert abs(back.area - s.area) < 0.2
    aisles = layers["aisles"]["features"]
    assert aisles and all(a["properties"]["width_m"] >= req_aisle for a in aisles)
    net = shapely.union_all([geo.to_utmk(shape(a["geometry"])).buffer(0.02) for a in aisles])
    assert net.geom_type == "Polygon"
    gate = layers["gate"]
    assert gate["geometry"]["type"] == "Point"
    assert net.distance(geo.to_utmk(shape(gate["geometry"]))) < 0.05
    assert gate["properties"]["width_m"] >= req_aisle
    assert layers["usable_area"]["geometry"]["type"] == "Polygon"
    m = alt.metrics
    for key in (
        "site_area_m2",
        "usable_area_m2",
        "parking_area_m2",
        "stall_area_m2",
        "aisle_area_m2",
        "efficiency_percent",
        "area_per_stall_m2",
        "aisle_width_m",
        "stall_width_m",
        "stall_length_m",
        "orientation_deg",
    ):
        assert key in m and isinstance(m[key], int | float), key
        assert round(m[key], 2) == m[key]
    assert m["aisle_width_m"] == round(req_aisle, 2)
    assert m["stall_width_m"] == round(w, 2) and m["stall_length_m"] == round(l_, 2)
    assert abs(m["stall_area_m2"] - alt.stall_count * w * l_) < 0.01
    assert abs(m["efficiency_percent"] - m["stall_area_m2"] / m["usable_area_m2"] * 100) < 0.02
    assert abs(m["area_per_stall_m2"] - m["usable_area_m2"] / alt.stall_count) < 0.02
    assert abs(m["parking_area_m2"] - (m["stall_area_m2"] + m["aisle_area_m2"])) < 0.02
    assert m["orientation_deg"] == alt.orientation_deg and 0 <= alt.orientation_deg < 180


def rough_upper_bound(
    usable_area: float, angle: str, dims: ParkingDimensions, stall_type: str = "general", gate_key: str = "one_gate"
) -> int:
    """구획 하나가 최소 '구획 면적 + 차로 절반(양측 배치)' 을 차지한다는 거친 상한."""
    size = (dims.stall_parallel if angle == "PARALLEL_0" else dims.stall_non_parallel)[stall_type]
    a = dims.aisle_width[angle][gate_key]
    w, l_ = size.width_m, size.length_m
    t = math.radians(THETA[angle])
    if angle == "PARALLEL_0":
        pitch, depth = l_, w
    elif angle == "PERPENDICULAR_90":
        pitch, depth = w, l_
    else:
        pitch, depth = w / math.sin(t), l_ * math.sin(t) + w * math.cos(t)
    return int(usable_area / (pitch * (depth + a / 2)))


def point_of(x: float, y: float) -> Point:
    return Point(X0 + x, Y0 + y)
