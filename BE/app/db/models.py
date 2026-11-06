"""
APP 법규 서버 DB 모델 — 설계서 5장 / schema.sql(22개 테이블 + 함수 1 + 뷰 1)의 이식 가능한 구현.

schema.sql 대비 차이 (모두 SQLite·PostgreSQL 양쪽에서 같은 의미로 동작시키기 위함)
  * ENUM 타입 → VARCHAR + CHECK 제약 (값 목록은 동일)
  * uuid[] / char(19)[] → JSON 배열
  * PostGIS geometry → GeoJSON(JSON) 컬럼, 공간 연산은 shapely 로 수행
  * fact_ledger 승인 제약: completeness_gaps = '[]' 대신 gap_count = 0 (JSON 비교의 이식성 문제)
  * 추가 컬럼(설계 보강): source_document.key/aliases, document_version.is_example,
    rule.confidence, rule_evidence.role, review_request.audience/progress/result_json/error/finished_at,
    parcel.road_side/is_example, fact_ledger.kind/ledger_json/status_value, review_request.status 'queued'
  * active_rules_at(date) 는 app.db.repo.active_rules_at() (PostgreSQL 에서는 같은 이름의 SQL 함수도 생성)
  * displayable_explanation 뷰는 schema.sql 과 같은 정의로 생성 (app.db.session.create_views)
"""
from __future__ import annotations

import uuid
from datetime import date, datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    Enum,
    Float,
    ForeignKey,
    Index,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from app.db.types import JSONType, UTCDateTime, utcnow

# ── A. 열거형 (schema.sql 과 같은 값) ─────────────────────────────
LAW_LAYERS = ("L1_NATIONAL", "L2_LAND_USE", "L3_LOCAL", "L4_SPECIAL_ZONE")
SOURCE_TYPES = ("law", "decree", "rule", "ordinance", "local_rule", "notice", "plan")
PROVISION_KINDS = ("article", "paragraph", "item", "subitem", "annex", "annex_row", "addendum")
RULE_CATEGORIES = (
    "ZONING_USE", "BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "PARKING_REQUIREMENT", "PARKING_STALL_SIZE",
    "DRIVE_AISLE_WIDTH", "ROAD_ACCESS", "SETBACK", "HEIGHT_SUNLIGHT", "SPECIAL_ZONE",
)
VALUE_TYPES = ("max", "min", "range", "exact", "enum_allowed", "enum_conditional", "enum_prohibited", "formula", "text_only")
VERIFY_STATES = ("not_checked", "confirmed", "needs_review")
RULE_REVIEW_STATUSES = ("draft", "auto_checked", "approved", "rejected", "superseded")
REVIEW_STATUS_LABELS = ("REVIEWABLE", "CONDITIONAL", "MANUAL_CHECK")


def _enum(name: str, values: tuple[str, ...]) -> Enum:
    return Enum(*values, name=name, native_enum=False, create_constraint=True, length=32, validate_strings=True)


LawLayer = _enum("law_layer", LAW_LAYERS)
SourceTypeE = _enum("source_type", SOURCE_TYPES)
ProvisionKind = _enum("provision_kind", PROVISION_KINDS)
RuleCategory = _enum("rule_category", RULE_CATEGORIES)
ValueType = _enum("value_type", VALUE_TYPES)
VerifyState = _enum("verify_state", VERIFY_STATES)
RuleReviewStatus = _enum("rule_review_status", RULE_REVIEW_STATUSES)
ReviewStatusLabel = _enum("review_status_label", REVIEW_STATUS_LABELS)


def _uuid_pk() -> Mapped[uuid.UUID]:
    return mapped_column(Uuid, primary_key=True, default=uuid.uuid4)


class Base(DeclarativeBase):
    pass


# ── 관할 ───────────────────────────────────────────────────────
class Jurisdiction(Base):
    __tablename__ = "jurisdiction"
    code: Mapped[str] = mapped_column(String(10), primary_key=True)  # '00' 전국, '27' 대구, '27260' 수성구
    name: Mapped[str] = mapped_column(Text)
    level: Mapped[int] = mapped_column(SmallInteger)
    parent_code: Mapped[str | None] = mapped_column(String(10), ForeignKey("jurisdiction.code"))
    is_supported: Mapped[bool] = mapped_column(Boolean, default=False)
    __table_args__ = (CheckConstraint("level IN (0,1,2)", name="ck_jurisdiction_level"),)


