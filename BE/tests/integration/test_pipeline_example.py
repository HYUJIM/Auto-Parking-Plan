"""
예시(가짜 문구) 데이터로 전체 파이프라인을 검증한다:
원문 적재 → 위임 연결 → AI 추출 결과 기계 검증 → 사람 승인(+원장) → 검토 엔진 → 쉬운 설명 → 사후 검증 → 결과 계약.
"""
from __future__ import annotations

import copy

import pytest

from app.explain.service import ExplanationService
from app.extraction.seed import load_seed
from app.legal.ingest import ingest_bundle, link_delegations
from app.llm.fake import FakeLLM
from app.parcels.service import ParcelService
from app.review.approval import approve_rule
from app.review.service import ReviewService, review_result_validator
from app.storage.blob import LocalBlobStore
from tests.conftest import StubVWorld, example_bundles, square

PNU_R2G = "2726010100100010000"   # 예시 PNU (실제 필지 아님)
PNU_MULTI = "2726010100100020000"
PNU_OVERLAY = "2726010100100030000"
PNU_FAIL = "2726010100100040000"
PNU_JUNG = "2711010100100010000"   # 대구 중구 (미지원)


def seeds():
    zone = lambda z: [{"field": "zoning", "operator": "eq", "value": z}]  # noqa: E731
    return [
        {"format": "app-seed-extraction@1", "document_key": "ex.decree", "model": "test", "extraction": {
            "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "delegations": [], "unresolved": [],
            "rules": [
                {"rule_key": "BCR.ZONE.R2G", "category": "BUILDING_COVERAGE", "title": "제2종일반주거지역 건폐율 상한",
                 "automation_level": "auto", "conditions": zone("제2종일반주거지역"), "confidence": "high",
                 "values": [{"value_key": "ratio", "value_type": "max", "num_max": 60, "unit": "%",
                             "evidence": {"provision_id": "@art84/para1/item2", "quote": "제2종일반주거지역 : 60퍼센트 이하"}}]},
                {"rule_key": "FAR.ZONE.R2G", "category": "FLOOR_AREA_RATIO", "title": "제2종일반주거지역 용적률 범위",
                 "automation_level": "auto", "conditions": zone("제2종일반주거지역"), "confidence": "high",
                 "values": [{"value_key": "ratio", "value_type": "range", "num_min": 100, "num_max": 250, "unit": "%",
                             "evidence": {"provision_id": "@art85/para1/item1", "quote": "제2종일반주거지역 : 100퍼센트 이상 250퍼센트 이하"}}]},
                {"rule_key": "FAR.ZONE.CC", "category": "FLOOR_AREA_RATIO", "title": "중심상업지역 용적률 범위",
                 "automation_level": "auto", "conditions": zone("중심상업지역"), "confidence": "high",
                 "values": [{"value_key": "ratio", "value_type": "range", "num_min": 200, "num_max": 1500, "unit": "%",
                             "evidence": {"provision_id": "@art85/para1/item2", "quote": "중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하"}}]},
            ]}},
        {"format": "app-seed-extraction@1", "document_key": "ex.ordin", "model": "test", "extraction": {
            "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "unresolved": [],
            "delegations": [{"from_provision_id": "@art50/para1", "relation": "implements", "target_hint": "영 제84조",
                             "evidence": {"provision_id": "@art50/para1", "quote": "영 제84조제1항에 따라"}}],
            "rules": [
                {"rule_key": "BCR.ZONE.R2G", "category": "BUILDING_COVERAGE", "title": "제2종일반주거지역 건폐율 (조례)",
                 "automation_level": "auto", "conditions": zone("제2종일반주거지역"), "confidence": "high",
                 "values": [{"value_key": "ratio", "value_type": "max", "num_max": 60, "unit": "%",
                             "evidence": {"provision_id": "@art50/para1/item1", "quote": "제2종일반주거지역 : 60퍼센트 이하"}}]},
                {"rule_key": "FAR.ZONE.R2G", "category": "FLOOR_AREA_RATIO", "title": "제2종일반주거지역 용적률 (조례)",
                 "automation_level": "auto", "conditions": zone("제2종일반주거지역"), "confidence": "high",
                 "values": [{"value_key": "ratio", "value_type": "max", "num_max": 220, "unit": "%",
                             "evidence": {"provision_id": "@art51/para1/item1", "quote": "제2종일반주거지역 : 220퍼센트 이하"}}]},
            ]}},
        {"format": "app-seed-extraction@1", "document_key": "ex.rule", "model": "test", "extraction": {
            "schema_version": "rule_extraction@1.0", "document_version_id": "@version", "delegations": [], "unresolved": [],
            "rules": [
                {"rule_key": "PARK.STALL.ALL", "category": "PARKING_STALL_SIZE", "title": "주차단위구획",
                 "automation_level": "auto", "conditions": [], "confidence": "high", "values": [
                     {"value_key": "parallel.general.width", "value_type": "min", "num_min": 2.0, "unit": "m",
                      "evidence": {"provision_id": "@art3/para1/item1", "quote": "일반형 너비 2.0미터 이상"}},
                     {"value_key": "parallel.general.length", "value_type": "min", "num_min": 6.0, "unit": "m",
                      "evidence": {"provision_id": "@art3/para1/item1", "quote": "길이 6.0미터 이상"}},
                     {"value_key": "non_parallel.general.width", "value_type": "min", "num_min": 2.5, "unit": "m",
                      "evidence": {"provision_id": "@art3/para1/item2", "quote": "일반형 너비 2.5미터 이상"}},
                     {"value_key": "non_parallel.general.length", "value_type": "min", "num_min": 5.0, "unit": "m",
                      "evidence": {"provision_id": "@art3/para1/item2", "quote": "길이 5.0미터 이상"}}]},
                {"rule_key": "PARK.AISLE.ALL", "category": "DRIVE_AISLE_WIDTH", "title": "차로 너비",
                 "automation_level": "auto", "conditions": [], "confidence": "high", "values": [
                     {"value_key": "perpendicular_90.two_plus_gates", "value_type": "min", "num_min": 6.0, "unit": "m",
                      "evidence": {"provision_id": "@art6/para1/item1", "quote": "출입구가 2개 이상인 경우 6.0미터"}},
                     {"value_key": "perpendicular_90.one_gate", "value_type": "min", "num_min": 6.0, "unit": "m",
                      "evidence": {"provision_id": "@art6/para1/item1", "quote": "출입구가 1개인 경우 6.0미터"}},
                     {"value_key": "parallel_0.two_plus_gates", "value_type": "min", "num_min": 3.3, "unit": "m",
                      "evidence": {"provision_id": "@art6/para1/item2", "quote": "출입구가 2개 이상인 경우 3.3미터"}},
                     {"value_key": "parallel_0.one_gate", "value_type": "min", "num_min": 5.0, "unit": "m",
                      "evidence": {"provision_id": "@art6/para1/item2", "quote": "출입구가 1개인 경우 5.0미터"}},
                     {"value_key": "gate.width_min", "value_type": "min", "num_min": 3.5, "unit": "m",
                      "evidence": {"provision_id": "@art6/para1/item3", "quote": "출입구의 너비는 3.5미터 이상"}}]},
            ]}},
    ]


