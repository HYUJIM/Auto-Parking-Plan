"""
외부 공공 API mock (FastAPI). 실제 API 와 같은 경로·같은 JSON 모양을 돌려주므로
app.external 의 실제 클라이언트를 base URL 만 바꿔 그대로 쓸 수 있다.

  GET /addrlink/addrLinkApi.do                       도로명주소 검색 API
  GET /req/data                                      브이월드 2D 데이터 API (LP_PA_CBND_BUBUN 만)
  GET /ned/data/getLandUseAttr                       브이월드 토지이용계획속성
  GET /ned/data/getLandCharacteristics               브이월드 토지특성속성
  GET /ned/data/ladfrlList                           브이월드 토지임야목록
  GET /1613000/BldRgstHubService/getBrTitleInfo      건축HUB 건축물대장 표제부
  GET /  ,  GET /__mock__/parcels                    mock 안내·예시 필지 요약

규칙
  * 인증키: 비어 있지 않으면 통과, 문자 그대로 'invalid' 이면 각 API 의 인증 오류 모양을 돌려준다.
  * 예시 필지의 behavior.land_use == 'server_error' → getLandUseAttr 이 HTTP 500.
    behavior.land_characteristics == 'slow' → getLandCharacteristics 가 slow_delay_s(기본 3.5초) 뒤에 응답.
  * 응답 JSON 모양의 근거와 미확인 항목은 app/external/*.py docstring 참고. 도로명주소 API 는 json 만 지원
    (resultType 이 json 이 아니어도 json 으로 응답).
"""

from __future__ import annotations

import asyncio
import re
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from shapely.geometry import box

from mockserver.dataset import MockDataset, MockParcel, load_dataset

__all__ = ["create_app", "app", "DEFAULT_SLOW_DELAY_S"]

DEFAULT_SLOW_DELAY_S = 3.5
INVALID_KEY = "invalid"

_JUSO_MESSAGES = {
    "0": "정상",
    "E0001": "승인되지 않은 KEY 입니다.",
    "E0005": "검색어가 입력되지 않았습니다.",
    "E0006": "주소를 상세히 입력해 주시기 바랍니다.",
    "E0008": "검색어는 두글자 이상 입력되어야 합니다.",
    "E0009": "검색어는 문자와 숫자 같이 입력되어야 합니다.",
    "E0010": "검색어가 너무 깁니다. (한글40자, 영문,숫자 80자 이하)",
    "E0013": "SQL 예약어 또는 특수문자( %,=,>,<,[,] )는 검색이 불가능 합니다.",
}
_REGION_ALIASES = {"대구", "대구시", "서울", "서울시"}
_POINT_RE = re.compile(r"^\s*POINT\s*\(\s*([-+0-9.eE]+)\s+([-+0-9.eE]+)\s*\)\s*$", re.I)
_BOX_RE = re.compile(
    r"^\s*BOX\s*\(\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*\)\s*$", re.I
)


def _int(value: Any, default: int, *, lo: int = 1, hi: int = 1000) -> int:
    try:
        n = int(str(value))
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


def _page(items: list[Any], page: int, size: int) -> list[Any]:
    start = (page - 1) * size
    return items[start : start + size]


def _ld_name(p: MockParcel) -> str:
    r = p.record
    return f"{r['sido']} {r['sigungu']} {r['emd']}"


# ---------------------------------------------------------------------------------------------
# 도로명주소 검색 API
# ---------------------------------------------------------------------------------------------
def _juso_record(p: MockParcel, idx: int) -> dict[str, str]:
    r = p.record
    return {
        "roadAddr": f"{r['road_address']} ({r['emd']})",
        "roadAddrPart1": r["road_address"],
        "roadAddrPart2": f" ({r['emd']})",
        "jibunAddr": r["jibun_address"],
        "engAddr": f"{r['building_main_no']}, Example-ro (not a real address), Korea",
        "zipNo": r["zip_code"],
        "admCd": r["adm_cd"],
        "rnMgtSn": f"{r['adm_cd'][:5]}9{idx:06d}",
        "bdMgtSn": f"{r['pnu']}{idx:06d}",
        "detBdNmList": "",
        "bdNm": r["building_name"],
        "bdKdcd": "0",
        "siNm": r["sido"],
        "sggNm": r["sigungu"],
        "emdNm": r["emd"],
        "liNm": "",
        "rn": r["road_name"],
        "udrtYn": "0",
        "buldMnnm": str(r["building_main_no"]),
        "buldSlno": str(r["building_sub_no"]),
        "mtYn": r["mt_yn"],
        "lnbrMnnm": str(r["bonbun"]),
        "lnbrSlno": str(r["bubun"]),
        "emdNo": "01",
    }


