"""
브이월드(공간정보 오픈플랫폼) 클라이언트: 연속지적도, 토지이용계획 속성, 토지특성, 토지임야.

1) 연속지적도 — 2D 데이터 API 2.0
   GET https://api.vworld.kr/req/data?service=data&version=2.0&request=GetFeature&data=LP_PA_CBND_BUBUN
       &key=..&domain=..&format=json&errorFormat=json&crs=EPSG:4326&geometry=true&attribute=true
       &attrFilter=pnu:=:{pnu}   (pnu 는 '단일검색=Y' 속성이라 geomFilter 없이 attrFilter 만으로 조회 가능)
       &geomFilter=POINT(x y)    (좌표 조회)
   속성: pnu, jibun('2-1대'), bonbun, bubun, ag_geom, addr('서울특별시 종로구 청운동 1234-5'),
         gosi_year, gosi_month, jiga(개별공시지가)
   응답: response.service{name,version,operation,time}, response.status(OK|NOT_FOUND|ERROR),
         response.record{total,current}, response.page{total,current,size}, response.result(json 이면 geojson),
         오류 시 response.error{level,code,text}
   출처: https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=cadastral (POST apiVer=2)
   [미확인] result 아래 GeoJSON 이 'featureCollection' 키에 들어 있다는 점은 공식 표에 없다(관행상 그렇게 온다).
            이 모듈은 result.featureCollection 과 result 자체가 FeatureCollection 인 경우를 모두 받는다.
   [미확인] crs=EPSG:4326 일 때 좌표 순서가 [경도, 위도]라는 점은 표에 명시되어 있지 않다. 한국 범위를 기준으로
            [위도, 경도]로 보이면 뒤집는다.

2) 토지이용계획속성조회 — 국가중점데이터 API
   GET https://api.vworld.kr/ned/data/getLandUseAttr?pnu=..&key=..&domain=..&format=json&numOfRows=..&pageNo=..
       (선택: cnflcAt 저촉여부코드, prposAreaDstrcCodeNm 용도지역지구명)
   출력: pnu, ldCode, ldCodeNm, regstrSeCode, regstrSeCodeNm, mnnmSlno, manageNo, cnflcAt, cnflcAtNm,
         prposAreaDstrcCode, prposAreaDstrcCodeNm, registDt, lastUpdtDt
   출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=51
   * 근거 법령명 필드는 없다 → LandUseDesignation.basis_law = None.
   * 같은 코드가 도면번호(manageNo)별로 여러 줄 오므로 (코드, 명칭) 기준으로 합치고 가장 강한 저촉여부를 남긴다.

3) 토지특성속성조회 — GET https://api.vworld.kr/ned/data/getLandCharacteristics?pnu=..&stdrYear=..(선택)&...
   출력: pnu, ldCode, ldCodeNm, regstrSeCode, regstrSeCodeNm, mnnmSlno, ladSn, stdrYear, stdrMt, lndcgrCode,
         lndcgrCodeNm(지목명), lndpclAr(토지면적 ㎡), prposArea1/Nm, prposArea2/Nm, ladUseSittn/Nm, tpgrphHgCode/Nm,
         tpgrphFrmCode/Nm, roadSideCode, roadSideCodeNm(도로접면), pblntfPclnd(공시지가 원/㎡), lastUpdtDt
   출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=18
   * 기준연도별로 여러 줄이 오므로 가장 최근 (stdrYear, stdrMt) 를 쓴다.

4) 토지임야목록조회 — GET https://api.vworld.kr/ned/data/ladfrlList?pnu=..&format=json&numOfRows=..&pageNo=..
   출력: pnu, ldCode, ldCodeNm, mnnmSlno, regstrSeCode, regstrSeCodeNm, lndcgrCode, lndcgrCodeNm, lndpclAr(면적 ㎡),
         posesnSeCode/Nm, cnrsPsnCo, ladFrtlSc/Nm, lastUpdtDt, numOfRows, pageNo, totalCount
   출처: https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=128

국가중점데이터 API 의 JSON 봉투(공식 표에 없음, [미확인]·실응답 관찰 기반):
   성공  {"landUses": {"field": [...], "pageNo", "numOfRows", "totalCount", "resultCode": "", "resultMsg": ""}}
         {"landCharacteristicss": {"field": [...], ...}}        (키 철자 's' 두 개가 실제 응답)
         {"ladfrlVOList": {"ladfrlVOList": [...], "error", "message", "numOfRows", "pageNo", "totalCount"}}
   결과없음 {"response": {"pageNo", "resultCode": "", "totalCount": "0", "numOfRows", "resultMsg": ""}} 형태도 관찰됨
   오류  {"landUses": {"resultCode": "INVALID_KEY", "resultMsg": "등록되지 않은 인증키입니다."}}   (2026-11-06 실호출 확인)
         {"landCharacteristicss": {"resultCode": "INVALID_KEY", ...}}                         (실호출 확인)
         {"ladfrlVOList": {"error": "INVALID_KEY", "message": "..."}}                          (실호출 확인)
         {"response": {"status": "ERROR", "error": {"level", "code", "text"}}}                 (문서형 오류 봉투)
   그래서 봉투의 첫 번째 값(객체)에서 resultCode/error 와 목록(field/ladfrlVOList)을 찾는 방식으로 읽는다.

오류코드(레퍼런스 공통 표): PARAM_REQUIRED, INVALID_TYPE, INVALID_RANGE, URL_TYPE, INVALID_KEY, INCORRECT_KEY
   → retriable=False / UNAVAILABLE_KEY, OVER_REQUEST_LIMIT, SYSTEM_ERROR, UNKNOWN_ERROR → retriable=True.
"""

