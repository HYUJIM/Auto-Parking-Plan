import math

import pytest
from shapely.geometry import MultiPolygon, Point, Polygon, box

from app.parking import geo
from tests.parking._helpers import X0, Y0, local_to_geojson


def test_transformers_cached_and_roundtrip():
    assert geo.get_transformer(geo.WGS84, geo.UTMK) is geo.get_transformer(geo.WGS84, geo.UTMK)
    x, y = geo.lnglat_to_utmk(128.63, 35.85)
    # UTM-K 대구 부근 대략값 (false easting 1,000,000 / northing 2,000,000)
    assert 1_000_000 < x < 1_200_000 and 1_700_000 < y < 1_800_000
    lng, lat = geo.utmk_to_lnglat(x, y)
    assert abs(lng - 128.63) < 1e-9 and abs(lat - 35.85) < 1e-9


def test_geometry_roundtrip_and_area():
    sq = box(X0, Y0, X0 + 50, Y0 + 60)
    wgs = geo.to_wgs84(sq)
    assert 128 < wgs.bounds[0] < 129 and 35 < wgs.bounds[1] < 36
    back = geo.to_utmk(wgs)
    assert abs(back.area - 3000.0) < 1e-6
    assert abs(geo.area_m2(wgs) - 3000.0) < 1e-6


def test_geojson_to_shape_variants():
    g = local_to_geojson(box(0, 0, 10, 10))
    assert isinstance(geo.geojson_to_shape(g), Polygon)
    feat = {"type": "Feature", "geometry": g, "properties": {}}
    assert isinstance(geo.geojson_to_shape(feat), Polygon)
    fc = {
        "type": "FeatureCollection",
        "features": [feat, {"type": "Feature", "geometry": local_to_geojson(box(20, 0, 30, 10)), "properties": {}}],
    }
    assert isinstance(geo.geojson_to_shape(fc), MultiPolygon)
    mp = local_to_geojson(MultiPolygon([box(0, 0, 1, 1), box(3, 3, 4, 4)]))
    assert isinstance(geo.geojson_to_shape(mp), MultiPolygon)
    for bad in (
        {"type": "Point", "coordinates": [1, 2]},
        {"type": "Polygon", "coordinates": "x"},
        [],
        {"type": "FeatureCollection", "features": []},
    ):
        with pytest.raises(geo.GeoJSONError):
            geo.geojson_to_shape(bad)  # type: ignore[arg-type]


def test_shape_to_geojson_rounding_and_orientation():
    cw = Polygon([(128.123456789, 35.1), (128.123456789, 35.2), (128.2, 35.2), (128.2, 35.1)])  # 시계 방향
    gj = geo.shape_to_geojson(cw)
    ring = gj["coordinates"][0]
    assert ring[0] == ring[-1]
    assert ring[0][0] == 128.1234568
    assert Polygon(ring).exterior.is_ccw  # RFC 7946: 외곽 반시계
    assert geo.shape_to_geojson(Point(1.123456789, 2.0)) == {"type": "Point", "coordinates": [1.1234568, 2.0]}
    f = geo.feature(Point(1, 2), {"a": 1})
    assert f["type"] == "Feature" and f["properties"] == {"a": 1}
    assert geo.feature_collection([f])["features"] == [f]


def test_bounds_check_detects_swapped_coordinates():
    swapped = Polygon([(35.85, 128.63), (35.86, 128.63), (35.86, 128.64)])
    assert not geo.lnglat_bounds_ok(swapped)
    assert geo.lnglat_bounds_ok(geo.geojson_to_shape(local_to_geojson(box(0, 0, 5, 5))))


def test_largest_polygon():
    mp = MultiPolygon([box(0, 0, 1, 1), box(5, 5, 8, 8)])
    assert math.isclose(geo.largest_polygon(mp).area, 9.0)
    assert geo.largest_polygon(Point(0, 0)) is None
