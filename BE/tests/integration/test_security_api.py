"""API 보안 회귀 테스트 — 로컬 모의 공격(2026-11-06)에서 실제로 뚫렸던 것들이 다시 열리지 않는지."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.config import Settings
from app.container import build_container
from app.db.models import ReviewRequest
from app.llm.factory import LLMSet
from app.llm.fake import FakeLLM
from app.main import create_app
from app.parcels.service import ParcelService
from tests.conftest import StubVWorld, square
from tests.integration.test_pipeline_example import PNU_R2G, world  # noqa: F401  (fixture 재사용)

ADMIN = "test-admin-token"
STRONG = "Zx8-qL2vN0pR7sT4uW1yA3bC6dE9fG5hJ"


class CountingVWorld(StubVWorld):
    def __init__(self, inner: StubVWorld):
        super().__init__(inner.parcels)
        self.calls = 0

    def get_parcel(self, pnu):  # noqa: ANN001, ANN201
        self.calls += 1
        return super().get_parcel(pnu)


@pytest.fixture
def make_client(world, tmp_path):  # noqa: F811
    clients = []

    def _make(**overrides) -> TestClient:  # noqa: ANN003
        base = {"database_url": "sqlite://", "blob_local_dir": tmp_path / "blobs", "llm_provider": "fake",
                "review_executor": "inline", "admin_token": ADMIN, "app_env": "test"}
        vw = overrides.pop("_vworld", None) or world["svc"].parcels.vworld
        settings = Settings(_env_file=None, **(base | overrides))
        parcels = ParcelService(vworld=vw, refresh_min_interval_s=settings.parcel_refresh_min_interval_s)
        c = build_container(settings, db=world["db"], llms=LLMSet(FakeLLM(), FakeLLM(model="judge"), None), parcels=parcels)
        tc = TestClient(create_app(settings, container=c), raise_server_exceptions=False)
        tc.__enter__()
        clients.append(tc)
        return tc

    yield _make
    for tc in clients:
        tc.__exit__(None, None, None)


# ── 보안 헤더 · 문서 · healthz ────────────────────────────
def test_security_headers_on_every_response(make_client) -> None:
    c = make_client()
    for r in (c.get("/v1/regions"), c.get("/v1/parcels/123"), c.get("/admin/rules")):
        assert r.headers["x-content-type-options"] == "nosniff"
        assert r.headers["x-frame-options"] == "DENY"
        assert "default-src 'none'" in r.headers["content-security-policy"]
        assert r.headers["referrer-policy"] == "no-referrer"
        assert "strict-transport-security" not in r.headers  # HTTPS 는 운영(ALB)에서만
    assert c.get("/admin/rules", headers={"X-Admin-Token": ADMIN}).headers["cache-control"] == "no-store"


def test_prod_hides_docs_and_internals_and_sends_hsts(make_client) -> None:
    c = make_client(app_env="prod", admin_token=STRONG, rate_limit_enabled=False)
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert c.get(path).status_code == 404
    r = c.get("/healthz")
    assert r.json() == {"status": "ok"}
    assert r.headers["strict-transport-security"].startswith("max-age=")
    assert make_client().get("/openapi.json").status_code == 200  # 개발·테스트에서는 문서 사용


def test_unknown_route_and_unhandled_error_do_not_leak(make_client) -> None:
    c = make_client()
    r = c.get("/nope")
    assert r.status_code == 404 and r.json()["error"]["code"] == "NOT_FOUND"

    @c.app.get("/boom")
    def boom() -> None:
        raise RuntimeError("db password is hunter2")

    r = c.get("/boom")
    assert r.status_code == 500 and r.json()["error"]["code"] == "INTERNAL_ERROR"
    assert "hunter2" not in r.text and "RuntimeError" not in r.text


# ── 관리자 인증 ───────────────────────────────────────────
@pytest.mark.parametrize("token", ["관리자".encode().decode("latin-1"), "\xe9\xe9", "test-admin-tokeñ"])
def test_admin_non_ascii_token_is_401_not_500(make_client, token: str) -> None:
    r = make_client().get("/admin/rules", headers={"X-Admin-Token": token.encode("latin-1")})
    assert r.status_code == 401, r.text


def test_admin_bruteforce_locks_ip_even_for_correct_token(make_client) -> None:
    c = make_client(admin_max_failures=5)
    for i in range(5):
        assert c.get("/admin/rules", headers={"X-Admin-Token": f"guess-{i}"}).status_code == 401
    r = c.get("/admin/rules", headers={"X-Admin-Token": ADMIN})
    assert r.status_code == 429 and int(r.headers["retry-after"]) > 0
    assert r.json()["error"]["code"] == "RATE_LIMITED"


def test_admin_lock_is_per_real_ip_behind_alb(make_client) -> None:
    c = make_client(admin_max_failures=3, trusted_proxy_hops=1)
    for i in range(3):  # 공격자는 매번 맨 앞 X-Forwarded-For 를 바꿔도 ALB 가 붙인 실제 IP 는 같다
        c.get("/admin/rules", headers={"X-Admin-Token": "x", "X-Forwarded-For": f"10.9.9.{i}, 198.51.100.66"})
    assert c.get("/admin/rules", headers={"X-Admin-Token": ADMIN, "X-Forwarded-For": "1.2.3.4, 198.51.100.66"}).status_code == 429
    ok = c.get("/admin/rules", headers={"X-Admin-Token": ADMIN, "X-Forwarded-For": "198.51.100.77"})
    assert ok.status_code == 200  # 다른 실제 IP 의 관리자는 영향 없음


# ── 요청 제한 ─────────────────────────────────────────────
def test_rate_limit_returns_429_with_retry_after(make_client) -> None:
    c = make_client(rate_limit_enabled=True, rate_limit_search_per_min=6)  # burst = 3
    codes = [c.get("/v1/address/search", params={"q": "수성구"}).status_code for _ in range(5)]
    assert codes[:3] != [429] * 3 and codes[-1] == 429
    r = c.get("/v1/address/search", params={"q": "수성구"})
    assert r.status_code == 429 and int(r.headers["retry-after"]) >= 1
    assert r.json()["error"]["code"] == "RATE_LIMITED"
    assert c.get("/v1/regions").status_code == 200  # 다른 묶음은 별도
    assert c.get("/healthz").status_code == 200  # 헬스체크는 제한 없음


def test_rate_limit_not_bypassed_by_spoofed_forwarded_for(make_client) -> None:
    c = make_client(rate_limit_enabled=True, rate_limit_reviews_per_min=6, trusted_proxy_hops=1)
    codes = [c.post("/v1/reviews", json={"pnu": PNU_R2G}, headers={"X-Forwarded-For": f"10.0.0.{i}, 203.0.113.9"}).status_code
             for i in range(6)]
    assert codes.count(429) >= 2, codes
    other = c.post("/v1/reviews", json={"pnu": PNU_R2G}, headers={"X-Forwarded-For": "203.0.113.10"})
    assert other.status_code == 202


# ── 본문 크기 ─────────────────────────────────────────────
def test_oversized_public_body_rejected_413(make_client) -> None:
    c = make_client(max_body_bytes=4096)
    big = '{"pnu":"' + PNU_R2G + '","x":"' + "A" * 10_000 + '"}'
    r = c.post("/v1/reviews", content=big, headers={"Content-Type": "application/json"})
    assert r.status_code == 413 and r.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"

    def chunks():  # Content-Length 없이 청크로 보내도 실제 읽은 양으로 막는다
        for _ in range(10):
            yield b"A" * 1024

    r = c.post("/v1/reviews", content=chunks(), headers={"Content-Type": "application/json"})
    assert r.status_code == 413


def test_lying_content_length_rejected(make_client) -> None:
    c = make_client()
    r = c.post("/v1/reviews", content=b"{}", headers={"Content-Type": "application/json", "Content-Length": "abc"})
    assert r.status_code == 400


# ── 입력 검증 ─────────────────────────────────────────────
@pytest.mark.parametrize("body", ['{"gate_point":[NaN,35.8]}', '{"gate_point":[Infinity,35.8]}', '{"gate_point":[500,35.8]}',
                                  '{"angles":["ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45","ANGLE_45"]}'])
def test_simulation_rejects_non_finite_or_out_of_range(make_client, body: str) -> None:
    c = make_client()
    rid = c.post("/v1/reviews", json={"pnu": PNU_R2G}).json()["review_id"]
    r = c.post(f"/v1/reviews/{rid}/parking-simulations", content=body, headers={"Content-Type": "application/json"})
    assert r.status_code == 422, r.text


def test_review_inputs_reject_nan(make_client) -> None:
    r = make_client().post("/v1/reviews", content='{"pnu":"' + PNU_R2G + '","inputs":{"gross_floor_area":NaN}}',
                           headers={"Content-Type": "application/json"})
    assert r.status_code == 422


# ── 저장형 XSS: 관리자 입력 → 공개 결과 링크 ───────────────
def _zone(**kw) -> dict:  # noqa: ANN003
    ring = square(128.6299, 35.8499, 0.002, 0.002)["coordinates"][0]
    return {"reviewer": "검수자", "zone_type": "other", "name": "예시 구역 (예시)", "notice_title": "예시 고시 (예시)",
            "jurisdiction_code": "27260", "geometry": {"type": "Polygon", "coordinates": ring}} | kw


@pytest.mark.parametrize("url", ["javascript:alert(document.cookie)", "data:text/html,<script>alert(1)</script>", "//evil.example"])
def test_zone_rejects_script_urls(make_client, url: str) -> None:
    r = make_client().post("/admin/zones", headers={"X-Admin-Token": ADMIN}, json=_zone(notice_url=url))
    assert r.status_code == 422


@pytest.mark.parametrize("geom", [{"type": "Polygon", "coordinates": "garbage"}, {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}])
def test_zone_rejects_bad_geometry(make_client, geom: dict) -> None:
    r = make_client().post("/admin/zones", headers={"X-Admin-Token": ADMIN}, json=_zone(geometry=geom))
    assert r.status_code == 422


def test_zone_rejects_bad_pnu_list(make_client) -> None:
    r = make_client().post("/admin/zones", headers={"X-Admin-Token": ADMIN}, json=_zone(geometry=None, pnu_list=["x" * 10_000]))
    assert r.status_code == 422


def test_public_result_links_are_http_only(make_client) -> None:
    c = make_client()
    assert c.post("/admin/zones", headers={"X-Admin-Token": ADMIN}, json=_zone()).status_code == 201  # notice_url 없음 → manual://
    rid = c.post("/v1/reviews", json={"pnu": PNU_R2G}).json()["review_id"]
    res = c.get(f"/v1/reviews/{rid}").json()
    urls = [b.get("url") for m in res["manual_checks"] for b in m.get("related_basis") or []]
    urls += [b.get("url") for it in res["items"] for b in it.get("basis") or [] if isinstance(b, dict)]
    assert urls and all(u is None or u.startswith(("http://", "https://")) for u in urls), urls


# ── 외부 API 할당량 보호 ──────────────────────────────────
def test_public_refresh_cannot_hammer_upstream(make_client, world) -> None:  # noqa: F811
    vw = CountingVWorld(world["svc"].parcels.vworld)
    c = make_client(_vworld=vw, parcel_refresh_min_interval_s=600)
    assert c.get(f"/v1/parcels/{PNU_R2G}").status_code == 200
    first = vw.calls
    for _ in range(10):
        assert c.get(f"/v1/parcels/{PNU_R2G}", params={"refresh": "true"}).status_code == 200
    assert vw.calls == first  # 10분 안의 재조회 요청은 저장된 정보로 응답


def test_refresh_allowed_after_interval(make_client, world) -> None:  # noqa: F811
    vw = CountingVWorld(world["svc"].parcels.vworld)
    c = make_client(_vworld=vw, parcel_refresh_min_interval_s=0)
    c.get(f"/v1/parcels/{PNU_R2G}")
    first = vw.calls
    c.get(f"/v1/parcels/{PNU_R2G}", params={"refresh": "true"})
    assert vw.calls == first + 1


# ── 폭주 · 실패 정보 ──────────────────────────────────────
def test_review_queue_backpressure_503(make_client, world) -> None:  # noqa: F811
    c = make_client(max_pending_reviews=1)
    rid = c.post("/v1/reviews", json={"pnu": PNU_R2G}).json()["review_id"]
    with world["db"].session() as s:
        s.scalar(select(ReviewRequest).where(ReviewRequest.status == "done")).status = "queued"
    r = c.post("/v1/reviews", json={"pnu": PNU_R2G})
    assert r.status_code == 503 and r.json()["error"]["code"] == "SERVICE_BUSY" and r.headers["retry-after"]
    assert rid


def test_failed_review_hides_internal_error_detail(make_client, world) -> None:  # noqa: F811
    c = make_client()
    rid = c.post("/v1/reviews", json={"pnu": PNU_R2G}).json()["review_id"]
    with world["db"].session() as s:
        req = s.scalar(select(ReviewRequest).order_by(ReviewRequest.created_at.desc()))
        req.status, req.result_json = "failed", None
        req.error = {"code": "RESULT_SCHEMA_VIOLATION", "message": "items/0/applied: internal-detail-xyz"}
    out = c.get(f"/v1/reviews/{rid}").json()
    assert out["error"]["code"] == "RESULT_SCHEMA_VIOLATION"
    assert "internal-detail-xyz" not in str(out)