# ── B. 원문 계층 ────────────────────────────────────────────────
class SourceDocument(Base):
    __tablename__ = "source_document"
    id: Mapped[uuid.UUID] = _uuid_pk()
    key: Mapped[str] = mapped_column(String(80), unique=True)  # 'kr.decree.nkpa' (보강)
    layer: Mapped[str] = mapped_column(LawLayer)
    source_type: Mapped[str] = mapped_column(SourceTypeE)
    official_title: Mapped[str] = mapped_column(Text)
    short_title: Mapped[str | None] = mapped_column(Text)
    jurisdiction_code: Mapped[str] = mapped_column(String(10), ForeignKey("jurisdiction.code"))
    external_system: Mapped[str] = mapped_column(Text)
    external_id: Mapped[str | None] = mapped_column(Text)
    parent_document_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("source_document.id"))
    homepage_url: Mapped[str | None] = mapped_column(Text)
    aliases: Mapped[dict] = mapped_column(JSONType, default=dict)  # 조문 안의 '법','영' 약칭 → 문서 key (보강)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    versions: Mapped[list[DocumentVersion]] = relationship(back_populates="document", cascade="all, delete-orphan")
    __table_args__ = (
        UniqueConstraint("external_system", "external_id", name="uq_source_document_external"),
        Index("ix_source_document_layer_jur", "layer", "jurisdiction_code"),
    )


class DocumentVersion(Base):
    __tablename__ = "document_version"
    id: Mapped[uuid.UUID] = _uuid_pk()
    document_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("source_document.id", ondelete="CASCADE"))
    promulgation_number: Mapped[str | None] = mapped_column(Text)
    promulgation_date: Mapped[date | None] = mapped_column(Date)
    effective_from: Mapped[date | None] = mapped_column(Date)  # 미확인 시 NULL (다운로드일로 대체 금지)
    effective_to: Mapped[date | None] = mapped_column(Date)
    external_version_id: Mapped[str | None] = mapped_column(Text)
    raw_object_key: Mapped[str] = mapped_column(Text)
    raw_format: Mapped[str] = mapped_column(String(8))
    raw_sha256: Mapped[str] = mapped_column(String(64))
    source_url: Mapped[str] = mapped_column(Text)
    fetched_at: Mapped[datetime] = mapped_column(UTCDateTime)
    # 같은 열거형을 쓰는 열이 4개 → PostgreSQL 은 테이블 안 CHECK 이름이 겹치면 안 되므로 열마다 이름을 따로 준다
    v_source_access: Mapped[str] = mapped_column(_enum("verify_state_source_access", VERIFY_STATES), default="not_checked")
    v_version_header: Mapped[str] = mapped_column(_enum("verify_state_version_header", VERIFY_STATES), default="not_checked")
    v_text_match: Mapped[str] = mapped_column(_enum("verify_state_text_match", VERIFY_STATES), default="not_checked")
    v_current_version: Mapped[str] = mapped_column(_enum("verify_state_current_version", VERIFY_STATES), default="not_checked")
    current_checked_on: Mapped[date | None] = mapped_column(Date)
    note: Mapped[str | None] = mapped_column(Text)
    is_example: Mapped[bool] = mapped_column(Boolean, default=False)  # 구조 시험용 가짜 문구 (보강)
    document: Mapped[SourceDocument] = relationship(back_populates="versions")
    __table_args__ = (
        UniqueConstraint("document_id", "raw_sha256", name="uq_document_version_hash"),
        CheckConstraint("raw_format IN ('xml','html','pdf','json')", name="ck_document_version_raw_format"),
        Index("ix_document_version_doc_eff", "document_id", "effective_from"),
    )


