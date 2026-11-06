"""
주차 배치용 좌표·기하 유틸리티.

- 입출력 좌표: EPSG:4326 GeoJSON ([lng, lat])
- 내부 계산 좌표: EPSG:5179 (UTM-K, m)

pyproj Transformer 는 모듈 수준에서 캐시한다 (pyproj >= 3.1 에서 스레드 안전).
I/O 없음, 순수 함수만 둔다.
"""

from __future__ import annotations

import math
from collections.abc import Iterable
from functools import lru_cache
from typing import Any

import numpy as np
import shapely
from pyproj import Transformer
from shapely.geometry import (
    GeometryCollection,
    LinearRing,
    LineString,
    MultiLineString,
    MultiPoint,
    MultiPolygon,
    Point,
    Polygon,
    shape,
)
from shapely.geometry.base import BaseGeometry
from shapely.geometry.polygon import orient

WGS84 = "EPSG:4326"
UTMK = "EPSG:5179"
COORD_PRECISION = 7  # GeoJSON 출력 소수 자릿수 (약 1cm)


class GeoJSONError(ValueError):
    """GeoJSON 을 다각형(Polygon/MultiPolygon)으로 해석할 수 없음."""


# ---------------------------------------------------------------------------
# 좌표 변환
# ---------------------------------------------------------------------------
@lru_cache(maxsize=8)
def get_transformer(src: str, dst: str) -> Transformer:
    """(src, dst) 별로 한 번만 만드는 always_xy Transformer."""
    return Transformer.from_crs(src, dst, always_xy=True)


def _transform_geom(geom: BaseGeometry, transformer: Transformer) -> BaseGeometry:
    def _fn(coords: np.ndarray) -> np.ndarray:
        x, y = transformer.transform(coords[:, 0], coords[:, 1])
        return np.column_stack([np.asarray(x, dtype=float), np.asarray(y, dtype=float)])

    return shapely.transform(geom, _fn)


def to_utmk(geom: BaseGeometry) -> BaseGeometry:
    """EPSG:4326(lng, lat) 기하 → EPSG:5179(m)."""
    return _transform_geom(geom, get_transformer(WGS84, UTMK))


def to_wgs84(geom: BaseGeometry) -> BaseGeometry:
    """EPSG:5179(m) 기하 → EPSG:4326(lng, lat)."""
    return _transform_geom(geom, get_transformer(UTMK, WGS84))


def lnglat_to_utmk(lng: float, lat: float) -> tuple[float, float]:
    x, y = get_transformer(WGS84, UTMK).transform(lng, lat)
    return float(x), float(y)


def utmk_to_lnglat(x: float, y: float) -> tuple[float, float]:
    lng, lat = get_transformer(UTMK, WGS84).transform(x, y)
    return float(lng), float(lat)


# ---------------------------------------------------------------------------
# GeoJSON ↔ shapely
# ---------------------------------------------------------------------------
def _polygonal_parts(geom: BaseGeometry) -> list[Polygon]:
    if geom is None or geom.is_empty:
        return []
    if isinstance(geom, Polygon):
        return [geom]
    if isinstance(geom, MultiPolygon):
        return [g for g in geom.geoms if not g.is_empty]
    if isinstance(geom, GeometryCollection):
        out: list[Polygon] = []
        for g in geom.geoms:
            out.extend(_polygonal_parts(g))
        return out
    return []


def polygon_parts(geom: BaseGeometry) -> list[Polygon]:
    """기하에서 면(Polygon) 부분만 꺼낸다 (GeometryCollection 포함)."""
    return _polygonal_parts(geom)


def largest_polygon(geom: BaseGeometry) -> Polygon | None:
    """가장 넓은 Polygon (동률이면 앞의 것)."""
    best: Polygon | None = None
    for p in _polygonal_parts(geom):
        if best is None or p.area > best.area:
            best = p
    return best