def _juso_keyword_error(keyword: str, ds: MockDataset) -> str | None:
    if not keyword:
        return "E0005"
    if re.search(r"[%=><\[\]]", keyword):
        return "E0013"
    compact = re.sub(r"\s+", "", keyword)
    if len(compact) < 2:
        return "E0008"
    if re.fullmatch(r"[0-9\-]+", compact):
        return "E0009"
    if len(keyword) > 80:
        return "E0010"
    regions = ds.region_names() | _REGION_ALIASES
    if all(tok in regions for tok in keyword.split()):
        return "E0006"
    return None


# ---------------------------------------------------------------------------------------------
# 브이월드
# ---------------------------------------------------------------------------------------------
def _vw_service(q: Any) -> dict[str, str]:
    return {
        "name": q.get("service") or "data",
        "version": q.get("version") or "2.0",
        "operation": q.get("request") or "GetFeature",
        "time": "3(ms)",
    }


def _vw_error(q: Any, code: str, level: int, text: str) -> dict[str, Any]:
    return {
        "response": {
            "service": _vw_service(q),
            "status": "ERROR",
            "error": {"level": str(level), "code": code, "text": text},
        }
    }


def _feature(p: MockParcel, idx: int, *, with_geom: bool, with_attr: bool) -> dict[str, Any]:
    r = p.record
    feat: dict[str, Any] = {"type": "Feature", "id": f"LP_PA_CBND_BUBUN.{9000000 + idx}"}
    if with_geom:
        feat["geometry"] = r["geometry"]
    if with_attr:
        feat["properties"] = {
            "pnu": r["pnu"],
            "jibun": f"{r['jibun']}{r['land']['category_symbol']}",
            "bonbun": str(r["bonbun"]),
            "bubun": str(r["bubun"]),
            "addr": r["jibun_address"],
            "gosi_year": "2026",
            "gosi_month": "01",
            "jiga": str(r["land"]["price_by_year"]["2026"]),
        }
    return feat


def _ned_key_error(q: Any, root: str, *, style: str = "resultCode") -> dict[str, Any] | None:
    key = q.get("key")
    if not key:
        code, msg = "PARAM_REQUIRED", "필수 파라미터인 <key>가 없어서 요청을 처리할수 없습니다."
    elif key == INVALID_KEY:
        code, msg = "INVALID_KEY", "등록되지 않은 인증키입니다."
    elif not q.get("pnu"):
        code, msg = "PARAM_REQUIRED", "필수 파라미터인 <pnu>가 없어서 요청을 처리할수 없습니다."
    else:
        return None
    if style == "error":
        return {root: {"error": code, "message": msg}}
    return {root: {"resultCode": code, "resultMsg": msg}}


def _ned_targets(ds: MockDataset, pnu: str) -> list[MockParcel]:
    if len(pnu) >= 19:
        p = ds.by_pnu(pnu)
        return [p] if p else []
    if len(pnu) >= 8:
        return ds.by_pnu_prefix(pnu)
    return []


def _ned_empty(num: int, page: int) -> dict[str, Any]:
    # 실응답에서 관찰된 '결과 없음' 봉투 (루트 키가 response 로 바뀜)
    return {
        "response": {"pageNo": str(page), "resultCode": "", "totalCount": "0", "numOfRows": str(num), "resultMsg": ""}
    }


def _ned_ok(root: str, rows: list[dict[str, Any]], total: int, num: int, page: int) -> dict[str, Any]:
    return {
        root: {
            "field": rows,
            "pageNo": str(page),
            "resultCode": "",
            "totalCount": str(total),
            "numOfRows": str(num),
            "resultMsg": "",
        }
    }


