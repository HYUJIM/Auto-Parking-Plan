"""FastAPI 앱. 실행: python -m app.cli serve  (또는 uvicorn app.main:app)"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import __version__
from app.api import admin, public
from app.api.errors import (
    ApiError,
    api_error_handler,
    http_error_handler,
    unhandled_error_handler,
    validation_error_handler,
)
from app.config import Settings, get_settings
from app.console import ensure_utf8_stdio
from app.container import Container, build_container
from app.security.middleware import RequestGuardMiddleware, SecurityHeadersMiddleware
from app.security.ratelimit import AuthFailureGuard, Policy, TokenBucketLimiter


class SecurityState:
    """요청 제한·관리자 인증 실패 잠금 상태 (프로세스 내)."""

    def __init__(self, settings: Settings):
        self.limiter = TokenBucketLimiter()
        self.admin_guard = AuthFailureGuard(max_failures=settings.admin_max_failures, lock_s=settings.admin_lock_minutes * 60)
        self.trusted_proxy_hops = settings.trusted_proxy_hops


def rate_policies(s: Settings) -> dict[str, Policy] | None:
    if not s.rate_limits_on:
        return None

    def pol(name: str, per_min: int) -> Policy:
        return Policy(name, per_min, max(3, per_min // 2))

    return {"reviews": pol("reviews", s.rate_limit_reviews_per_min), "simulations": pol("simulations", s.rate_limit_simulations_per_min),
            "search": pol("search", s.rate_limit_search_per_min), "parcels": pol("parcels", s.rate_limit_parcels_per_min),
            "default": pol("default", s.rate_limit_default_per_min)}


def configure_logging(level: str) -> None:
    ensure_utf8_stdio()  # 윈도우 콘솔(cp949/cp1252)·파이프에서도 한글 로그가 깨지지 않도록
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


def create_app(settings: Settings | None = None, container: Container | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if getattr(app.state, "container", None) is None:
            app.state.container = build_container(settings)
        yield
        app.state.container.close()

    docs = settings.docs_enabled  # 운영에서는 API 문서(관리자 경로 포함)를 외부에 내보이지 않는다
    app = FastAPI(title="APP(Auto Parking Plan) 법규 서버", version=__version__, lifespan=lifespan,
                  description="주소 기반 토지·법규 초기검토 및 주차장 배치 시뮬레이션 API (비로그인)",
                  docs_url="/docs" if docs else None, redoc_url="/redoc" if docs else None,
                  openapi_url="/openapi.json" if docs else None)
    app.state.container = container
    app.state.security = sec = SecurityState(settings)
    # 미들웨어는 나중에 추가한 것이 바깥쪽: 보안 헤더 → CORS → 요청 제한·본문 크기 → 앱
    app.add_middleware(RequestGuardMiddleware, limiter=sec.limiter, policies=rate_policies(settings),
                       trusted_proxy_hops=settings.trusted_proxy_hops, max_body_bytes=settings.max_body_bytes,
                       max_admin_body_bytes=settings.max_admin_body_bytes)
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["GET", "POST"],
                       allow_headers=["Content-Type", "X-Admin-Token"], allow_credentials=False,
                       expose_headers=["Retry-After", "Location"], max_age=600)
    app.add_middleware(SecurityHeadersMiddleware, hsts=settings.app_env == "prod")
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_error_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)
    app.include_router(public.router)
    app.include_router(admin.router)

    @app.get("/healthz", tags=["ops"])
    def healthz() -> dict:
        from sqlalchemy import text  # noqa: PLC0415

        c = app.state.container
        with c.db.engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        if c.settings.app_env == "prod":  # 운영: 내부 구성(LLM 제공자·외부 API 설정 여부)은 알리지 않는다
            return {"status": "ok"}
        return {"status": "ok", "version": __version__, "llm": c.settings.llm_provider,
                "external": {"juso": c.parcels.juso is not None, "vworld": c.parcels.vworld is not None}}

    return app


def _default_app() -> FastAPI:
    s = get_settings()
    configure_logging(s.log_level)
    return create_app(s)


app = _default_app() if __name__ != "__main__" else None
