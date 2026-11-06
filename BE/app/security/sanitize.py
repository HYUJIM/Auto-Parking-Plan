"""입력·출력 정화: 링크는 http(s) 만, GeoJSON 경계는 모양·좌표 범위·꼭짓점 수를 확인한다."""
from __future__ import annotations

import math
from typing import Any
from urllib.parse import urlsplit

from shapely.geometry import shape

# 대한민국 영역을 넉넉히 감싸는 경위도 범위 (EPSG:4326)
KOREA_BOUNDS = (124.0, 33.0, 132.0, 39.0)
MAX_VERTICES = 50_000
_CTRL = frozenset(chr(c) for c in range(0x20)) | {"\x7f"}


def safe_url(url: Any) -> str | None:
    """화면에서 링크(<a href>)로 쓰일 값. http/https 이고 호스트가 있을 때만 그대로, 아니면 None.

    javascript:, data:, vbscript:, file:, 상대경로, 제어문자(줄바꿈 등)가 섞인 값은 버린다 → 저장형 XSS 차단.
    """
    if not isinstance(url, str):
        return None
    u = url.strip()
    if not u or len(u) > 2048 or any(ch in _CTRL for ch in u):
        return None
    try:
        parts = urlsplit(u)
    except ValueError:
        return None
    if parts.scheme.lower() not in ("http", "https") or not parts.hostname:
        return None
    return u


class GeometryError(ValueError):
    pass


def _iter_coords(coords: Any, depth: int = 0):  # noqa: ANN202
    if depth > 4:  # MultiPolygon 도 [다각형[고리[점[x, y]]]] 4단계면 끝난다 → 깊은 중첩으로 재귀 한도를 노리는 입력 차단
        raise GeometryError("coordinates 중첩이 너무 깊습니다")
    if isinstance(coords, (list, tuple)) and coords and isinstance(coords[0], (int, float)) and not isinstance(coords[0], bool):
        yield coords
        return
    if not isinstance(coords, (list, tuple)):
        raise GeometryError("coordinates 형식이 올바르지 않습니다")
    for c in coords:
        yield from _iter_coords(c, depth + 1)


def validate_zone_geometry(geom: dict[str, Any]) -> dict[str, Any]:
    """관리자가 등록하는 L4 구역 경계. Polygon/MultiPolygon, 유한한 경위도, 대한민국 범위, 꼭짓점 상한, 유효한 도형."""
    if not isinstance(geom, dict) or geom.get("type") not in ("Polygon", "MultiPolygon"):
        raise GeometryError("geometry 는 GeoJSON Polygon 또는 MultiPolygon 이어야 합니다")
    if set(geom) - {"type", "coordinates"}:
        raise GeometryError("geometry 에는 type, coordinates 만 둘 수 있습니다")
    n = 0
    minx, miny, maxx, maxy = KOREA_BOUNDS
    for pt in _iter_coords(geom.get("coordinates")):
        n += 1
        if n > MAX_VERTICES:
            raise GeometryError(f"꼭짓점이 너무 많습니다 (최대 {MAX_VERTICES:,}개)")
        if len(pt) < 2 or not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in pt[:2]):
            raise GeometryError("좌표는 유한한 [경도, 위도] 숫자여야 합니다")
        if not (minx <= pt[0] <= maxx and miny <= pt[1] <= maxy):
            raise GeometryError("좌표가 대한민국 경위도 범위(EPSG:4326)를 벗어났습니다")
    if n == 0:
        raise GeometryError("좌표가 없습니다")
    try:
        g = shape(geom)
    except Exception as e:  # noqa: BLE001  shapely 는 형식 오류마다 다른 예외를 던진다
        raise GeometryError("geometry 를 해석할 수 없습니다") from e
    if g.is_empty or not g.is_valid or g.area <= 0:
        raise GeometryError("유효한 면(자기 교차 없음, 넓이 > 0)이 아닙니다")
    return {"type": geom["type"], "coordinates": geom["coordinates"]}