from __future__ import annotations

import logging
import math
from collections.abc import Callable
from datetime import datetime
from typing import Any

import httpx

from app.external._http import (
    DEFAULT_TIMEOUT,
    HttpJsonClient,
    clean_str,
    join_url,
    to_positive_float,
    to_positive_int,
    utcnow,
)
from app.external.pnu import is_valid_pnu
from app.external.types import (
    CadastralParcel,
    Conflict,
    LandCharacteristics,
    LandUseDesignation,
    UpstreamError,
)
from app.external.zoning_codes import canonical_designation_name, classify_designation, conflict_from_source

__all__ = [
    "VWORLD_BASE_URL",
    "CADASTRAL_DATASET",
    "SOURCE_CADASTRAL",
    "SOURCE_LAND_USE",
    "SOURCE_LAND_CHAR",
    "SOURCE_LADFRL",
    "VWorldClient",
    "normalize_multipolygon",
    "parse_cadastral_response",
    "parse_land_use_response",
    "parse_land_characteristics_response",
    "parse_ladfrl_response",
    "merge_land_characteristics",
]

logger = logging.getLogger(__name__)

SERVICE = "vworld"
VWORLD_BASE_URL = "https://api.vworld.kr"
DATA_PATH = "/req/data"
LAND_USE_PATH = "/ned/data/getLandUseAttr"
LAND_CHAR_PATH = "/ned/data/getLandCharacteristics"
LADFRL_PATH = "/ned/data/ladfrlList"
CADASTRAL_DATASET = "LP_PA_CBND_BUBUN"

SOURCE_CADASTRAL = f"vworld:{CADASTRAL_DATASET}"
SOURCE_LAND_USE = "vworld:getLandUseAttr"
SOURCE_LAND_CHAR = "vworld:getLandCharacteristics"
SOURCE_LADFRL = "vworld:ladfrlList"

AUTH_ERROR_CODES = frozenset({"INVALID_KEY", "INCORRECT_KEY"})
PARAM_ERROR_CODES = frozenset({"PARAM_REQUIRED", "INVALID_TYPE", "INVALID_RANGE", "URL_TYPE"})
_OK_RESULT_CODES = frozenset({"", "OK", "0", "00", "INFO-000", "NORMAL_SERVICE"})
_NO_ROAD_SIDE = frozenset({"지정되지않음", "-"})