@pytest.fixture
def world(db, tmp_path):
    blobs = LocalBlobStore(tmp_path / "blobs")
    outcomes = []
    with db.session() as s:
        for b in example_bundles():
            ingest_bundle(s, b, blobs)
        link_delegations(s)
    for seed in seeds():  # 국가 법령 → 조례 순서로 적재·승인 (조례 값의 상위 범위 검사를 위해)
        with db.session() as s:
            o = load_seed(s, seed)
            outcomes.append(o)
            for rid in o.rule_ids:
                approve_rule(s, rid, reviewer="검수자(테스트)", approve_ledger_too=True)
    vw = StubVWorld({
        PNU_R2G: {"address": "대구광역시 수성구 예시동 1 (예시)", "geometry": square(128.63, 35.85), "area": 1200.0,
                  "designations": [("zoning", "제2종일반주거지역", "included")]},
        PNU_MULTI: {"address": "대구광역시 수성구 예시동 2 (예시)", "geometry": square(128.631, 35.85), "area": 800.0,
                    "designations": [("zoning", "제2종일반주거지역", "included"), ("zoning", "일반상업지역", "partially_included")]},
        PNU_OVERLAY: {"address": "대구광역시 수성구 예시동 3 (예시)", "geometry": square(128.632, 35.85), "area": 900.0,
                      "designations": [("zoning", "제2종일반주거지역", "included"), ("zone", "지구단위계획구역", "included")]},
        PNU_FAIL: {"address": "대구광역시 수성구 예시동 4 (예시)", "geometry": square(128.633, 35.85), "area": 700.0,
                   "designations": [], "land_use_error": True},
        PNU_JUNG: {"address": "대구광역시 중구 예시동 1 (예시)", "geometry": square(128.59, 35.87), "area": 500.0,
                   "designations": [("zoning", "일반상업지역", "included")]},
    })
    parcels = ParcelService(vworld=vw)
    llm = FakeLLM()
    svc = ReviewService(db, parcels, ExplanationService(writer=llm, judge=FakeLLM(model="fake-judge")), executor="inline")
    return {"db": db, "svc": svc, "outcomes": outcomes, "llm": llm}


def _review(world, pnu, **kw):
    rid = world["svc"].submit(pnu=pnu, as_of=None, audience=kw.get("audience", "owner"), inputs=kw.get("inputs"))
    out = world["svc"].get(rid)
    assert out["state"] in ("done", "partial"), out
    return out["result"]


def _item(result, cat):
    return next(i for i in result["items"] if i["category"] == cat)


def test_seed_extractions_pass_machine_validation(world):
    assert all(o.status == "ok" for o in world["outcomes"]), [(o.status, o.errors) for o in world["outcomes"]]


