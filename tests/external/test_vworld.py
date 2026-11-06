import httpx
import pytest

from app.external.types import UpstreamError
from app.external.vworld import (
    VWorldClient,
    merge_land_characteristics,
    normalize_multipolygon,
    parse_cadastral_response,
    parse_ladfrl_response,
    parse_land_characteristics_response,
    parse_land_use_response,
)

from .conftest import json_response, load_fixture

SECRET = "SECRET-VWORLD-KEY-5678"
PNU = "2726010100190010004"


def _client(http, **kw) -> VWorldClient:
    return VWorldClient(
        SECRET, base_url_data="https://vw.example", base_url_ned="https://ned.example", client=http, **kw
    )


# ---------------------------------------------------------------- 연속지적도
def test_parse_cadastral_ok():
    (parcel,) = parse_cadastral_response(load_fixture("synthetic_vworld_cadastral_ok.json"))
    assert parcel.pnu == PNU
    assert parcel.jibun == "9001-4대"
    assert parcel.address == "대구광역시 수성구 범어동 9001-4"
    assert parcel.official_land_price == 3850000
    assert parcel.source == "vworld:LP_PA_CBND_BUBUN"
    assert parcel.fetched_at.tzinfo is not None
    assert parcel.geometry["type"] == "MultiPolygon"
    lng, lat = parcel.geometry["coordinates"][0][0][0]
    assert 124 < lng < 132 and 33 < lat < 39
    assert parcel.raw["bonbun"] == "9001"


def test_normalize_polygon_closes_ring_and_wraps():
    g = normalize_multipolygon(
        {"type": "Polygon", "coordinates": [[[128.1, 35.1, 12.0], [128.2, 35.1], [128.2, 35.2]]]}
    )
    assert g == {
        "type": "MultiPolygon",
        "coordinates": [[[[128.1, 35.1], [128.2, 35.1], [128.2, 35.2], [128.1, 35.1]]]],
    }


def test_normalize_swaps_lat_lng_order():
    g = normalize_multipolygon(
        {"type": "MultiPolygon", "coordinates": [[[[35.1, 128.1], [35.1, 128.2], [35.2, 128.2], [35.1, 128.1]]]]}
    )
    assert g["coordinates"][0][0][0] == [128.1, 35.1]


@pytest.mark.parametrize(
    "geom", [None, {"type": "Point", "coordinates": [1, 2]}, {"type": "Polygon", "coordinates": [[[1, 2]]]}]
)
def test_normalize_rejects(geom):
    assert normalize_multipolygon(geom) is None


def test_get_parcel_params_and_result(mock_http):
    http, rec = mock_http(load_fixture("synthetic_vworld_cadastral_ok.json"))
    parcel = _client(http, domain="https://app.example").get_parcel(PNU)
    assert parcel is not None and parcel.pnu == PNU
    req = rec.requests[0]
    assert (req.url.host, req.url.path) == ("vw.example", "/req/data")
    p = rec.params
    assert p["service"] == "data" and p["request"] == "GetFeature" and p["data"] == "LP_PA_CBND_BUBUN"
    assert p["attrFilter"] == f"pnu:=:{PNU}"
    assert p["crs"] == "EPSG:4326" and p["format"] == "json" and p["geometry"] == "true" and p["attribute"] == "true"
    assert p["key"] == SECRET and p["domain"] == "https://app.example"


def test_get_parcel_at_uses_point_filter(mock_http):
    http, rec = mock_http(load_fixture("synthetic_vworld_cadastral_ok.json"))
    assert _client(http).get_parcel_at(128.6261, 35.8605).pnu == PNU
    assert rec.params["geomFilter"] == "POINT(128.62610000 35.86050000)"
    assert "domain" not in rec.params
    with pytest.raises(ValueError):
        _client(http).get_parcel_at(float("nan"), 35.0)


def test_get_parcel_not_found_is_none(mock_http):
    http, _ = mock_http(load_fixture("synthetic_vworld_not_found.json"))
    assert _client(http).get_parcel(PNU) is None


def test_get_parcel_invalid_key(mock_http):
    http, rec = mock_http(load_fixture("observed_vworld_data_invalid_key.json"))
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_parcel(PNU)
    assert ei.value.retriable is False and "INVALID_KEY" in ei.value.message
    assert SECRET not in str(ei.value)
    assert len(rec.requests) == 1


