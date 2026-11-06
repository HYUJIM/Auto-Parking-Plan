"""
도로명주소 검색 API(행정안전부 주소기반산업지원서비스) 클라이언트.

요청: GET https://business.juso.go.kr/addrlink/addrLinkApi.do
      confmKey(필수), currentPage(필수), countPerPage(필수), keyword(필수), resultType(json/xml, 기본 xml),
      hstryYn·firstSort·addInfoYn(선택, 미사용)
응답: {"results": {"common": {"totalCount", "currentPage", "countPerPage", "errorCode", "errorMessage"},
                   "juso": [ {roadAddr, roadAddrPart1, roadAddrPart2, jibunAddr, engAddr, zipNo, admCd, rnMgtSn,
                              bdMgtSn, detBdNmList, bdNm, bdKdcd, siNm, sggNm, emdNm, liNm, rn, udrtYn, buldMnnm,
                              buldSlno, mtYn, lnbrMnnm, lnbrSlno, emdNo, ...}, ... ] | null}}
      오류도 HTTP 200 + common.errorCode 로 온다(2026-11-06 실호출로 E0001/E0005/E0006/E0013 확인).
출처: business.juso.go.kr 개발자센터 '도로명주소 검색 API' 개발가이드 (SPA 경로 /jst/jstRoadNmAddrApiSearch),
      요청변수·출력결과·오류코드 표.

확인 메모
  * 공식 출력결과 표에는 지번본번이 'InbrMnnm'(대문자 I), lnbrSlno 설명이 '읍면동일련번호'로 오기되어 있다.
    실제 응답 필드명은 lnbrMnnm / lnbrSlno(지번 부번)이므로 둘 다 읽는다. buldMnnm·lnbrMnnm 은 표에 Number 로
    적혀 있으나 JSON 에서는 문자열로 오는 경우가 있어 둘 다 허용한다.
  * admCd(표기: 행정구역코드)는 10자리 법정동코드이며 PNU 앞 10자리로 쓴다.
  * countPerPage 상한은 가이드에 명시되어 있지 않아 검사하지 않는다.
  * 이 API 는 좌표를 주지 않는다(좌표는 별도 '좌표제공 API'·별도 승인키). 후보 좌표가 필요하면 PNU 로
    브이월드 연속지적도를 조회해 경계 중심점을 쓴다.

오류코드 처리 (가이드 오류코드 표)
  0      정상 → 결과(없으면 [])
  -999   시스템에러 → UpstreamError(retriable=True)
  E0001  승인되지 않은 KEY / E0014 개발승인키 기간 만료 → UpstreamError(retriable=False)
  E0005 검색어 없음, E0006 주소를 상세히(시도명만), E0008 한 글자, E0009 숫자만, E0010 너무 김,
  E0011 긴 숫자, E0012 특수문자+숫자만, E0013 SQL 예약어·특수문자, E0015 검색 범위 초과(9천 건)
         → JusoQueryError(UpstreamError 하위, retriable=False). API 계층은 이것을 사용자 입력 문제
           (ADDRESS_NOT_FOUND 계열)로 바꿔 보여주는 것이 맞다.
"""

from __future__ import annotations

import logging
import re
from datetime import datetime
from typing import Any

import httpx

from app.external._http import DEFAULT_TIMEOUT, HttpJsonClient, clean_str, join_url, utcnow
from app.external.pnu import build_pnu, format_jibun
from app.external.types import AddressCandidate, UpstreamError

__all__ = [
    "JUSO_BASE_URL",
    "JUSO_SEARCH_PATH",
    "SOURCE",
    "JusoClient",
    "JusoQueryError",
    "parse_search_response",
    "sanitize_keyword",
]

logger = logging.getLogger(__name__)

JUSO_BASE_URL = "https://business.juso.go.kr"
JUSO_SEARCH_PATH = "/addrlink/addrLinkApi.do"
SOURCE = "juso:addrLinkApi"
SERVICE = "juso"

