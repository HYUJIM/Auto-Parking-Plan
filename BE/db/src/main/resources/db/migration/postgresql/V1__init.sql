-- APP 법규 서버 스키마 V1 (postgresql)
-- 파이썬판 BE/app/db/models.py 와 같은 테이블·컬럼·제약. 생성: gen_ddl.py (SQLAlchemy 모델에서 자동 생성 후 검토)
-- 차이: 제약 이름을 ck_/uq_/fk_<테이블>_<컬럼> 로 통일 (H2 는 제약 이름이 스키마 전체에서 유일해야 한다)

CREATE TABLE fact_ledger (
    id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL,
    rule_ids JSONB NOT NULL,
    provision_ids JSONB NOT NULL,
    review_status VARCHAR(12) NOT NULL,
    ledger_hash VARCHAR(64) NOT NULL,
    ledger_json JSONB NOT NULL,
    completeness_gaps JSONB NOT NULL,
    gap_count INTEGER NOT NULL,
    status_value VARCHAR(16),
    approved_by TEXT,
    approved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fact_ledger PRIMARY KEY (id),
    CONSTRAINT ck_fact_ledger_complete_before_approve CHECK (review_status <> 'approved' OR gap_count = 0),
    CONSTRAINT ck_fact_ledger_kind CHECK (kind IN ('rule','composite')),
    CONSTRAINT ck_fact_ledger_status CHECK (review_status IN ('draft','approved','superseded'))
);
CREATE INDEX ix_fact_ledger_hash ON fact_ledger (ledger_hash);

CREATE TABLE jurisdiction (
    code VARCHAR(10) NOT NULL,
    name TEXT NOT NULL,
    level SMALLINT NOT NULL,
    parent_code VARCHAR(10),
    is_supported BOOLEAN NOT NULL,
    CONSTRAINT pk_jurisdiction PRIMARY KEY (code),
    CONSTRAINT ck_jurisdiction_level CHECK (level IN (0,1,2)),
    CONSTRAINT fk_jurisdiction_parent_code FOREIGN KEY (parent_code) REFERENCES jurisdiction (code)
);

CREATE TABLE review_log (
    id UUID NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    reviewer TEXT NOT NULL,
    diff_json JSONB,
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_review_log PRIMARY KEY (id),
    CONSTRAINT ck_review_log_action CHECK (action IN ('approve','reject','edit','comment','request_reextract')),
    CONSTRAINT ck_review_log_target CHECK (target_type IN ('rule','delegation','explanation','document_version','fact_ledger'))
);

CREATE TABLE ledger_dismissal (
    ledger_id UUID NOT NULL,
    candidate_id VARCHAR(40) NOT NULL,
    reason TEXT NOT NULL,
    reviewer TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ledger_dismissal PRIMARY KEY (ledger_id, candidate_id),
    CONSTRAINT ck_ledger_dismissal_reason CHECK (length(reason) >= 2),
    CONSTRAINT fk_ledger_dismissal_ledger_id FOREIGN KEY (ledger_id) REFERENCES fact_ledger (id) ON DELETE CASCADE
);

CREATE TABLE parcel (
    pnu VARCHAR(19) NOT NULL,
    jurisdiction_code VARCHAR(10) NOT NULL,
    road_address TEXT,
    jibun_address TEXT NOT NULL,
    land_area_m2 DOUBLE PRECISION,
    land_category TEXT,
    official_land_price INTEGER,
    road_side TEXT,
    geom JSONB,
    centroid JSONB,
    source_meta JSONB NOT NULL,
    designations_ok BOOLEAN NOT NULL,
    is_example BOOLEAN NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_parcel PRIMARY KEY (pnu),
    CONSTRAINT fk_parcel_jurisdiction_code FOREIGN KEY (jurisdiction_code) REFERENCES jurisdiction (code)
);