class Provision(Base):
    __tablename__ = "provision"
    id: Mapped[uuid.UUID] = _uuid_pk()
    version_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("document_version.id", ondelete="CASCADE"))
    parent_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("provision.id", ondelete="CASCADE"))
    kind: Mapped[str] = mapped_column(ProvisionKind)
    label: Mapped[str] = mapped_column(Text)
    path: Mapped[str] = mapped_column(Text)
    heading: Mapped[str | None] = mapped_column(Text)
    text: Mapped[str] = mapped_column(Text)
    table_json: Mapped[object | None] = mapped_column(JSONType)
    sort_order: Mapped[int] = mapped_column(Integer)
    version: Mapped[DocumentVersion] = relationship()
    __table_args__ = (
        UniqueConstraint("version_id", "path", name="uq_provision_version_path"),
        Index("ix_provision_version_kind", "version_id", "kind"),
    )


# ── C. 계층 연결 ────────────────────────────────────────────────
class Delegation(Base):
    __tablename__ = "delegation"
    id: Mapped[uuid.UUID] = _uuid_pk()
    from_provision_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("provision.id", ondelete="CASCADE"))
    to_provision_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("provision.id", ondelete="CASCADE"))
    relation: Mapped[str] = mapped_column(String(24))
    evidence_text: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[str] = mapped_column(String(8))
    review_status: Mapped[str] = mapped_column(RuleReviewStatus, default="draft")
    __table_args__ = (
        UniqueConstraint("from_provision_id", "to_provision_id", "relation", name="uq_delegation_edge"),
        CheckConstraint("relation IN ('delegates','implements','references','overrides_in_zone')", name="ck_delegation_relation"),
        CheckConstraint("created_by IN ('parser','ai','human')", name="ck_delegation_created_by"),
    )


# ── D. 규칙 ────────────────────────────────────────────────────
class Rule(Base):
    __tablename__ = "rule"
    id: Mapped[uuid.UUID] = _uuid_pk()
    rule_key: Mapped[str] = mapped_column(Text)
    category: Mapped[str] = mapped_column(RuleCategory)
    layer: Mapped[str] = mapped_column(LawLayer)
    jurisdiction_code: Mapped[str] = mapped_column(String(10), ForeignKey("jurisdiction.code"))
    version_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("document_version.id"))
    effective_from: Mapped[date | None] = mapped_column(Date)
    effective_to: Mapped[date | None] = mapped_column(Date)
    title: Mapped[str] = mapped_column(Text)
    automation_level: Mapped[str] = mapped_column(String(16))
    review_status: Mapped[str] = mapped_column(RuleReviewStatus, default="draft")
    extraction_run_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("extraction_run.id"))
    supersedes_rule_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("rule.id"))
    confidence: Mapped[str | None] = mapped_column(String(8))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    approved_by: Mapped[str | None] = mapped_column(Text)
    approved_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    conditions: Mapped[list[RuleCondition]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    values: Mapped[list[RuleValue]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    evidence: Mapped[list[RuleEvidence]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    version: Mapped[DocumentVersion] = relationship()
    __table_args__ = (
        CheckConstraint("automation_level IN ('auto','reference_only','manual_check')", name="ck_rule_automation"),
        Index("ix_rule_cat_jur_status", "category", "jurisdiction_code", "review_status"),
        Index("ix_rule_key_eff", "rule_key", "effective_from"),
    )


RULE_CONDITION_FIELDS = ("zoning", "district", "zone", "land_category", "building_use", "gross_floor_area",
                         "households", "site_area", "road_width", "special_zone_type")


class RuleCondition(Base):
    __tablename__ = "rule_condition"
    id: Mapped[uuid.UUID] = _uuid_pk()
    rule_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("rule.id", ondelete="CASCADE"))
    field: Mapped[str] = mapped_column(String(24))
    operator: Mapped[str] = mapped_column(String(8))
    value_json: Mapped[object] = mapped_column(JSONType)
    unit: Mapped[str | None] = mapped_column(Text)
    __table_args__ = (
        CheckConstraint("field IN ('zoning','district','zone','land_category','building_use','gross_floor_area',"
                        "'households','site_area','road_width','special_zone_type')", name="ck_rule_condition_field"),
        CheckConstraint("operator IN ('eq','in','gte','lte','between','exists')", name="ck_rule_condition_operator"),
        Index("ix_rule_condition_field", "field"),
    )


class RuleValue(Base):
    __tablename__ = "rule_value"
    id: Mapped[uuid.UUID] = _uuid_pk()
    rule_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("rule.id", ondelete="CASCADE"))
    value_key: Mapped[str] = mapped_column(Text)
    value_type: Mapped[str] = mapped_column(ValueType)
    num_min: Mapped[float | None] = mapped_column(Float)
    num_max: Mapped[float | None] = mapped_column(Float)
    num_exact: Mapped[float | None] = mapped_column(Float)
    enum_value: Mapped[str | None] = mapped_column(Text)
    formula: Mapped[str | None] = mapped_column(Text)
    unit: Mapped[str | None] = mapped_column(Text)
    condition_note: Mapped[str | None] = mapped_column(Text)
    __table_args__ = (UniqueConstraint("rule_id", "value_key", name="uq_rule_value_key"),)


class RuleEvidence(Base):
    __tablename__ = "rule_evidence"
    id: Mapped[uuid.UUID] = _uuid_pk()
    rule_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("rule.id", ondelete="CASCADE"))
    rule_value_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("rule_value.id", ondelete="CASCADE"))
    provision_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("provision.id"))
    char_start: Mapped[int] = mapped_column(Integer)
    char_end: Mapped[int] = mapped_column(Integer)
    quoted_text: Mapped[str] = mapped_column(Text)  # provision.text[char_start:char_end] 와 정확히 일치
    role: Mapped[str] = mapped_column(String(12), default="value")  # value | exception (보강)
    provision: Mapped[Provision] = relationship(lazy="joined")
    __table_args__ = (
        CheckConstraint("char_end > char_start", name="ck_rule_evidence_span"),
        CheckConstraint("role IN ('value','exception')", name="ck_rule_evidence_role"),
    )


