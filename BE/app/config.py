"""환경 설정. 모든 값은 환경변수(.env) 로 덮어쓸 수 있다. 윈도우/맥 공통."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
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
    lawgo_base_url: str = "http://www.law.go.kr/DRF"
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

    @property
    def effective_judge_provider(self) -> str:
        return self.judge_provider or self.llm_provider


@lru_cache
def get_settings() -> Settings:
    return Settings()