CREATE TABLE source_document (
    id UUID NOT NULL,
    key VARCHAR(80) NOT NULL,
    layer VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    official_title TEXT NOT NULL,
    short_title TEXT,
    jurisdiction_code VARCHAR(10) NOT NULL,
    external_system TEXT NOT NULL,
    external_id TEXT,
    parent_document_id UUID,
    homepage_url TEXT,
    aliases JSONB NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_source_document PRIMARY KEY (id),
    CONSTRAINT ck_source_document_layer CHECK (layer IN ('L1_NATIONAL', 'L2_LAND_USE', 'L3_LOCAL', 'L4_SPECIAL_ZONE')),
    CONSTRAINT ck_source_document_source_type CHECK (source_type IN ('law', 'decree', 'rule', 'ordinance', 'local_rule', 'notice', 'plan')),
    CONSTRAINT uq_source_document_key UNIQUE (key),
    CONSTRAINT uq_source_document_external UNIQUE (external_system, external_id),
    CONSTRAINT fk_source_document_jurisdiction_code FOREIGN KEY (jurisdiction_code) REFERENCES jurisdiction (code),
    CONSTRAINT fk_source_document_parent_document_id FOREIGN KEY (parent_document_id) REFERENCES source_document (id)
);
CREATE INDEX ix_source_document_layer_jur ON source_document (layer, jurisdiction_code);

CREATE TABLE document_version (
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    promulgation_number TEXT,
    promulgation_date DATE,
    effective_from DATE,
    effective_to DATE,
    external_version_id TEXT,
    raw_object_key TEXT NOT NULL,
    raw_format VARCHAR(8) NOT NULL,
    raw_sha256 VARCHAR(64) NOT NULL,
    source_url TEXT NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    v_source_access VARCHAR(32) NOT NULL,
    v_version_header VARCHAR(32) NOT NULL,
    v_text_match VARCHAR(32) NOT NULL,
    v_current_version VARCHAR(32) NOT NULL,
    current_checked_on DATE,
    note TEXT,
    is_example BOOLEAN NOT NULL,
    CONSTRAINT pk_document_version PRIMARY KEY (id),
    CONSTRAINT ck_document_version_raw_format CHECK (raw_format IN ('xml','html','pdf','json')),
    CONSTRAINT ck_document_version_v_current_version CHECK (v_current_version IN ('not_checked', 'confirmed', 'needs_review')),
    CONSTRAINT ck_document_version_v_source_access CHECK (v_source_access IN ('not_checked', 'confirmed', 'needs_review')),
    CONSTRAINT ck_document_version_v_text_match CHECK (v_text_match IN ('not_checked', 'confirmed', 'needs_review')),
    CONSTRAINT ck_document_version_v_version_header CHECK (v_version_header IN ('not_checked', 'confirmed', 'needs_review')),
    CONSTRAINT uq_document_version_hash UNIQUE (document_id, raw_sha256),
    CONSTRAINT fk_document_version_document_id FOREIGN KEY (document_id) REFERENCES source_document (id) ON DELETE CASCADE
);
CREATE INDEX ix_document_version_doc_eff ON document_version (document_id, effective_from);

CREATE TABLE parcel_building (
    id UUID NOT NULL,
    pnu VARCHAR(19) NOT NULL,
    main_use TEXT,
    gross_floor_area DOUBLE PRECISION,
    building_area DOUBLE PRECISION,
    approval_date DATE,
    raw JSONB,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_parcel_building PRIMARY KEY (id),
    CONSTRAINT fk_parcel_building_pnu FOREIGN KEY (pnu) REFERENCES parcel (pnu) ON DELETE CASCADE
);

CREATE TABLE parcel_designation (
    id UUID NOT NULL,
    pnu VARCHAR(19) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    name TEXT NOT NULL,
    code TEXT,
    conflict VARCHAR(24),
    basis_law TEXT,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_parcel_designation PRIMARY KEY (id),
    CONSTRAINT ck_parcel_designation_conflict CHECK (conflict IN ('included','partially_included','adjacent','unknown')),
    CONSTRAINT ck_parcel_designation_kind CHECK (kind IN ('zoning','district','zone','other')),
    CONSTRAINT fk_parcel_designation_pnu FOREIGN KEY (pnu) REFERENCES parcel (pnu) ON DELETE CASCADE
);
CREATE INDEX ix_parcel_designation_name ON parcel_designation (name);
CREATE INDEX ix_parcel_designation_pnu ON parcel_designation (pnu);

