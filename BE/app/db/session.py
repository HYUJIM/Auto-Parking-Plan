"""DB 엔진·세션·스키마 생성. SQLite(로컬/테스트)와 PostgreSQL(운영) 공통."""
from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import Engine, create_engine, event, text
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.models import Base

DISPLAYABLE_VIEW_SQL = """
SELECT e.*
FROM explanation e
JOIN verification_report vr ON vr.id = e.passed_report_id
JOIN fact_ledger fl ON fl.id = e.ledger_id
WHERE vr.verdict = 'PASS'
  AND fl.review_status = 'approved'
  AND vr.ledger_hash = fl.ledger_hash
"""

# PostgreSQL 전용: schema.sql 의 active_rules_at(date) 와 같은 함수 (엔진은 이식 가능한 파이썬 구현을 사용)
PG_ACTIVE_RULES_FN = """
CREATE OR REPLACE FUNCTION active_rules_at(p_date date)
RETURNS SETOF rule LANGUAGE sql STABLE AS $$
  SELECT r.* FROM rule r
  WHERE r.review_status = 'approved'
    AND (r.effective_from IS NOT NULL AND r.effective_from <= p_date)
    AND (r.effective_to   IS NULL     OR  r.effective_to   >= p_date);
$$;
"""


def make_engine(url: str, *, echo: bool = False) -> Engine:
    if url.startswith("sqlite"):
        kwargs: dict = {"connect_args": {"check_same_thread": False, "timeout": 30}}
        if url in ("sqlite://", "sqlite:///:memory:"):
            kwargs["poolclass"] = StaticPool  # 테스트: 스레드 간 같은 메모리 DB 공유
        else:
            db_path = Path(url.removeprefix("sqlite:///"))
            db_path.parent.mkdir(parents=True, exist_ok=True)
        engine = create_engine(url, echo=echo, **kwargs)

        @event.listens_for(engine, "connect")
        def _sqlite_pragmas(dbapi_conn, _):  # noqa: ANN001
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA foreign_keys=ON")
            if url not in ("sqlite://", "sqlite:///:memory:"):
                cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA busy_timeout=30000")
            cur.close()

        return engine
    return create_engine(url, echo=echo, pool_pre_ping=True, pool_size=10, max_overflow=10)


SCHEMA_LOCK_ID = 7262600  # 임의 상수 (pg_advisory_xact_lock 키)


def create_schema(engine: Engine) -> None:
    """스키마 생성. API·워커가 동시에 떠도 안전하도록 PostgreSQL 에서는 트랜잭션 advisory lock 으로 직렬화한다."""
    with engine.begin() as conn:
        if engine.dialect.name == "postgresql":
            conn.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": SCHEMA_LOCK_ID})
            Base.metadata.create_all(conn)
            conn.execute(text(f"CREATE OR REPLACE VIEW displayable_explanation AS {DISPLAYABLE_VIEW_SQL}"))
            conn.execute(text(PG_ACTIVE_RULES_FN))
        else:
            Base.metadata.create_all(conn)
            conn.execute(text(f"CREATE VIEW IF NOT EXISTS displayable_explanation AS {DISPLAYABLE_VIEW_SQL}"))


def drop_schema(engine: Engine) -> None:
    with engine.begin() as conn:
        conn.execute(text("DROP VIEW IF EXISTS displayable_explanation"))
    Base.metadata.drop_all(engine)


class Database:
    """엔진 + 세션 팩토리 묶음. 앱/워커/CLI/테스트가 하나씩 만든다."""

    def __init__(self, url: str, *, echo: bool = False):
        self.url = url
        self.engine = make_engine(url, echo=echo)
        self.session_factory = sessionmaker(self.engine, expire_on_commit=False)

    def create_all(self) -> None:
        create_schema(self.engine)

    @contextmanager
    def session(self) -> Iterator[Session]:
        s = self.session_factory()
        try:
            yield s
            s.commit()
        except Exception:
            s.rollback()
            raise
        finally:
            s.close()