Scrub = Callable[[str], str]


def _identity(s: str) -> str:
    return s


def _error_from_code(code: Any, text: Any, *, endpoint: str, scrub: Scrub = _identity) -> UpstreamError:
    c = (clean_str(code) or "UNKNOWN_ERROR").upper()
    t = scrub(clean_str(text) or "")
    if c in AUTH_ERROR_CODES:
        return UpstreamError(SERVICE, f"{endpoint} 인증 오류({c}): {t}", retriable=False)
    if c in PARAM_ERROR_CODES:
        return UpstreamError(SERVICE, f"{endpoint} 요청 파라미터 오류({c}): {t}", retriable=False)
    return UpstreamError(SERVICE, f"{endpoint} 오류({c}): {t}", retriable=True)


# --------------------------------------------------------------------------------------------
# 기하
# --------------------------------------------------------------------------------------------
def _looks_lat_lng(x: float, y: float) -> bool:
    return 33.0 <= x <= 39.5 and 124.0 <= y <= 132.5


def normalize_multipolygon(geometry: Any) -> dict[str, Any] | None:
    """GeoJSON Polygon/MultiPolygon → MultiPolygon(EPSG:4326, [경도, 위도]). Z 값은 버리고 고리는 닫는다."""
    if not isinstance(geometry, dict):
        return None
    gtype = geometry.get("type")
    coords = geometry.get("coordinates")
    if gtype == "Polygon":
        polygons = [coords]
    elif gtype == "MultiPolygon":
        polygons = coords
    else:
        return None
    if not isinstance(polygons, list):
        return None
    out: list[list[list[list[float]]]] = []
    for poly in polygons:
        if not isinstance(poly, list):
            continue
        rings: list[list[list[float]]] = []
        for ring in poly:
            try:
                pts = [[float(p[0]), float(p[1])] for p in ring if isinstance(p, list | tuple) and len(p) >= 2]
            except (TypeError, ValueError):
                continue
            pts = [p for p in pts if math.isfinite(p[0]) and math.isfinite(p[1])]
            if len(pts) >= 3 and pts[0] != pts[-1]:
                pts.append(list(pts[0]))
            if len(pts) >= 4:
                rings.append(pts)
        if rings:
            out.append(rings)
    if not out:
        return None
    x0, y0 = out[0][0][0]
    if _looks_lat_lng(x0, y0):
        out = [[[[p[1], p[0]] for p in ring] for ring in poly] for poly in out]
    return {"type": "MultiPolygon", "coordinates": out}


# --------------------------------------------------------------------------------------------
# 파서 (네트워크 없이 단위 시험 가능)
# --------------------------------------------------------------------------------------------
def _feature_to_parcel(feature: dict[str, Any], fetched_at: datetime) -> CadastralParcel | None:
    props = feature.get("properties") or {}
    pnu = clean_str(props.get("pnu"))
    if not pnu:
        return None
    return CadastralParcel(
        pnu=pnu,
        jibun=clean_str(props.get("jibun")),
        address=clean_str(props.get("addr")),
        geometry=normalize_multipolygon(feature.get("geometry")),
        official_land_price=to_positive_int(props.get("jiga")),
        source=SOURCE_CADASTRAL,
        fetched_at=fetched_at,
        raw=dict(props),
    )