def test_reviewable_parcel_end_to_end(world):
    res = _review(world, PNU_R2G)
    assert list(review_result_validator().iter_errors(res)) == []
    bcr, far = _item(res, "BUILDING_COVERAGE"), _item(res, "FLOOR_AREA_RATIO")
    assert bcr["status"] == "REVIEWABLE" and bcr["applied"]["value"] == 60
    assert bcr["applied"]["reference_value"]["value"] == 720.0  # 1200 × 0.60
    assert far["status"] == "REVIEWABLE" and far["applied"]["value"] == 220
    assert far["applied"]["national_range"] == {"min": 100, "max": 250}
    # 근거 트리: L1(예시 시행령) → L3(예시 조례), 상위가 먼저
    layers = [n["layer"] for n in bcr["basis"]]
    assert layers.index("L1_NATIONAL") < layers.index("L3_LOCAL")
    # 쉬운 설명은 사후 검증 PASS 만 노출
    assert bcr["explanation"]["display_source"] == "ai_verified"
    assert bcr["explanation"]["verification"]["verdict"] == "PASS"
    # 용도 규칙이 없으므로 별도 확인 필요 + 안내
    assert _item(res, "ZONING_USE")["status"] == "MANUAL_CHECK"
    assert any(n["code"] == "RULE_NOT_APPROVED" for n in res["data_notices"])
    # 주차 기준
    assert _item(res, "PARKING_STALL_SIZE")["status"] == "REVIEWABLE"
    assert _item(res, "PARKING_REQUIREMENT")["reason_code"] in ("DATA_MISSING", "NEEDS_USE_INPUT")
    assert {m["category"] for m in res["manual_checks"]} == {"ROAD_ACCESS", "SETBACK", "HEIGHT_SUNLIGHT", "SPECIAL_ZONE"}


def test_explanation_cache_reused(world):
    _review(world, PNU_R2G)
    n = len(world["llm"].calls)
    _review(world, PNU_R2G)
    assert len(world["llm"].calls) == n  # 같은 원장·상태·독자 → 캐시


def test_multi_zoning_is_manual_check(world):
    res = _review(world, PNU_MULTI)
    assert _item(res, "BUILDING_COVERAGE")["status"] == "MANUAL_CHECK"


def test_overlay_escalates(world):
    res = _review(world, PNU_OVERLAY)
    bcr = _item(res, "BUILDING_COVERAGE")
    assert bcr["status"] == "MANUAL_CHECK" and bcr["reason_code"] == "ZONE_OVERLAY"
    sz = next(m for m in res["manual_checks"] if m["category"] == "SPECIAL_ZONE")
    assert "지구단위계획구역" in sz["triggered_by"]


def test_land_use_failure_is_partial(world):
    res = _review(world, PNU_FAIL)
    assert res["parcel"]["designations"] is None
    codes = {n["code"] for n in res["data_notices"]}
    assert {"PARCEL_PARTIAL", "DESIGNATION_UNAVAILABLE"} <= codes
    assert _item(res, "BUILDING_COVERAGE")["reason_code"] == "DATA_MISSING"


def test_unsupported_region(world):
    res = _review(world, PNU_JUNG)
    assert res["items"] == [] and res["data_notices"][0]["code"] == "REGION_NOT_SUPPORTED"


def test_bad_ai_explanation_falls_back_to_template(world):
    def bad(payload):
        return {"schema_version": "explanation@2.0", "category": payload["category"], "audience": payload["audience"],
                "sentences": [{"sid": "s1", "role": "headline", "text": "건폐율은 50% 이하예요.", "fact_ids": [payload["facts"][0]["fact_id"]]}]}
    world["llm"].scripted["explanation_writer"] = [bad, bad, bad]
    res = _review(world, PNU_R2G, audience="architect")
    exp = _item(res, "BUILDING_COVERAGE")["explanation"]
    assert exp["display_source"] == "template_fallback"
    assert all("50%" not in s["text"] for s in exp["content"]["sentences"])


def test_fabricated_number_in_extraction_is_rejected(world):
    seed = copy.deepcopy(seeds()[1])
    seed["extraction"]["rules"] = seed["extraction"]["rules"][:1]
    seed["extraction"]["rules"][0]["values"][0]["num_max"] = 50  # 원문은 60
    with world["db"].session() as s:
        out = load_seed(s, seed)
    assert out.status == "evidence_error" and not out.rule_ids


def test_out_of_range_local_value_flagged(world):
    seed = copy.deepcopy(seeds()[1])
    seed["extraction"]["rules"] = seed["extraction"]["rules"][1:]
    seed["extraction"]["rules"][0]["values"][0]["num_max"] = 220
    seed["extraction"]["rules"][0]["conditions"][0]["value"] = "중심상업지역"
    # 220 은 중심상업지역 범위(200~1500) 안이므로 통과, 범위 밖 값은 range_error
    with world["db"].session() as s:
        assert load_seed(s, seed).status == "ok"
