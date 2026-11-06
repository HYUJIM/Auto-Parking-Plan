"""
주차장 배치 시뮬레이션 서비스 (검토 결과 → 배치안 GeoJSON).

  * 치수는 해당 검토(review)에 쓰인 '승인된' 주차구획·차로 폭 규칙에서만 가져온다 (원문이 진실이다).
  * 주차장 설치 가능 여부(eligibility)는 '검토 가능한 주요 용도' 항목의 주차장 관련 용도 상태를 그대로 쓴다.
    별도 확인 필요 상태이면 기본적으로 배치를 만들지 않는다 (include_unverified=True 로 참고 배치 요청 가능).
  * 결과는 허가 가능 여부를 단정하지 않는 '참고 배치안'이다.
"""
from __future__ import annotations

import re
import uuid
from dataclasses import asdict
from typing import Any

from sqlalchemy.orm import Session

from app.db.models import ReviewItem, ReviewRequest, Rule
from app.parking.types import ParkingDimensions, SimulationParams, StallSize

ANGLE_KEYS = {"parallel_0": "PARALLEL_0", "perpendicular_90": "PERPENDICULAR_90", "angle_60": "ANGLE_60", "angle_45": "ANGLE_45"}
PARKING_USE_RE = re.compile(r"주차장|자동차\s*관련\s*시설")
DISCLAIMER = ("법정 최소 치수로 계산한 참고 배치안입니다. 도로 접도·진출입 위치, 경사, 지장물, 조경·보행 동선 등 현장 조건은 반영하지 않았으며 "
              "인허가 가능 여부를 확정하지 않습니다.")