def test_get_parcel_system_error_body_is_retriable(mock_http):
    http, _ = mock_http(load_fixture("synthetic_vworld_system_error.json"))
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_parcel(PNU)
    assert ei.value.retriable is True


def test_error_text_echoing_key_is_scrubbed(mock_http):
    payload = {
        "response": {
            "status": "ERROR",
            "error": {"level": "1", "code": "INVALID_TYPE", "text": f"입력한 파라미터 값 : <{SECRET}>"},
        }
    }
    http, _ = mock_http(payload)
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_parcel(PNU)
    assert SECRET not in str(ei.value)


def test_invalid_pnu_rejected_before_call(mock_http):
    http, rec = mock_http({})
    with pytest.raises(ValueError):
        _client(http).get_parcel("12345")
    assert rec.requests == []


# ---------------------------------------------------------------- 토지이용계획
def test_parse_land_use_kinds_dedup_and_conflicts():
    items = parse_land_use_response(load_fixture("synthetic_ned_landuse_ok.json"), pnu=PNU)
    by_code = {d.code: d for d in items}
    assert len(items) == 8  # UQA122 두 줄이 하나로 합쳐짐
    z = by_code["UQA122"]
    assert (z.kind, z.name, z.conflict) == ("zoning", "제2종일반주거지역", "included")  # 접함+포함 → 가장 강한 포함
    assert z.raw["_merged_records"] == 2
    assert z.basis_law is None and z.source == "vworld:getLandUseAttr" and z.fetched_at.tzinfo is not None
    assert by_code["UQA01X"].kind == "other"
    assert (by_code["UQQ300"].kind, by_code["UQQ300"].conflict) == ("zone", "included")
    assert (by_code["UQF110"].kind, by_code["UQF110"].conflict) == ("district", "partially_included")
    assert by_code["UOA120"].kind == "other"
    assert (by_code["ZA0011"].kind, by_code["ZA0011"].conflict) == ("other", "adjacent")
    assert (by_code["UQS121"].name, by_code["UQS121"].conflict) == ("소로2류(폭 8M~10M)", "unknown")


def test_parse_land_use_filters_other_pnu():
    payload = load_fixture("synthetic_ned_landuse_ok.json")
    assert parse_land_use_response(payload, pnu="2726010100190019999") == []


def test_parse_land_use_empty_envelope():
    assert parse_land_use_response(load_fixture("synthetic_ned_empty.json")) == []


@pytest.mark.parametrize(
    ("fixture", "retriable"),
    [("observed_ned_landuse_invalid_key.json", False), ("synthetic_ned_error_envelope.json", True)],
)
def test_parse_land_use_errors(fixture, retriable):
    with pytest.raises(UpstreamError) as ei:
        parse_land_use_response(load_fixture(fixture))
    assert ei.value.retriable is retriable


def test_get_land_use_params(mock_http):
    http, rec = mock_http(load_fixture("synthetic_ned_landuse_ok.json"))
    items = _client(http).get_land_use(PNU)
    assert any(d.kind == "zoning" for d in items)
    req = rec.requests[0]
    assert (req.url.host, req.url.path) == ("ned.example", "/ned/data/getLandUseAttr")
    assert rec.params["pnu"] == PNU and rec.params["format"] == "json" and rec.params["key"] == SECRET
    assert rec.params["numOfRows"] == "1000" and rec.params["pageNo"] == "1"


def test_get_land_use_http_500(mock_http):
    http, rec = mock_http(json_response({"landUses": {"resultCode": "SYSTEM_ERROR"}}, 500))
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_land_use(PNU)
    assert ei.value.status_code == 500 and ei.value.retriable is True
    assert len(rec.requests) == 2


def test_get_land_use_timeout(mock_http):
    http, rec = mock_http(httpx.ReadTimeout("slow"))
    with pytest.raises(UpstreamError) as ei:
        _client(http, timeout=0.2).get_land_use(PNU)
    assert ei.value.retriable is True and len(rec.requests) == 2


def test_get_land_use_http_4xx_with_error_body(mock_http):
    http, _ = mock_http(json_response(load_fixture("observed_ned_landuse_invalid_key.json"), 401))
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_land_use(PNU)
    assert ei.value.status_code == 401 and ei.value.retriable is False


