"""
지상 자주식 노외주차장 배치 시뮬레이션 엔진.

공개 API
    simulate(site_geojson, dims, params) -> SimulationResult
    simulate_detailed(site_geojson, dims, params) -> (SimulationResult, list[LayoutGeometry])

원칙
- 법정 치수(구획 폭·길이, 차로 폭, 출입구 너비)는 전부 ``ParkingDimensions`` 로 받는다.
  엔진에는 법규 값을 두지 않는다. 필요한 치수가 없으면 그 형식은 배치하지 않고
  ``DIMENSION_MISSING:<angle>`` 를 남긴다.
- 순수 함수, I/O 없음, 결정적(같은 입력 → 같은 출력).
- 내부 계산은 EPSG:5179(m). 출력 레이어는 EPSG:4326 GeoJSON(소수 7자리).

알고리즘 개요
1. 대지(MultiPolygon 이면 가장 큰 부분)를 setback 만큼 안쪽으로 줄여 사용 가능 영역 U 를 만든다.
2. 차로 방향 후보(0~180°를 orientation_step 간격 + 대지 긴 변·최소회전사각형 방향)마다
   U 를 회전해 차로가 x 축과 평행한 지역 좌표계를 만든다.
3. 주차 모듈(구획열 D + 차로 A + 구획열 D, 주기 2D+A)을 y 방향으로 쌓는다. 시작 위치(phase)를
   바꿔 가며 탐색한다. 열이 들어가지 않는 쪽은 비워지므로 편측(single-loaded) 배치도 자연히 나온다.
4. 각 띠(band)가 U 안에 완전히 들어가는 x 구간을 해석적으로 구한다 (경계선 투영 + 내부점 판정).
   경사주차 열은 전단(shear) 좌표계에서 계산해 평행사변형 구획이 정확히 U 안에 들도록 한다.
5. 차로가 여러 개면 수직 연결차로(spine)로 하나의 망으로 묶는다. 연결차로 위치도 탐색한다.
6. 탐색: 방향마다 모든 거친 phase(0.5 m)의 상한(연결차로 무시 구획 수)을 구하고, 상한 상위 방향만
   연결차로까지 정밀 평가한다(상한 < 현재 최선이면 가지치기). 상위 phase 주변 미세 탐색(1/16 m),
   연결차로 위치 미세 조정(0.1 m) 후 방향별 최선안에 출입구를 붙인다.
7. 출입구: 지정점이 있으면 대지 경계 최근접점으로 스냅, 없으면 구획 손실이 가장 적은 경계점을 고른다.
   출입구에서 대지 경계에 수직으로 진입로(driveway)를 내어 차로망에 붙인다. 진입로와 겹치는 구획은 뺀다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any

import numpy as np
import shapely
from shapely import affinity
from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.geometry.base import BaseGeometry
from shapely.geometry.polygon import orient
from shapely.ops import linemerge, nearest_points, substring

from app.parking import geo
from app.parking.types import (
    Alternative,
    ParkingAngle,
    ParkingDimensions,
    SimulationParams,
    SimulationResult,
)

__all__ = [
    "simulate",
    "simulate_detailed",
    "LayoutGeometry",
    "SITE_GEOMETRY_INVALID",
    "SITE_GEOMETRY_REPAIRED",
    "MULTIPART_SITE_LARGEST_USED",
    "USABLE_AREA_SPLIT_LARGEST_USED",
    "DIMENSION_MISSING",
    "LIMIT_SITE_WIDTH_NARROW",
    "NO_FEASIBLE_LAYOUT",
    "GATE_SNAPPED",
    "GATE_POINT_INVALID",
    "GATE_UNREACHABLE",
]

# ---------------------------------------------------------------------------
# 제약 사유 코드
# ---------------------------------------------------------------------------
SITE_GEOMETRY_INVALID = "SITE_GEOMETRY_INVALID"
SITE_GEOMETRY_REPAIRED = "SITE_GEOMETRY_REPAIRED"  # 자기교차 등 → make_valid 로 보정해 진행
MULTIPART_SITE_LARGEST_USED = "MULTIPART_SITE_LARGEST_USED"
USABLE_AREA_SPLIT_LARGEST_USED = "USABLE_AREA_SPLIT_LARGEST_USED"  # setback 후 영역이 갈라짐
DIMENSION_MISSING = "DIMENSION_MISSING"  # 실제 코드는 "DIMENSION_MISSING:<angle>"
LIMIT_SITE_WIDTH_NARROW = "LIMIT_SITE_WIDTH_NARROW"  # 상세 코드 "LIMIT_SITE_WIDTH_NARROW:<angle>" 도 함께
NO_FEASIBLE_LAYOUT = "NO_FEASIBLE_LAYOUT"  # 상세 코드 "NO_FEASIBLE_LAYOUT:<angle>" 도 사용
GATE_SNAPPED = "GATE_SNAPPED"
GATE_POINT_INVALID = "GATE_POINT_INVALID"
GATE_UNREACHABLE = "GATE_UNREACHABLE"

# ---------------------------------------------------------------------------
# 엔진 조정값 (법규 값 아님: 탐색·수치 허용오차)
# ---------------------------------------------------------------------------
GATE_SNAP_WARN_M = 15.0  # 지정 출입구가 경계에서 이보다 멀면 GATE_SNAPPED
_ANGLE_ORDER: tuple[ParkingAngle, ...] = ("PERPENDICULAR_90", "ANGLE_60", "ANGLE_45", "PARALLEL_0")
_THETA_DEG = {"PERPENDICULAR_90": 90.0, "ANGLE_60": 60.0, "ANGLE_45": 45.0, "PARALLEL_0": 0.0}  # 형식 정의상의 각도

_EPS = 1e-9  # 띠 맞춤 허용오차 (m)
_LEN_TOL = 1e-9
_Y_TOL = 1e-7
_COARSE_PHASE_STEP_M = 0.5
_REFINE_PHASE_OFFSETS = tuple(i * 0.0625 for i in range(-4, 5) if i != 0)
_TOP_ORIENTATIONS = 12  # 상한 기준 상위 방향만 정밀 평가
_REFINE_SEEDS = 2  # 방향마다 미세 탐색할 상위 phase 수
_FINAL_PER_ANGLE = 3
_MIN_ORIENTATION_GAP_DEG = 10.0
_SECONDARY_MIN_RATIO = 0.5
_SPINE_GRID = 8
_SPINE_REFINE = tuple(j * 0.1 for j in range(-10, 11) if j != 0)
_EDGE_CANDIDATES = 6
_GATE_OVERSHOOT_M = 0.5
_GATE_MAX_BULGE_M = 0.3
_GATE_SEARCH_STEP_M = 0.5
_GATE_DEPTH_STEP_M = 0.25
_AREA_TOL = 1e-9
_CONTAIN_TOL = 1e-7
_NARROW_TOL_M = 0.025  # 최대 내접원 계산 허용오차(0.01m 반경)를 흡수


# ---------------------------------------------------------------------------
# 내부 자료형
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class _Spec:
    angle: ParkingAngle
    theta_deg: float
    stall_w: float  # 법정 구획 폭
    stall_l: float  # 법정 구획 길이
    depth: float  # 차로에 수직인 구획열 깊이
    pitch: float  # 차로 방향 구획 간격
    shear: float  # cot(theta) (직각·평행은 0)
    aisle_w: float  # 차로 폭
    driveway_w: float  # 진입로·출입구 폭 = max(차로 폭, 출입구 최소 너비)
    gate_min: float | None  # 출입구 최소 너비 (치수 미제공이면 None)

    @property
    def period(self) -> float:
        return 2.0 * self.depth + self.aisle_w


@dataclass
class _Frame:
    idx: int
    phi: float  # 차로 방향 (EPSG:5179 x 축에서 반시계, 도)
    cos: float
    sin: float
    E0: np.ndarray  # 회전 좌표계 경계 선분 (n,4)
    E0T: np.ndarray  # x↔y 교환 (세로 띠용)
    xmin: float
    xmax: float
    ymin: float
    ymax: float
    sheared: dict[float, tuple[np.ndarray, np.ndarray]] = field(default_factory=dict)


@dataclass
class _Mod:
    ya: float  # 차로 아래변 y
    aisle_iv: list[tuple[float, float]]
    low_iv: list[tuple[float, float]]  # 아래 열 (접근변 좌표 u)
    up_iv: list[tuple[float, float]]  # 위 열


@dataclass
class _Plan:
    count: int
    n_aisles: int
    fidx: int
    phase: float
    mods: list[_Mod]
    choice: tuple


@dataclass
class _Candidate:
    spec: _Spec
    fr: _Frame
    plan: _Plan
    stalls: list[Polygon]  # EPSG:5179
    edges: list[LineString]
    aisles: list[tuple[str, Polygon, float]]  # (kind, polygon, width)
    network: Polygon
    gate_point: Point
    gate_opening: BaseGeometry
    reasons: list[str]

    @property
    def count(self) -> int:
        return len(self.stalls)

    @property
    def stall_area(self) -> float:
        """순 구획 면적 = 구획 수 × 법정 폭 × 길이 (경사주차 평행사변형의 삼각 여유는 제외)."""
        return float(len(self.stalls) * self.spec.stall_w * self.spec.stall_l)

    @property
    def stall_footprint(self) -> float:
        """그려진 구획 다각형 면적 합 (경사주차는 평행사변형)."""
        return float(sum(s.area for s in self.stalls))

    @property
    def aisle_area(self) -> float:
        return float(self.network.area)


@dataclass
class LayoutGeometry:
    """대안 하나의 내부 기하 (EPSG:5179, 검증·디버깅용). result.alternatives 와 같은 순서."""

    alt_code: str
    parking_angle: ParkingAngle
    orientation_deg: float
    site: Polygon
    usable: Polygon
    stalls: list[Polygon]
    access_edges: list[LineString]
    aisles: list[tuple[str, Polygon, float]]  # (kind: aisle|spine|driveway, polygon, width_m)
    aisle_union: Polygon
    gate_point: Point
    gate_opening: BaseGeometry
    aisle_width_m: float
    driveway_width_m: float
    stall_width_m: float
    stall_length_m: float
    theta_deg: float
    row_depth_m: float
    pitch_m: float


# ---------------------------------------------------------------------------
# 치수 해석
# ---------------------------------------------------------------------------
def _num(v: Any) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) and f > 0 else None


def _size_of(obj: Any) -> tuple[float, float] | None:
    if obj is None:
        return None
    if isinstance(obj, dict):
        w, l_ = obj.get("width_m"), obj.get("length_m")
    else:
        w, l_ = getattr(obj, "width_m", None), getattr(obj, "length_m", None)
    w, l_ = _num(w), _num(l_)
    if w is None or l_ is None:
        return None
    return w, l_


def _resolve_spec(angle: str, dims: ParkingDimensions, params: SimulationParams) -> _Spec | None:
    if angle not in _THETA_DEG:
        return None
    table = (dims.stall_parallel if angle == "PARALLEL_0" else dims.stall_non_parallel) or {}
    size = _size_of(table.get(params.stall_type))
    widths = (dims.aisle_width or {}).get(angle) or {}
    key = "one_gate" if int(params.gate_count or 1) <= 1 else "two_plus_gates"
    aw = _num(widths.get(key)) if isinstance(widths, dict) else None
    if size is None or aw is None:
        return None
    w, l_ = size
    gate_min = _num(dims.gate_width_min_m)
    drive_w = max(aw, gate_min) if gate_min is not None else aw
    theta = _THETA_DEG[angle]
    if angle == "PARALLEL_0":
        depth, pitch, k = w, l_, 0.0
    elif angle == "PERPENDICULAR_90":
        depth, pitch, k = l_, w, 0.0
    else:
        r = math.radians(theta)
        depth = l_ * math.sin(r) + w * math.cos(r)
        pitch = w / math.sin(r)
        k = math.cos(r) / math.sin(r)
    return _Spec(angle, theta, w, l_, depth, pitch, k, aw, drive_w, gate_min)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# 띠 맞춤: 수평 띠 [y0,y1] 가 다각형 안에 완전히 들어가는 x 구간
# ---------------------------------------------------------------------------
def _pip(E: np.ndarray, px: np.ndarray, py: np.ndarray) -> np.ndarray:
    """짝홀 규칙 점-다각형 판정 (모든 링의 선분 E 사용)."""
    xa, ya, xb, yb = E[:, 0], E[:, 1], E[:, 2], E[:, 3]
    pyc = py[:, None]
    cond = (ya > pyc) != (yb > pyc)
    dy = yb - ya
    xint = xa + (pyc - ya) * (xb - xa) / np.where(dy == 0.0, 1.0, dy)
    return ((cond & (px[:, None] < xint)).sum(axis=1) % 2) == 1


def _fit_intervals(E: np.ndarray, y0: np.ndarray, y1: np.ndarray) -> list[list[tuple[float, float]]]:
    """각 띠 i 에 대해 {x : 선분 {x}×[y0_i,y1_i] ⊂ 다각형} 을 닫힌 구간 목록으로 돌려준다.

    경계선 중 띠 내부(허용오차 _EPS 안쪽)를 지나는 부분의 x 투영이 금지 구간이고,
    그 사이 틈 중 중앙점이 다각형 내부인 것만 허용 구간이다.
    """
    m = int(y0.shape[0])
    out: list[list[tuple[float, float]]] = [[] for _ in range(m)]
    if m == 0:
        return out
    xa, ya, xb, yb = E[:, 0], E[:, 1], E[:, 2], E[:, 3]
    eymin = np.minimum(ya, yb)
    eymax = np.maximum(ya, yb)
    Y0 = (y0 + _EPS)[:, None]
    Y1 = (y1 - _EPS)[:, None]
    lo = np.maximum(eymin, Y0)
    hi = np.minimum(eymax, Y1)
    valid = lo <= hi
    dy = yb - ya
    flat = np.abs(dy) <= 1e-12
    inv = np.where(flat, 0.0, (xb - xa) / np.where(flat, 1.0, dy))
    xl = xa + (lo - ya) * inv
    xh = xa + (hi - ya) * inv
    a = np.where(flat, np.minimum(xa, xb), np.minimum(xl, xh))
    b = np.where(flat, np.maximum(xa, xb), np.maximum(xl, xh))
    ri, ci = np.nonzero(valid)
    if ri.size == 0:
        return out
    av = a[ri, ci]
    bv = b[ri, ci]
    order = np.lexsort((av, ri))
    rows = ri[order].tolist()
    al = av[order].tolist()
    bl = bv[order].tolist()
    g_band: list[int] = []
    g_a: list[float] = []
    g_b: list[float] = []
    cur = -1
    run_end = 0.0
    for r, ai, bi in zip(rows, al, bl, strict=True):
        if r != cur:
            cur = r
            run_end = bi
            continue
        if ai > run_end:
            g_band.append(r)
            g_a.append(run_end)
            g_b.append(ai)
        if bi > run_end:
            run_end = bi
    if not g_band:
        return out
    gb = np.asarray(g_band)
    ga_arr = np.asarray(g_a)
    gb_arr = np.asarray(g_b)
    inside = _pip(E, (ga_arr + gb_arr) * 0.5, (y0[gb] + y1[gb]) * 0.5)
    for r, x0, x1, ok in zip(g_band, g_a, g_b, inside.tolist(), strict=True):
        if ok:
            out[r].append((x0, x1))
    return out


# ---------------------------------------------------------------------------
# 좌표계·후보 방향
# ---------------------------------------------------------------------------
def _edges_of(rings: list[np.ndarray]) -> np.ndarray:
    return np.vstack([np.hstack([r[:-1], r[1:]]) for r in rings])


def _rot_neg(xy: np.ndarray, c: float, s: float) -> np.ndarray:
    """-phi 회전 (차로 방향 → x 축)."""
    x, y = xy[:, 0], xy[:, 1]
    return np.column_stack([x * c + y * s, -x * s + y * c])


def _angle_gap(a: float, b: float) -> float:
    d = abs(a - b) % 180.0
    return min(d, 180.0 - d)


def _orientation_candidates(usable: Polygon, site: Polygon, step: float) -> list[float]:
    """대지 변 방향(우선) + 균등 간격. 앞쪽일수록 동률 시 우선."""
    raw: list[float] = []
    for poly in (usable, site):
        xy = np.asarray(poly.exterior.coords)
        d = xy[1:] - xy[:-1]
        lens = np.hypot(d[:, 0], d[:, 1])
        for i in np.argsort(-lens, kind="stable")[:_EDGE_CANDIDATES]:
            if lens[i] < 1.0:
                continue
            ang = math.degrees(math.atan2(d[i, 1], d[i, 0])) % 180.0
            raw.extend([ang, (ang + 90.0) % 180.0])
    mrr = usable.minimum_rotated_rectangle
    if isinstance(mrr, Polygon):
        xy = np.asarray(mrr.exterior.coords)
        for i in range(min(2, len(xy) - 1)):
            d = xy[i + 1] - xy[i]
            if math.hypot(d[0], d[1]) > 1e-6:
                raw.append(math.degrees(math.atan2(d[1], d[0])) % 180.0)
    n = max(1, int(math.ceil(180.0 / step - 1e-9)))
    raw.extend(i * step for i in range(n) if i * step < 180.0 - 1e-9)
    out: list[float] = []
    for v in raw:
        v = round(v % 180.0, 9)
        if v >= 180.0:
            v = 0.0
        if all(_angle_gap(v, o) > 1e-6 for o in out):
            out.append(v)
    return out


def _build_frames(rings_local: list[np.ndarray], angles: list[float]) -> list[_Frame]:
    frames = []
    for idx, phi in enumerate(angles):
        r = math.radians(phi)
        c, s = math.cos(r), math.sin(r)
        rr = [_rot_neg(ring, c, s) for ring in rings_local]
        E0 = _edges_of(rr)
        pts = np.vstack(rr)
        frames.append(
            _Frame(
                idx=idx,
                phi=phi,
                cos=c,
                sin=s,
                E0=E0,
                E0T=np.ascontiguousarray(E0[:, [1, 0, 3, 2]]),
                xmin=float(pts[:, 0].min()),
                xmax=float(pts[:, 0].max()),
                ymin=float(pts[:, 1].min()),
                ymax=float(pts[:, 1].max()),
            )
        )
    return frames


def _sheared(fr: _Frame, k: float) -> tuple[np.ndarray, np.ndarray]:
    """(위 열용, 아래 열용) 전단 좌표계 경계. 위 열: x' = x - k·y, 아래 열: x' = x + k·y."""
    hit = fr.sheared.get(k)
    if hit is not None:
        return hit
    if k == 0.0:
        res = (fr.E0, fr.E0)
    else:
        up = fr.E0.copy()
        dn = fr.E0.copy()
        up[:, 0] -= k * fr.E0[:, 1]
        up[:, 2] -= k * fr.E0[:, 3]
        dn[:, 0] += k * fr.E0[:, 1]
        dn[:, 2] += k * fr.E0[:, 3]
        res = (up, dn)
    fr.sheared[k] = res
    return res


# ---------------------------------------------------------------------------
# 모듈 배치·계수
# ---------------------------------------------------------------------------
def _coarse_phases(fr: _Frame, spec: _Spec) -> list[float]:
    P, D, A = spec.period, spec.depth, spec.aisle_w
    H = fr.ymax - fr.ymin
    vals = [0.0, -D, H, H - D - A, H - D]
    vals.extend(np.arange(0.0, P, _COARSE_PHASE_STEP_M).tolist())
    return sorted({round(v % P, 9) % P for v in vals})


def _modules_for_phases(fr: _Frame, spec: _Spec, phases: list[float]) -> list[list[_Mod]]:
    D, A, P, k = spec.depth, spec.aisle_w, spec.period, spec.shear
    Eup, Edn = _sheared(fr, k)
    meta: list[tuple[int, float, bool, bool]] = []
    for pi, ph in enumerate(phases):
        base0 = fr.ymin - P + ph
        j = 0
        while True:
            b = base0 + j * P
            ya = b + D
            if ya >= fr.ymax:
                break
            if ya >= fr.ymin - _Y_TOL and ya + A <= fr.ymax + _Y_TOL:
                has_low = b >= fr.ymin - _Y_TOL
                has_up = b + 2 * D + A <= fr.ymax + _Y_TOL
                if has_low or has_up:
                    meta.append((pi, ya, has_low, has_up))
            j += 1
    result: list[list[_Mod]] = [[] for _ in phases]
    if not meta:
        return result
    ya_arr = np.array([m[1] for m in meta])
    aisle_ivs = _fit_intervals(fr.E0, ya_arr, ya_arr + A)
    low_sel = [i for i, m in enumerate(meta) if m[2] and aisle_ivs[i]]
    up_sel = [i for i, m in enumerate(meta) if m[3] and aisle_ivs[i]]
    low_ivs = _fit_intervals(Edn, ya_arr[low_sel] - D, ya_arr[low_sel]) if low_sel else []
    up_ivs = _fit_intervals(Eup, ya_arr[up_sel] + A, ya_arr[up_sel] + A + D) if up_sel else []
    low_map = dict(zip(low_sel, low_ivs, strict=True))
    up_map = dict(zip(up_sel, up_ivs, strict=True))
    for i, (pi, ya, _hl, _hu) in enumerate(meta):
        if not aisle_ivs[i]:
            continue
        lo_iv = low_map.get(i, [])
        up_iv = up_map.get(i, [])
        if k:
            sl = k * ya  # 아래 열: u = x' - k·y_acc (y_acc = ya)
            lo_iv = [(x0 - sl, x1 - sl) for x0, x1 in lo_iv]
            su = k * (ya + A)  # 위 열: u = x' + k·y_acc (y_acc = ya + A)
            up_iv = [(x0 + su, x1 + su) for x0, x1 in up_iv]
        if lo_iv or up_iv:
            result[pi].append(_Mod(ya, aisle_ivs[i], lo_iv, up_iv))
    return result


def _count(ivs: list[tuple[float, float]], lo: float, hi: float, p: float, block: tuple[float, float] | None) -> int:
    n = 0
    for a, b in ivs:
        if a < lo:
            a = lo
        if b > hi:
            b = hi
        if b - a + _LEN_TOL < p:
            continue
        if block is None:
            n += int((b - a + _LEN_TOL) // p)
        else:
            bl, bh = block
            e = b if b < bl else bl
            if e - a + _LEN_TOL >= p:
                n += int((e - a + _LEN_TOL) // p)
            s = a if a > bh else bh
            if b - s + _LEN_TOL >= p:
                n += int((b - s + _LEN_TOL) // p)
    return n


def _positions(
    ivs: list[tuple[float, float]], lo: float, hi: float, p: float, block: tuple[float, float] | None
) -> list[float]:
    """구획 접근변 시작 u 좌표. 연결차로 왼쪽 조각은 오른쪽 정렬, 오른쪽 조각은 왼쪽 정렬, 그 외 가운데."""
    pieces: list[tuple[float, float, int]] = []
    for a, b in ivs:
        a = max(a, lo)
        b = min(b, hi)
        if b - a + _LEN_TOL < p:
            continue
        if block is None:
            pieces.append((a, b, 0))
        else:
            bl, bh = block
            e = min(b, bl)
            if e - a + _LEN_TOL >= p:
                pieces.append((a, e, -1))
            s = max(a, bh)
            if b - s + _LEN_TOL >= p:
                pieces.append((s, b, 1))
    us: list[float] = []
    for a, b, side in pieces:
        n = int((b - a + _LEN_TOL) // p)
        slack = max(0.0, (b - a) - n * p)
        start = a + slack if side < 0 else (a if side > 0 else a + slack * 0.5)
        us.extend(start + i * p for i in range(n))
    return us


def _upper_bound(mods: list[_Mod], p: float) -> int:
    total = 0
    for m in mods:
        for x0, x1 in m.aisle_iv:
            total += _count(m.low_iv, x0, x1, p, None) + _count(m.up_iv, x0, x1, p, None)
    return total


def _containing(ivs: list[tuple[float, float]], a: float, b: float) -> tuple[float, float] | None:
    for x0, x1 in ivs:
        if x0 <= a + _Y_TOL and x1 >= b - _Y_TOL:
            return (x0, x1)
    return None


def _spine_candidates(fr: _Frame, spec: _Spec, mods: list[_Mod], extra: tuple[float, ...]) -> list[float]:
    As = spec.aisle_w
    cands: set[float] = set()
    for m in mods:
        for x0, x1 in m.aisle_iv:
            if x1 - x0 >= As - _LEN_TOL:
                cands.add(x0)
                cands.add(x1 - As)
    lo, hi = fr.xmin, fr.xmax - As
    if hi > lo:
        cands.update(np.linspace(lo, hi, _SPINE_GRID).tolist())
    cands.update(extra)
    return sorted(cands)


def _full_eval(
    fr: _Frame, spec: _Spec, mods: list[_Mod], extra_s: tuple[float, ...] = ()
) -> tuple[int, int, tuple] | None:
    """연결차로까지 고려한 최적 배치 선택. (구획 수, 차로 수, 선택) 또는 None."""
    p, A, D, k = spec.pitch, spec.aisle_w, spec.depth, spec.shear
    best: tuple[int, int, tuple] | None = None

    def better(c: int, n: int) -> bool:
        return best is None or c > best[0] or (c == best[0] and n < best[1])

    for mi, m in enumerate(mods):
        for iv in m.aisle_iv:
            c = _count(m.low_iv, iv[0], iv[1], p, None) + _count(m.up_iv, iv[0], iv[1], p, None)
            if c > 0 and better(c, 1):
                best = (c, 1, ("single", mi, iv))
    if len(mods) < 2:
        return best
    cands = _spine_candidates(fr, spec, mods, extra_s)
    s_arr = np.asarray(cands)
    J_all = _fit_intervals(fr.E0T, s_arr, s_arr + A)
    for s, Js in zip(cands, J_all, strict=True):
        block = (s - D * k, s + A)
        for jy0, jy1 in Js:
            if jy1 - jy0 < 2 * A:
                continue
            conn: list[tuple[int, tuple[float, float]]] = []
            for mi, m in enumerate(mods):
                if m.ya >= jy0 - _Y_TOL and m.ya + A <= jy1 + _Y_TOL:
                    iv = _containing(m.aisle_iv, s, s + A)
                    if iv is not None:
                        conn.append((mi, iv))
            if len(conn) < 2:
                continue
            last = len(conn) - 1
            c = 0
            for ci, (mi, iv) in enumerate(conn):
                m = mods[mi]
                c += _count(m.low_iv, iv[0], iv[1], p, None if ci == 0 else block)
                c += _count(m.up_iv, iv[0], iv[1], p, None if ci == last else block)
            if c > 0 and better(c, len(conn)):
                best = (c, len(conn), ("spine", s, tuple(conn)))
    return best


# ---------------------------------------------------------------------------
# 각도별 탐색
# ---------------------------------------------------------------------------
def _better(res: tuple[int, int, tuple], plan: _Plan | None) -> bool:
    return plan is None or res[0] > plan.count or (res[0] == plan.count and res[1] < plan.n_aisles)


def _eval_phases(
    fr: _Frame, spec: _Spec, phases: list[float], mods_list: list[list[_Mod]], floor: int, keep: int
) -> list[_Plan]:
    """상한(UB)이 높은 phase 부터 정밀 평가. UB 는 실제 구획 수의 상한이므로 floor 미만이면 건너뛴다."""
    ubs = [_upper_bound(m, spec.pitch) for m in mods_list]
    order = sorted(range(len(phases)), key=lambda i: (-ubs[i], phases[i]))
    found: list[_Plan] = []
    for i in order:
        if ubs[i] <= 0 or ubs[i] < floor:
            break
        res = _full_eval(fr, spec, mods_list[i])
        if res is None:
            continue
        found.append(_Plan(res[0], res[1], fr.idx, phases[i], mods_list[i], res[2]))
        found.sort(key=lambda pl: (-pl.count, pl.n_aisles, pl.phase))
        del found[keep:]
        if len(found) >= keep:
            floor = max(floor, found[-1].count)
    return found


def _search_angle(frames: list[_Frame], spec: _Spec) -> list[_Plan]:
    """한 주차형식에 대해 방향별 최적 배치 (구획 수 내림차순)."""
    P = spec.period
    coarse: list[tuple[int, int, list[float], list[list[_Mod]]]] = []
    for fr in frames:
        phases = _coarse_phases(fr, spec)
        mods_list = _modules_for_phases(fr, spec, phases)
        mx = max((_upper_bound(m, spec.pitch) for m in mods_list), default=0)
        if mx > 0:
            coarse.append((-mx, fr.idx, phases, mods_list))
    coarse.sort(key=lambda t: (t[0], t[1]))
    best: dict[int, _Plan] = {}
    for _nmx, fidx, phases, mods_list in coarse[:_TOP_ORIENTATIONS]:
        fr = frames[fidx]
        top = _eval_phases(fr, spec, phases, mods_list, 0, _REFINE_SEEDS)
        if not top:
            continue
        plan = top[0]
        for seed in top:  # 상위 phase 주변 미세 탐색
            ref = sorted({round((seed.phase + d) % P, 9) % P for d in _REFINE_PHASE_OFFSETS})
            for cand in _eval_phases(fr, spec, ref, _modules_for_phases(fr, spec, ref), plan.count, 1):
                if _better((cand.count, cand.n_aisles, ()), plan):
                    plan = cand
        if plan.choice[0] == "spine":  # 연결차로 위치 미세 조정
            s0 = plan.choice[1]
            res = _full_eval(fr, spec, plan.mods, tuple(s0 + d for d in _SPINE_REFINE))
            if res is not None and _better(res, plan):
                plan = _Plan(res[0], res[1], fidx, plan.phase, plan.mods, res[2])
        best[fidx] = plan
    return sorted(best.values(), key=lambda pl: (-pl.count, pl.n_aisles, pl.fidx))


# ---------------------------------------------------------------------------
# 기하 생성 (지역 회전 좌표계)
# ---------------------------------------------------------------------------
def _realize(
    spec: _Spec, plan: _Plan
) -> tuple[list[np.ndarray], list[np.ndarray], list[tuple[str, tuple[float, float, float, float]]]] | None:
    """구획 좌표(각 (5,2)), 접근변 좌표(각 (2,2)), 차로 사각형 [(kind,(x0,y0,x1,y1))]."""
    p, A, D, k = spec.pitch, spec.aisle_w, spec.depth, spec.shear
    mods = plan.mods
    if plan.choice[0] == "single":
        _, mi, iv = plan.choice
        conn = ((mi, iv),)
        s = None
    else:
        _, s, conn = plan.choice
    block = (s - D * k, s + A) if s is not None else None
    last = len(conn) - 1
    per: list[tuple[_Mod, tuple[float, float], list[float], list[float]]] = []
    for ci, (mi, iv) in enumerate(conn):
        m = mods[mi]
        lo_us = _positions(m.low_iv, iv[0], iv[1], p, None if (block is None or ci == 0) else block)
        up_us = _positions(m.up_iv, iv[0], iv[1], p, None if (block is None or ci == last) else block)
        if lo_us or up_us:
            per.append((m, iv, lo_us, up_us))
    if not per:
        return None
    use_spine = s is not None and len(per) >= 2
    stalls: list[np.ndarray] = []
    edges: list[np.ndarray] = []
    rects: list[tuple[str, tuple[float, float, float, float]]] = []
    dk = D * k
    for m, iv, lo_us, up_us in per:
        ya = m.ya
        yt = ya + A
        for u in lo_us:
            stalls.append(np.array([[u, ya], [u + p, ya], [u + p + dk, ya - D], [u + dk, ya - D], [u, ya]]))
            edges.append(np.array([[u, ya], [u + p, ya]]))
        for u in up_us:
            stalls.append(np.array([[u, yt], [u + p, yt], [u + p + dk, yt + D], [u + dk, yt + D], [u, yt]]))
            edges.append(np.array([[u, yt], [u + p, yt]]))
        us = lo_us + up_us
        x0, x1 = min(us), max(us) + p
        if use_spine:
            x0, x1 = min(x0, s), max(x1, s + A)
        x0, x1 = max(x0, iv[0]), min(x1, iv[1])
        rects.append(("aisle", (x0, ya, x1, yt)))
    if use_spine:
        rects.append(("spine", (s, per[0][0].ya, s + A, per[-1][0].ya + A)))
    return stalls, edges, rects


def _rect(x0: float, y0: float, x1: float, y1: float) -> Polygon:
    return Polygon([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


# ---------------------------------------------------------------------------
# 출입구
# ---------------------------------------------------------------------------
@dataclass
class _GateFit:
    point: np.ndarray
    opening: BaseGeometry
    connector: Polygon
    removed: list[int]
    score: tuple


class _GateBuilder:
    def __init__(self, site_l: Polygon, network: Polygon, stall_geoms: np.ndarray, spec: _Spec, setback: float):
        self.site = orient(site_l, 1.0)
        self.ext = LineString(self.site.exterior.coords)
        self.L = self.ext.length
        xy = np.asarray(self.ext.coords)
        self.ring_xy = xy
        self.ring2 = LineString(np.vstack([xy, xy[1:]]))
        self.cum = np.concatenate([[0.0], np.cumsum(np.hypot(*(xy[1:] - xy[:-1]).T))])
        self.network = network
        self.net_cover = network.buffer(1e-6, join_style="mitre")
        shapely.prepare(self.net_cover)
        self.stalls = stall_geoms
        self.tree = shapely.STRtree(stall_geoms) if len(stall_geoms) else None
        self.spec = spec
        self.w = spec.driveway_w
        self.min_opening = spec.gate_min if spec.gate_min is not None else 0.95 * spec.driveway_w
        self.ext_len = spec.aisle_w * 0.5
        self.setback = setback
        self.site_buf = self.site.buffer(1e-6)
        shapely.prepare(self.site_buf)
        minx, miny, maxx, maxy = self.site.bounds
        self.far = 2.0 * math.hypot(maxx - minx, maxy - miny) + 10.0

    # -- 위치 ----------------------------------------------------------------
    def _frame(self, d: float):
        w, L = self.w, self.L
        if self.ring2.length < w or L < w:
            return None
        dd = d % L
        if dd < w / 2:
            dd += L
        d0, d1 = dd - w / 2, dd + w / 2
        G = np.asarray(self.ring2.interpolate(dd).coords[0])
        P0 = np.asarray(self.ring2.interpolate(d0).coords[0])
        P1 = np.asarray(self.ring2.interpolate(d1).coords[0])
        t = P1 - P0
        nt = math.hypot(t[0], t[1])
        if nt < 0.5 * w:
            return None
        t = t / nt
        n = np.array([-t[1], t[0]])  # CCW 외곽 → 왼쪽이 내부
        span = np.asarray(substring(self.ring2, d0, d1).coords)
        dev = (span - G) @ n
        bulge = max(0.0, float(dev.max()))
        outward = max(0.0, -float(dev.min()))
        if bulge > _GATE_MAX_BULGE_M:
            return None
        return G, n, bulge, outward, d0, d1

    def _edge_clamped(self, d: float) -> float | None:
        """d 가 속한 변 안쪽으로 출입구 폭 전체가 들어가도록 옮긴 위치 (변이 짧으면 None)."""
        dd = d % self.L
        i = int(np.searchsorted(self.cum, dd, side="right") - 1)
        i = min(max(i, 0), len(self.cum) - 2)
        a, b = self.cum[i], self.cum[i + 1]
        if b - a < self.w:
            return None
        return min(max(dd, a + self.w / 2), b - self.w / 2)

    # -- 진입로 --------------------------------------------------------------
    def _make(self, frame: tuple, path_tail: list[np.ndarray], order: int):
        G, n, bulge, outward, d0, d1 = frame
        w = self.w
        start = G - n * (outward + _GATE_OVERSHOOT_M)
        core_start = G + n * (bulge + 1e-3)
        geom = LineString([start, *path_tail]).buffer(w / 2, cap_style="flat", join_style="mitre", mitre_limit=2.0)
        core = LineString([core_start, *path_tail]).buffer(w / 2, cap_style="flat", join_style="mitre", mitre_limit=2.0)
        if core.is_empty or not self.site_buf.contains(core):
            return None
        clipped = geom.intersection(self.site)
        parts = [g for g in geo.polygon_parts(clipped) if g.area > 1e-6]
        if len(parts) != 1:
            return None
        C = parts[0]
        if C.intersection(self.network).area <= 1e-6:
            return None
        if shapely.union(self.network, C).geom_type != "Polygon":
            return None
        opening = self._opening(C, G, d0, d1)
        if opening is None or opening.length < self.min_opening - 1e-6:
            return None
        removed: list[int] = []
        if self.tree is not None:
            for i in self.tree.query(C, predicate="intersects").tolist():
                if self.stalls[i].intersection(C).area > _AREA_TOL:
                    removed.append(int(i))
        removed.sort()
        return _GateFit(G, opening, C, removed, (len(removed), round(C.area, 4), order))

    def _opening(self, C: Polygon, G: np.ndarray, d0: float, d1: float) -> BaseGeometry | None:
        """출입구 개구부 = 출입구 중심 ±w/2 의 대지 경계선 중 진입로에 닿는 부분."""
        inter = substring(self.ring2, d0, d1).intersection(C.buffer(1e-6, join_style="mitre"))
        lines = [g for g in getattr(inter, "geoms", [inter]) if g.geom_type in ("LineString", "MultiLineString")]
        if not lines:
            return None
        merged = (
            linemerge(shapely.union_all(lines)) if len(lines) > 1 or lines[0].geom_type != "LineString" else lines[0]
        )
        cand = list(getattr(merged, "geoms", [merged]))
        gp = Point(G)
        cand = [c for c in cand if c.distance(gp) < 1e-6]
        if not cand:
            return None
        return max(cand, key=lambda g: g.length)

    def _options(self, d: float, order: int) -> list[_GateFit]:
        fr = self._frame(d)
        if fr is None:
            return []
        G, n, bulge, _outward, _d0, _d1 = fr
        out: list[_GateFit] = []
        # (1) 경계에 수직으로 곧장 차로망까지
        ray = LineString([G, G + n * self.far])
        hit = ray.intersection(self.network)
        if not hit.is_empty:
            h = max(0.0, float(((shapely.get_coordinates(hit) - G) @ n).min()))
            t = self._full_depth(G, n, h)
            if t is not None:
                f = self._make(fr, [G + n * (t + self.ext_len)], order)
                if f is not None:
                    out.append(f)
        # (2) 경계에서 수직으로 들어온 뒤 가장 가까운 차로망 점으로 꺾기
        r1 = max(bulge + 1e-3, self.setback) + self.w / 2
        P1 = G + n * r1
        P1pt = Point(P1)
        if self.network.distance(P1pt) > 1e-6:
            q = np.asarray(nearest_points(P1pt, self.network)[1].coords[0])
            v = q - P1
            dist = math.hypot(v[0], v[1])
            if dist > 1e-6:
                u = v / dist
                t = self._full_depth(q, u, 0.0) if float(u @ n) > -0.2 else None
                if t is not None:
                    f = self._make(fr, [P1, q + u * (t + self.ext_len)], order + 1)
                    if f is not None:
                        out.append(f)
        return out

    def _full_depth(self, origin: np.ndarray, dirv: np.ndarray, t0: float) -> float | None:
        """origin + dirv·t 에서 진행 방향에 수직인 차로 폭 단면이 차로망 안에 완전히 드는 최소 t.

        진입로가 차로망 모서리에 살짝만 걸쳐 통로가 차로 폭보다 좁아지는 것을 막는다.
        """
        half = self.spec.aisle_w / 2
        perp = np.array([-dirv[1], dirv[0]]) * half
        t = t0
        t_max = t0 + 2.0 * self.spec.aisle_w + self.w
        while t <= t_max:
            c = origin + dirv * t
            if self.net_cover.covers(LineString([c - perp, c + perp])):
                return t
            t += _GATE_DEPTH_STEP_M
        return None

    # -- 진입점 --------------------------------------------------------------
    def for_target(self, target: np.ndarray) -> _GateFit | None:
        d0 = self.ext.project(Point(target))
        positions = [d0]
        dc = self._edge_clamped(d0)
        if dc is not None:
            positions.append(dc)
        steps = int(math.ceil(2 * self.w / _GATE_SEARCH_STEP_M))
        for j in range(1, steps + 1):
            positions.extend([d0 + j * _GATE_SEARCH_STEP_M, d0 - j * _GATE_SEARCH_STEP_M])
        for oi, d in enumerate(positions):
            opts = self._options(d, 2 * oi)
            if opts:
                return min(opts, key=lambda f: f.score)
        # 근처에서 못 찾으면: 가장 가까운 '출입구 폭 이상의 변' 위로 이동
        best = None
        for i in range(len(self.cum) - 1):
            a, b = self.cum[i], self.cum[i + 1]
            if b - a < self.w:
                continue
            seg = LineString([self.ring_xy[i], self.ring_xy[i + 1]])
            proj = seg.project(Point(target))
            d = min(max(a + proj, a + self.w / 2), b - self.w / 2)
            dist = Point(self.ring2.interpolate(d).coords[0]).distance(Point(target))
            if best is None or dist < best[0]:
                best = (dist, d)
        if best is not None:
            opts = self._options(best[1], 10_000)
            if opts:
                return min(opts, key=lambda f: f.score)
        return None

    def auto(self, rects: list[tuple[str, tuple[float, float, float, float]]]) -> _GateFit | None:
        raw: list[float] = []
        pe, _pn = nearest_points(self.ext, self.network)
        raw.append(self.ext.project(pe))
        for kind, (x0, y0, x1, y1) in rects:
            if kind == "aisle":
                ym = (y0 + y1) / 2
                rays = [((x0, ym), (-1.0, 0.0)), ((x1, ym), (1.0, 0.0))]
            else:
                xm = (x0 + x1) / 2
                rays = [((xm, y0), (0.0, -1.0)), ((xm, y1), (0.0, 1.0))]
            for o, dv in rays:
                o_ = np.asarray(o)
                seg = LineString([o_, o_ + np.asarray(dv) * self.far])
                inter = seg.intersection(self.ext)
                pts = shapely.get_coordinates(inter)
                if len(pts):
                    q = pts[np.argmin(((pts - o_) ** 2).sum(axis=1))]
                    raw.append(self.ext.project(Point(q)))
        for i in range(len(self.cum) - 1):
            a, b = self.cum[i], self.cum[i + 1]
            if b - a < self.w:
                continue
            seg = LineString([self.ring_xy[i], self.ring_xy[i + 1]])
            q = nearest_points(seg, self.network)[0]
            raw.append(a + seg.project(q))
            raw.append((a + b) / 2)
        positions: list[float] = []
        seen: set[float] = set()
        for d in raw:
            for dd in (d, self._edge_clamped(d)):
                if dd is None:
                    continue
                key = round(dd % self.L, 3)
                if key in seen:
                    continue
                seen.add(key)
                positions.append(dd)
        best: _GateFit | None = None
        for oi, d in enumerate(positions):
            for f in self._options(d, 2 * oi):
                if best is None or f.score < best.score:
                    best = f
        return best


# ---------------------------------------------------------------------------
# 후보 → 최종 기하
# ---------------------------------------------------------------------------
def _local_to_abs_fn(fr: _Frame, origin: tuple[float, float]):
    c, s = fr.cos, fr.sin
    ox, oy = origin

    def fn(xy: np.ndarray) -> np.ndarray:
        x, y = xy[:, 0], xy[:, 1]
        return np.column_stack([ox + x * c - y * s, oy + x * s + y * c])

    return fn


def _abs_to_local(fr: _Frame, origin: tuple[float, float], xy: np.ndarray) -> np.ndarray:
    return _rot_neg(np.atleast_2d(xy) - np.asarray(origin), fr.cos, fr.sin)


def _build_candidate(
    spec: _Spec,
    fr: _Frame,
    plan: _Plan,
    origin: tuple[float, float],
    site: Polygon,
    usable_prep: BaseGeometry,
    setback: float,
    gate_target: np.ndarray | None,
) -> _Candidate | str:
    """배치안 → 출입구 연결 → EPSG:5179 기하. 실패하면 사유 문자열("EMPTY"|"NETWORK"|"GATE")."""
    real = _realize(spec, plan)
    if real is None:
        return "EMPTY"
    stall_xy, edge_xy, rects = real
    stalls_l = shapely.polygons(np.asarray(stall_xy))
    rect_polys = [_rect(*r) for _k, r in rects]
    network_l = shapely.union_all(rect_polys)
    if network_l.geom_type != "Polygon":
        return "NETWORK"
    c, s = fr.cos, fr.sin
    ox, oy = origin
    site_l = affinity.affine_transform(site, [c, s, -s, c, -(c * ox + s * oy), s * ox - c * oy])
    gb = _GateBuilder(site_l, network_l, stalls_l, spec, setback)
    reasons: list[str] = []
    gfit = gb.for_target(_abs_to_local(fr, origin, gate_target)[0]) if gate_target is not None else None
    if gfit is None:
        if gate_target is not None:
            # 지정 출입구에서 차로망까지 진입로를 낼 수 없음 → 자동 출입구로 대체하고 표시
            reasons.append(GATE_UNREACHABLE)
        gfit = gb.auto(rects)
    if gfit is None:
        return "GATE"
    keep = np.ones(len(stalls_l), dtype=bool)
    keep[gfit.removed] = False
    to_abs = _local_to_abs_fn(fr, origin)
    stalls_abs = shapely.transform(stalls_l[keep], to_abs)
    edges_abs = shapely.transform(shapely.linestrings(np.asarray(edge_xy)[keep]), to_abs)
    aisles_abs: list[tuple[str, Polygon, float]] = [
        (kind, shapely.transform(poly, to_abs), spec.aisle_w)
        for (kind, _r), poly in zip(rects, rect_polys, strict=True)
    ]
    aisles_abs.append(("driveway", shapely.transform(gfit.connector, to_abs), spec.driveway_w))
    # 안전망: 사용영역 밖 구획 제거 (정상이면 없음)
    inside = shapely.contains(usable_prep, stalls_abs)
    if not inside.all():
        stalls_abs = stalls_abs[inside]
        edges_abs = edges_abs[inside]
    if len(stalls_abs) == 0:
        return "EMPTY"
    network = shapely.union_all([a[1] for a in aisles_abs])
    if network.geom_type != "Polygon":
        return "NETWORK"
    gate_pt = shapely.transform(Point(gfit.point), to_abs)
    opening = shapely.transform(gfit.opening, to_abs)
    if gate_target is not None and gate_pt.distance(Point(gate_target)) > GATE_SNAP_WARN_M:
        reasons.append(GATE_SNAPPED)
    return _Candidate(
        spec=spec,
        fr=fr,
        plan=plan,
        stalls=list(stalls_abs),
        edges=list(edges_abs),
        aisles=aisles_abs,
        network=network,
        gate_point=gate_pt,
        gate_opening=opening,
        reasons=reasons,
    )


def _cand_key(c: _Candidate) -> tuple:
    return (-c.count, round(c.aisle_area, 6), _ANGLE_ORDER.index(c.spec.angle), c.fr.idx)


# ---------------------------------------------------------------------------
# 입력 준비
# ---------------------------------------------------------------------------
def _prepare_site(site_geojson: Any, reasons: list[str]) -> tuple[float, Polygon] | None:
    try:
        g = geo.geojson_to_shape(site_geojson)
    except geo.GeoJSONError:
        return None
    if g.is_empty or not geo.lnglat_bounds_ok(g):
        return None
    try:
        g_abs = geo.to_utmk(g)
    except Exception:
        return None
    if not geo.all_finite(g_abs):
        return None
    if not g_abs.is_valid:
        parts = [p for p in geo.polygon_parts(shapely.make_valid(g_abs)) if p.area > 1e-6]
        if not parts:
            return None
        reasons.append(SITE_GEOMETRY_REPAIRED)
        g_abs = parts[0] if len(parts) == 1 else MultiPolygon(parts)
    parts = [p for p in geo.polygon_parts(g_abs) if p.area > 1e-6]
    if not parts:
        return None
    total = float(sum(p.area for p in parts))
    site = parts[0]
    for p in parts[1:]:
        if p.area > site.area:
            site = p
    if len(parts) > 1:
        reasons.append(MULTIPART_SITE_LARGEST_USED)
    return total, orient(site, 1.0)


def _usable_area(site: Polygon, setback: float, reasons: list[str]) -> Polygon | None:
    u = site.buffer(-setback, join_style="mitre", mitre_limit=10.0) if setback > 0 else site
    parts = [p for p in geo.polygon_parts(u) if p.area > 1e-6]
    if not parts:
        return None
    if len(parts) > 1:
        reasons.append(USABLE_AREA_SPLIT_LARGEST_USED)
    best = parts[0]
    for p in parts[1:]:
        if p.area > best.area:
            best = p
    best = shapely.remove_repeated_points(best, 1e-9)
    return orient(best, 1.0)


def _gate_target(params: SimulationParams, reasons: list[str]) -> np.ndarray | None:
    gp = params.gate_point
    if gp is None:
        return None
    try:
        lng, lat = float(gp[0]), float(gp[1])
    except (TypeError, ValueError, IndexError):
        reasons.append(GATE_POINT_INVALID)
        return None
    if not (math.isfinite(lng) and math.isfinite(lat) and -180 <= lng <= 180 and -90 <= lat <= 90):
        reasons.append(GATE_POINT_INVALID)
        return None
    x, y = geo.lnglat_to_utmk(lng, lat)
    if not (math.isfinite(x) and math.isfinite(y)):
        reasons.append(GATE_POINT_INVALID)
        return None
    return np.array([x, y])


def _inscribed_width(poly: Polygon) -> float:
    try:
        line = shapely.maximum_inscribed_circle(poly, 0.01)
        return 2.0 * float(line.length)
    except Exception:  # pragma: no cover - 구버전 shapely 대비
        lo, hi = 0.0, max(poly.bounds[2] - poly.bounds[0], poly.bounds[3] - poly.bounds[1])
        for _ in range(30):
            mid = (lo + hi) / 2
            if poly.buffer(-mid).is_empty:
                hi = mid
            else:
                lo = mid
        return 2.0 * lo


def _add(reasons: list[str], code: str) -> None:
    if code not in reasons:
        reasons.append(code)


# ---------------------------------------------------------------------------
# 출력 변환
# ---------------------------------------------------------------------------
def _r2(v: float) -> float:
    return round(float(v) + 0.0, 2)


def _ori(phi: float) -> float:
    """방향(도)을 소수 2자리로, [0, 180) 범위 유지 (179.999 → 0.0)."""
    v = _r2(phi)
    return 0.0 if v >= 180.0 else v


def _to_alternative(
    code: str, cand: _Candidate, site_area: float, usable: Polygon, stall_type: str, base_reasons: list[str]
) -> Alternative:
    spec = cand.spec
    to_wgs = geo.to_wgs84
    stall_feats = []
    stalls_wgs = shapely.transform(_geom_array(cand.stalls), _wgs_fn())
    for i, sp in enumerate(stalls_wgs):
        stall_feats.append(
            geo.feature(
                sp,
                {
                    "id": f"P-{i + 1:03d}",
                    "stall_type": stall_type,
                    "angle": spec.angle,
                    "width_m": _r2(spec.stall_w),
                    "length_m": _r2(spec.stall_l),
                },
            )
        )
    aisle_feats = [
        geo.feature(to_wgs(poly), {"kind": kind, "width_m": _r2(width)}) for kind, poly, width in cand.aisles
    ]
    opening_len = float(cand.gate_opening.length)
    layers = {
        "stalls": geo.feature_collection(stall_feats),
        "aisles": geo.feature_collection(aisle_feats),
        "gate": geo.feature(to_wgs(cand.gate_point), {"width_m": _r2(spec.driveway_w)}),
        "gate_opening": geo.feature(to_wgs(cand.gate_opening), {"width_m": _r2(opening_len)}),
        "usable_area": geo.feature(to_wgs(usable), {"area_m2": _r2(usable.area)}),
    }
    usable_area = float(usable.area)
    stall_area = cand.stall_area
    aisle_area = cand.aisle_area
    n = cand.count
    metrics = {
        "site_area_m2": _r2(site_area),
        "usable_area_m2": _r2(usable_area),
        "parking_area_m2": _r2(stall_area + aisle_area),
        "stall_area_m2": _r2(stall_area),
        "stall_footprint_m2": _r2(cand.stall_footprint),
        "aisle_area_m2": _r2(aisle_area),
        "efficiency_percent": _r2(stall_area / usable_area * 100.0) if usable_area > 0 else 0.0,
        "area_per_stall_m2": _r2(usable_area / n) if n else None,
        "aisle_width_m": _r2(spec.aisle_w),
        "stall_width_m": _r2(spec.stall_w),
        "stall_length_m": _r2(spec.stall_l),
        "orientation_deg": _ori(cand.fr.phi),
        "stall_count": n,
        "gate_width_m": _r2(spec.driveway_w),
        "stall_row_depth_m": _r2(spec.depth),
        "stall_pitch_m": _r2(spec.pitch),
    }
    reasons = [r for r in base_reasons]
    for r in cand.reasons:
        _add(reasons, r)
    return Alternative(
        alt_code=code,
        parking_angle=spec.angle,
        stall_type=stall_type,  # type: ignore[arg-type]
        orientation_deg=_ori(cand.fr.phi),
        stall_count=n,
        layers=layers,
        metrics=metrics,
        constraint_reasons=reasons,
        is_recommended=False,
    )


def _geom_array(geoms: list[BaseGeometry]) -> np.ndarray:
    arr = np.empty(len(geoms), dtype=object)
    arr[:] = geoms
    return arr


def _wgs_fn():
    tr = geo.get_transformer(geo.UTMK, geo.WGS84)

    def fn(xy: np.ndarray) -> np.ndarray:
        x, y = tr.transform(xy[:, 0], xy[:, 1])
        return np.column_stack([x, y])

    return fn


def _alt_code(i: int) -> str:
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(ord("A") + r) + s
    return f"ALT_{s}"


# ---------------------------------------------------------------------------
# 공개 API
# ---------------------------------------------------------------------------
def simulate(site_geojson: dict, dims: ParkingDimensions, params: SimulationParams | None = None) -> SimulationResult:
    """대지 GeoJSON(EPSG:4326) 과 법정 치수로 주차 배치 대안을 만든다."""
    return simulate_detailed(site_geojson, dims, params)[0]


def simulate_detailed(
    site_geojson: dict, dims: ParkingDimensions, params: SimulationParams | None = None
) -> tuple[SimulationResult, list[LayoutGeometry]]:
    """simulate() 와 같고, 대안별 내부 기하(EPSG:5179)를 함께 돌려준다."""
    params = params or SimulationParams()
    reasons: list[str] = []
    prepared = _prepare_site(site_geojson, reasons)
    if prepared is None:
        return SimulationResult(alternatives=[], constraint_reasons=[SITE_GEOMETRY_INVALID], site_area_m2=None), []
    site_area, site = prepared
    setback = max(0.0, float(params.setback_m or 0.0))
    usable = _usable_area(site, setback, reasons)

    specs: list[_Spec] = []
    seen_angles: set[str] = set()
    for angle in params.angles or []:
        if angle in seen_angles:
            continue
        seen_angles.add(angle)
        spec = _resolve_spec(angle, dims, params)
        if spec is None:
            _add(reasons, f"{DIMENSION_MISSING}:{angle}")
        else:
            specs.append(spec)
    gate_target = _gate_target(params, reasons)

    if usable is None:
        if specs:
            _add(reasons, LIMIT_SITE_WIDTH_NARROW)
            for spec in specs:
                _add(reasons, f"{LIMIT_SITE_WIDTH_NARROW}:{spec.angle}")
        _add(reasons, NO_FEASIBLE_LAYOUT)
        return SimulationResult(alternatives=[], constraint_reasons=reasons, site_area_m2=_r2(site_area)), []

    cx, cy = usable.centroid.coords[0]
    origin = (round(cx, 3), round(cy, 3))
    rings = [np.asarray(usable.exterior.coords) - origin] + [np.asarray(r.coords) - origin for r in usable.interiors]
    step = float(params.orientation_step_deg or 5.0)
    if not math.isfinite(step) or step <= 0:
        step = 5.0
    step = min(max(step, 0.5), 90.0)
    frames = _build_frames(rings, _orientation_candidates(usable, site, step))
    usable_prep = usable.buffer(_CONTAIN_TOL, join_style="mitre")
    shapely.prepare(usable_prep)

    per_angle: dict[str, list[_Candidate]] = {}
    inscribed = _inscribed_width(usable) if specs else 0.0
    for spec in specs:
        # 가장 넓은 곳의 폭조차 편측 모듈(구획열 + 차로) 깊이에 못 미치면 '좁은 대지'
        # (좁은 방향으로 모듈을 돌려 끝단에만 몇 대 두는 퇴화 배치는 만들지 않는다)
        if inscribed + _NARROW_TOL_M < spec.depth + spec.aisle_w:
            _add(reasons, LIMIT_SITE_WIDTH_NARROW)
            _add(reasons, f"{LIMIT_SITE_WIDTH_NARROW}:{spec.angle}")
            continue
        plans = _search_angle(frames, spec)
        finals: list[_Candidate] = []
        tried: list[float] = []
        for plan in plans:
            fr = frames[plan.fidx]
            if any(_angle_gap(fr.phi, t) < _MIN_ORIENTATION_GAP_DEG for t in tried):
                continue
            cand = _build_candidate(spec, fr, plan, origin, site, usable_prep, setback, gate_target)
            if isinstance(cand, str):
                continue
            tried.append(fr.phi)
            finals.append(cand)
            if len(finals) >= _FINAL_PER_ANGLE:
                break
        finals.sort(key=_cand_key)
        # 같은 형식의 다른 방향 대안은 최선의 절반 이상일 때만 (의미 없는 대안 제외)
        finals = [c for i, c in enumerate(finals) if i == 0 or c.count >= _SECONDARY_MIN_RATIO * finals[0].count]
        if finals:
            per_angle[spec.angle] = finals
        else:
            _add(reasons, f"{NO_FEASIBLE_LAYOUT}:{spec.angle}")

    # 대안 고르기: 각도 우선 다양화 → 같은 각도 내 다른 방향
    max_alt = max(1, int(params.max_alternatives or 1))
    ranked_angles = sorted(per_angle, key=lambda a: _cand_key(per_angle[a][0]))
    chosen: list[_Candidate] = []
    rank = 0
    while len(chosen) < max_alt:
        added = False
        for a in ranked_angles:
            lst = per_angle[a]
            if rank < len(lst) and len(chosen) < max_alt:
                chosen.append(lst[rank])
                added = True
        if not added:
            break
        rank += 1
    chosen.sort(key=_cand_key)

    if not chosen:
        _add(reasons, NO_FEASIBLE_LAYOUT)
    base_reasons = [
        r for r in reasons if r in (MULTIPART_SITE_LARGEST_USED, USABLE_AREA_SPLIT_LARGEST_USED, SITE_GEOMETRY_REPAIRED)
    ]
    alternatives: list[Alternative] = []
    geoms: list[LayoutGeometry] = []
    for i, cand in enumerate(chosen):
        code = _alt_code(i)
        alt = _to_alternative(code, cand, site_area, usable, params.stall_type, base_reasons)
        alternatives.append(alt)
        for r in cand.reasons:
            _add(reasons, r)
        spec = cand.spec
        geoms.append(
            LayoutGeometry(
                alt_code=code,
                parking_angle=spec.angle,
                orientation_deg=cand.fr.phi,
                site=site,
                usable=usable,
                stalls=cand.stalls,
                access_edges=cand.edges,
                aisles=cand.aisles,
                aisle_union=cand.network,
                gate_point=cand.gate_point,
                gate_opening=cand.gate_opening,
                aisle_width_m=spec.aisle_w,
                driveway_width_m=spec.driveway_w,
                stall_width_m=spec.stall_w,
                stall_length_m=spec.stall_l,
                theta_deg=spec.theta_deg,
                row_depth_m=spec.depth,
                pitch_m=spec.pitch,
            )
        )
    if alternatives:
        alternatives[0].is_recommended = True
    return SimulationResult(alternatives=alternatives, constraint_reasons=reasons, site_area_m2=_r2(site_area)), geoms
