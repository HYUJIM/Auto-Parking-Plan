"""SQLAlchemy 모델(BE/app/db/models.py)에서 Flyway V1 DDL 을 PostgreSQL·H2 두 벌로 만든다 (BE-spring/db).

V1 을 처음 만들 때 쓴 도구다. 이미 적용된 마이그레이션은 고치지 않는다 — 스키마를 바꿀 때는 V2__… 를 손으로 추가한다.
실행 (파이썬판 가상환경 필요):
    cd BE && .venv/bin/python ../BE-spring/db/tools/gen_ddl.py ../BE-spring/db/src/main/resources/db/migration
"""
import re, sys
from pathlib import Path
from sqlalchemy import CheckConstraint, ForeignKeyConstraint, UniqueConstraint, PrimaryKeyConstraint
from app.db.models import Base
from app.db.session import DISPLAYABLE_VIEW_SQL, PG_ACTIVE_RULES_FN

def coltype(col, vendor):
    t = col.type
    name = type(t).__name__
    s = str(t.compile(dialect=__import__('sqlalchemy.dialects.postgresql', fromlist=['dialect']).dialect()))
    if s == "JSONB" or name in ("JSON",) or "JSON" in s:
        return "JSONB" if vendor == "postgresql" else "JSON"
    if s == "TEXT":
        return "TEXT" if vendor == "postgresql" else "CHARACTER VARYING"
    if s == "FLOAT":
        return "DOUBLE PRECISION"
    if s.startswith("TIMESTAMP"):
        return "TIMESTAMP WITH TIME ZONE"
    return s

def gen(vendor):
    out = ["-- APP 법규 서버 스키마 V1 (" + vendor + ")",
           "-- 파이썬판 BE/app/db/models.py 와 같은 테이블·컬럼·제약. 생성: gen_ddl.py (SQLAlchemy 모델에서 자동 생성 후 검토)",
           "-- 차이: 제약 이름을 ck_/uq_/fk_<테이블>_<컬럼> 로 통일 (H2 는 제약 이름이 스키마 전체에서 유일해야 한다)", ""]
    deferred = []
    for t in Base.metadata.sorted_tables:
        lines = []
        for c in t.columns:
            lines.append(f"    {c.name} {coltype(c, vendor)}{'' if c.nullable else ' NOT NULL'}")
        pk = t.primary_key
        lines.append(f"    CONSTRAINT pk_{t.name} PRIMARY KEY ({', '.join(c.name for c in pk.columns)})")
        for con in sorted(t.constraints, key=lambda x: (type(x).__name__, str(getattr(x, 'name', '') or ''))):
            if isinstance(con, UniqueConstraint):
                cols = [c.name for c in con.columns]
                name = con.name if con.name and not str(con.name).startswith("_") else f"uq_{t.name}_{'_'.join(cols)}"
                lines.append(f"    CONSTRAINT {name} UNIQUE ({', '.join(cols)})")
            elif isinstance(con, CheckConstraint):
                from sqlalchemy.dialects import postgresql as _pg
                sql = str(con.sqltext.compile(dialect=_pg.dialect(), compile_kwargs={"literal_binds": True}))
                sql = re.sub(r"\b" + t.name + r"\.", "", sql)
                name = str(con.name)
                if not name.startswith("ck_") and name != "explanation_display_requires_pass":
                    col = re.match(r"\s*(\w+)\s+IN", sql).group(1)
                    name = f"ck_{t.name}_{col}"
                if name == "explanation_display_requires_pass":
                    name = "ck_explanation_display_requires_pass"
                lines.append(f"    CONSTRAINT {name} CHECK ({sql})")
        for fk in sorted(t.foreign_key_constraints, key=lambda f: f.column_keys):
            col = fk.column_keys[0]
            ref = list(fk.elements)[0].column
            ondel = f" ON DELETE {fk.ondelete}" if fk.ondelete else ""
            stmt = f"CONSTRAINT fk_{t.name}_{col} FOREIGN KEY ({col}) REFERENCES {ref.table.name} ({ref.name}){ondel}"
            if fk.use_alter:
                deferred.append(f"ALTER TABLE {t.name} ADD {stmt};")
            else:
                lines.append("    " + stmt)
        out.append(f"CREATE TABLE {t.name} (\n" + ",\n".join(lines) + "\n);")
        for ix in sorted(t.indexes, key=lambda i: i.name):
            out.append(f"CREATE INDEX {ix.name} ON {t.name} ({', '.join(c.name for c in ix.columns)});")
        out.append("")
    out += deferred + [""]
    view = " ".join(DISPLAYABLE_VIEW_SQL.split())
    out.append("-- 화면에 표시할 수 있는 설명: 통과 보고서 + 승인된 원장 + 해시 일치 (schema.sql 과 같은 정의)")
    out.append(f"CREATE VIEW displayable_explanation AS {view};")
    if vendor == "postgresql":
        out += ["", "-- schema.sql 의 active_rules_at(date) (엔진은 이식 가능한 자바 구현 RuleQueries.activeRulesAt 을 쓴다)",
                PG_ACTIVE_RULES_FN.strip()]
    return "\n".join(out) + "\n"

root = Path(sys.argv[1])
for v in ("postgresql", "h2"):
    p = root / v / "V1__init.sql"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(gen(v), encoding="utf-8", newline="\n")
    print(p)