# ── E. AI 추출 이력과 검수 ──────────────────────────────────────
class ExtractionRun(Base):
    __tablename__ = "extraction_run"
    id: Mapped[uuid.UUID] = _uuid_pk()
    version_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("document_version.id"))  # explanation 은 NULL 허용
    provision_ids: Mapped[list] = mapped_column(JSONType, default=list)
    task: Mapped[str] = mapped_column(String(32))
    model: Mapped[str] = mapped_column(Text)
    prompt_version: Mapped[str] = mapped_column(Text)
    schema_version: Mapped[str] = mapped_column(Text)
    input_sha256: Mapped[str] = mapped_column(String(64))
    output_json: Mapped[object | None] = mapped_column(JSONType)
    validation: Mapped[object | None] = mapped_column(JSONType)
    status: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    __table_args__ = (
        CheckConstraint("task IN ('rule_extraction','delegation_extraction','explanation','judge')", name="ck_extraction_run_task"),
        CheckConstraint("status IN ('ok','schema_error','evidence_error','range_error','failed')", name="ck_extraction_run_status"),
    )


class ReviewLog(Base):
    __tablename__ = "review_log"
    id: Mapped[uuid.UUID] = _uuid_pk()
    target_type: Mapped[str] = mapped_column(String(24))
    target_id: Mapped[uuid.UUID] = mapped_column(Uuid)
    action: Mapped[str] = mapped_column(String(24))
    reviewer: Mapped[str] = mapped_column(Text)
    diff_json: Mapped[object | None] = mapped_column(JSONType)
    comment: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    __table_args__ = (
        CheckConstraint("target_type IN ('rule','delegation','explanation','document_version','fact_ledger')", name="ck_review_log_target"),
        CheckConstraint("action IN ('approve','reject','edit','comment','request_reextract')", name="ck_review_log_action"),
    )