CREATE TABLE review_request (
    id UUID NOT NULL,
    pnu VARCHAR(19) NOT NULL,
    as_of_date DATE NOT NULL,
    audience VARCHAR(10) NOT NULL,
    inputs JSONB NOT NULL,
    engine_version TEXT NOT NULL,
    ruleset_hash VARCHAR(64),
    status VARCHAR(10) NOT NULL,
    progress JSONB NOT NULL,
    result_json JSONB,
    error JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_review_request PRIMARY KEY (id),
    CONSTRAINT ck_review_request_audience CHECK (audience IN ('owner','architect')),
    CONSTRAINT ck_review_request_status CHECK (status IN ('queued','running','done','partial','failed')),
    CONSTRAINT fk_review_request_pnu FOREIGN KEY (pnu) REFERENCES parcel (pnu)
);

CREATE TABLE zone_overlay (
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    zone_type TEXT NOT NULL,
    name TEXT NOT NULL,
    geom JSONB,
    pnu_list JSONB NOT NULL,
    effective_from DATE,
    effective_to DATE,
    CONSTRAINT pk_zone_overlay PRIMARY KEY (id),
    CONSTRAINT fk_zone_overlay_document_id FOREIGN KEY (document_id) REFERENCES source_document (id)
);

CREATE TABLE extraction_run (
    id UUID NOT NULL,
    version_id UUID,
    provision_ids JSONB NOT NULL,
    task VARCHAR(32) NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    schema_version TEXT NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    output_json JSONB,
    validation JSONB,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_extraction_run PRIMARY KEY (id),
    CONSTRAINT ck_extraction_run_status CHECK (status IN ('ok','schema_error','evidence_error','range_error','failed')),
    CONSTRAINT ck_extraction_run_task CHECK (task IN ('rule_extraction','delegation_extraction','explanation','judge')),
    CONSTRAINT fk_extraction_run_version_id FOREIGN KEY (version_id) REFERENCES document_version (id)
);

CREATE TABLE provision (
    id UUID NOT NULL,
    version_id UUID NOT NULL,
    parent_id UUID,
    kind VARCHAR(32) NOT NULL,
    label TEXT NOT NULL,
    path TEXT NOT NULL,
    heading TEXT,
    text TEXT NOT NULL,
    table_json JSONB,
    sort_order INTEGER NOT NULL,
    CONSTRAINT pk_provision PRIMARY KEY (id),
    CONSTRAINT ck_provision_kind CHECK (kind IN ('article', 'paragraph', 'item', 'subitem', 'annex', 'annex_row', 'addendum')),
    CONSTRAINT uq_provision_version_path UNIQUE (version_id, path),
    CONSTRAINT fk_provision_parent_id FOREIGN KEY (parent_id) REFERENCES provision (id) ON DELETE CASCADE,
    CONSTRAINT fk_provision_version_id FOREIGN KEY (version_id) REFERENCES document_version (id) ON DELETE CASCADE
);
CREATE INDEX ix_provision_version_kind ON provision (version_id, kind);

CREATE TABLE review_item (
    id UUID NOT NULL,
    request_id UUID NOT NULL,
    category VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    applied_rule_ids JSONB NOT NULL,
    result_json JSONB NOT NULL,
    reason_code TEXT,
    sort_order INTEGER NOT NULL,
    CONSTRAINT pk_review_item PRIMARY KEY (id),
    CONSTRAINT ck_review_item_status CHECK (status IN ('REVIEWABLE', 'CONDITIONAL', 'MANUAL_CHECK')),
    CONSTRAINT ck_review_item_category CHECK (category IN ('ZONING_USE', 'BUILDING_COVERAGE', 'FLOOR_AREA_RATIO', 'PARKING_REQUIREMENT', 'PARKING_STALL_SIZE', 'DRIVE_AISLE_WIDTH', 'ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE')),
    CONSTRAINT fk_review_item_request_id FOREIGN KEY (request_id) REFERENCES review_request (id) ON DELETE CASCADE
);

