"""
공개 API (비로그인·무인증) — 설계서 10장.

  GET  /v1/address/search?q=        초기 화면: 주소 후보 (도로명, 지번, PNU, 좌표, supported)
  GET  /v1/parcels/{pnu}            결과 상단·지도: 대지면적, 지목, 용도지역·지구·구역, GeoJSON 경계, 출처·조회시각
  GET  /v1/parcels/at?lng=&lat=     지도 클릭 → 필지 (보강)
  POST /v1/reviews                  검토 시작 → 202 {review_id}
  GET  /v1/reviews/{id}             진행 중이면 단계별 진행상태, 완료면 review_result 전체
  GET  /v1/rules/{rule_id}/basis    근거 상세: 근거 트리 + 원문 조문 전체 + 국가법령정보센터 링크
  GET  /v1/regions                  지원 지역 목록
  POST /v1/reviews/{id}/parking-simulations   주차장 배치 시뮬레이션 (지도 오버레이용 GeoJSON)
"""
from __future__ import annotations

import uuid
from datetime import date
from typing import Any, Literal

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.api.errors import ApiError
from app.db.models import DocumentVersion, Jurisdiction, Provision, Rule, SourceDocument
from app.parcels.service import AddressNotFound, ParcelNotFound, UpstreamUnavailable, parcel_public_view
from app.parking.service import SimulationUnavailable, simulate_for_review
from app.parking.types import SimulationParams
from app.review.basis import basis_for_rules, provision_label
from app.review.service import ReviewNotFound, today_kst

router = APIRouter(prefix="/v1", tags=["public"])


def _c(request: Request):  # noqa: ANN202
    return request.app.state.container


@router.get("/address/search")
def address_search(request: Request, q: str = Query(..., min_length=2, max_length=200)) -> dict[str, Any]:
    c = _c(request)
    with c.db.session() as s:
        try:
            items = c.parcels.search(s, q)
        except AddressNotFound as e:
            reason = str(e) if str(e) and str(e) != q else None  # 도로명주소 API 의 검색어 안내 (예: 주소를 상세히 입력)
            raise ApiError("ADDRESS_NOT_FOUND", details={"reason": reason} if reason else None) from e
        except UpstreamUnavailable as e:
            raise ApiError("UPSTREAM_UNAVAILABLE") from e
    return {"query": q, "count": len(items), "items": items}


@router.get("/parcels/at")
def parcel_at(request: Request, lng: float = Query(..., ge=124, le=132), lat: float = Query(..., ge=33, le=39)) -> dict[str, Any]:
    c = _c(request)
    with c.db.session() as s:
        try:
            row = c.parcels.get_parcel_at(s, lng, lat)
        except ParcelNotFound as e:
            raise ApiError("PARCEL_NOT_FOUND") from e
        except UpstreamUnavailable as e:
            raise ApiError("UPSTREAM_UNAVAILABLE") from e
        return parcel_public_view(c.parcels.snapshot(s, row))


@router.get("/parcels/{pnu}")
def parcel_detail(request: Request, pnu: str, refresh: bool = False) -> dict[str, Any]:
    c = _c(request)
    with c.db.session() as s:
        try:
            row = c.parcels.get_parcel(s, pnu, refresh=refresh)
        except ParcelNotFound as e:
            raise ApiError("PARCEL_NOT_FOUND") from e
        except UpstreamUnavailable as e:
            raise ApiError("UPSTREAM_UNAVAILABLE") from e
        return parcel_public_view(c.parcels.snapshot(s, row))


class ReviewInputs(BaseModel):
    """향후 확장(LATER): 계획 용도·연면적. MVP 에서는 비워 두면 주차대수는 NEEDS_USE_INPUT."""
    model_config = {"extra": "forbid"}
    building_use: str | None = Field(default=None, max_length=60, description="「건축법 시행령」 별표 1 용도명")
    gross_floor_area: float | None = Field(default=None, gt=0, lt=1e7)
    households: int | None = Field(default=None, gt=0, lt=100000)


class ReviewCreate(BaseModel):
    model_config = {"extra": "forbid"}
    pnu: str = Field(..., pattern=r"^\d{19}$")
    as_of_date: date | None = None
    audience: Literal["owner", "architect"] = "owner"
    inputs: ReviewInputs | None = None


@router.post("/reviews", status_code=202)
def create_review(request: Request, body: ReviewCreate) -> JSONResponse:
    c = _c(request)
    if body.as_of_date and abs((body.as_of_date - today_kst(c.settings.timezone)).days) > 3650:
        raise ApiError("INVALID_REQUEST", "기준일은 오늘 기준 ±10년 이내여야 합니다.")
    try:
        rid = c.reviews.submit(pnu=body.pnu, as_of=body.as_of_date, audience=body.audience,
                               inputs=body.inputs.model_dump(exclude_none=True) if body.inputs else {})
    except ParcelNotFound as e:
        raise ApiError("PARCEL_NOT_FOUND") from e
    except UpstreamUnavailable as e:
        raise ApiError("UPSTREAM_UNAVAILABLE") from e
    return JSONResponse({"review_id": str(rid)}, status_code=202, headers={"Location": f"/v1/reviews/{rid}"})