# ── F. 필지 (외부 API 캐시) ─────────────────────────────────────
class Parcel(Base):
    __tablename__ = "parcel"
    pnu: Mapped[str] = mapped_column(String(19), primary_key=True)
    jurisdiction_code: Mapped[str] = mapped_column(String(10), ForeignKey("jurisdiction.code"))
    road_address: Mapped[str | None] = mapped_column(Text)
    jibun_address: Mapped[str] = mapped_column(Text)
    land_area_m2: Mapped[float | None] = mapped_column(Float)  # NULL = 미확인
    land_category: Mapped[str | None] = mapped_column(Text)
    official_land_price: Mapped[int | None] = mapped_column(Integer)
    road_side: Mapped[str | None] = mapped_column(Text)  # 도로접면 (보강)
    geom: Mapped[dict | None] = mapped_column(JSONType)  # GeoJSON MultiPolygon EPSG:4326
    centroid: Mapped[list | None] = mapped_column(JSONType)  # [lng, lat]
    source_meta: Mapped[dict] = mapped_column(JSONType)  # 항목별 {source, fetched_at, ok, error}
    designations_ok: Mapped[bool] = mapped_column(Boolean, default=False)  # 토지이용계획 조회 성공 여부 (False → designations=null)
    is_example: Mapped[bool] = mapped_column(Boolean, default=False)
    fetched_at: Mapped[datetime] = mapped_column(UTCDateTime)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    designations: Mapped[list[ParcelDesignation]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    buildings: Mapped[list[ParcelBuilding]] = relationship(cascade="all, delete-orphan", lazy="selectin")


class ParcelDesignation(Base):
    __tablename__ = "parcel_designation"
    id: Mapped[uuid.UUID] = _uuid_pk()
    pnu: Mapped[str] = mapped_column(String(19), ForeignKey("parcel.pnu", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(10))
    name: Mapped[str] = mapped_column(Text, index=True)
    code: Mapped[str | None] = mapped_column(Text)
    conflict: Mapped[str | None] = mapped_column(String(24))
    basis_law: Mapped[str | None] = mapped_column(Text)
    fetched_at: Mapped[datetime] = mapped_column(UTCDateTime)
    __table_args__ = (
        CheckConstraint("kind IN ('zoning','district','zone','other')", name="ck_parcel_designation_kind"),
        CheckConstraint("conflict IN ('included','partially_included','adjacent','unknown')", name="ck_parcel_designation_conflict"),
    )


class ZoneOverlay(Base):
    __tablename__ = "zone_overlay"
    id: Mapped[uuid.UUID] = _uuid_pk()
    document_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("source_document.id"))  # layer = L4 고시
    zone_type: Mapped[str] = mapped_column(Text)
    name: Mapped[str] = mapped_column(Text)
    geom: Mapped[dict | None] = mapped_column(JSONType)  # 도면 미확보 시 NULL (PNU 목록으로 대체)
    pnu_list: Mapped[list] = mapped_column(JSONType, default=list)
    effective_from: Mapped[date | None] = mapped_column(Date)
    effective_to: Mapped[date | None] = mapped_column(Date)
    document: Mapped[SourceDocument] = relationship()


class ParcelBuilding(Base):
    __tablename__ = "parcel_building"
    id: Mapped[uuid.UUID] = _uuid_pk()
    pnu: Mapped[str] = mapped_column(String(19), ForeignKey("parcel.pnu", ondelete="CASCADE"))
    main_use: Mapped[str | None] = mapped_column(Text)
    gross_floor_area: Mapped[float | None] = mapped_column(Float)
    building_area: Mapped[float | None] = mapped_column(Float)
    approval_date: Mapped[date | None] = mapped_column(Date)
    raw: Mapped[object | None] = mapped_column(JSONType)
    fetched_at: Mapped[datetime] = mapped_column(UTCDateTime)


# ── G. 검토 결과와 쉬운 설명 ────────────────────────────────────
class ReviewRequest(Base):
    __tablename__ = "review_request"
    id: Mapped[uuid.UUID] = _uuid_pk()
    pnu: Mapped[str] = mapped_column(String(19), ForeignKey("parcel.pnu"))
    as_of_date: Mapped[date] = mapped_column(Date)
    audience: Mapped[str] = mapped_column(String(10), default="owner")
    inputs: Mapped[dict] = mapped_column(JSONType, default=dict)
    engine_version: Mapped[str] = mapped_column(Text)
    ruleset_hash: Mapped[str | None] = mapped_column(String(64))  # 실행 시작 시 계산
    status: Mapped[str] = mapped_column(String(10))
    progress: Mapped[list] = mapped_column(JSONType, default=list)
    result_json: Mapped[dict | None] = mapped_column(JSONType)
    error: Mapped[dict | None] = mapped_column(JSONType)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    items: Mapped[list[ReviewItem]] = relationship(cascade="all, delete-orphan", order_by="ReviewItem.sort_order")
    __table_args__ = (
        CheckConstraint("status IN ('queued','running','done','partial','failed')", name="ck_review_request_status"),
        CheckConstraint("audience IN ('owner','architect')", name="ck_review_request_audience"),
    )


class ReviewItem(Base):
    __tablename__ = "review_item"
    id: Mapped[uuid.UUID] = _uuid_pk()
    request_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("review_request.id", ondelete="CASCADE"))
    category: Mapped[str] = mapped_column(RuleCategory)
    status: Mapped[str] = mapped_column(ReviewStatusLabel)
    applied_rule_ids: Mapped[list] = mapped_column(JSONType, default=list)  # L1→L4 순서
    result_json: Mapped[dict] = mapped_column(JSONType)
    reason_code: Mapped[str | None] = mapped_column(Text)
    sort_order: Mapped[int] = mapped_column(Integer)