CREATE TABLE delegation (
    id UUID NOT NULL,
    from_provision_id UUID NOT NULL,
    to_provision_id UUID NOT NULL,
    relation VARCHAR(24) NOT NULL,
    evidence_text TEXT,
    created_by VARCHAR(8) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    CONSTRAINT pk_delegation PRIMARY KEY (id),
    CONSTRAINT ck_delegation_created_by CHECK (created_by IN ('parser','ai','human')),
    CONSTRAINT ck_delegation_relation CHECK (relation IN ('delegates','implements','references','overrides_in_zone')),
    CONSTRAINT ck_delegation_review_status CHECK (review_status IN ('draft', 'auto_checked', 'approved', 'rejected', 'superseded')),
    CONSTRAINT uq_delegation_edge UNIQUE (from_provision_id, to_provision_id, relation),
    CONSTRAINT fk_delegation_from_provision_id FOREIGN KEY (from_provision_id) REFERENCES provision (id) ON DELETE CASCADE,
    CONSTRAINT fk_delegation_to_provision_id FOREIGN KEY (to_provision_id) REFERENCES provision (id) ON DELETE CASCADE
);

CREATE TABLE explanation (
    id UUID NOT NULL,
    cache_key VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    audience VARCHAR(10) NOT NULL,
    explanation_json JSONB,
    extraction_run_id UUID,
    review_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ledger_id UUID,
    display_source VARCHAR(20) NOT NULL,
    passed_report_id UUID,
    CONSTRAINT pk_explanation PRIMARY KEY (id),
    CONSTRAINT ck_explanation_audience CHECK (audience IN ('owner','architect')),
    CONSTRAINT ck_explanation_display_source CHECK (display_source IN ('ai_verified','template_fallback','basis_only')),
    CONSTRAINT ck_explanation_display_requires_pass CHECK (display_source = 'basis_only' OR passed_report_id IS NOT NULL),
    CONSTRAINT ck_explanation_category CHECK (category IN ('ZONING_USE', 'BUILDING_COVERAGE', 'FLOOR_AREA_RATIO', 'PARKING_REQUIREMENT', 'PARKING_STALL_SIZE', 'DRIVE_AISLE_WIDTH', 'ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE')),
    CONSTRAINT ck_explanation_review_status CHECK (review_status IN ('draft', 'auto_checked', 'approved', 'rejected', 'superseded')),
    CONSTRAINT uq_explanation_cache_key UNIQUE (cache_key),
    CONSTRAINT fk_explanation_extraction_run_id FOREIGN KEY (extraction_run_id) REFERENCES extraction_run (id),
    CONSTRAINT fk_explanation_ledger_id FOREIGN KEY (ledger_id) REFERENCES fact_ledger (id)
);

CREATE TABLE ledger_fact (
    id UUID NOT NULL,
    ledger_id UUID NOT NULL,
    fact_key VARCHAR(40) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    origin VARCHAR(10) NOT NULL,
    provision_id UUID,
    quote TEXT NOT NULL,
    canonical JSONB,
    modality VARCHAR(10),
    name TEXT,
    status_value VARCHAR(16),
    key_terms JSONB NOT NULL,
    must_mention BOOLEAN NOT NULL,
    candidate_ids JSONB NOT NULL,
    CONSTRAINT pk_ledger_fact PRIMARY KEY (id),
    CONSTRAINT ck_ledger_fact_kind CHECK (kind IN ('PERIOD','QUANTITY','DATE','REFERENCE','SUBJECT','CONDITION','EXCEPTION','MODALITY','STATUS')),
    CONSTRAINT ck_ledger_fact_modality CHECK (modality IS NULL OR modality IN ('ALLOW','PROHIBIT','OBLIGE','EXEMPT')),
    CONSTRAINT ck_ledger_fact_origin CHECK (origin IN ('provision','engine')),
    CONSTRAINT ck_ledger_fact_status CHECK (status_value IS NULL OR status_value IN ('REVIEWABLE','CONDITIONAL','MANUAL_CHECK')),
    CONSTRAINT uq_ledger_fact_key UNIQUE (ledger_id, fact_key),
    CONSTRAINT fk_ledger_fact_ledger_id FOREIGN KEY (ledger_id) REFERENCES fact_ledger (id) ON DELETE CASCADE,
    CONSTRAINT fk_ledger_fact_provision_id FOREIGN KEY (provision_id) REFERENCES provision (id)
);