AUTH_ERROR_CODES = frozenset({"E0001", "E0014"})
QUERY_ERROR_CODES = frozenset({"E0005", "E0006", "E0008", "E0009", "E0010", "E0011", "E0012", "E0013", "E0015"})
SYSTEM_ERROR_CODES = frozenset({"-999"})

# 사용자에게 보여줄 문구 (가이드 오류코드 표의 메시지)
QUERY_ERROR_MESSAGES: dict[str, str] = {
    "E0005": "검색어가 입력되지 않았습니다.",
    "E0006": "주소를 상세히 입력해 주시기 바랍니다.",
    "E0008": "검색어는 두글자 이상 입력되어야 합니다.",
    "E0009": "검색어는 문자와 숫자 같이 입력되어야 합니다.",
    "E0010": "검색어가 너무 깁니다. (한글40자, 영문,숫자 80자 이하)",
    "E0011": "검색어에 너무 긴 숫자가 포함되어 있습니다. (숫자10자 이하)",
    "E0012": "특수문자+숫자만으로는 검색이 불가능 합니다.",
    "E0013": "SQL 예약어 또는 특수문자( %,=,>,<,[,] )는 검색이 불가능 합니다.",
    "E0015": "검색 범위를 초과하였습니다.",
}

_FORBIDDEN_CHARS_RE = re.compile(r"[%=><\[\]]")
_WS_RE = re.compile(r"\s+")


class JusoQueryError(UpstreamError):
    """검색어 자체가 API 규칙에 맞지 않음(사용자 입력 문제). retriable=False."""

    def __init__(self, code: str, message: str):
        super().__init__(SERVICE, f"검색어 오류({code}): {message}", retriable=False)
        self.code = code
        self.user_message = message


def sanitize_keyword(keyword: str | None) -> str:
    """가이드가 검색 불가로 정한 특수문자(%,=,>,<,[,])를 지우고 공백을 정리한다."""
    if not keyword:
        return ""
    return _WS_RE.sub(" ", _FORBIDDEN_CHARS_RE.sub(" ", str(keyword))).strip()


def _record_to_candidate(rec: dict[str, Any], fetched_at: datetime) -> AddressCandidate | None:
    adm_cd = clean_str(rec.get("admCd"))
    mt_yn = clean_str(rec.get("mtYn")) or "0"
    main = rec.get("lnbrMnnm", rec.get("InbrMnnm"))
    sub = rec.get("lnbrSlno", 0)
    try:
        pnu = build_pnu(adm_cd or "", mt_yn, main if main is not None else "", sub if sub is not None else 0)
    except ValueError as exc:
        logger.warning("[juso] PNU 를 만들 수 없는 결과를 건너뜀: %s", exc)
        return None
    if pnu[11:15] == "0000":
        logger.warning("[juso] 지번 본번이 없는 결과를 건너뜀 (admCd=%s)", adm_cd)
        return None

    road = clean_str(rec.get("roadAddr"))
    if road is None:
        part1 = clean_str(rec.get("roadAddrPart1"))
        part2 = clean_str(rec.get("roadAddrPart2"))
        road = f"{part1} {part2}".strip() if part1 and part2 else part1
    sido = clean_str(rec.get("siNm")) or ""
    sigungu = clean_str(rec.get("sggNm"))
    emd = clean_str(rec.get("emdNm"))
    jibun = clean_str(rec.get("jibunAddr"))
    if jibun is None:
        li = clean_str(rec.get("liNm"))
        lot = format_jibun(int(pnu[11:15]), int(pnu[15:19]), is_mountain=pnu[10] == "2")
        jibun = " ".join(x for x in (sido, sigungu, emd, li, lot) if x)
    return AddressCandidate(
        road_address=road,
        jibun_address=jibun,
        pnu=pnu,
        adm_code=pnu[:10],
        sido=sido,
        sigungu=sigungu,
        emd=emd,
        zip_code=clean_str(rec.get("zipNo")),
        building_name=clean_str(rec.get("bdNm")),
        source=SOURCE,
        fetched_at=fetched_at,
    )


