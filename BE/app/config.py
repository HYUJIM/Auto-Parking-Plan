"""환경 설정. 모든 값은 환경변수(.env) 로 덮어쓸 수 있다. 윈도우/맥 공통."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: Literal["local", "test", "prod"] = "local"
    log_level: str = "INFO"
    timezone: str = "Asia/Seoul"

    # DB: 로컬/테스트는 SQLite, 운영(AWS RDS)은 postgresql+psycopg://user:pw@host:5432/db
    database_url: str = Field(default_factory=lambda: f"sqlite:///{(PROJECT_ROOT / 'var' / 'app.sqlite3').as_posix()}")
    db_echo: bool = False

    # 원문 보관소: local(파일) 또는 s3
    blob_store: Literal["local", "s3"] = "local"
    blob_local_dir: Path = PROJECT_ROOT / "var" / "blobs"
    s3_bucket: str | None = None
    s3_prefix: str = "law-raw/"
    aws_region: str = "ap-northeast-2"

    # 외부 공공 API (키는 서버에만 둔다)
    juso_api_key: str | None = None
    juso_base_url: str = "https://business.juso.go.kr"
    vworld_api_key: str | None = None
    vworld_domain: str | None = None
    vworld_data_url: str = "https://api.vworld.kr"  # scheme+host (경로는 클라이언트가 붙임)
    vworld_ned_url: str = "https://api.vworld.kr"
    building_hub_key: str | None = None
    building_hub_url: str = "https://apis.data.go.kr"
    lawgo_oc: str | None = None
    lawgo_base_url: str = "https://www.law.go.kr/DRF"  # OC(인증값)가 평문으로 오가지 않도록 https
    external_timeout_s: float = 3.0
    parcel_cache_days: int = 30

    # LLM (프론티어 모델 API). none 이면 AI 설명 없이 원문 템플릿만 사용
    llm_provider: Literal["anthropic", "openai", "fake", "none"] = "none"
    judge_provider: Literal["anthropic", "openai", "fake", "none"] | None = None  # None → llm_provider 와 동일
    anthropic_api_key: str | None = None
    anthropic_base_url: str = "https://api.anthropic.com"
    anthropic_fallbacks: Literal["default", "off"] = "default"
    openai_api_key: str | None = None
    openai_base_url: str | None = None
    llm_writer_model: str = "claude-opus-5-5"
    llm_judge_model: str = "claude-sonnet-5-5"  # 설계서 9장: 작성 모델과 분리된 판정 모델
    llm_extractor_model: str = "claude-opus-5-5"
    llm_writer_effort: Literal["low", "medium", "high", "xhigh", "max"] = "medium"
    llm_judge_effort: Literal["low", "medium", "high", "xhigh", "max"] = "medium"
    llm_extractor_effort: Literal["low", "medium", "high", "xhigh", "max"] = "high"
    llm_timeout_s: float = 90.0
    llm_extractor_timeout_s: float = 600.0
    explanation_max_attempts: int = 3  # 첫 생성 + 재생성 2회
    explanation_audiences: list[str] = ["owner", "architect"]

    # 검토 실행 방식: thread(API 프로세스 내 백그라운드), inline(동기, 테스트용), worker(별도 워커 프로세스)
    review_executor: Literal["thread", "inline", "worker"] = "thread"
    review_workers: int = 4

    # 관리자 API 토큰 (검수 화면용). 비어 있으면 관리자 API 비활성
    admin_token: str | None = None
    cors_origins: list[str] = ["http://localhost:3000"]

    # 보안 (외부 공격 대비) — docs/SECURITY.md
    enable_docs: bool | None = None  # Swagger(/docs, /openapi.json). None → 운영(prod)에서만 끔
    trusted_proxy_hops: int = Field(default=0, ge=0, le=5)  # 앞단 프록시 수 (AWS ALB 뒤 = 1). 실제 클라이언트 IP 판별용
    rate_limit_enabled: bool | None = None  # None → 테스트(APP_ENV=test)에서만 끔
    rate_limit_reviews_per_min: int = Field(default=10, ge=1)  # POST /v1/reviews (DB 쓰기 + 백그라운드 검토)
    rate_limit_simulations_per_min: int = Field(default=30, ge=1)  # 배치 시뮬레이션 (CPU)
    rate_limit_search_per_min: int = Field(default=30, ge=1)  # 주소 검색 (도로명주소 API 호출)
    rate_limit_parcels_per_min: int = Field(default=60, ge=1)  # 필지 조회 (브이월드·건축HUB 호출 가능)
    rate_limit_default_per_min: int = Field(default=240, ge=1)  # 그 밖의 모든 요청 (결과 조회 폴링 포함)
    max_body_bytes: int = Field(default=64 * 1024, ge=1024)  # 공개 API 요청 본문 상한
    max_admin_body_bytes: int = Field(default=2 * 1024 * 1024, ge=1024)  # 관리자 (L4 구역 경계 GeoJSON)
    admin_max_failures: int = Field(default=10, ge=1)  # 관리자 토큰을 이만큼 틀리면
    admin_lock_minutes: int = Field(default=15, ge=1)  # 그 IP 를 이 시간 동안 잠근다
    parcel_refresh_min_interval_s: int = Field(default=600, ge=0)  # ?refresh=true 로 외부 API 를 다시 부를 수 있는 최소 간격
    max_pending_reviews: int = Field(default=500, ge=1)  # 대기 중 검토가 이보다 많으면 새 요청을 503 으로 돌려보냄

    @model_validator(mode="after")
    def _prod_guards(self) -> Settings:
        if self.admin_token is not None and not self.admin_token.strip():
            self.admin_token = None  # 공백만 있는 토큰 = 미설정
        if self.app_env == "prod" and self.admin_token:
            t = self.admin_token
            if len(t) < 32 or "change-me" in t.lower() or len(set(t)) < 8:
                raise ValueError("운영(APP_ENV=prod)의 ADMIN_TOKEN 은 32자 이상 무작위 값이어야 합니다 "
                                 "(예: python -c \"import secrets; print(secrets.token_urlsafe(32))\")")
        return self

    @property
    def docs_enabled(self) -> bool:
        return self.enable_docs if self.enable_docs is not None else self.app_env != "prod"

    @property
    def rate_limits_on(self) -> bool:
        return self.rate_limit_enabled if self.rate_limit_enabled is not None else self.app_env != "test"

    @property
    def effective_judge_provider(self) -> str:
        return self.judge_provider or self.llm_provider


@lru_cache
def get_settings() -> Settings:
    return Settings()
