"""HTTP 계층 테스트: 공개 API 4종 + 보조 API + 관리자 인증 + 오류 코드 (예시 데이터, 외부 API 가짜)."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.container import build_container
from app.external.types import AddressCandidate
from app.llm.factory import LLMSet
from app.llm.fake import FakeLLM
from app.main import create_app
from app.parcels.service import ParcelService
from tests.conftest import StubJuso, square
from tests.integration.test_pipeline_example import PNU_JUNG, PNU_R2G, world  # noqa: F401  (fixture 재사용)


@pytest.fixture
def client(world, settings):  # noqa: F811
    vw = world["svc"].parcels.vworld
    juso = StubJuso([
        AddressCandidate(road_address="대구광역시 수성구 예시로 1 (예시)", jibun_address="대구광역시 수성구 예시동 1 (예시)", pnu=PNU_R2G,
                         adm_code=PNU_R2G[:10], sido="대구광역시", sigungu="수성구", emd="예시동"),
        AddressCandidate(road_address=None, jibun_address="대구광역시 중구 예시동 1 (예시)", pnu=PNU_JUNG,
                         adm_code=PNU_JUNG[:10], sido="대구광역시", sigungu="중구", emd="예시동"),
    ])
    parcels = ParcelService(juso=juso, vworld=vw)
    c = build_container(settings, db=world["db"], llms=LLMSet(FakeLLM(), FakeLLM(model="judge"), None), parcels=parcels)
    app = create_app(settings, container=c)
    with TestClient(app) as tc:
        yield tc


def test_address_search(client):
    r = client.get("/v1/address/search", params={"q": "수성구"})
    assert r.status_code == 200
    item = r.json()["items"][0]
    assert item["pnu"] == PNU_R2G and item["supported"] is True and item["location"]["lng"] > 128


def test_address_not_found(client):
    r = client.get("/v1/address/search", params={"q": "없는주소입니다"})
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "ADDRESS_NOT_FOUND"
    assert r.json()["error"]["message"].startswith("주소를 찾을 수 없습니다")


def test_parcel_detail(client):
    r = client.get(f"/v1/parcels/{PNU_R2G}")
    body = r.json()
    assert r.status_code == 200 and body["land_area_m2"] == 1200.0
    assert body["designations"][0]["name"] == "제2종일반주거지역"
    assert body["location"]["geometry"]["type"] == "MultiPolygon"
    assert {s["field"] for s in body["sources"]} >= {"geometry", "designations", "land_area_m2"}


def test_parcel_not_found_and_bad_pnu(client):
    assert client.get("/v1/parcels/2726099999999999999").json()["error"]["code"] == "PARCEL_NOT_FOUND"
    assert client.get("/v1/parcels/abc").status_code == 404


def test_review_flow_and_basis(client):
    r = client.post("/v1/reviews", json={"pnu": PNU_R2G, "audience": "architect"})
    assert r.status_code == 202
    rid = r.json()["review_id"]
    res = client.get(f"/v1/reviews/{rid}").json()
    assert res["schema_version"] == "review_result@1.0" and res["review_id"] == rid
    bcr = next(i for i in res["items"] if i["category"] == "BUILDING_COVERAGE")
    rule_id = next(n["rule_id"] for n in bcr["basis"] if n["rule_id"])
    b = client.get(f"/v1/rules/{rule_id}/basis").json()
    assert b["evidence"][0]["quoted_text"] in b["evidence"][0]["provision_text"]
    assert b["basis_tree"] and b["document"]["source_url"]


def test_review_validation_errors(client):
    r = client.post("/v1/reviews", json={"pnu": "123"})
    assert r.status_code == 422 and r.json()["error"]["code"] == "INVALID_REQUEST"
    assert client.post("/v1/reviews", json={"pnu": PNU_R2G, "audience": "hacker"}).status_code == 422
    assert client.get("/v1/reviews/00000000-0000-4000-8000-000000000000").status_code == 404


def test_regions(client):
    body = client.get("/v1/regions").json()
    assert body["supported"] == [{"code": "27260", "name": "대구광역시 수성구"}]


def test_admin_requires_token(client):
    assert client.get("/admin/rules").status_code == 401
    assert client.get("/admin/rules", headers={"X-Admin-Token": "wrong"}).status_code == 401
    r = client.get("/admin/rules", params={"status": "approved"}, headers={"X-Admin-Token": "test-admin-token"})
    assert r.status_code == 200 and len(r.json()["items"]) >= 5


def test_admin_zone_registration_escalates(client):
    h = {"X-Admin-Token": "test-admin-token"}
    r = client.post("/admin/zones", headers=h, json={
        "reviewer": "검수자", "zone_type": "district_unit_plan", "name": "예시 지구단위계획구역 (예시)",
        "notice_title": "예시 지구단위계획 결정 고시 (예시)", "notice_number": "예시고시-1", "notice_date": "2026-01-01",
        "notice_url": "https://example.invalid/gosi", "jurisdiction_code": "27260",
        "geometry": square(128.6299, 35.8499, 0.002, 0.002)["coordinates"] and {"type": "Polygon", "coordinates": square(128.6299, 35.8499, 0.002, 0.002)["coordinates"][0]}})
    assert r.status_code == 201, r.text
    rid = client.post("/v1/reviews", json={"pnu": PNU_R2G}).json()["review_id"]
    res = client.get(f"/v1/reviews/{rid}").json()
    bcr = next(i for i in res["items"] if i["category"] == "BUILDING_COVERAGE")
    assert bcr["status"] == "MANUAL_CHECK" and bcr["reason_code"] == "ZONE_OVERLAY"
    sz = next(m for m in res["manual_checks"] if m["category"] == "SPECIAL_ZONE")
    assert sz["related_basis"] and sz["related_basis"][0]["layer"] == "L4_SPECIAL_ZONE"


def test_healthz(client):
    assert client.get("/healthz").json()["status"] == "ok"
