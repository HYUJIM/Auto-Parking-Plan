"""SQLite(로컬·테스트)와 PostgreSQL(운영) 양쪽에서 같은 의미로 동작하는 컬럼 타입."""
from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import JSON, DateTime, TypeDecorator
from sqlalchemy.dialects.postgresql import JSONB

JSONType = JSON().with_variant(JSONB(), "postgresql")


class UTCDateTime(TypeDecorator):
    """항상 UTC 로 저장하고 tz-aware 로 돌려준다 (SQLite 는 tz 정보를 잃기 때문)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("naive datetime 은 저장할 수 없습니다 (timezone-aware 필요)")
        value = value.astimezone(UTC)
        return value.replace(tzinfo=None) if dialect.name == "sqlite" else value

    def process_result_value(self, value: datetime | None, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


def utcnow() -> datetime:
    return datetime.now(UTC)
