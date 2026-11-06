import httpx
import pytest

from app.external.building_hub import BuildingHubClient, parse_title_info_response
from app.external.types import UpstreamError

from .conftest import json_response, load_fixture

SECRET = "SECRET-HUB-KEY+abc/def=="


def test_parse_ok_list():
    a, b = parse_title_info_response(load_fixture("synthetic_bldhub_ok.json"))
    assert (a.main_use, a.gross_floor_area, a.building_area, a.approval_date) == ("단독주택", 182.4, 98.6, "1994-05-12")
    assert a.source == "data.go.kr:BldRgstHubService/getBrTitleInfo" and a.fetched_at.tzinfo is not None
    # 0·빈 값은 미확인(None)
    assert (b.gross_floor_area, b.building_area, b.approval_date) == (None, 35.5, None)
    assert b.raw["mainAtchGbCdNm"] == "부속건축물"


def test_parse_single_item_object_and_empty():
    (only,) = parse_title_info_response(load_fixture("synthetic_bldhub_single.json"))
    assert only.gross_floor_area == 612.3 and only.approval_date == "2003-08-20"
    assert parse_title_info_response(load_fixture("synthetic_bldhub_empty.json")) == []
    assert parse_title_info_response({"response": {"header": {"resultCode": "03", "resultMsg": "NODATA_ERROR"}}}) == []


def test_parse_gateway_error():
    with pytest.raises(UpstreamError) as ei:
        parse_title_info_response(load_fixture("observed_bldhub_invalid_key_http403.json"), status_code=403)
    assert ei.value.retriable is False and ei.value.status_code == 403


def test_get_buildings_params(mock_http):
    http, rec = mock_http(load_fixture("synthetic_bldhub_ok.json"))
    hub = BuildingHubClient(SECRET, base_url="https://hub.example", client=http)
    assert len(hub.get_buildings("2726010100190010004")) == 2
    req = rec.requests[0]
    assert (req.url.host, req.url.path) == ("hub.example", "/1613000/BldRgstHubService/getBrTitleInfo")
    p = rec.params
    assert (p["sigunguCd"], p["bjdongCd"], p["platGbCd"], p["bun"], p["ji"]) == ("27260", "10100", "0", "9001", "0004")
    assert p["_type"] == "json" and p["serviceKey"] == SECRET


def test_get_buildings_mountain_lot(mock_http):
    http, rec = mock_http(load_fixture("synthetic_bldhub_empty.json"))
    assert BuildingHubClient(SECRET, client=http).get_buildings("2726011400200120000") == []
    assert rec.params["platGbCd"] == "1"


def test_encoded_service_key_is_decoded_once(mock_http):
    http, rec = mock_http(load_fixture("synthetic_bldhub_empty.json"))
    BuildingHubClient("abc%2Bdef%3D%3D", client=http).get_buildings("2726010100190010004")
    assert rec.params["serviceKey"] == "abc+def=="


def test_invalid_key_http_403(mock_http):
    http, rec = mock_http(json_response(load_fixture("observed_bldhub_invalid_key_http403.json"), 403))
    with pytest.raises(UpstreamError) as ei:
        BuildingHubClient(SECRET, client=http).get_buildings("2726010100190010004")
    assert ei.value.status_code == 403 and ei.value.retriable is False
    assert SECRET not in str(ei.value) and len(rec.requests) == 1


def test_xml_gateway_error(mock_http):
    xml = (
        "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
        "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>"
        "<returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"
    )
    http, _ = mock_http(httpx.Response(200, text=xml, headers={"content-type": "text/xml"}))
    with pytest.raises(UpstreamError) as ei:
        BuildingHubClient(SECRET, client=http).get_buildings("2726010100190010004")
    assert ei.value.retriable is True and "22" in ei.value.message


def test_timeout_and_5xx(mock_http):
    http, rec = mock_http(httpx.ReadTimeout("slow"))
    with pytest.raises(UpstreamError):
        BuildingHubClient(SECRET, client=http).get_buildings("2726010100190010004")
    assert len(rec.requests) == 2
    http, rec = mock_http(json_response({}, 500))
    with pytest.raises(UpstreamError) as ei:
        BuildingHubClient(SECRET, client=http).get_buildings("2726010100190010004")
    assert ei.value.status_code == 500 and len(rec.requests) == 2


def test_bad_pnu():
    with BuildingHubClient(SECRET) as hub, pytest.raises(ValueError):
        hub.get_buildings("abc")
