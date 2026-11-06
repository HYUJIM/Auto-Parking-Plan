"""
공공데이터포털 국토교통부_건축HUB_건축물대장정보 서비스 — 표제부 조회(선택 정보).

요청: GET https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo
      serviceKey(필수), sigunguCd(필수, 시군구코드 5), bjdongCd(필수, 법정동코드 5),
      platGbCd(대지구분코드 0:대지 1:산 2:블록), bun(번 4), ji(지 4), startDate, endDate,
      _type(xml|json, 생략 시 xml), numOfRows, pageNo
응답: response.header{resultCode, resultMsg}, response.body{items{item}, numOfRows, pageNo, totalCount}
      item 주요 필드: mainPurpsCdNm(주용도코드명), totArea(연면적 ㎡), archArea(건축면적 ㎡), platArea(대지면적 ㎡),
      useAprDay(사용승인일 YYYYMMDD), bldNm, dongNm, mainAtchGbCdNm(주부속구분), platPlc, newPlatPlc ...
출처: https://www.data.go.kr/data/15134735/openapi.do (페이지에 포함된 OpenAPI(Swagger) 명세, 2026-11-06 확인)

인증 오류(2026-11-06 실호출 확인): HTTP 403 +
      {"OpenAPI_ServiceResponse": {"cmmMsgHeader": {"errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                                                    "returnAuthMsg": "...", "returnReasonCode": "30"}}}
게이트웨이 오류코드(같은 페이지 '오픈API 에러코드 안내'): 01 APPLICATION_ERROR, 04 HTTP_ERROR, 05 SERVICETIMEOUT_ERROR,
      10 INVALID_REQUEST_PARAMETER_ERROR, 12 NO_OPENAPI_SERVICE_ERROR, 20 SERVICE_KEY_IS_NULL/PERMISSION_DENIED/
      SERVICE_ACCESS_DENIED_ERROR, 22·23 호출량 초과, 29 BLACKLIST_IP_ACCESS_ERROR, 30 키 미등록, 31 키 기한 만료

[미확인] JSON 응답에서 items 가 비었을 때의 모양(빈 문자열/누락), item 이 1건일 때 객체로 오는지 배열로 오는지,
         숫자 필드가 숫자/문자열 중 무엇으로 오는지는 명세에 없다 → 모두 허용한다.
[미확인] header.resultCode 정상값 '00' 은 공공데이터포털 관례이며 이 서비스 명세에는 값 목록이 없다.

serviceKey: 공공데이터포털이 주는 'Encoding' 키(%2B 등 포함)를 그대로 넣으면 이중 인코딩되므로 '%' 가 있으면
            한 번 디코딩해서 보낸다.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any
from urllib.parse import unquote

import httpx

from app.external._http import DEFAULT_TIMEOUT, HttpJsonClient, clean_str, join_url, to_positive_float, utcnow
from app.external.pnu import parse_pnu
from app.external.types import BuildingInfo, UpstreamError

__all__ = ["BUILDING_HUB_BASE_URL", "TITLE_INFO_PATH", "SOURCE", "BuildingHubClient", "parse_title_info_response"]

SERVICE = "building_hub"
BUILDING_HUB_BASE_URL = "https://apis.data.go.kr"
TITLE_INFO_PATH = "/1613000/BldRgstHubService/getBrTitleInfo"
SOURCE = "data.go.kr:BldRgstHubService/getBrTitleInfo"

_OK_CODES = frozenset({"00", "0", "000"})
_NO_DATA_CODES = frozenset({"03"})  # 공공데이터포털 관례 NODATA_ERROR
_NON_RETRIABLE_GW_CODES = frozenset({"10", "12", "20", "30", "31"})
_DATE_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})$")
_XML_REASON_RE = re.compile(r"<returnReasonCode>\s*(\d+)\s*</returnReasonCode>")
_XML_ERRMSG_RE = re.compile(r"<errMsg>\s*([^<]*)</errMsg>")


def _approval_date(value: Any) -> str | None:
    s = clean_str(value)
    if not s:
        return None
    m = _DATE_RE.match(s.replace("-", ""))
    if not m:
        return None
    y, mo, d = m.groups()
    try:
        datetime(int(y), int(mo), int(d))
    except ValueError:
        return None
    return f"{y}-{mo}-{d}"


def _gateway_error(reason: str | None, err_msg: str | None, *, status_code: int | None) -> UpstreamError:
    reason = (reason or "").strip()
    return UpstreamError(
        SERVICE,
        f"공공데이터포털 오류({reason or '?'} {err_msg or ''})".strip(),
        retriable=reason not in _NON_RETRIABLE_GW_CODES,
        status_code=status_code,
    )


def parse_title_info_response(
    payload: Any, *, fetched_at: datetime | None = None, status_code: int | None = None
) -> list[BuildingInfo]:
    """getBrTitleInfo JSON → BuildingInfo 목록. 자료 없음 → []."""
    fetched_at = fetched_at or utcnow()
    if not isinstance(payload, dict):
        raise UpstreamError(SERVICE, "응답 형식이 예상과 다릅니다", retriable=True, status_code=status_code)
    gw = payload.get("OpenAPI_ServiceResponse")
    if isinstance(gw, dict):
        hdr = gw.get("cmmMsgHeader") or {}
        raise _gateway_error(
            clean_str(hdr.get("returnReasonCode")), clean_str(hdr.get("errMsg")), status_code=status_code
        )
    resp = payload.get("response")
    if not isinstance(resp, dict):
        raise UpstreamError(
            SERVICE, "응답 형식이 예상과 다릅니다(response 없음)", retriable=True, status_code=status_code
        )
    header = resp.get("header") or {}
    code = clean_str(header.get("resultCode"))
    if code is not None and code not in _OK_CODES:
        if code in _NO_DATA_CODES:
            return []
        raise _gateway_error(code, clean_str(header.get("resultMsg")), status_code=status_code)
    body = resp.get("body") or {}
    items = body.get("items") if isinstance(body, dict) else None
    records: Any = items.get("item") if isinstance(items, dict) else None
    if isinstance(records, dict):
        records = [records]
    if not isinstance(records, list):
        return []
    out: list[BuildingInfo] = []
    for rec in records:
        if not isinstance(rec, dict):
            continue
        out.append(
            BuildingInfo(
                main_use=clean_str(rec.get("mainPurpsCdNm")),
                gross_floor_area=to_positive_float(rec.get("totArea")),
                building_area=to_positive_float(rec.get("archArea")),
                approval_date=_approval_date(rec.get("useAprDay")),
                source=SOURCE,
                fetched_at=fetched_at,
                raw=dict(rec),
            )
        )
    return out


class BuildingHubClient:
    """건축HUB 건축물대장 표제부 동기 클라이언트. base_url 은 scheme+host(+port) 만 받는다."""

    def __init__(
        self,
        service_key: str,
        *,
        base_url: str = BUILDING_HUB_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        client: httpx.Client | None = None,
    ) -> None:
        if not service_key:
            raise ValueError("공공데이터포털 서비스키(service_key)가 비어 있습니다")
        key = unquote(service_key) if "%" in service_key else service_key
        self._service_key = key
        self.base_url = base_url
        self._http = HttpJsonClient(SERVICE, timeout=timeout, client=client, secrets=(key, service_key))

    def __repr__(self) -> str:  # 키 노출 방지
        return f"BuildingHubClient(base_url={self.base_url!r})"

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> BuildingHubClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def get_buildings(self, pnu: str, *, max_rows: int = 100) -> list[BuildingInfo]:
        """PNU 의 건축물대장 표제부(동별 1건). [] = 건축물 없음(또는 대장 미등재)."""
        p = parse_pnu(pnu)
        params = {
            "serviceKey": self._service_key,
            "sigunguCd": p["sigungu_code"],
            "bjdongCd": p["bjdong_code"],
            "platGbCd": p["plat_gb_cd"],
            "bun": p["bun"],
            "ji": p["ji"],
            "_type": "json",
            "numOfRows": max_rows,
            "pageNo": 1,
        }
        resp = self._http.get(join_url(self.base_url, TITLE_INFO_PATH), params, endpoint="getBrTitleInfo")
        try:
            payload = self._http.decode_json(resp, endpoint="getBrTitleInfo")
        except UpstreamError:
            # 게이트웨이는 _type=json 이어도 XML 오류를 줄 때가 있다
            text = resp.text
            reason = _XML_REASON_RE.search(text)
            if reason:
                msg = _XML_ERRMSG_RE.search(text)
                raise _gateway_error(
                    reason.group(1), msg.group(1) if msg else None, status_code=resp.status_code
                ) from None
            raise
        result = parse_title_info_response(payload, fetched_at=utcnow(), status_code=resp.status_code)
        if resp.status_code >= 400:
            raise UpstreamError(
                SERVICE, f"getBrTitleInfo HTTP {resp.status_code} 응답", retriable=False, status_code=resp.status_code
            )
        return result