def parse_cadastral_response(
    payload: Any, *, fetched_at: datetime | None = None, scrub: Scrub = _identity
) -> list[CadastralParcel]:
    """Data API(GetFeature, LP_PA_CBND_BUBUN) JSON → 필지 목록. NOT_FOUND → []."""
    fetched_at = fetched_at or utcnow()
    resp = payload.get("response") if isinstance(payload, dict) else None
    if not isinstance(resp, dict):
        raise UpstreamError(SERVICE, "연속지적도 응답 형식이 예상과 다릅니다(response 없음)", retriable=True)
    status = str(resp.get("status", "")).upper()
    if status == "NOT_FOUND":
        return []
    if status == "ERROR":
        err = resp.get("error") or {}
        raise _error_from_code(err.get("code"), err.get("text"), endpoint=CADASTRAL_DATASET, scrub=scrub)
    if status != "OK":
        raise UpstreamError(SERVICE, f"연속지적도 응답 status 를 알 수 없습니다({status or '없음'})", retriable=True)
    result = resp.get("result") or {}
    fc: Any = None
    if isinstance(result, dict):
        fc = result.get("featureCollection")
        if fc is None and (result.get("type") == "FeatureCollection" or "features" in result):
            fc = result
    features = fc.get("features") if isinstance(fc, dict) else None
    parcels: list[CadastralParcel] = []
    for feat in features or []:
        if isinstance(feat, dict):
            parcel = _feature_to_parcel(feat, fetched_at)
            if parcel is not None:
                parcels.append(parcel)
    return parcels


def _ned_records(payload: Any, *, endpoint: str, scrub: Scrub = _identity) -> list[dict[str, Any]]:
    """국가중점데이터 API JSON 봉투에서 레코드 목록을 꺼낸다. 오류 봉투면 UpstreamError."""
    if not isinstance(payload, dict) or not payload:
        raise UpstreamError(SERVICE, f"{endpoint} 응답 형식이 예상과 다릅니다", retriable=True)
    resp = payload.get("response")
    if isinstance(resp, dict) and "status" in resp:
        status = str(resp.get("status", "")).upper()
        if status == "ERROR":
            err = resp.get("error") or {}
            raise _error_from_code(err.get("code"), err.get("text"), endpoint=endpoint, scrub=scrub)
        if status == "NOT_FOUND":
            return []
    root = next((v for v in payload.values() if isinstance(v, dict)), None)
    if root is None:
        raise UpstreamError(SERVICE, f"{endpoint} 응답 형식이 예상과 다릅니다", retriable=True)
    code = clean_str(root.get("resultCode")) or clean_str(root.get("error"))
    if code is not None and code.upper() not in _OK_RESULT_CODES:
        raise _error_from_code(code, root.get("resultMsg") or root.get("message"), endpoint=endpoint, scrub=scrub)
    for key in ("field", "ladfrlVOList", "item", "items"):
        val = root.get(key)
        if isinstance(val, list):
            return [r for r in val if isinstance(r, dict)]
        if isinstance(val, dict):
            inner = val.get("item")
            if isinstance(inner, list):
                return [r for r in inner if isinstance(r, dict)]
            return [val]
    total = to_positive_int(root.get("totalCount"))
    if total:
        raise UpstreamError(SERVICE, f"{endpoint} 응답에 목록이 없습니다(totalCount={total})", retriable=False)
    return []


_CONFLICT_RANK: dict[Conflict, int] = {"included": 3, "partially_included": 2, "adjacent": 1, "unknown": 0}


def parse_land_use_response(
    payload: Any,
    *,
    pnu: str | None = None,
    fetched_at: datetime | None = None,
    scrub: Scrub = _identity,
) -> list[LandUseDesignation]:
    """getLandUseAttr JSON → LandUseDesignation 목록(같은 코드·명칭은 합침, 원천 순서 유지)."""
    fetched_at = fetched_at or utcnow()
    records = _ned_records(payload, endpoint="getLandUseAttr", scrub=scrub)
    merged: dict[tuple[str, str], tuple[LandUseDesignation, int]] = {}
    for rec in records:
        rec_pnu = clean_str(rec.get("pnu"))
        if pnu and rec_pnu and rec_pnu != pnu:
            continue
        code = clean_str(rec.get("prposAreaDstrcCode"))
        name = canonical_designation_name(rec.get("prposAreaDstrcCodeNm"), code)
        if not name and not code:
            continue
        conflict = conflict_from_source(rec.get("cnflcAt"), rec.get("cnflcAtNm"))
        key = ((code or "").upper(), name)
        prev = merged.get(key)
        if prev is not None:
            prev_item, count = prev
            if _CONFLICT_RANK[conflict] > _CONFLICT_RANK[prev_item.conflict]:
                prev_item.conflict = conflict
                prev_item.raw = {**rec, "_merged_records": count + 1}
            else:
                prev_item.raw["_merged_records"] = count + 1
            merged[key] = (prev_item, count + 1)
            continue
        item = LandUseDesignation(
            kind=classify_designation(name, code),
            name=name or (code or ""),
            code=code,
            conflict=conflict,
            basis_law=None,  # 원천에 근거 법령명 필드 없음
            source=SOURCE_LAND_USE,
            fetched_at=fetched_at,
            raw=dict(rec),
        )
        merged[key] = (item, 1)
    return [item for item, _ in merged.values()]