CREATE TABLE rule (
    id UUID NOT NULL,
    rule_key TEXT NOT NULL,
    category VARCHAR(32) NOT NULL,
    layer VARCHAR(32) NOT NULL,
    jurisdiction_code VARCHAR(10) NOT NULL,
    version_id UUID NOT NULL,
    effective_from DATE,
    effective_to DATE,
    title TEXT NOT NULL,
    automation_level VARCHAR(16) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    extraction_run_id UUID,
    supersedes_rule_id UUID,
    confidence VARCHAR(8),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by TEXT,
    approved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_rule PRIMARY KEY (id),
    CONSTRAINT ck_rule_automation CHECK (automation_level IN ('auto','reference_only','manual_check')),
    CONSTRAINT ck_rule_layer CHECK (layer IN ('L1_NATIONAL', 'L2_LAND_USE', 'L3_LOCAL', 'L4_SPECIAL_ZONE')),
    CONSTRAINT ck_rule_category CHECK (category IN ('ZONING_USE', 'BUILDING_COVERAGE', 'FLOOR_AREA_RATIO', 'PARKING_REQUIREMENT', 'PARKING_STALL_SIZE', 'DRIVE_AISLE_WIDTH', 'ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE')),
    CONSTRAINT ck_rule_review_status CHECK (review_status IN ('draft', 'auto_checked', 'approved', 'rejected', 'superseded')),
    CONSTRAINT fk_rule_extraction_run_id FOREIGN KEY (extraction_run_id) REFERENCES extraction_run (id),
    CONSTRAINT fk_rule_jurisdiction_code FOREIGN KEY (jurisdiction_code) REFERENCES jurisdiction (code),
    CONSTRAINT fk_rule_supersedes_rule_id FOREIGN KEY (supersedes_rule_id) REFERENCES rule (id),
    CONSTRAINT fk_rule_version_id FOREIGN KEY (version_id) REFERENCES document_version (id)
);
CREATE INDEX ix_rule_cat_jur_status ON rule (category, jurisdiction_code, review_status);
CREATE INDEX ix_rule_key_eff ON rule (rule_key, effective_from);

CREATE TABLE rule_condition (
    id UUID NOT NULL,
    rule_id UUID NOT NULL,
    field VARCHAR(24) NOT NULL,
    operator VARCHAR(8) NOT NULL,
    value_json JSONB NOT NULL,
    unit TEXT,
    CONSTRAINT pk_rule_condition PRIMARY KEY (id),
    CONSTRAINT ck_rule_condition_field CHECK (field IN ('zoning','district','zone','land_category','building_use','gross_floor_area','households','site_area','road_width','special_zone_type')),
    CONSTRAINT ck_rule_condition_operator CHECK (operator IN ('eq','in','gte','lte','between','exists')),
    CONSTRAINT fk_rule_condition_rule_id FOREIGN KEY (rule_id) REFERENCES rule (id) ON DELETE CASCADE
);
CREATE INDEX ix_rule_condition_field ON rule_condition (field);