def parse_search_response(payload: Any, *, fetched_at: datetime | None = None) -> tuple[list[AddressCandidate], int]:
    """검색 API JSON 응답 → (후보 목록, totalCount). 오류코드는 모듈 docstring 표대로 예외로 바꾼다."""
    fetched_at = fetched_at or utcnow()
    if not isinstance(payload, dict) or not isinstance(payload.get("results"), dict):
        raise UpstreamError(SERVICE, "응답 형식이 예상과 다릅니다(results 없음)", retriable=True)
    results = payload["results"]
    common = results.get("common") or {}
    code = str(common.get("errorCode", "")).strip()
    message = str(common.get("errorMessage", "")).strip()
    if code not in ("0", ""):
        if code in AUTH_ERROR_CODES:
            raise UpstreamError(SERVICE, f"인증 오류({code}): {message}", retriable=False)
        if code in QUERY_ERROR_CODES:
            raise JusoQueryError(code, message or QUERY_ERROR_MESSAGES.get(code, ""))
        if code in SYSTEM_ERROR_CODES:
            raise UpstreamError(SERVICE, f"시스템 오류({code}): {message}", retriable=True)
        raise UpstreamError(SERVICE, f"알 수 없는 오류코드({code}): {message}", retriable=False)

    try:
        total = int(str(common.get("totalCount", "0")).strip() or 0)
    except ValueError:
        total = 0
    records = results.get("juso") or []
    if isinstance(records, dict):
        records = [records]
    candidates: list[AddressCandidate] = []
    for rec in records:
        if isinstance(rec, dict):
            cand = _record_to_candidate(rec, fetched_at)
            if cand is not None:
                candidates.append(cand)
    return candidates, total


class JusoClient:
    """도로명주소 검색 API 동기 클라이언트.

    base_url 은 scheme+host(+port) 만 받는다(경로는 고정). mock 서버를 쓰려면 base_url="http://127.0.0.1:8090".
    """

    def __init__(
        self,
        api_key: str,
        *,
        base_url: str = JUSO_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        client: httpx.Client | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("도로명주소 API 승인키(api_key)가 비어 있습니다")
        self._api_key = api_key
        self.base_url = base_url
        self._http = HttpJsonClient(SERVICE, timeout=timeout, client=client, secrets=(api_key,))

    def __repr__(self) -> str:  # 키 노출 방지
        return f"JusoClient(base_url={self.base_url!r})"

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> JusoClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def search_page(self, keyword: str, *, page: int = 1, per_page: int = 10) -> tuple[list[AddressCandidate], int]:
        """한 페이지 검색 → (후보 목록, 전체 건수)."""
        if page < 1 or per_page < 1:
            raise ValueError("page, per_page 는 1 이상이어야 합니다")
        kw = sanitize_keyword(keyword)
        if not kw:
            raise JusoQueryError("E0005", QUERY_ERROR_MESSAGES["E0005"])
        params = {
            "confmKey": self._api_key,
            "currentPage": page,
            "countPerPage": per_page,
            "keyword": kw,
            "resultType": "json",
        }
        _, payload = self._http.get_json(join_url(self.base_url, JUSO_SEARCH_PATH), params, endpoint="addrLinkApi")
        try:
            return parse_search_response(payload, fetched_at=utcnow())
        except UpstreamError as exc:
            exc.message = self._http.scrub(exc.message)
            exc.args = (f"[{exc.service}] {exc.message}",)
            raise

    def search(self, keyword: str, *, page: int = 1, per_page: int = 10) -> list[AddressCandidate]:
        """주소 검색. [] = 결과 없음. 인증·검색어·서버 오류는 UpstreamError(검색어 오류는 JusoQueryError)."""
        candidates, _ = self.search_page(keyword, page=page, per_page=per_page)
        return candidates