def _year_month_key(rec: dict[str, Any]) -> tuple[int, int, str]:
    def _int(v: Any) -> int:
        try:
            return int(str(v).strip())
        except (TypeError, ValueError):
            return 0

    return _int(rec.get("stdrYear")), _int(rec.get("stdrMt")), str(rec.get("lastUpdtDt") or "")


def parse_land_characteristics_response(
    payload: Any,
    *,
    pnu: str | None = None,
    fetched_at: datetime | None = None,
    scrub: Scrub = _identity,
) -> LandCharacteristics | None:
    """getLandCharacteristics JSON → 가장 최근 기준연도의 LandCharacteristics. 자료 없음 → None."""
    fetched_at = fetched_at or utcnow()
    records = _ned_records(payload, endpoint="getLandCharacteristics", scrub=scrub)
    if pnu:
        records = [r for r in records if clean_str(r.get("pnu")) in (None, pnu)]
    if not records:
        return None
    rec = max(records, key=_year_month_key)
    road_side = clean_str(rec.get("roadSideCodeNm"))
    if road_side in _NO_ROAD_SIDE:
        road_side = None
    return LandCharacteristics(
        pnu=clean_str(rec.get("pnu")) or (pnu or ""),
        land_area_m2=to_positive_float(rec.get("lndpclAr")),
        land_category=clean_str(rec.get("lndcgrCodeNm")),
        road_side=road_side,
        official_land_price=to_positive_int(rec.get("pblntfPclnd")),
        source=SOURCE_LAND_CHAR,
        fetched_at=fetched_at,
        raw=dict(rec),
    )


def parse_ladfrl_response(
    payload: Any,
    *,
    pnu: str | None = None,
    fetched_at: datetime | None = None,
    scrub: Scrub = _identity,
) -> LandCharacteristics | None:
    """ladfrlList(토지임야목록) JSON → LandCharacteristics(면적·지목만). 자료 없음 → None."""
    fetched_at = fetched_at or utcnow()
    records = _ned_records(payload, endpoint="ladfrlList", scrub=scrub)
    if pnu:
        records = [r for r in records if clean_str(r.get("pnu")) in (None, pnu)]
    if not records:
        return None
    rec = max(records, key=lambda r: str(r.get("lastUpdtDt") or ""))
    return LandCharacteristics(
        pnu=clean_str(rec.get("pnu")) or (pnu or ""),
        land_area_m2=to_positive_float(rec.get("lndpclAr")),
        land_category=clean_str(rec.get("lndcgrCodeNm")),
        road_side=None,
        official_land_price=None,
        source=SOURCE_LADFRL,
        fetched_at=fetched_at,
        raw=dict(rec),
    )