class Explanation(Base):
    __tablename__ = "explanation"
    id: Mapped[uuid.UUID] = _uuid_pk()
    cache_key: Mapped[str] = mapped_column(String(64), unique=True)
    category: Mapped[str] = mapped_column(RuleCategory)
    audience: Mapped[str] = mapped_column(String(10))
    explanation_json: Mapped[dict | None] = mapped_column(JSONType)  # basis_only 이면 NULL
    extraction_run_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("extraction_run.id"))
    review_status: Mapped[str] = mapped_column(RuleReviewStatus, default="auto_checked")
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    ledger_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("fact_ledger.id"))
    display_source: Mapped[str] = mapped_column(String(20), default="basis_only")
    passed_report_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("verification_report.id", use_alter=True, name="fk_explanation_passed_report"))
    __table_args__ = (
        CheckConstraint("audience IN ('owner','architect')", name="ck_explanation_audience"),
        CheckConstraint("display_source IN ('ai_verified','template_fallback','basis_only')", name="ck_explanation_display_source"),
        # 통과 보고서 없이는 화면용 설명으로 표시할 수 없다 (schema.sql 과 동일)
        CheckConstraint("display_source = 'basis_only' OR passed_report_id IS NOT NULL", name="explanation_display_requires_pass"),
    )