# ---------------------------------------------------------------- 토지특성·토지임야
def test_parse_land_characteristics_latest_year():
    lc = parse_land_characteristics_response(load_fixture("synthetic_ned_landchar_ok.json"), pnu=PNU)
    assert lc.raw["stdrYear"] == "2026"
    assert lc.land_area_m2 == pytest.approx(624.1)
    assert lc.land_category == "대"
    assert lc.road_side == "세로한면(가)"
    assert lc.official_land_price == 3850000
    assert lc.source == "vworld:getLandCharacteristics"


def test_parse_land_characteristics_unspecified_values_become_none():
    payload = load_fixture("synthetic_ned_landchar_ok.json")
    for row in payload["landCharacteristicss"]["field"]:
        row.update({"roadSideCodeNm": "지정되지않음", "pblntfPclnd": "0", "lndpclAr": ""})
    lc = parse_land_characteristics_response(payload)
    assert (lc.road_side, lc.official_land_price, lc.land_area_m2) == (None, None, None)


def test_parse_land_characteristics_empty_and_errors():
    assert parse_land_characteristics_response(load_fixture("synthetic_ned_empty.json")) is None
    with pytest.raises(UpstreamError):
        parse_land_characteristics_response(load_fixture("observed_ned_landchar_invalid_key.json"))


def test_parse_ladfrl():
    reg = parse_ladfrl_response(load_fixture("synthetic_ned_ladfrl_ok.json"), pnu=PNU)
    assert (reg.land_area_m2, reg.land_category, reg.road_side, reg.official_land_price) == (625.0, "대", None, None)
    assert reg.source == "vworld:ladfrlList"
    with pytest.raises(UpstreamError) as ei:
        parse_ladfrl_response(load_fixture("observed_ned_ladfrl_invalid_key.json"))
    assert ei.value.retriable is False
    empty = {"ladfrlVOList": {"ladfrlVOList": [], "error": "", "message": "", "totalCount": "0"}}
    assert parse_ladfrl_response(empty) is None


def _route(mapping):
    def handler(request: httpx.Request):
        out = mapping[request.url.path]
        if isinstance(out, BaseException):
            raise out
        return out if isinstance(out, httpx.Response) else json_response(out)

    return handler


def test_get_land_characteristics_merges_sources(mock_http):
    http, _ = mock_http(
        _route(
            {
                "/ned/data/ladfrlList": load_fixture("synthetic_ned_ladfrl_ok.json"),
                "/ned/data/getLandCharacteristics": load_fixture("synthetic_ned_landchar_ok.json"),
            }
        )
    )
    lc = _client(http).get_land_characteristics(PNU)
    assert lc.land_area_m2 == 625.0  # 토지대장(ladfrlList) 면적 우선
    assert lc.official_land_price == 3850000 and lc.road_side == "세로한면(가)"
    assert lc.source == "vworld:ladfrlList+vworld:getLandCharacteristics"
    assert lc.raw["_errors"] == {}
    assert lc.raw["ladfrlList"]["lndpclAr"] == "625.0"


def test_get_land_characteristics_partial_when_one_fails(mock_http):
    http, _ = mock_http(
        _route(
            {
                "/ned/data/ladfrlList": load_fixture("synthetic_ned_ladfrl_ok.json"),
                "/ned/data/getLandCharacteristics": httpx.ReadTimeout("slow"),
            }
        )
    )
    lc = _client(http).get_land_characteristics(PNU)
    assert lc.land_area_m2 == 625.0 and lc.land_category == "대"
    assert lc.road_side is None and lc.official_land_price is None
    assert "getLandCharacteristics" in lc.raw["_errors"]
    assert lc.source == "vworld:ladfrlList"


def test_get_land_characteristics_both_fail(mock_http):
    http, _ = mock_http(json_response({}, 503))
    with pytest.raises(UpstreamError) as ei:
        _client(http).get_land_characteristics(PNU)
    assert ei.value.retriable is True and ei.value.status_code == 503


def test_get_land_characteristics_no_data(mock_http):
    http, _ = mock_http(load_fixture("synthetic_ned_empty.json"))
    assert _client(http).get_land_characteristics(PNU) is None


def test_merge_helper_none():
    assert merge_land_characteristics(PNU, None, None) is None


def test_constructor_and_repr():
    with pytest.raises(ValueError):
        VWorldClient("")
    with VWorldClient(SECRET) as c:
        assert SECRET not in repr(c)