class SimulationUnavailable(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def dims_from_rules(stall_rules: list[Rule], aisle_rules: list[Rule]) -> ParkingDimensions:
    d = ParkingDimensions()
    sizes: dict[tuple[str, str], dict[str, float]] = {}
    for r in stall_rules:
        for v in r.values:
            parts = v.value_key.split(".")
            if len(parts) == 3 and parts[2] in ("width", "length") and v.num_min is not None:
                sizes.setdefault((parts[0], parts[1]), {})[parts[2]] = float(v.num_min)
        d.rule_ids.append(str(r.id))
    for (form, typ), wl in sizes.items():
        if "width" in wl and "length" in wl and typ in ("general", "extended", "compact", "disabled"):
            target = d.stall_parallel if form == "parallel" else d.stall_non_parallel
            target[typ] = StallSize(wl["width"], wl["length"])
    for r in aisle_rules:
        for v in r.values:
            parts = v.value_key.split(".")
            val = v.num_min if v.num_min is not None else v.num_exact
            if val is None:
                continue
            if len(parts) == 2 and parts[0] in ANGLE_KEYS and parts[1] in ("one_gate", "two_plus_gates"):
                d.aisle_width.setdefault(ANGLE_KEYS[parts[0]], {})[parts[1]] = float(val)
            elif parts[0] == "gate" and (d.gate_width_min_m is None or val < d.gate_width_min_m):
                d.gate_width_min_m = float(val)  # 출입구 최소 너비 (가장 작은 기본 기준)
        d.rule_ids.append(str(r.id))
    return d


def eligibility_from_review(result: dict[str, Any]) -> dict[str, Any]:
    use_item = next((i for i in result["items"] if i["category"] == "ZONING_USE"), None)
    if use_item is None:
        return {"status": "MANUAL_CHECK", "use_name": None, "reason": "용도 검토 결과가 없습니다."}
    if use_item["reason_code"] == "ZONE_OVERLAY":
        return {"status": "MANUAL_CHECK", "use_name": None, "reason": "특화구역(지구단위계획 등)과 겹쳐 별도 확인이 필요합니다."}
    uses = use_item["applied"].get("uses") or []
    hit = [u for u in uses if PARKING_USE_RE.search(u["use_name"]) and not u["use_name"].endswith(" 용도")]
    if not hit:
        why = ("승인된 용도 규칙이 없어 주차장 설치 가능 여부를 확인하지 못했습니다." if not uses else
               "이 용도지역의 검토 가능 용도 목록에서 주차장 관련 용도가 확인되지 않았습니다.")
        return {"status": "MANUAL_CHECK", "use_name": None, "reason": why}
    best = min(hit, key=lambda u: {"REVIEWABLE": 0, "CONDITIONAL": 1, "MANUAL_CHECK": 2}[u["status"]])
    reason = {"REVIEWABLE": "검토 가능한 용도 목록에 포함되어 있습니다.",
              "CONDITIONAL": "조건부로 검토할 수 있는 용도입니다." + (f" ({best['condition_note']})" if best.get("condition_note") else ""),
              "MANUAL_CHECK": "별도 확인이 필요한 용도입니다."}[best["status"]]
    return {"status": best["status"], "use_name": best["use_name"], "reason": reason}


def simulate_for_review(s: Session, review_id: uuid.UUID, params: SimulationParams, *, include_unverified: bool = False) -> dict[str, Any]:
    from app.parking.engine import simulate  # noqa: PLC0415  (기하 엔진은 무거운 import)

    req = s.get(ReviewRequest, review_id)
    if req is None:
        raise SimulationUnavailable("REVIEW_NOT_FOUND", "검토 결과를 찾을 수 없습니다.")
    if req.status not in ("done", "partial") or not req.result_json:
        raise SimulationUnavailable("REVIEW_NOT_READY", "검토가 아직 끝나지 않았습니다.")
    result = req.result_json
    geom = result["parcel"]["location"]["geometry"]
    if not geom:
        raise SimulationUnavailable("SITE_GEOMETRY_MISSING", "필지 경계 정보가 없어 배치를 계산할 수 없습니다.")
    elig = eligibility_from_review(result)
    items = {i.category: i for i in s.query(ReviewItem).filter(ReviewItem.request_id == review_id).all()}
    stall_item, aisle_item = items.get("PARKING_STALL_SIZE"), items.get("DRIVE_AISLE_WIDTH")
    stall_rules = [s.get(Rule, uuid.UUID(x)) for x in (stall_item.applied_rule_ids if stall_item else [])]
    aisle_rules = [s.get(Rule, uuid.UUID(x)) for x in (aisle_item.applied_rule_ids if aisle_item else [])]
    base = {"review_id": str(review_id), "pnu": req.pnu, "as_of_date": req.as_of_date.isoformat(),
            "ruleset_hash": req.ruleset_hash, "eligibility": elig,
            "legal_basis": [{"category": i.category, "status": i.status, "rule_ids": i.applied_rule_ids,
                             "criteria_text": i.result_json["applied"].get("criteria_text")}
                            for i in (stall_item, aisle_item) if i is not None],
            "params": {**asdict(params), "gate_point": list(params.gate_point) if params.gate_point else None},
            "disclaimer": DISCLAIMER}
    if not stall_rules or not aisle_rules or any(r is None for r in stall_rules + aisle_rules):
        return {**base, "simulated": False, "result": None,
                "constraint_reasons": ["DIMENSION_RULES_NOT_APPROVED"],
                "message": "주차구획·차로 폭 기준이 검수 승인되지 않아 배치를 계산하지 않았습니다."}
    if elig["status"] == "MANUAL_CHECK" and not include_unverified:
        return {**base, "simulated": False, "result": None, "constraint_reasons": ["PARKING_USE_NOT_CONFIRMED"],
                "message": "주차장 설치 가능 여부가 확인되지 않아 배치를 계산하지 않았습니다. 참고 배치를 보려면 include_unverified=true 로 요청하세요."}
    dims = dims_from_rules(stall_rules, aisle_rules)
    sim = simulate(geom, dims, params)
    alts = [asdict(a) for a in sim.alternatives]
    return {**base, "simulated": True, "reference_only": elig["status"] != "REVIEWABLE",
            "result": {"site_area_m2": sim.site_area_m2, "crs_internal": sim.crs_internal,
                       "recommended_alt_code": next((a["alt_code"] for a in alts if a["is_recommended"]), None),
                       "alternatives": alts},
            "constraint_reasons": sim.constraint_reasons, "message": None}