def _land_use_rows(p: MockParcel) -> list[dict[str, str]]:
    r = p.record
    rows = []
    for i, d in enumerate(r["designations"], start=1):
        rows.append(
            {
                "pnu": r["pnu"],
                "ldCode": r["adm_cd"],
                "ldCodeNm": _ld_name(p),
                "regstrSeCode": "1",
                "regstrSeCodeNm": "토지대장",
                "mnnmSlno": r["jibun"],
                "manageNo": f"99999{r['adm_cd']}{d['code']}{i:07d}",
                "cnflcAt": d["conflict_code"],
                "cnflcAtNm": d["conflict"],
                "prposAreaDstrcCode": d["code"],
                "prposAreaDstrcCodeNm": d["name"],
                "registDt": "2020-01-01",
                "lastUpdtDt": "2026-10-09",
            }
        )
    return rows


def _land_char_rows(p: MockParcel) -> list[dict[str, str]]:
    r = p.record
    land = r["land"]
    rows = []
    for year, price in sorted(land["price_by_year"].items()):
        rows.append(
            {
                "pnu": r["pnu"],
                "ldCode": r["adm_cd"],
                "ldCodeNm": _ld_name(p),
                "regstrSeCode": "1",
                "regstrSeCodeNm": "일반",
                "mnnmSlno": r["jibun"],
                "ladSn": "1",
                "stdrYear": year,
                "stdrMt": "01",
                "lndcgrCode": land["category_code"],
                "lndcgrCodeNm": land["category"],
                "lndpclAr": f"{land['area_m2']:.1f}",
                "prposArea1": land["zoning_char_code"],
                "prposArea1Nm": r["zone"],
                "prposArea2": "00",
                "prposArea2Nm": "지정되지않음",
                "ladUseSittn": land["land_use_status_code"],
                "ladUseSittnNm": land["land_use_status"],
                "tpgrphHgCode": land["height_code"],
                "tpgrphHgCodeNm": land["height"],
                "tpgrphFrmCode": land["form_code"],
                "tpgrphFrmCodeNm": land["form"],
                "roadSideCode": land["road_side_code"],
                "roadSideCodeNm": land["road_side"],
                "pblntfPclnd": str(price),
                "lastUpdtDt": f"{year}-07-30",
            }
        )
    return rows


def _ladfrl_row(p: MockParcel) -> dict[str, str]:
    r = p.record
    land = r["land"]
    return {
        "pnu": r["pnu"],
        "ldCode": r["adm_cd"],
        "ldCodeNm": _ld_name(p),
        "mnnmSlno": r["jibun"],
        "regstrSeCode": "1",
        "regstrSeCodeNm": "토지대장",
        "lndcgrCode": land["category_code"],
        "lndcgrCodeNm": land["category"],
        "lndpclAr": f"{land['area_m2']:.1f}",
        "posesnSeCode": "01",
        "posesnSeCodeNm": land["owner"],
        "cnrsPsnCo": "1",
        "ladFrtlSc": "12",
        "ladFrtlScNm": "1:1200",
        "lastUpdtDt": "2026-11-05",
    }


# ---------------------------------------------------------------------------------------------
# 건축HUB
# ---------------------------------------------------------------------------------------------
def _building_items(p: MockParcel) -> list[dict[str, Any]]:
    r = p.record
    items = []
    for i, b in enumerate(r["buildings"], start=1):
        items.append(
            {
                "rnum": i,
                "platPlc": r["jibun_address"],
                "newPlatPlc": r["road_address"],
                "sigunguCd": r["adm_cd"][:5],
                "bjdongCd": r["adm_cd"][5:10],
                "platGbCd": "0",
                "bun": f"{r['bonbun']:04d}",
                "ji": f"{r['bubun']:04d}",
                "mgmBldrgstPk": f"99999-{r['pnu'][-9:]}-{i}",
                "regstrGbCd": "1",
                "regstrGbCdNm": "일반",
                "regstrKindCd": "2",
                "regstrKindCdNm": "일반건축물",
                "bldNm": r["building_name"],
                "dongNm": "",
                "mainAtchGbCd": "0",
                "mainAtchGbCdNm": "주건축물",
                "platArea": r["land"]["area_m2"],
                "archArea": b["building_area_m2"],
                "bcRat": round(b["building_area_m2"] / r["land"]["area_m2"] * 100, 2),
                "totArea": b["total_area_m2"],
                "vlRatEstmTotArea": b["total_area_m2"],
                "vlRat": round(b["total_area_m2"] / r["land"]["area_m2"] * 100, 2),
                "strctCdNm": "철근콘크리트구조",
                "mainPurpsCd": b["main_use_code"],
                "mainPurpsCdNm": b["main_use"],
                "etcPurps": b["etc_use"] or b["main_use"],
                "grndFlrCnt": b["ground_floors"],
                "ugrndFlrCnt": 0,
                "useAprDay": b["use_approval_day"],
                "crtnDay": "20261101",
            }
        )
    return items