def merge_land_characteristics(
    pnu: str,
    char: LandCharacteristics | None,
    register: LandCharacteristics | None,
    *,
    errors: dict[str, str] | None = None,
    fetched_at: datetime | None = None,
) -> LandCharacteristics | None:
    """토지임야(현행 토지대장: 면적·지목 우선) + 토지특성(도로접면·공시지가, 면적·지목 보조)을 합친다.

    raw = {"ladfrlList": {...}|None, "getLandCharacteristics": {...}|None, "_errors": {endpoint: 메시지}}
    한쪽 조회가 실패했으면 raw["_errors"] 에 남는다(API 계층의 PARCEL_PARTIAL 판단용).
    """
    if char is None and register is None:
        return None
    sources = [s.source for s in (register, char) if s is not None]

    def _pick(attr: str) -> Any:
        for src in (register, char):
            if src is not None and getattr(src, attr) is not None:
                return getattr(src, attr)
        return None

    return LandCharacteristics(
        pnu=pnu,
        land_area_m2=_pick("land_area_m2"),
        land_category=_pick("land_category"),
        road_side=char.road_side if char else None,
        official_land_price=char.official_land_price if char else None,
        source="+".join(sources),
        fetched_at=fetched_at or utcnow(),
        raw={
            "ladfrlList": register.raw if register else None,
            "getLandCharacteristics": char.raw if char else None,
            "_errors": dict(errors or {}),
        },
    )


