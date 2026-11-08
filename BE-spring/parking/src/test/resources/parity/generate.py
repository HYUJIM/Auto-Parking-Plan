"""파이썬판 주차 배치 엔진(BE/app/parking/engine.py)의 결과를 자바 이식판 대조용 fixture 로 기록한다.

실행 (저장소 루트에서):
    BE/.venv/bin/python BE-spring/parking/src/test/resources/parity/generate.py

출력: 같은 폴더의 parity_cases.json.gz
  - 입력: 대지 GeoJSON(EPSG:4326, 파이썬이 만든 좌표 그대로), 치수, 매개변수
  - forward: 파이썬(pyproj)이 이 입력 좌표들을 EPSG:5179 로 바꾼 값 (자바의 '투영 고정' 대조에 쓴다)
  - 기대값: simulate() 결과 전체 (dataclasses.asdict 와 같은 구조: 대안·레이어 GeoJSON·지표·제약 사유)
자바 쪽 ParkingParityTest 가 같은 입력으로 엔진을 돌려 비교한다.

대상
  - tests/parking/_helpers.py 의 합성 대지 + 각 테스트가 쓰는 특수 대지(아령, 다중 다각형, 나비넥타이 …)
  - 고정 시드 무작위 대지 (test_engine_random_sites.py 와 같은 생성기, 시드 2개)
  - 공개 API(POST /v1/reviews/{id}/parking-simulations)가 받는 옵션 조합:
    stall_type × gate_count × setback × max_alternatives × gate_point, 각 형식 단독·중복 목록

같은 폴더의 geos_ops.json.gz 도 만든다: 엔진이 쓰는 shapely(GEOS) 기하 연산 하나하나의 결과 (자바 GeosCompatTest 용).
"""

from __future__ import annotations

import dataclasses
import gzip
import itertools
import json
import math
import random
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
BE = HERE.parents[5] / "BE"  # BE-spring/parking/src/test/resources/parity → 저장소 루트/BE
sys.path.insert(0, str(BE))

from shapely import affinity  # noqa: E402
from shapely.geometry import MultiPolygon, Polygon, box  # noqa: E402
from shapely.ops import unary_union  # noqa: E402

import shapely  # noqa: E402

from app.parking import geo  # noqa: E402
from app.parking.engine import simulate  # noqa: E402
from app.parking.types import ParkingDimensions, SimulationParams, StallSize  # noqa: E402
from tests.parking._helpers import (  # noqa: E402
    IRREGULAR_HEX,
    L_SHAPE,
    PENTAGON,
    local_point_lnglat,
    local_to_geojson,
    make_dims,
    rect,
    rotated_rect,
    scaled_to_area,
)


def dims_json(d: ParkingDimensions) -> dict:
    return {
        "stall_non_parallel": {k: [v.width_m, v.length_m] for k, v in d.stall_non_parallel.items()},
        "stall_parallel": {k: [v.width_m, v.length_m] for k, v in d.stall_parallel.items()},
        "aisle_width": {k: dict(v) for k, v in d.aisle_width.items()},
        "gate_width_min_m": d.gate_width_min_m,
        "rule_ids": list(d.rule_ids),
    }


def params_json(p: SimulationParams) -> dict:
    return {
        "angles": list(p.angles) if p.angles is not None else None,
        "stall_type": p.stall_type,
        "setback_m": p.setback_m,
        "gate_point": list(p.gate_point) if p.gate_point is not None else None,
        "gate_count": p.gate_count,
        "max_alternatives": p.max_alternatives,
        "orientation_step_deg": p.orientation_step_deg,
    }