CREATE TABLE rule_value (
    id UUID NOT NULL,
    rule_id UUID NOT NULL,
    value_key TEXT NOT NULL,
    value_type VARCHAR(32) NOT NULL,
    num_min DOUBLE PRECISION,
    num_max DOUBLE PRECISION,
    num_exact DOUBLE PRECISION,
    enum_value TEXT,
    formula TEXT,
    unit TEXT,
    condition_note TEXT,
    CONSTRAINT pk_rule_value PRIMARY KEY (id),
    CONSTRAINT ck_rule_value_value_type CHECK (value_type IN ('max', 'min', 'range', 'exact', 'enum_allowed', 'enum_conditional', 'enum_prohibited', 'formula', 'text_only')),
    CONSTRAINT uq_rule_value_key UNIQUE (rule_id, value_key),
    CONSTRAINT fk_rule_value_rule_id FOREIGN KEY (rule_id) REFERENCES rule (id) ON DELETE CASCADE
);

CREATE TABLE verification_report (
    id UUID NOT NULL,
    explanation_id UUID,
    ledger_id UUID NOT NULL,
    attempt_no SMALLINT NOT NULL,
    candidate_source VARCHAR(20) NOT NULL,
    verdict VARCHAR(4) NOT NULL,
    error_codes JSONB NOT NULL,
    report_json JSONB NOT NULL,
    verifier_version TEXT NOT NULL,
    judge_model TEXT,
    ledger_hash VARCHAR(64) NOT NULL,
    explanation_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_verification_report PRIMARY KEY (id),
    CONSTRAINT ck_verification_report_source CHECK (candidate_source IN ('ai','template_fallback')),
    CONSTRAINT ck_verification_report_verdict CHECK (verdict IN ('PASS','FAIL')),
    CONSTRAINT fk_verification_report_explanation_id FOREIGN KEY (explanation_id) REFERENCES explanation (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_report_ledger_id FOREIGN KEY (ledger_id) REFERENCES fact_ledger (id)
);
CREATE INDEX ix_verification_report_verdict ON verification_report (verdict, created_at);

CREATE TABLE rule_evidence (
    id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_value_id UUID,
    provision_id UUID NOT NULL,
    char_start INTEGER NOT NULL,
    char_end INTEGER NOT NULL,
    quoted_text TEXT NOT NULL,
    role VARCHAR(12) NOT NULL,
    CONSTRAINT pk_rule_evidence PRIMARY KEY (id),
    CONSTRAINT ck_rule_evidence_role CHECK (role IN ('value','exception')),
    CONSTRAINT ck_rule_evidence_span CHECK (char_end > char_start),
    CONSTRAINT fk_rule_evidence_provision_id FOREIGN KEY (provision_id) REFERENCES provision (id),
    CONSTRAINT fk_rule_evidence_rule_id FOREIGN KEY (rule_id) REFERENCES rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_evidence_rule_value_id FOREIGN KEY (rule_value_id) REFERENCES rule_value (id) ON DELETE CASCADE
);

ALTER TABLE explanation ADD CONSTRAINT fk_explanation_passed_report_id FOREIGN KEY (passed_report_id) REFERENCES verification_report (id);

-- 화면에 표시할 수 있는 설명: 통과 보고서 + 승인된 원장 + 해시 일치 (schema.sql 과 같은 정의)
CREATE VIEW displayable_explanation AS SELECT e.* FROM explanation e JOIN verification_report vr ON vr.id = e.passed_report_id JOIN fact_ledger fl ON fl.id = e.ledger_id WHERE vr.verdict = 'PASS' AND fl.review_status = 'approved' AND vr.ledger_hash = fl.ledger_hash;

-- schema.sql 의 active_rules_at(date) (엔진은 이식 가능한 자바 구현 RuleQueries.activeRulesAt 을 쓴다)
CREATE OR REPLACE FUNCTION active_rules_at(p_date date)
RETURNS SETOF rule LANGUAGE sql STABLE AS $$
  SELECT r.* FROM rule r
  WHERE r.review_status = 'approved'
    AND (r.effective_from IS NOT NULL AND r.effective_from <= p_date)
    AND (r.effective_to   IS NULL     OR  r.effective_to   >= p_date);
$$;