def _hub_auth_error() -> JSONResponse:
    # 2026-11-06 실호출로 확인한 모양 (HTTP 403)
    return JSONResponse(
        status_code=403,
        content={
            "OpenAPI_ServiceResponse": {
                "cmmMsgHeader": {
                    "errMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
                    "returnAuthMsg": "등록되지 않은 서비스키",
                    "returnReasonCode": "30",
                }
            }
        },
    )


# ---------------------------------------------------------------------------------------------
# 앱
# ---------------------------------------------------------------------------------------------
def create_app(*, dataset_path: str | Path | None = None, slow_delay_s: float = DEFAULT_SLOW_DELAY_S) -> FastAPI:
    ds = load_dataset(dataset_path)
    api = FastAPI(
        title="APP 외부 API mock",
        description="도로명주소·브이월드·건축HUB API 모양을 흉내 내는 로컬 mock. 모든 필지는 예시(가상)이다.",
        version="0.1.0",
    )
    api.state.dataset = ds
    api.state.slow_delay_s = float(slow_delay_s)

    @api.get("/")
    def index() -> dict[str, Any]:
        return {
            "service": "APP external API mock",
            "is_example": True,
            "notice": "모든 필지는 예시 데이터이며 실제 필지가 아닙니다.",
            "invalid_key": INVALID_KEY,
            "slow_delay_s": api.state.slow_delay_s,
            "endpoints": [
                "/addrlink/addrLinkApi.do",
                "/req/data",
                "/ned/data/getLandUseAttr",
                "/ned/data/getLandCharacteristics",
                "/ned/data/ladfrlList",
                "/1613000/BldRgstHubService/getBrTitleInfo",
                "/__mock__/parcels",
            ],
        }

    @api.get("/__mock__/parcels")
    def mock_parcels() -> dict[str, Any]:
        return {"is_example": True, "parcels": [p.summary() for p in ds.parcels]}

    # --- 도로명주소 ---------------------------------------------------------------------------
    @api.get("/addrlink/addrLinkApi.do")
    def juso_search(request: Request) -> JSONResponse:
        q = request.query_params
        page = _int(q.get("currentPage"), 1, hi=100000)
        size = _int(q.get("countPerPage"), 10, hi=100)
        keyword = (q.get("keyword") or "").strip()

        def common(code: str, total: int) -> dict[str, str]:
            return {
                "countPerPage": str(size),
                "errorMessage": _JUSO_MESSAGES[code],
                "errorCode": code,
                "totalCount": str(total),
                "currentPage": str(page),
            }

        # 실 API 는 검색어 검사를 승인키 검사보다 먼저 한다(2026-11-06 실호출 관찰)
        err = _juso_keyword_error(keyword, ds)
        if err is None and (not q.get("confmKey") or q.get("confmKey") == INVALID_KEY):
            err = "E0001"
        if err is not None:
            return JSONResponse({"results": {"common": common(err, 0), "juso": None}})
        matched = ds.search(keyword)
        rows = [_juso_record(p, ds.parcels.index(p) + 1) for p in _page(matched, page, size)]
        return JSONResponse({"results": {"common": common("0", len(matched)), "juso": rows}})

    # --- 브이월드 데이터 API ----------------------------------------------------------------------
    @api.get("/req/data")
    def vworld_data(request: Request) -> JSONResponse:
        q = request.query_params
        if not q.get("request"):
            return JSONResponse(
                _vw_error(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <request> 가 없어서 요청을 처리할수 없습니다.")
            )
        key = q.get("key")
        if not key:
            return JSONResponse(
                _vw_error(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <key> 가 없어서 요청을 처리할수 없습니다.")
            )
        if key == INVALID_KEY:
            return JSONResponse(_vw_error(q, "INVALID_KEY", 2, "등록되지 않은 인증키입니다."))
        data = q.get("data")
        if data != "LP_PA_CBND_BUBUN":
            return JSONResponse(
                _vw_error(
                    q,
                    "INVALID_RANGE",
                    1,
                    f"<data> 파라미터의 값이 유효한 범위를 넘었습니다. 입력한 파라미터 값 : <{data}> (mock 은 LP_PA_CBND_BUBUN 만 지원)",
                )
            )
        crs = (q.get("crs") or "EPSG:4326").upper()
        if crs != "EPSG:4326":
            return JSONResponse(
                _vw_error(
                    q, "INVALID_RANGE", 1, f"<crs> 파라미터 값 <{crs}> 은 mock 에서 지원하지 않습니다(EPSG:4326 만)."
                )
            )

        attr_filter = q.get("attrFilter")
        geom_filter = q.get("geomFilter")
        hits: list[MockParcel]
        if attr_filter:
            m = re.fullmatch(r"\s*pnu\s*:\s*(=|like)\s*:\s*(\d+)\s*", attr_filter, flags=re.I)
            if not m:
                return JSONResponse(
                    _vw_error(
                        q,
                        "INVALID_TYPE",
                        1,
                        f"<attrFilter> 파라미터 타입이 유효하지 않습니다. 입력한 파라미터 값 : <{attr_filter}>",
                    )
                )
            op, val = m.group(1).lower(), m.group(2)
            hits = ([ds.by_pnu(val)] if ds.by_pnu(val) else []) if op == "=" else ds.by_pnu_prefix(val)
        elif geom_filter:
            pm, bm = _POINT_RE.match(geom_filter), _BOX_RE.match(geom_filter)
            if pm:
                hits = ds.at_point(float(pm.group(1)), float(pm.group(2)))
            elif bm:
                hits = ds.intersecting(box(*(float(bm.group(i)) for i in range(1, 5))))
            else:
                return JSONResponse(
                    _vw_error(
                        q,
                        "INVALID_TYPE",
                        1,
                        f"<geomFilter> 파라미터 타입이 유효하지 않습니다. 입력한 파라미터 값 : <{geom_filter}>",
                    )
                )
        else:
            return JSONResponse(
                _vw_error(q, "PARAM_REQUIRED", 1, "필수 파라미터인 <geomFilter> 가 없어서 요청을 처리할수 없습니다.")
            )

        size = _int(q.get("size"), 10)
        page = _int(q.get("page"), 1, hi=100000)
        rows = _page(hits, page, size)
        if not rows:
            return JSONResponse(
                {
                    "response": {
                        "service": _vw_service(q),
                        "status": "NOT_FOUND",
                        "record": {"total": "0", "current": "0"},
                        "page": {"total": "0", "current": str(page), "size": str(size)},
                    }
                }
            )
        with_geom = (q.get("geometry") or "true").lower() != "false"
        with_attr = (q.get("attribute") or "true").lower() != "false"
        features = [_feature(p, ds.parcels.index(p) + 1, with_geom=with_geom, with_attr=with_attr) for p in rows]
        minx, miny, maxx, maxy = rows[0].geom.bounds
        for p in rows[1:]:
            b = p.geom.bounds
            minx, miny, maxx, maxy = min(minx, b[0]), min(miny, b[1]), max(maxx, b[2]), max(maxy, b[3])
        total_pages = (len(hits) + size - 1) // size
        return JSONResponse(
            {
                "response": {
                    "service": _vw_service(q),
                    "status": "OK",
                    "record": {"total": str(len(hits)), "current": str(len(rows))},
                    "page": {"total": str(total_pages), "current": str(page), "size": str(size)},
                    "result": {
                        "featureCollection": {
                            "type": "FeatureCollection",
                            "bbox": [minx, miny, maxx, maxy],
                            "features": features,
                        }
                    },
                }
            }
        )

    # --- 브이월드 국가중점데이터 -------------------------------------------------------------------
    @api.get("/ned/data/getLandUseAttr")
    def land_use_attr(request: Request) -> JSONResponse:
        q = request.query_params
        err = _ned_key_error(q, "landUses")
        if err:
            return JSONResponse(err)
        num = _int(q.get("numOfRows"), 10)
        page = _int(q.get("pageNo"), 1, hi=100000)
        targets = _ned_targets(ds, q.get("pnu") or "")
        if any(p.behavior.get("land_use") == "server_error" for p in targets):
            return JSONResponse(
                status_code=500,
                content={"landUses": {"resultCode": "SYSTEM_ERROR", "resultMsg": "시스템 에러가 발생하였습니다."}},
            )
        rows = [row for p in targets for row in _land_use_rows(p)]
        if q.get("cnflcAt"):
            rows = [r for r in rows if r["cnflcAt"] == q.get("cnflcAt")]
        if q.get("prposAreaDstrcCodeNm"):
            rows = [r for r in rows if r["prposAreaDstrcCodeNm"] == q.get("prposAreaDstrcCodeNm")]
        if not rows:
            return JSONResponse(_ned_empty(num, page))
        return JSONResponse(_ned_ok("landUses", _page(rows, page, num), len(rows), num, page))

    @api.get("/ned/data/getLandCharacteristics")
    async def land_characteristics(request: Request) -> JSONResponse:
        q = request.query_params
        err = _ned_key_error(q, "landCharacteristicss")
        if err:
            return JSONResponse(err)
        num = _int(q.get("numOfRows"), 10)
        page = _int(q.get("pageNo"), 1, hi=100000)
        targets = _ned_targets(ds, q.get("pnu") or "")
        if any(p.behavior.get("land_characteristics") == "slow" for p in targets):
            await asyncio.sleep(api.state.slow_delay_s)
        rows = [row for p in targets for row in _land_char_rows(p)]
        if q.get("stdrYear"):
            rows = [r for r in rows if r["stdrYear"] == q.get("stdrYear")]
        if not rows:
            return JSONResponse(_ned_empty(num, page))
        return JSONResponse(_ned_ok("landCharacteristicss", _page(rows, page, num), len(rows), num, page))

    @api.get("/ned/data/ladfrlList")
    def ladfrl_list(request: Request) -> JSONResponse:
        q = request.query_params
        err = _ned_key_error(q, "ladfrlVOList", style="error")
        if err:
            return JSONResponse(err)
        num = _int(q.get("numOfRows"), 10)
        page = _int(q.get("pageNo"), 1, hi=100000)
        rows = [_ladfrl_row(p) for p in _ned_targets(ds, q.get("pnu") or "")]
        return JSONResponse(
            {
                "ladfrlVOList": {
                    "ladfrlVOList": _page(rows, page, num),
                    "error": "",
                    "message": "",
                    "numOfRows": str(num),
                    "pageNo": str(page),
                    "totalCount": str(len(rows)),
                }
            }
        )

    # --- 건축HUB -----------------------------------------------------------------------------
    @api.get("/1613000/BldRgstHubService/getBrTitleInfo")
    def br_title_info(request: Request) -> JSONResponse:
        q = request.query_params
        key = q.get("serviceKey")
        if not key or key == INVALID_KEY:
            return _hub_auth_error()
        num = _int(q.get("numOfRows"), 10)
        page = _int(q.get("pageNo"), 1, hi=100000)
        sgg, bjd = q.get("sigunguCd") or "", q.get("bjdongCd") or ""
        if len(sgg) != 5 or len(bjd) != 5:
            return JSONResponse(
                {"response": {"header": {"resultCode": "10", "resultMsg": "INVALID_REQUEST_PARAMETER_ERROR"}}}
            )
        plat = q.get("platGbCd")
        land_type = {"0": "1", "1": "2"}.get(plat or "", None)

        def _num(v: str | None) -> int | None:
            return int(v) if v and v.isdigit() else None

        bun, ji = _num(q.get("bun")), _num(q.get("ji"))
        items: list[dict[str, Any]] = []
        for p in ds.parcels:
            r = p.record
            if r["adm_cd"] != sgg + bjd:
                continue
            if land_type and r["pnu"][10] != land_type:
                continue
            if bun is not None and r["bonbun"] != bun:
                continue
            if ji is not None and r["bubun"] != ji:
                continue
            items.extend(_building_items(p))
        rows = _page(items, page, num)
        body: dict[str, Any] = {
            "items": {"item": rows} if rows else "",
            "numOfRows": num,
            "pageNo": page,
            "totalCount": len(items),
        }
        return JSONResponse(
            {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."}, "body": body}}
        )

    return api


app = create_app()