DIMS = make_dims()
CUSTOM_DIMS = make_dims(
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

cases: list[dict] = []


def forward_table(site: dict, params: SimulationParams) -> list[list[float]]:
    """이 사례에서 파이썬이 EPSG:4326 → 5179 로 바꾼 좌표 [lng, lat, x, y] (자바 '투영 고정' 대조용)."""
    rows: list[list[float]] = []
    try:
        g = geo.geojson_to_shape(site)
    except geo.GeoJSONError:
        g = None
    if g is not None and not g.is_empty:
        src = shapely.get_coordinates(g)
        dst = shapely.get_coordinates(geo.to_utmk(g))
        rows.extend([float(a), float(b), float(c), float(d)] for (a, b), (c, d) in zip(src, dst, strict=True))
    gp = params.gate_point
    if gp is not None and all(math.isfinite(float(v)) for v in gp[:2]):
        x, y = geo.lnglat_to_utmk(float(gp[0]), float(gp[1]))
        rows.append([float(gp[0]), float(gp[1]), x, y])
    return rows


def add(name: str, site: dict, dims: ParkingDimensions = DIMS, **kw) -> None:
    params = SimulationParams(**kw)
    t0 = time.perf_counter()
    res = simulate(site, dims, params)
    dt = time.perf_counter() - t0
    cases.append(
        {
            "name": name,
            "site": site,
            "dims": dims_json(dims),
            "params": params_json(params),
            "forward": forward_table(site, params),
            "expected": json.loads(json.dumps(dataclasses.asdict(res))),
            "python_seconds": round(dt, 3),
        }
    )
    print(f"{name:60s} {[a.stall_count for a in res.alternatives]} {dt:.2f}s", flush=True)


# --- 테스트 도우미 대지 ------------------------------------------------------
SITES = {
    "rect_20x40": rect(20, 40),
    "rect_50x60": rect(50, 60),
    "rotated_33": rotated_rect(30, 45, 33),
    "l_shape": L_SHAPE,
    "pentagon": PENTAGON,
    "strip_8x60": rect(8, 60),
    "rect_30x45": rect(30, 45),
    "rect_30x40": rect(30, 40),
    "irregular_hex_3000": scaled_to_area(IRREGULAR_HEX, 3000.0),
    "tiny_6x6": rect(6, 6),
}
for name, poly in SITES.items():
    add(f"{name}/default", local_to_geojson(poly))
for name in ("rect_20x40", "rect_50x60", "rotated_33", "l_shape", "pentagon", "strip_8x60"):
    for angle in ("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0"):
        add(f"{name}/{angle}", local_to_geojson(SITES[name]), angles=[angle], max_alternatives=3)

# --- 특수 대지 ---------------------------------------------------------------
add("multipolygon", local_to_geojson(MultiPolygon([box(0, 0, 30, 40), box(50, 0, 60, 10)])))
dumbbell = unary_union([box(0, 0, 40, 40), box(39, 19, 51, 21), box(50, 15, 60, 25)])
add("dumbbell/unreachable_gate", local_to_geojson(dumbbell), gate_point=local_point_lnglat(60, 20))
lng0, lat0 = local_point_lnglat(0, 0)
lng1, lat1 = local_point_lnglat(60, 60)
bow = {"type": "Polygon", "coordinates": [[[lng0, lat0], [lng1, lat1], [lng1, lat0], [lng0, lat1], [lng0, lat0]]]}
add("bowtie", bow)
add("feature_input", {"type": "Feature", "geometry": local_to_geojson(rect(20, 40)), "properties": {"pnu": "x"}})
add("custom_dims", local_to_geojson(rect(50, 60)), dims=CUSTOM_DIMS, max_alternatives=4)
add("missing_gate_width", local_to_geojson(rect(30, 40)), dims=make_dims(gate_width_min_m=None),
    angles=["PARALLEL_0"], gate_count=2)
aisles = dict(DIMS.aisle_width)
del aisles["ANGLE_45"]
add("missing_angle45", local_to_geojson(rect(50, 60)), dims=make_dims(aisle_width=aisles), max_alternatives=4)
add("empty_dims", local_to_geojson(rect(50, 60)), dims=ParkingDimensions())
add("invalid_point", {"type": "Point", "coordinates": [128.63, 35.85]})
add("invalid_swapped", {"type": "Polygon", "coordinates": [[[35.85, 128.63], [35.851, 128.63], [35.851, 128.631],
                                                            [35.85, 128.63]]]})
add("invalid_gate_point_nan", local_to_geojson(rect(50, 60)), gate_point=(float("nan"), 35.0))
add("gate_far_south", local_to_geojson(rect(50, 60)), gate_point=local_point_lnglat(25, -30))
add("gate_near_east", local_to_geojson(rect(50, 60)), gate_point=local_point_lnglat(53, 30))
add("gate_corner", local_to_geojson(rect(50, 60)), gate_point=local_point_lnglat(0.2, 0.1))
add("pentagon/gate_70_30", local_to_geojson(PENTAGON), gate_point=local_point_lnglat(70, 30))
add("pentagon/setback0", local_to_geojson(PENTAGON), setback_m=0.0)
add("rect_50x60/step_7.5", local_to_geojson(rect(50, 60)), orientation_step_deg=7.5)

# --- 공개 API 옵션 조합 ------------------------------------------------------
for site_name in ("rect_50x60", "pentagon"):
    site = local_to_geojson(SITES[site_name])
    for stall_type, gate_count, setback, max_alt, gate in itertools.product(
        ("general", "extended"), (1, 2), (0.0, 1.5), (1, 6), (None, (40, -5))
    ):
        kw = dict(stall_type=stall_type, gate_count=gate_count, setback_m=setback, max_alternatives=max_alt)
        if gate is not None:
            kw["gate_point"] = local_point_lnglat(*gate)
        add(f"api/{site_name}/{stall_type}/g{gate_count}/sb{setback}/max{max_alt}/gate{gate is not None}", site, **kw)
site = local_to_geojson(SITES["l_shape"])
add("api/l_shape/angles_dup", site, angles=["ANGLE_45", "PARALLEL_0", "ANGLE_45"], max_alternatives=6)
add("api/l_shape/setback10", site, setback_m=10.0)
add("api/l_shape/extended_60", site, angles=["ANGLE_60"], stall_type="extended", gate_count=2)


# --- 고정 시드 무작위 대지 (test_engine_random_sites.py 와 같은 생성기) ---------
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
            r = radius * (0.8 + 0.2 * math.sin(3 * a))
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


for seed, n in ((20261106, 10), (7, 12)):
    for i, (poly, kw) in enumerate(_cases(seed, n)):
        add(f"random/{seed}/{i}", local_to_geojson(poly), **kw)

# --- 기하 연산 단위 대조 (shapely 2.2 / GEOS 3.14.1 ↔ 자바 geom 패키지) -------------------
def _geos_cases(seed: int, n: int) -> list[dict]:
    """버퍼·합집합·최대내접원·최소회전사각형·방향·make_valid·선형 참조 결과(WKB, 2D little-endian)."""
    from shapely.geometry import LineString, Point
    from shapely.geometry.polygon import orient
    from shapely.ops import nearest_points, substring

    rng = random.Random(seed)
    x0, y0 = geo.lnglat_to_utmk(128.63, 35.85)

    def wkb(g) -> str:
        return shapely.to_wkb(g, hex=True, output_dimension=2, byte_order=1)

    def rand_poly():
        kind = rng.choice(["star", "convex", "parcel", "rect", "notched", "quad"])
        if kind == "rect":
            p = affinity.rotate(box(0, 0, rng.uniform(8, 80), rng.uniform(8, 80)), rng.uniform(0, 180), origin=(0, 0))
        elif kind == "notched":
            w, h = rng.uniform(15, 80), rng.uniform(15, 80)
            p = affinity.rotate(box(0, 0, w, h), rng.uniform(0, 180), origin=(0, 0))
            p = p.difference(affinity.rotate(box(0, 0, w * 0.4, h * 0.4), rng.uniform(0, 180), origin=(0, 0)))
        elif kind == "quad":
            p = Polygon([(rng.uniform(-30, -5), rng.uniform(-30, -5)), (rng.uniform(5, 30), rng.uniform(-30, -5)),
                         (rng.uniform(5, 30), rng.uniform(5, 30)), (rng.uniform(-30, -5), rng.uniform(5, 30))])
        else:
            k = rng.randint(4, 12)
            angs = sorted(rng.uniform(0, 2 * math.pi) for _ in range(k))
            r = rng.uniform(15, 55)
            pts = [((r if kind != "star" else r * rng.uniform(0.55, 1)) * math.cos(a),
                    (r if kind != "star" else r * rng.uniform(0.55, 1)) * math.sin(a)) for a in angs]
            if kind == "parcel":  # 거의 일직선인 정점이 많은 지적 경계 흉내
                out = []
                for i in range(len(pts)):
                    a, b = pts[i], pts[(i + 1) % len(pts)]
                    out.append(a)
                    for _ in range(rng.randint(0, 3)):
                        t = rng.uniform(0.1, 0.9)
                        dx, dy = b[0] - a[0], b[1] - a[1]
                        L = math.hypot(dx, dy)
                        off = rng.uniform(-0.05, 0.05)
                        out.append((a[0] + t * dx - dy / L * off, a[1] + t * dy + dx / L * off))
                pts = out
            p = Polygon(pts)
            p = p.convex_hull if kind == "convex" else (p if p.is_valid else p.buffer(0))
        return affinity.translate(p, x0, y0)

    out = []
    while len(out) < n:
        p = rand_poly()
        if p.geom_type != "Polygon" or p.is_empty:
            continue
        rects = []
        ya = rng.uniform(-20, 0)
        for k in range(rng.randint(1, 5)):
            rects.append(box(x0 + rng.uniform(-20, -5), y0 + ya + k * 16, x0 + rng.uniform(5, 20), y0 + ya + k * 16 + 6))
        sx = x0 + rng.uniform(-5, 5)
        rects.append(box(sx, y0 + ya, sx + 6, y0 + ya + (len(rects) - 1) * 16 + 6))
        c = p.centroid
        line = LineString([(c.x + rng.uniform(-30, 30), c.y + rng.uniform(-30, 30)) for _ in range(rng.choice([2, 3]))])
        w = rng.choice([3.5, 5.0, 6.0, 8.0])
        ext = LineString(p.exterior.coords)
        ring2 = LineString(list(ext.coords) + list(ext.coords)[1:])
        d = rng.uniform(0, ext.length)
        t = Point(x0 + rng.uniform(-60, 60), y0 + rng.uniform(-60, 60))
        union = shapely.union_all(rects)
        rec = {
            "poly": wkb(p),
            "neg_buffer_0.5_mitre10": wkb(p.buffer(-0.5, join_style="mitre", mitre_limit=10.0)),
            "neg_buffer_1.0_mitre10": wkb(p.buffer(-1.0, join_style="mitre", mitre_limit=10.0)),
            "buffer_1e-6_round": wkb(p.buffer(1e-6)),
            "buffer_1e-6_mitre": wkb(p.buffer(1e-6, join_style="mitre")),
            "buffer_1e-7_mitre": wkb(p.buffer(1e-7, join_style="mitre")),
            "rects": [wkb(r) for r in rects],
            "union_all": wkb(union),
            "line": wkb(line),
            "width": w,
            "line_buffer_flat_mitre2": wkb(line.buffer(w / 2, cap_style="flat", join_style="mitre", mitre_limit=2.0)),
            "line_intersection": wkb(line.intersection(p)),
            "mic_0.01": wkb(shapely.maximum_inscribed_circle(p, 0.01)),
            "mrr": wkb(p.minimum_rotated_rectangle),
            "orient": wkb(orient(p, 1.0)),
            "orient_rotated": wkb(orient(affinity.rotate(p, 180, origin="centroid"), 1.0)),
            "rotated": wkb(affinity.rotate(p, 180, origin="centroid")),
            "ring2": wkb(ring2),
            "d": d,
            "interpolate": wkb(ring2.interpolate(d)),
            "substring": wkb(substring(ring2, d, d + w)),
            "target": wkb(t),
            "project": ext.project(t),
            "nearest_ext_union": [wkb(q) for q in nearest_points(ext, union)],
            "remove_repeated": wkb(shapely.remove_repeated_points(p, 1e-9)),
            "centroid": wkb(p.centroid),
            "area": p.area,
        }
        out.append(rec)
    invalid = []
    while len(invalid) < 30:
        a = rng.uniform(20, 60)
        pts = [(0, 0), (a, a), (a, 0), (0, a)] if len(invalid) % 3 == 0 else \
            [(rng.uniform(-30, 30), rng.uniform(-30, 30)) for _ in range(rng.randint(4, 8))]
        p = affinity.translate(Polygon(pts), x0, y0)
        if p.is_valid:
            continue
        invalid.append({"poly": wkb(p), "make_valid": wkb(shapely.make_valid(p))})
    return out, invalid


geos_ops, geos_invalid = _geos_cases(20261108, 150)
gout = HERE / "geos_ops.json.gz"
with gzip.open(gout, "wt", encoding="utf-8") as f:
    json.dump({"generator": "BE-spring/parking/src/test/resources/parity/generate.py", "shapely": shapely.__version__,
               "geos": shapely.geos_version_string, "cases": geos_ops, "invalid": geos_invalid}, f,
              separators=(",", ":"))
print(f"wrote {len(geos_ops)} + {len(geos_invalid)} geometry cases → {gout} ({gout.stat().st_size} bytes)")

out = HERE / "parity_cases.json.gz"
with gzip.open(out, "wt", encoding="utf-8") as f:
    json.dump({"generator": "BE-spring/parking/src/test/resources/parity/generate.py", "cases": cases}, f,
              ensure_ascii=False, separators=(",", ":"))
print(f"wrote {len(cases)} cases → {out} ({out.stat().st_size} bytes)")
