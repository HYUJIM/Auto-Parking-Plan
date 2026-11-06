"""FastAPI 앱. 실행: python -m app.cli serve  (또는 uvicorn app.main:app)"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api import admin, public
from app.api.errors import ApiError, api_error_handler, validation_error_handler
from app.config import Settings, get_settings
from app.console import ensure_utf8_stdio
from app.container import Container, build_container


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

    app = FastAPI(title="APP(Auto Parking Plan) 법규 서버", version=__version__, lifespan=lifespan,
                  description="주소 기반 토지·법규 초기검토 및 주차장 배치 시뮬레이션 API (비로그인)")
    app.state.container = container
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["GET", "POST"],
                       allow_headers=["Content-Type", "X-Admin-Token"])
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.include_router(public.router)
    app.include_router(admin.router)

    @app.get("/healthz", tags=["ops"])
    def healthz() -> dict:
        from sqlalchemy import text  # noqa: PLC0415

        c = app.state.container
        with c.db.engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return {"status": "ok", "version": __version__, "llm": c.settings.llm_provider,
                "external": {"juso": c.parcels.juso is not None, "vworld": c.parcels.vworld is not None}}

    return app


def _default_app() -> FastAPI:
    s = get_settings()
    configure_logging(s.log_level)
    return create_app(s)


app = _default_app() if __name__ != "__main__" else None