# --------------------------------------------------------------------------------------------
# 클라이언트
# --------------------------------------------------------------------------------------------
class VWorldClient:
    """브이월드 동기 클라이언트.

    base_url_data / base_url_ned 는 scheme+host(+port) 만 받는다(경로는 고정).
    domain: 인증키 발급 시 등록한 서비스 URL. 키가 도메인에 묶여 있으면 넣어야 INCORRECT_KEY 를 피한다.
    """

    def __init__(
        self,
        api_key: str,
        *,
        domain: str | None = None,
        base_url_data: str = VWORLD_BASE_URL,
        base_url_ned: str = VWORLD_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        client: httpx.Client | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("브이월드 인증키(api_key)가 비어 있습니다")
        self._api_key = api_key
        self.domain = domain
        self.base_url_data = base_url_data
        self.base_url_ned = base_url_ned
        self._http = HttpJsonClient(SERVICE, timeout=timeout, client=client, secrets=(api_key,))

    def __repr__(self) -> str:  # 키 노출 방지
        return f"VWorldClient(base_url_data={self.base_url_data!r}, base_url_ned={self.base_url_ned!r})"

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> VWorldClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # --- 내부 -------------------------------------------------------------------------------
    def _auth(self) -> dict[str, str]:
        params = {"key": self._api_key}
        if self.domain:
            params["domain"] = self.domain
        return params

    def _call(self, base: str, path: str, params: dict[str, Any], *, endpoint: str) -> Any:
        status, payload = self._http.get_json(join_url(base, path), {**params, **self._auth()}, endpoint=endpoint)
        if status >= 400:
            # 4xx 라도 본문에 원천 오류코드가 있으면 그것을 쓰고, 없으면 HTTP 오류로 처리
            try:
                if path == DATA_PATH:
                    parse_cadastral_response(payload, scrub=self._http.scrub)
                else:
                    _ned_records(payload, endpoint=endpoint, scrub=self._http.scrub)
            except UpstreamError as exc:
                exc.status_code = status
                raise
            raise UpstreamError(SERVICE, f"{endpoint} HTTP {status} 응답", retriable=False, status_code=status)
        return payload

    def _get_features(self, extra: dict[str, Any]) -> list[CadastralParcel]:
        params = {
            "service": "data",
            "version": "2.0",
            "request": "GetFeature",
            "data": CADASTRAL_DATASET,
            "format": "json",
            "errorFormat": "json",
            "crs": "EPSG:4326",
            "geometry": "true",
            "attribute": "true",
            "size": 10,
            "page": 1,
            **extra,
        }
        payload = self._call(self.base_url_data, DATA_PATH, params, endpoint=CADASTRAL_DATASET)
        return parse_cadastral_response(payload, fetched_at=utcnow(), scrub=self._http.scrub)

    # --- 공개 메서드 ---------------------------------------------------------------------------
    def get_parcel(self, pnu: str) -> CadastralParcel | None:
        """PNU 로 연속지적도 필지(경계·지번·주소·공시지가) 조회. 없으면 None."""
        _require_pnu(pnu)
        parcels = self._get_features({"attrFilter": f"pnu:=:{pnu}"})
        exact = [p for p in parcels if p.pnu == pnu]
        return (exact or parcels or [None])[0]

    def get_parcel_at(self, lng: float, lat: float) -> CadastralParcel | None:
        """좌표(EPSG:4326 경도·위도)가 속한 필지. 없으면 None. 경계 위라 여러 개면 첫 번째."""
        if not (math.isfinite(lng) and math.isfinite(lat) and -180 <= lng <= 180 and -90 <= lat <= 90):
            raise ValueError(f"좌표가 올바르지 않습니다: lng={lng}, lat={lat}")
        parcels = self._get_features({"geomFilter": f"POINT({lng:.8f} {lat:.8f})"})
        return parcels[0] if parcels else None

    def get_land_use(self, pnu: str) -> list[LandUseDesignation]:
        """토지이용계획 속성(용도지역·지구·구역 등). [] = 조회는 성공했으나 지정 없음. 실패는 UpstreamError."""
        _require_pnu(pnu)
        params = {"pnu": pnu, "format": "json", "numOfRows": 1000, "pageNo": 1}
        payload = self._call(self.base_url_ned, LAND_USE_PATH, params, endpoint="getLandUseAttr")
        return parse_land_use_response(payload, pnu=pnu, fetched_at=utcnow(), scrub=self._http.scrub)

    def get_land_char_attr(self, pnu: str) -> LandCharacteristics | None:
        """토지특성속성조회(가장 최근 기준연도). 자료 없음 → None."""
        _require_pnu(pnu)
        params = {"pnu": pnu, "format": "json", "numOfRows": 1000, "pageNo": 1}
        payload = self._call(self.base_url_ned, LAND_CHAR_PATH, params, endpoint="getLandCharacteristics")
        return parse_land_characteristics_response(payload, pnu=pnu, fetched_at=utcnow(), scrub=self._http.scrub)

    def get_land_register(self, pnu: str) -> LandCharacteristics | None:
        """토지임야목록조회(현행 토지·임야대장의 면적·지목). 자료 없음 → None."""
        _require_pnu(pnu)
        params = {"pnu": pnu, "format": "json", "numOfRows": 10, "pageNo": 1}
        payload = self._call(self.base_url_ned, LADFRL_PATH, params, endpoint="ladfrlList")
        return parse_ladfrl_response(payload, pnu=pnu, fetched_at=utcnow(), scrub=self._http.scrub)

    def get_land_characteristics(self, pnu: str) -> LandCharacteristics | None:
        """토지임야 + 토지특성을 합친 결과(면적·지목·도로접면·공시지가).

        한쪽만 실패하면 나머지로 채우고 raw["_errors"] 에 실패 내용을 남긴다. 둘 다 실패하면 UpstreamError.
        둘 다 자료 없음이면 None.
        """
        _require_pnu(pnu)
        errors: dict[str, UpstreamError] = {}
        char: LandCharacteristics | None = None
        register: LandCharacteristics | None = None
        try:
            register = self.get_land_register(pnu)
        except UpstreamError as exc:
            errors["ladfrlList"] = exc
        try:
            char = self.get_land_char_attr(pnu)
        except UpstreamError as exc:
            errors["getLandCharacteristics"] = exc
        if len(errors) == 2:
            raise UpstreamError(
                SERVICE,
                "토지임야·토지특성 조회 모두 실패: " + " / ".join(e.message for e in errors.values()),
                retriable=any(e.retriable for e in errors.values()),
                status_code=next((e.status_code for e in errors.values() if e.status_code), None),
            )
        merged = merge_land_characteristics(
            pnu, char, register, errors={k: v.message for k, v in errors.items()}, fetched_at=utcnow()
        )
        if merged is None and errors:
            raise next(iter(errors.values()))
        return merged


def _require_pnu(pnu: str) -> None:
    if not is_valid_pnu(pnu):
        raise ValueError(f"PNU 는 19자리 숫자(필지구분 1·2)여야 합니다: {pnu!r}")