# ── H. 사후 검증 ───────────────────────────────────────────────
class FactLedger(Base):
    __tablename__ = "fact_ledger"
    id: Mapped[uuid.UUID] = _uuid_pk()
    kind: Mapped[str] = mapped_column(String(10), default="rule")  # rule(규칙별, 사람 승인) | composite(검토 항목용 합성)
    rule_ids: Mapped[list] = mapped_column(JSONType)
    provision_ids: Mapped[list] = mapped_column(JSONType)
    review_status: Mapped[str] = mapped_column(String(12), default="draft")
    ledger_hash: Mapped[str] = mapped_column(String(64))
    ledger_json: Mapped[dict] = mapped_column(JSONType)  # fact_ledger@1.0 전체 (검증기 입력 그대로)
    completeness_gaps: Mapped[list] = mapped_column(JSONType, default=list)
    gap_count: Mapped[int] = mapped_column(Integer, default=0)
    status_value: Mapped[str | None] = mapped_column(String(16))  # composite 의 STATUS 사실 값
    approved_by: Mapped[str | None] = mapped_column(Text)
    approved_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    facts: Mapped[list[LedgerFact]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    dismissals: Mapped[list[LedgerDismissal]] = relationship(cascade="all, delete-orphan", lazy="selectin")
    __table_args__ = (
        CheckConstraint("review_status IN ('draft','approved','superseded')", name="ck_fact_ledger_status"),
        CheckConstraint("kind IN ('rule','composite')", name="ck_fact_ledger_kind"),
        # 처리되지 않은 원문 후보가 남아 있으면 승인 불가
        CheckConstraint("review_status <> 'approved' OR gap_count = 0", name="ck_fact_ledger_complete_before_approve"),
        Index("ix_fact_ledger_hash", "ledger_hash"),
    )


class LedgerFact(Base):
    __tablename__ = "ledger_fact"
    id: Mapped[uuid.UUID] = _uuid_pk()
    ledger_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("fact_ledger.id", ondelete="CASCADE"))
    fact_key: Mapped[str] = mapped_column(String(40))
    kind: Mapped[str] = mapped_column(String(12))
    origin: Mapped[str] = mapped_column(String(10))
    provision_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("provision.id"))
    quote: Mapped[str] = mapped_column(Text)
    canonical: Mapped[dict | None] = mapped_column(JSONType)
    modality: Mapped[str | None] = mapped_column(String(10))
    name: Mapped[str | None] = mapped_column(Text)
    status_value: Mapped[str | None] = mapped_column(String(16))
    key_terms: Mapped[list] = mapped_column(JSONType, default=list)
    must_mention: Mapped[bool] = mapped_column(Boolean)
    candidate_ids: Mapped[list] = mapped_column(JSONType, default=list)
    __table_args__ = (
        UniqueConstraint("ledger_id", "fact_key", name="uq_ledger_fact_key"),
        CheckConstraint("kind IN ('PERIOD','QUANTITY','DATE','REFERENCE','SUBJECT','CONDITION','EXCEPTION','MODALITY','STATUS')", name="ck_ledger_fact_kind"),
        CheckConstraint("origin IN ('provision','engine')", name="ck_ledger_fact_origin"),
        CheckConstraint("modality IS NULL OR modality IN ('ALLOW','PROHIBIT','OBLIGE','EXEMPT')", name="ck_ledger_fact_modality"),
        CheckConstraint("status_value IS NULL OR status_value IN ('REVIEWABLE','CONDITIONAL','MANUAL_CHECK')", name="ck_ledger_fact_status"),
    )


class LedgerDismissal(Base):
    __tablename__ = "ledger_dismissal"
    ledger_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("fact_ledger.id", ondelete="CASCADE"), primary_key=True)
    candidate_id: Mapped[str] = mapped_column(String(40), primary_key=True)
    reason: Mapped[str] = mapped_column(Text)
    reviewer: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    __table_args__ = (CheckConstraint("length(reason) >= 2", name="ck_ledger_dismissal_reason"),)


class VerificationReport(Base):
    __tablename__ = "verification_report"
    id: Mapped[uuid.UUID] = _uuid_pk()
    explanation_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("explanation.id", ondelete="CASCADE"))
    ledger_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("fact_ledger.id"))
    attempt_no: Mapped[int] = mapped_column(SmallInteger)
    candidate_source: Mapped[str] = mapped_column(String(20))
    verdict: Mapped[str] = mapped_column(String(4))
    error_codes: Mapped[list] = mapped_column(JSONType, default=list)
    report_json: Mapped[dict] = mapped_column(JSONType)
    verifier_version: Mapped[str] = mapped_column(Text)
    judge_model: Mapped[str | None] = mapped_column(Text)
    ledger_hash: Mapped[str] = mapped_column(String(64))
    explanation_hash: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    __table_args__ = (
        CheckConstraint("candidate_source IN ('ai','template_fallback')", name="ck_verification_report_source"),
        CheckConstraint("verdict IN ('PASS','FAIL')", name="ck_verification_report_verdict"),
        Index("ix_verification_report_verdict", "verdict", "created_at"),
    )


ALL_TABLES = [m.__tablename__ for m in (
    Jurisdiction, SourceDocument, DocumentVersion, Provision, Delegation, Rule, RuleCondition, RuleValue,
    RuleEvidence, ExtractionRun, ReviewLog, Parcel, ParcelDesignation, ZoneOverlay, ParcelBuilding,
    ReviewRequest, ReviewItem, Explanation, FactLedger, LedgerFact, LedgerDismissal, VerificationReport)]
