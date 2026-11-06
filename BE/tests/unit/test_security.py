"""보안 부품 단위 테스트: 클라이언트 IP 판별, 요청 제한, 관리자 잠금, 링크·경계 정화, 계산식, 비밀값 가리기, 운영 설정 검사."""
from __future__ import annotations

import httpx
import pytest

from app.config import Settings
from app.external._http import redact_secrets
from app.legal.lawgo_drf import DrfError, LawGoDrfClient
from app.review.rules_logic import eval_formula
from app.security.ratelimit import AuthFailureGuard, Policy, TokenBucketLimiter, client_ip
from app.security.sanitize import GeometryError, safe_url, validate_zone_geometry


class Clock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def _scope(peer: str = "10.0.0.9", xff: list[str] | None = None) -> dict:
    return {"client": (peer, 1234), "headers": [(b"x-forwarded-for", v.encode()) for v in (xff or [])]}


# ── 클라이언트 IP ──────────────────────────────────────────
def test_client_ip_without_proxy_ignores_forwarded_header() -> None:
    assert client_ip(_scope(xff=["6.6.6.6"]), 0) == "10.0.0.9"


def test_client_ip_behind_alb_uses_entry_appended_by_proxy() -> None:
    # 공격자가 X-Forwarded-For: 1.1.1.1 을 넣어 보내면 ALB 가 실제 IP(203.0.113.7)를 뒤에 덧붙인다
    assert client_ip(_scope(xff=["1.1.1.1, 203.0.113.7"]), 1) == "203.0.113.7"
    assert client_ip(_scope(xff=["1.1.1.1", "203.0.113.7"]), 1) == "203.0.113.7"  # 헤더가 여러 줄이어도
    assert client_ip(_scope(xff=["9.9.9.9, 1.1.1.1, 203.0.113.7"]), 2) == "1.1.1.1"


def test_client_ip_falls_back_to_peer_on_missing_or_garbage() -> None:
    assert client_ip(_scope(), 1) == "10.0.0.9"
    assert client_ip(_scope(xff=["not-an-ip"]), 1) == "10.0.0.9"
    assert client_ip(_scope(xff=["1.1.1.1"]), 2) == "10.0.0.9"  # 프록시 수보다 짧으면 위조로 보고 믿지 않음


# ── 토큰 버킷 ──────────────────────────────────────────────
def test_token_bucket_allows_burst_then_limits_and_refills() -> None:
    clock = Clock()
    lim = TokenBucketLimiter(clock=clock)
    pol = Policy("search", per_minute=6, burst=3)  # 10초에 1개
    assert [lim.hit(pol, "a") for _ in range(3)] == [0, 0, 0]
    wait = lim.hit(pol, "a")
    assert 9.9 < wait <= 10.0
    assert lim.hit(pol, "b") == 0  # IP 별로 따로
    clock.t += 10.0
    assert lim.hit(pol, "a") == 0
    assert lim.hit(pol, "a") > 0


def test_token_bucket_memory_is_bounded() -> None:
    lim = TokenBucketLimiter(max_keys=100)
    pol = Policy("p", 60, 5)
    for i in range(1000):
        lim.hit(pol, f"10.0.{i // 250}.{i % 250}")
    assert len(lim._buckets) == 100


# ── 관리자 토큰 대입 잠금 ──────────────────────────────────
def test_auth_failure_guard_locks_and_unlocks() -> None:
    clock = Clock()
    g = AuthFailureGuard(max_failures=3, window_s=60, lock_s=120, clock=clock)
    for _ in range(2):
        g.failure("ip")
    assert g.retry_after("ip") == 0
    g.failure("ip")
    assert g.retry_after("ip") == 120
    clock.t += 121
    assert g.retry_after("ip") == 0


def test_auth_failure_guard_window_and_success_reset() -> None:
    clock = Clock()
    g = AuthFailureGuard(max_failures=3, window_s=60, lock_s=120, clock=clock)
    g.failure("ip")
    g.failure("ip")
    clock.t += 61  # 오래된 실패는 창 밖
    g.failure("ip")
    assert g.retry_after("ip") == 0
    g.failure("ip")
    g.success("ip")
    g.failure("ip")
    assert g.retry_after("ip") == 0


# ── 링크 정화 (저장형 XSS) ─────────────────────────────────
@pytest.mark.parametrize("url", ["https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=1", "http://example.invalid/gosi", " https://a.example/x "])
def test_safe_url_keeps_http_links(url: str) -> None:
    assert safe_url(url) == url.strip()


@pytest.mark.parametrize("url", [
    "javascript:alert(1)", "JaVaScRiPt:alert(1)", "  javascript:alert(1)", "java\tscript:alert(1)", "data:text/html,<script>x</script>",
    "vbscript:msgbox(1)", "file:///etc/passwd", "//evil.example/x", "/relative", "http://", "https://a.example/\nSet-Cookie:x",
    "manual://registration", "", None, 123, "https://" + "a" * 3000,
])
def test_safe_url_rejects_dangerous_or_odd_links(url) -> None:  # noqa: ANN001
    assert safe_url(url) is None


# ── L4 구역 경계 검사 ──────────────────────────────────────
def _poly(*pts: tuple[float, float]) -> dict:
    return {"type": "Polygon", "coordinates": [[list(p) for p in pts]]}


def test_zone_geometry_accepts_valid_polygon() -> None:
    g = _poly((128.63, 35.85), (128.64, 35.85), (128.64, 35.86), (128.63, 35.86), (128.63, 35.85))
    assert validate_zone_geometry(g) == g


