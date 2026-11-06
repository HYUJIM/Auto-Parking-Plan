"""검토 결과 → 주차 치수 변환, 주차장 설치 가능 여부, 워커 모드 실행."""
from __future__ import annotations

from sqlalchemy import select

from app.db.models import Rule
from app.parking.service import dims_from_rules, eligibility_from_review
from app.review.service import ReviewService
from tests.integration.test_pipeline_example import PNU_R2G, world  # noqa: F401


def test_dims_from_approved_rules(world):  # noqa: F811
    with world["db"].session() as s:
        stall = s.scalars(select(Rule).where(Rule.category == "PARKING_STALL_SIZE", Rule.review_status == "approved")).all()
        aisle = s.scalars(select(Rule).where(Rule.category == "DRIVE_AISLE_WIDTH", Rule.review_status == "approved")).all()
        d = dims_from_rules(list(stall), list(aisle))
    assert d.stall_non_parallel["general"].width_m == 2.5 and d.stall_non_parallel["general"].length_m == 5.0
    assert d.stall_parallel["general"].width_m == 2.0 and d.stall_parallel["general"].length_m == 6.0
    assert d.aisle_width["PERPENDICULAR_90"] == {"one_gate": 6.0, "two_plus_gates": 6.0}
    assert d.aisle_width["PARALLEL_0"] == {"one_gate": 5.0, "two_plus_gates": 3.3}
    assert d.gate_width_min_m == 3.5 and len(d.rule_ids) == 2


def _result_with_uses(uses, reason=None):
    return {"items": [{"category": "ZONING_USE", "reason_code": reason, "applied": {"uses": uses}}]}


def test_eligibility_rules():
    assert eligibility_from_review(_result_with_uses([]))["status"] == "MANUAL_CHECK"
    ok = _result_with_uses([{"use_name": "자동차 관련 시설 중 주차장", "use_group": "기타", "status": "REVIEWABLE", "condition_note": None}])
    assert eligibility_from_review(ok)["status"] == "REVIEWABLE"
    cond = _result_with_uses([{"use_name": "주차장", "use_group": "기타", "status": "CONDITIONAL", "condition_note": "조례로 정함"}])
    e = eligibility_from_review(cond)
    assert e["status"] == "CONDITIONAL" and "조례로 정함" in e["reason"]
    assert eligibility_from_review(_result_with_uses(ok["items"][0]["applied"]["uses"], "ZONE_OVERLAY"))["status"] == "MANUAL_CHECK"


def test_simulation_gated_when_parking_use_unconfirmed(world):  # noqa: F811
    from app.parking.service import simulate_for_review
    from app.parking.types import SimulationParams

    rid = world["svc"].submit(pnu=PNU_R2G, as_of=None, audience="owner")
    with world["db"].session() as s:
        out = simulate_for_review(s, rid, SimulationParams())
    # 예시 데이터에는 용도(ZONING_USE) 규칙이 없으므로 주차장 설치 가능 여부 미확인 → 배치 계산 안 함
    assert out["simulated"] is False and out["constraint_reasons"] == ["PARKING_USE_NOT_CONFIRMED"]
    assert out["eligibility"]["status"] == "MANUAL_CHECK"


def test_worker_mode_claims_and_runs(world):  # noqa: F811
    svc = world["svc"]
    wsvc = ReviewService(svc.db, svc.parcels, svc.explainer, executor="worker")
    rid = wsvc.submit(pnu=PNU_R2G, as_of=None, audience="owner")
    assert wsvc.get(rid)["state"] == "queued"
    claimed = wsvc.claim_next()
    assert claimed == rid and wsvc.claim_next() is None
    wsvc.run_claimed(claimed)
    assert wsvc.get(rid)["state"] == "done"