def geojson_to_shape(obj: dict[str, Any]) -> Polygon | MultiPolygon:
    """GeoJSON Geometry / Feature / FeatureCollection → Polygon 또는 MultiPolygon.

    좌표는 [lng, lat] 로 가정한다. 면이 아닌 기하는 GeoJSONError.
    """
    if not isinstance(obj, dict):
        raise GeoJSONError("GeoJSON object must be a dict")
    gtype = obj.get("type")
    if gtype == "Feature":
        geometry = obj.get("geometry")
        if not isinstance(geometry, dict):
            raise GeoJSONError("Feature has no geometry")
        return geojson_to_shape(geometry)
    if gtype == "FeatureCollection":
        polys: list[Polygon] = []
        for feat in obj.get("features") or []:
            polys.extend(_polygonal_parts(geojson_to_shape(feat)))
        if not polys:
            raise GeoJSONError("FeatureCollection has no polygonal features")
        return polys[0] if len(polys) == 1 else MultiPolygon(polys)
    if gtype not in ("Polygon", "MultiPolygon"):
        raise GeoJSONError(f"unsupported geometry type: {gtype!r}")
    try:
        geom = shape(obj)
    except Exception as exc:  # 잘못된 중첩·좌표 등
        raise GeoJSONError(f"invalid {gtype} coordinates: {exc}") from exc
    if not isinstance(geom, Polygon | MultiPolygon):
        raise GeoJSONError(f"unsupported geometry type: {geom.geom_type}")
    return geom


def _round_coords(coords: Iterable[Iterable[float]], precision: int) -> list[list[float]]:
    out: list[list[float]] = []
    for c in coords:
        pt = [round(float(c[0]), precision), round(float(c[1]), precision)]
        if out and out[-1] == pt:
            continue  # 반올림으로 생긴 연속 중복점 제거
        out.append(pt)
    return out


def _ring(ring: LinearRing, precision: int) -> list[list[float]]:
    pts = _round_coords(ring.coords, precision)
    if pts and pts[0] != pts[-1]:
        pts.append(list(pts[0]))
    return pts


def shape_to_geojson(geom: BaseGeometry, precision: int = COORD_PRECISION) -> dict[str, Any]:
    """shapely 기하 → GeoJSON geometry dict (좌표 반올림, RFC 7946 링 방향)."""
    if isinstance(geom, Point):
        return {"type": "Point", "coordinates": [round(geom.x, precision), round(geom.y, precision)]}
    if isinstance(geom, MultiPoint):
        return {
            "type": "MultiPoint",
            "coordinates": [[round(p.x, precision), round(p.y, precision)] for p in geom.geoms],
        }
    if isinstance(geom, LineString):
        return {"type": "LineString", "coordinates": _round_coords(geom.coords, precision)}
    if isinstance(geom, MultiLineString):
        return {"type": "MultiLineString", "coordinates": [_round_coords(g.coords, precision) for g in geom.geoms]}
    if isinstance(geom, Polygon):
        p = orient(geom, 1.0)
        return {
            "type": "Polygon",
            "coordinates": [_ring(p.exterior, precision)] + [_ring(r, precision) for r in p.interiors],
        }
    if isinstance(geom, MultiPolygon):
        coords = []
        for g in geom.geoms:
            p = orient(g, 1.0)
            coords.append([_ring(p.exterior, precision)] + [_ring(r, precision) for r in p.interiors])
        return {"type": "MultiPolygon", "coordinates": coords}
    if isinstance(geom, GeometryCollection):
        return {"type": "GeometryCollection", "geometries": [shape_to_geojson(g, precision) for g in geom.geoms]}
    raise GeoJSONError(f"unsupported geometry type: {geom.geom_type}")


def feature(
    geom: BaseGeometry, properties: dict[str, Any] | None = None, precision: int = COORD_PRECISION
) -> dict[str, Any]:
    return {"type": "Feature", "geometry": shape_to_geojson(geom, precision), "properties": dict(properties or {})}


def feature_collection(features: list[dict[str, Any]]) -> dict[str, Any]:
    return {"type": "FeatureCollection", "features": features}


# ---------------------------------------------------------------------------
# 면적·검사
# ---------------------------------------------------------------------------
def area_m2(geom_wgs84: BaseGeometry) -> float:
    """EPSG:4326 기하의 면적(㎡). EPSG:5179 로 투영해 계산한다."""
    return float(to_utmk(geom_wgs84).area)


def lnglat_bounds_ok(geom: BaseGeometry) -> bool:
    """경위도 범위(-180..180, -90..90)와 유한값 여부."""
    if geom.is_empty:
        return False
    minx, miny, maxx, maxy = geom.bounds
    vals = (minx, miny, maxx, maxy)
    if not all(math.isfinite(v) for v in vals):
        return False
    return -180.0 <= minx <= maxx <= 180.0 and -90.0 <= miny <= maxy <= 90.0


def all_finite(geom: BaseGeometry) -> bool:
    coords = shapely.get_coordinates(geom)
    return bool(coords.size) and bool(np.isfinite(coords).all())
