import logging

import httpx
import pytest

from app.external._http import redact_secrets
from app.external.juso import JusoClient, JusoQueryError, parse_search_response, sanitize_keyword
from app.external.types import UpstreamError

from .conftest import json_response, load_fixture

SECRET = "SECRET-JUSO-KEY-1234"


def test_parse_success_builds_pnu_and_fields():
    candidates, total = parse_search_response(load_fixture("synthetic_juso_ok.json"))
    assert total == 3
    assert len(candidates) == 2  # admCd 없는 결과는 건너뜀
    first, mountain = candidates
    assert first.pnu == "2726010100190010004"
    assert first.adm_code == "2726010100"
    assert first.road_address == "대구광역시 수성구 범어예시로 12 (범어동)"
    assert first.jibun_address == "대구광역시 수성구 범어동 9001-4"
    assert (first.sido, first.sigungu, first.emd) == ("대구광역시", "수성구", "범어동")
    assert first.zip_code == "42999"
    assert first.building_name == "[예시] 가상 건물"
    assert first.source == "juso:addrLinkApi"
    assert first.fetched_at is not None and first.fetched_at.tzinfo is not None
    # 산 지번(mtYn=1) → 필지구분 2, 숫자형 필드도 허용
    assert mountain.pnu == "2726010100200120000"
    assert mountain.building_name is None


def test_parse_empty_is_no_results():
    assert parse_search_response(load_fixture("synthetic_juso_empty.json")) == ([], 0)
    assert parse_search_response({"results": {"common": {"errorCode": "0", "totalCount": "0"}, "juso": None}}) == (
        [],
        0,
    )


def test_parse_invalid_key_is_non_retriable_upstream_error():
    with pytest.raises(UpstreamError) as ei:
        parse_search_response(load_fixture("observed_juso_E0001.json"))
    assert not isinstance(ei.value, JusoQueryError)
    assert ei.value.retriable is False
    assert ei.value.service == "juso"


@pytest.mark.parametrize("code", ["E0005", "E0006", "E0013"])
def test_parse_keyword_errors_are_query_errors(code):
    with pytest.raises(JusoQueryError) as ei:
        parse_search_response(load_fixture(f"observed_juso_{code}.json"))
    assert isinstance(ei.value, UpstreamError)
    assert ei.value.retriable is False
    assert ei.value.code == code
    assert ei.value.user_message


def test_parse_system_error_is_retriable():
    with pytest.raises(UpstreamError) as ei:
        parse_search_response(load_fixture("synthetic_juso_system_error.json"))
    assert ei.value.retriable is True


def test_parse_garbage():
    with pytest.raises(UpstreamError):
        parse_search_response({"unexpected": 1})


def test_sanitize_keyword():
    assert sanitize_keyword("  수성구  범어로[12]% ") == "수성구 범어로 12"
    assert sanitize_keyword(None) == ""


def test_search_sends_documented_params(mock_http):
    client, rec = mock_http(load_fixture("synthetic_juso_ok.json"))
    juso = JusoClient(SECRET, base_url="https://juso.example", client=client)
    result = juso.search("대구 수성구 범어예시로 12", page=2, per_page=5)
    assert [c.pnu for c in result] == ["2726010100190010004", "2726010100200120000"]
    req = rec.requests[0]
    assert req.url.path == "/addrlink/addrLinkApi.do"
    assert req.url.host == "juso.example"
    assert rec.params["confmKey"] == SECRET
    assert rec.params["keyword"] == "대구 수성구 범어예시로 12"
    assert rec.params["currentPage"] == "2"
    assert rec.params["countPerPage"] == "5"
    assert rec.params["resultType"] == "json"


def test_search_page_returns_total(mock_http):
    client, _ = mock_http(load_fixture("synthetic_juso_ok.json"))
    candidates, total = JusoClient(SECRET, client=client).search_page("범어예시로")
    assert total == 3 and len(candidates) == 2


def test_empty_keyword_fails_without_network(mock_http):
    client, rec = mock_http(load_fixture("synthetic_juso_ok.json"))
    with pytest.raises(JusoQueryError) as ei:
        JusoClient(SECRET, client=client).search("  %% ")
    assert ei.value.code == "E0005"
    assert rec.requests == []


def test_server_error_retries_once_then_raises(mock_http):
    client, rec = mock_http(json_response({"x": 1}, 503))
    with pytest.raises(UpstreamError) as ei:
        JusoClient(SECRET, client=client).search("범어예시로 12")
    assert len(rec.requests) == 2
    assert ei.value.retriable is True
    assert ei.value.status_code == 503
    assert SECRET not in str(ei.value)


def test_server_error_then_success(mock_http):
    client, rec = mock_http(json_response({}, 502), load_fixture("synthetic_juso_ok.json"))
    assert len(JusoClient(SECRET, client=client).search("범어예시로 12")) == 2
    assert len(rec.requests) == 2


def test_timeout_retries_once_then_raises(mock_http):
    client, rec = mock_http(httpx.ReadTimeout("timed out"))
    with pytest.raises(UpstreamError) as ei:
        JusoClient(SECRET, client=client, timeout=0.5).search("범어예시로 12")
    assert len(rec.requests) == 2
    assert ei.value.retriable is True
    assert "시간 초과" in ei.value.message
    assert ei.value.__cause__ is None and ei.value.__context__ is None  # httpx 예외(요청 URL 포함)를 달고 가지 않음


def test_connect_error_is_retriable(mock_http):
    client, rec = mock_http(httpx.ConnectError("refused"))
    with pytest.raises(UpstreamError) as ei:
        JusoClient(SECRET, client=client).search("범어예시로 12")
    assert ei.value.retriable is True and len(rec.requests) == 2


def test_invalid_key_message_never_contains_key(mock_http):
    client, rec = mock_http(load_fixture("observed_juso_E0001.json"))
    with pytest.raises(UpstreamError) as ei:
        JusoClient(SECRET, client=client).search("범어예시로 12")
    assert len(rec.requests) == 1  # 인증 오류는 재시도하지 않음
    assert SECRET not in str(ei.value) and SECRET not in ei.value.message
    assert SECRET not in repr(JusoClient(SECRET, client=client))


def test_non_json_response(mock_http):
    client, _ = mock_http(httpx.Response(200, text="<html>점검중</html>"))
    with pytest.raises(UpstreamError):
        JusoClient(SECRET, client=client).search("범어예시로 12")


def test_constructor_validation():
    with pytest.raises(ValueError):
        JusoClient("")
    with pytest.raises(ValueError):
        JusoClient(SECRET, timeout=0)
    with JusoClient(SECRET) as c:  # 자체 httpx.Client 생성/종료
        assert c.base_url == "https://business.juso.go.kr"


def test_redact_secrets_and_httpx_log_filter(caplog):
    url = f"https://x.example/a?confmKey={SECRET}&keyword=1&serviceKey=abc%2B1&key=zzz"
    red = redact_secrets(url)
    assert SECRET not in red and "abc%2B1" not in red and "zzz" not in red and "keyword=1" in red
    assert redact_secrets(f"echo <{SECRET}>", [SECRET]) == "echo <***>"
    with caplog.at_level(logging.INFO, logger="httpx"):
        logging.getLogger("httpx").info('HTTP Request: GET %s "HTTP/1.1 200 OK"', url)
    assert SECRET not in caplog.text and "confmKey=***" in caplog.text