@pytest.mark.parametrize("geom", [
    {"type": "Polygon", "coordinates": "garbage"},
    {"type": "Point", "coordinates": [128.6, 35.8]},
    {"type": "Polygon", "coordinates": []},
    _poly((128.63, 35.85), (float("nan"), 35.85), (128.64, 35.86), (128.63, 35.85)),
    _poly((0, 0), (1, 0), (1, 1), (0, 0)),  # 대한민국 범위 밖
    _poly((128.63, 35.85), (128.64, 35.86), (128.64, 35.85), (128.63, 35.86), (128.63, 35.85)),  # 자기 교차(나비넥타이)
    {"type": "Polygon", "coordinates": [[[128.6, 35.8]] * 3], "crs": "x"},  # 허용 안 된 키
    {"type": "Polygon", "coordinates": [[[[[[128.6, 35.8]]]]]]},  # 너무 깊은 중첩
    {"type": "Polygon", "coordinates": [[[128.6 + i * 1e-6, 35.8] for i in range(50_001)]]},  # 꼭짓점 상한
    {"type": "Polygon", "coordinates": [[[True, False], [1, 1], [1, 0], [True, False]]]},
])
def test_zone_geometry_rejects_bad_input(geom: dict) -> None:
    with pytest.raises(GeometryError):
        validate_zone_geometry(geom)


# ── 계산식 (AI 가 추출한 식) ──────────────────────────────
@pytest.mark.parametrize("expr", [
    "__import__('os').system('id')", "gross_floor_area.__class__", "open('x')", "gross_floor_area / 0", "min()",
    "ceil(1e308 * 1e308)", "1" * 600, "gross_floor_area ** 99999", "lambda: 1",
])
def test_eval_formula_rejects_code_and_arithmetic_errors(expr: str) -> None:
    with pytest.raises(ValueError):
        eval_formula(expr, {"gross_floor_area": 1000.0})


def test_eval_formula_still_computes() -> None:
    assert eval_formula("ceil(gross_floor_area / 150)", {"gross_floor_area": 1000.0}) == 7


# ── 비밀값 가리기 ─────────────────────────────────────────
def test_redact_covers_lawgo_oc_and_other_keys() -> None:
    url = "https://www.law.go.kr/DRF/lawService.do?OC=myoc&target=law&ID=1&confmKey=abc&serviceKey=x%2By&key=v&doc=keep"
    out = redact_secrets(url)
    assert "myoc" not in out and "abc" not in out and "x%2By" not in out and "key=v" not in out
    assert "OC=***" in out and "doc=keep" in out


def test_drf_errors_do_not_carry_oc_in_message() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(403, content=b"forbidden")

    c = LawGoDrfClient("secret-oc-value", transport=httpx.MockTransport(handler))
    with pytest.raises(DrfError) as ei:
        c.search("law", "주차장법")
    assert "secret-oc-value" not in str(ei.value) and ei.value.__cause__ is None

    def boom(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=req)

    c = LawGoDrfClient("secret-oc-value", transport=httpx.MockTransport(boom))
    with pytest.raises(DrfError) as ei:
        c.search("law", "주차장법")
    assert "secret-oc-value" not in str(ei.value)


def test_drf_default_base_url_is_https() -> None:
    assert LawGoDrfClient("x").base_url.startswith("https://")
    assert Settings(_env_file=None).lawgo_base_url.startswith("https://")


# ── 운영 설정 검사 ────────────────────────────────────────
@pytest.mark.parametrize("token", ["short", "change-me-local-admin-0123456789abcdef", "a" * 40])
def test_prod_rejects_weak_admin_token(token: str) -> None:
    with pytest.raises(ValueError):
        Settings(_env_file=None, app_env="prod", admin_token=token)


def test_prod_accepts_strong_admin_token_and_turns_docs_off() -> None:
    s = Settings(_env_file=None, app_env="prod", admin_token="Zx8-qL2vN0pR7sT4uW1yA3bC6dE9fG5hJ")
    assert not s.docs_enabled and s.rate_limits_on
    assert Settings(_env_file=None, app_env="prod").admin_token is None  # 토큰 없으면 관리자 API 비활성 (허용)


def test_blank_admin_token_means_disabled_and_test_env_defaults() -> None:
    assert Settings(_env_file=None, admin_token="   ").admin_token is None
    t = Settings(_env_file=None, app_env="test")
    assert t.docs_enabled and not t.rate_limits_on
    loc = Settings(_env_file=None, app_env="local")
    assert loc.rate_limits_on  # 로컬도 기본은 켬 (안전한 기본값)


def test_cli_serve_does_not_trust_forwarded_headers(monkeypatch) -> None:
    """로컬 모의 공격에서 실제로 뚫린 것: uvicorn 기본값(127.0.0.1 의 X-Forwarded-For 신뢰) 때문에 같은 PC 에서
    헤더만 바꿔 관리자 잠금·요청 제한을 피할 수 있었다."""
    import argparse

    import uvicorn

    from app import cli

    seen: dict = {}
    monkeypatch.setattr(uvicorn, "run", lambda *a, **kw: seen.update(kw))
    cli.cmd_serve(argparse.Namespace(host="127.0.0.1", port=8000))
    assert seen["proxy_headers"] is False and seen["server_header"] is False


def test_dockerfile_never_trusts_all_forwarders() -> None:
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    cmd = next(line for line in (root / "Dockerfile").read_text(encoding="utf-8").splitlines() if line.startswith("CMD"))
    assert "--forwarded-allow-ips" not in cmd and "--no-server-header" in cmd
    tf = (root / "deploy" / "aws" / "main.tf").read_text(encoding="utf-8")
    assert '"FORWARDED_ALLOW_IPS", value = aws_vpc.main.cidr_block' in tf and '"TRUSTED_PROXY_HOPS", value = "1"' in tf
