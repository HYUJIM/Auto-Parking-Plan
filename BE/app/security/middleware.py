"""
ASGI 미들웨어 (순수 ASGI — 본문을 스트리밍으로 세기 위해 BaseHTTPMiddleware 를 쓰지 않는다).

  RequestGuardMiddleware   본문 크기 제한(413) + IP·경로 묶음별 요청 제한(429, Retry-After)
  SecurityHeadersMiddleware 모든 응답에 보안 헤더 (nosniff, 프레임 금지, CSP, 리퍼러 차단, 운영 HSTS, 관리자 no-store)
"""
from __future__ import annotations

import json
import math
from collections.abc import Awaitable, Callable

from app.security.ratelimit import Policy, TokenBucketLimiter, client_ip

ASGIApp = Callable[[dict, Callable[[], Awaitable[dict]], Callable[[dict], Awaitable[None]]], Awaitable[None]]

BODY_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})
DOCS_PATHS = ("/docs", "/redoc", "/openapi.json")


async def send_error(send, status: int, code: str, message: str, headers: list[tuple[bytes, bytes]] | None = None) -> None:  # noqa: ANN001
    body = json.dumps({"error": {"code": code, "message": message}}, ensure_ascii=False).encode("utf-8")
    hdrs = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())] + (headers or [])
    await send({"type": "http.response.start", "status": status, "headers": hdrs})
    await send({"type": "http.response.body", "body": body})


class RequestGuardMiddleware:
    def __init__(self, app: ASGIApp, *, limiter: TokenBucketLimiter, policies: dict[str, Policy] | None,
                 trusted_proxy_hops: int = 0, max_body_bytes: int = 64 * 1024, max_admin_body_bytes: int = 2 * 1024 * 1024):
        self.app = app
        self.limiter = limiter
        self.policies = policies  # None 이면 요청 제한 끔 (테스트)
        self.hops = trusted_proxy_hops
        self.max_body = max_body_bytes
        self.max_admin_body = max_admin_body_bytes

    def classify(self, method: str, path: str) -> Policy | None:
        p = self.policies
        if p is None or path == "/healthz" or method == "OPTIONS":
            return None
        if method == "POST" and path.rstrip("/") == "/v1/reviews":
            return p["reviews"]
        if method == "POST" and path.startswith("/v1/reviews/") and path.rstrip("/").endswith("/parking-simulations"):
            return p["simulations"]
        if path.startswith("/v1/address/"):
            return p["search"]
        if path.startswith("/v1/parcels"):
            return p["parcels"]
        return p["default"]

    async def __call__(self, scope: dict, receive, send) -> None:  # noqa: ANN001
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        method, path = scope["method"], scope["path"]

        policy = self.classify(method, path)
        if policy is not None:
            wait = self.limiter.hit(policy, client_ip(scope, self.hops))
            if wait > 0:
                await send_error(send, 429, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
                                 [(b"retry-after", str(max(1, math.ceil(wait))).encode())])
                return

        if method not in BODY_METHODS:
            await self.app(scope, receive, send)
            return
        limit = self.max_admin_body if path.startswith("/admin") else self.max_body
        cl = next((v for k, v in scope.get("headers") or () if k == b"content-length"), None)
        if cl is not None:
            try:
                declared = int(cl)
            except ValueError:
                await send_error(send, 400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.")
                return
            if declared > limit:
                await send_error(send, 413, "PAYLOAD_TOO_LARGE", f"요청 본문이 너무 큽니다 (최대 {limit // 1024}KB).")
                return
        # Content-Length 가 없거나(청크 전송) 거짓이어도 실제로 읽은 양으로 다시 센다
        chunks, total = [], 0
        while True:
            msg = await receive()
            if msg["type"] == "http.disconnect":
                return
            chunk = msg.get("body", b"")
            total += len(chunk)
            if total > limit:
                await send_error(send, 413, "PAYLOAD_TOO_LARGE", f"요청 본문이 너무 큽니다 (최대 {limit // 1024}KB).")
                return
            chunks.append(chunk)
            if not msg.get("more_body", False):
                break
        body = b"".join(chunks)
        replayed = False

        async def replay() -> dict:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


class SecurityHeadersMiddleware:
    """JSON API 응답에 붙이는 보안 헤더. 이미 정한 헤더는 덮어쓰지 않는다."""

    BASE = (
        (b"x-content-type-options", b"nosniff"),
        (b"x-frame-options", b"DENY"),
        (b"referrer-policy", b"no-referrer"),
        (b"permissions-policy", b"camera=(), microphone=(), geolocation=(), payment=()"),
        (b"cross-origin-opener-policy", b"same-origin"),
    )
    CSP = b"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

    def __init__(self, app: ASGIApp, *, hsts: bool = False):
        self.app = app
        self.hsts = hsts

    async def __call__(self, scope: dict, receive, send) -> None:  # noqa: ANN001
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope["path"]

        async def send_wrapper(msg: dict) -> None:
            if msg["type"] == "http.response.start":
                headers = list(msg.get("headers") or [])
                have = {k.lower() for k, _ in headers}
                extra = list(self.BASE)
                if not path.startswith(DOCS_PATHS):  # Swagger 화면은 스크립트가 필요 (운영에서는 화면 자체를 끈다)
                    extra.append((b"content-security-policy", self.CSP))
                if path.startswith("/admin"):
                    extra.append((b"cache-control", b"no-store"))
                if self.hsts:
                    extra.append((b"strict-transport-security", b"max-age=31536000; includeSubDomains"))
                headers += [(k, v) for k, v in extra if k not in have]
                msg = dict(msg, headers=headers)
            await send(msg)

        await self.app(scope, receive, send_wrapper)