@router.get("/reviews/{review_id}")
def get_review(request: Request, review_id: uuid.UUID) -> JSONResponse:
    c = _c(request)
    try:
        out = c.reviews.get(review_id)
    except ReviewNotFound as e:
        raise ApiError("REVIEW_NOT_FOUND") from e
    if "result" in out:
        return JSONResponse(out["result"], status_code=200)
    # 진행 중(또는 실패): 단계별 진행상태. 화면은 상태 코드 200 + status 로 구분한다.
    return JSONResponse(out["progress"], status_code=200, headers={"Retry-After": "1"} if out["state"] in ("queued", "running") else None)


@router.get("/rules/{rule_id}/basis")
def rule_basis(request: Request, rule_id: uuid.UUID, as_of_date: date | None = None) -> dict[str, Any]:
    c = _c(request)
    on = as_of_date or today_kst(c.settings.timezone)
    with c.db.session() as s:
        rule = s.get(Rule, rule_id)
        if rule is None or rule.review_status not in ("approved", "superseded"):
            raise ApiError("RULE_NOT_FOUND")  # 검수 전·반려 규칙은 공개 API 로 보여주지 않는다
        ver = s.get(DocumentVersion, rule.version_id)
        doc = s.get(SourceDocument, ver.document_id)
        provisions = []
        for ev in rule.evidence:
            p = s.get(Provision, ev.provision_id)
            root = p
            while root.parent_id is not None:
                root = s.get(Provision, root.parent_id)
            provisions.append({
                "role": ev.role, "article_label": provision_label(s, p), "quoted_text": ev.quoted_text,
                "char_start": ev.char_start, "char_end": ev.char_end, "provision_text": p.text,
                "article_full_text": root.text, "article_heading": root.heading,
            })
        return {
            "rule_id": str(rule.id), "rule_key": rule.rule_key, "category": rule.category, "title": rule.title,
            "layer": rule.layer, "review_status": rule.review_status,
            "effective_from": rule.effective_from.isoformat() if rule.effective_from else None,
            "effective_to": rule.effective_to.isoformat() if rule.effective_to else None,
            "document": {"title": doc.official_title, "short_title": doc.short_title, "source_type": doc.source_type,
                         "promulgation_number": ver.promulgation_number,
                         "promulgation_date": ver.promulgation_date.isoformat() if ver.promulgation_date else None,
                         "effective_from": ver.effective_from.isoformat() if ver.effective_from else None,
                         "source_url": ver.source_url, "fetched_at": ver.fetched_at.isoformat(),
                         "verification": {"source_access": ver.v_source_access, "version_header": ver.v_version_header,
                                          "text_match": ver.v_text_match, "current_version": ver.v_current_version,
                                          "current_checked_on": ver.current_checked_on.isoformat() if ver.current_checked_on else None},
                         "is_example": ver.is_example},
            "conditions": [{"field": x.field, "operator": x.operator, "value": x.value_json, "unit": x.unit} for x in rule.conditions],
            "values": [{"value_key": v.value_key, "value_type": v.value_type, "num_min": v.num_min, "num_max": v.num_max,
                        "num_exact": v.num_exact, "enum_value": v.enum_value, "formula": v.formula, "unit": v.unit,
                        "condition_note": v.condition_note} for v in sorted(rule.values, key=lambda v: v.value_key)],
            "evidence": provisions,
            "basis_tree": basis_for_rules(s, [rule], on),
        }


@router.get("/regions")
def regions(request: Request) -> dict[str, Any]:
    c = _c(request)
    with c.db.session() as s:
        rows = s.scalars(select(Jurisdiction).where(Jurisdiction.is_supported.is_(True), Jurisdiction.level == 2)).all()
        return {"supported": [{"code": j.code, "name": j.name} for j in rows],
                "message": "현재 MVP는 대구 수성구를 우선 검토 대상으로 합니다.",
                "roadmap": "추후 대구 전 지역 → 경북 → 전국 확대 예정"}


class SimulationRequest(BaseModel):
    model_config = {"extra": "forbid"}
    angles: list[Literal["PARALLEL_0", "ANGLE_45", "ANGLE_60", "PERPENDICULAR_90"]] | None = None
    stall_type: Literal["general", "extended"] = "general"
    setback_m: float = Field(default=0.5, ge=0, le=10)
    gate_point: tuple[float, float] | None = Field(default=None, description="[경도, 위도] 진출입구 희망 위치")
    gate_count: Literal[1, 2] = 1
    max_alternatives: int = Field(default=3, ge=1, le=6)
    include_unverified: bool = Field(default=False, description="주차장 설치 가능 여부가 '별도 확인 필요'여도 참고 배치를 계산")


@router.post("/reviews/{review_id}/parking-simulations")
def parking_simulation(request: Request, review_id: uuid.UUID, body: SimulationRequest | None = None) -> dict[str, Any]:
    c = _c(request)
    body = body or SimulationRequest()
    params = SimulationParams(stall_type=body.stall_type, setback_m=body.setback_m, gate_point=body.gate_point,
                              gate_count=body.gate_count, max_alternatives=body.max_alternatives)
    if body.angles:
        params.angles = list(body.angles)
    with c.db.session() as s:
        try:
            return simulate_for_review(s, review_id, params, include_unverified=body.include_unverified)
        except SimulationUnavailable as e:
            raise ApiError(e.code, e.message) from e
