-- =====================================================================
-- APP(Auto Parking Plan) 법규 서버 DB 스키마
-- 대상: PostgreSQL 15+ / PostGIS 3+
-- 작성일: 2026-11-06
--
-- 구성
--   A. 코드/열거형
--   B. 원문 계층 (source_document → document_version → provision)
--   C. 계층 연결 (delegation)
--   D. 규칙 (rule, rule_condition, rule_value, rule_evidence)
--   E. AI 추출 이력과 검수 (extraction_run, review_log)
--   F. 필지와 지정 정보 (parcel, parcel_designation, zone_overlay)
--   G. 검토 결과와 쉬운 설명 (review_request, review_item, explanation)
--   H. 사후 검증 (fact_ledger, ledger_fact, ledger_dismissal, verification_report, displayable_explanation)
--
-- 원칙
--   * NULL = 미확인. "규제 없음/허용/0"으로 해석하지 않는다.
--   * 모든 버전은 시행일(effective_from/to)과 수집일(fetched_at)을 따로 가진다.
--   * 검토에 쓰이는 rule 은 review_status = 'approved' 인 것만.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------
-- A. 열거형
-- ---------------------------------------------------------------------
CREATE TYPE law_layer AS ENUM (
  'L1_NATIONAL',      -- 국가 법령 (법률·시행령·시행규칙)
  'L2_LAND_USE',      -- 토지이용 규제 (용도지역·지구·구역 지정 체계)
  'L3_LOCAL',         -- 지역 조례·규칙
  'L4_SPECIAL_ZONE'   -- 특화구역 지정 고시·계획
);

CREATE TYPE source_type AS ENUM (
  'law', 'decree', 'rule', 'ordinance', 'local_rule', 'notice', 'plan'
);

CREATE TYPE provision_kind AS ENUM (
  'article',    -- 조
  'paragraph',  -- 항
  'item',       -- 호
  'subitem',    -- 목
  'annex',      -- 별표
  'annex_row',  -- 별표의 표 행
  'addendum'    -- 부칙
);

CREATE TYPE rule_category AS ENUM (
  'ZONING_USE',          -- 용도지역별 건축 가능 용도
  'BUILDING_COVERAGE',   -- 건폐율
  'FLOOR_AREA_RATIO',    -- 용적률
  'PARKING_REQUIREMENT', -- 부설주차장 설치 기준(용도·면적당 대수)
  'PARKING_STALL_SIZE',  -- 주차구획 크기
  'DRIVE_AISLE_WIDTH',   -- 차로 폭
  'ROAD_ACCESS',         -- 접도 (별도 확인)
  'SETBACK',             -- 건축선·이격거리 (별도 확인)
  'HEIGHT_SUNLIGHT',     -- 높이·일조 (별도 확인)
  'SPECIAL_ZONE'         -- 지역 추가 제한 (별도 확인)
);

CREATE TYPE value_type AS ENUM (
  'max', 'min', 'range', 'exact', 'enum_allowed', 'enum_conditional',
  'enum_prohibited', 'formula', 'text_only'
);

CREATE TYPE verify_state AS ENUM ('not_checked', 'confirmed', 'needs_review');

CREATE TYPE rule_review_status AS ENUM (
  'draft',          -- AI 추출 직후
  'auto_checked',   -- 기계 검증(근거 span, 스키마, 범위) 통과
  'approved',       -- 사람 검수 완료 → 검토 엔진 사용 가능
  'rejected',
  'superseded'      -- 새 버전 규칙으로 대체됨
);

CREATE TYPE review_status_label AS ENUM (
  'REVIEWABLE',        -- 검토 가능
  'CONDITIONAL',       -- 조건부 검토
  'MANUAL_CHECK'       -- 별도 확인 필요
);

-- ---------------------------------------------------------------------
-- 관할(지역) 코드: 법정동코드 앞자리 기준
-- ---------------------------------------------------------------------
CREATE TABLE jurisdiction (
  code          varchar(10) PRIMARY KEY,  -- '27' 대구, '27260' 수성구 (법정동코드 앞 2/5자리)
  name          text        NOT NULL,     -- '대구광역시', '대구광역시 수성구'
  level         smallint    NOT NULL CHECK (level IN (0,1,2)),  -- 0 전국, 1 시·도, 2 시·군·구
  parent_code   varchar(10) REFERENCES jurisdiction(code),
  is_supported  boolean     NOT NULL DEFAULT false   -- MVP 지원 지역 여부 (수성구 true)
);
INSERT INTO jurisdiction (code, name, level, parent_code, is_supported) VALUES
  ('00', '전국', 0, NULL, true);
-- 대구·수성구 코드는 법정동코드 원본에서 확인 후 입력

-- ---------------------------------------------------------------------
-- B. 원문 계층
-- ---------------------------------------------------------------------

-- 법령/조례/고시 "문서" 단위 (버전과 무관한 정체성)
CREATE TABLE source_document (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  layer             law_layer    NOT NULL,
  source_type       source_type  NOT NULL,
  official_title    text         NOT NULL,        -- 공식 제명
  short_title       text,                          -- 약칭 (국토계획법 등)
  jurisdiction_code varchar(10)  NOT NULL REFERENCES jurisdiction(code),
  external_system   text         NOT NULL,        -- 'law.go.kr', 'eum.go.kr', 'manual'
  external_id       text,                          -- 법령ID / 자치법규ID / 고시번호
  parent_document_id uuid REFERENCES source_document(id), -- 시행령 → 법률 등
  homepage_url      text,
  is_active         boolean      NOT NULL DEFAULT true,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (external_system, external_id)
);
CREATE INDEX ON source_document (layer, jurisdiction_code);

-- 문서의 "버전" (공포·시행 단위). 원문은 객체저장소에 두고 해시로 변경 감지
CREATE TABLE document_version (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id         uuid        NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
  promulgation_number text,                 -- '법률 제21447호', '조례 제6551호'
  promulgation_date   date,
  effective_from      date,                 -- 시행일 (미확인 시 NULL, 다운로드일로 대체 금지)
  effective_to        date,                 -- 다음 버전 시행 전날 (현행이면 NULL)
  external_version_id text,                 -- MST 등
  raw_object_key      text        NOT NULL, -- s3://.../law/009294/2026-07-01.xml
  raw_format          text        NOT NULL CHECK (raw_format IN ('xml','html','pdf','json')),
  raw_sha256          char(64)    NOT NULL,
  source_url          text        NOT NULL,
  fetched_at          timestamptz NOT NULL,
  -- 확인 상태는 항목별로 분리 (단일 verified 플래그 금지)
  v_source_access     verify_state NOT NULL DEFAULT 'not_checked',
  v_version_header    verify_state NOT NULL DEFAULT 'not_checked',
  v_text_match        verify_state NOT NULL DEFAULT 'not_checked',
  v_current_version   verify_state NOT NULL DEFAULT 'not_checked',
  current_checked_on  date,
  note                text,
  UNIQUE (document_id, raw_sha256)
);
CREATE INDEX ON document_version (document_id, effective_from);

-- 조/항/호/목/별표 단위 조문 (결정적 파서가 생성, AI 미사용)
CREATE TABLE provision (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id         uuid NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
  parent_id          uuid REFERENCES provision(id) ON DELETE CASCADE,
  kind               provision_kind NOT NULL,
  label              text NOT NULL,         -- '제77조', '①', '1.', '가.', '별표 1'
  path               text NOT NULL,         -- 'art77/para1/item2' (버전 간 동일 조문 매칭 키)
  heading            text,                  -- '(용도지역의 건폐율)'
  text               text NOT NULL,         -- 해당 단위 원문 전체
  table_json         jsonb,                 -- 별표가 표이면 행/열 구조
  sort_order         integer NOT NULL,
  UNIQUE (version_id, path)
);
CREATE INDEX ON provision (version_id, kind);
CREATE INDEX provision_text_fts ON provision USING gin (to_tsvector('simple', text));

-- ---------------------------------------------------------------------
-- C. 계층 연결: 위임 관계 (상위 조문 → 하위 조문)
-- ---------------------------------------------------------------------
CREATE TABLE delegation (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  from_provision_id   uuid NOT NULL REFERENCES provision(id) ON DELETE CASCADE, -- 위임하는 조문
  to_provision_id     uuid NOT NULL REFERENCES provision(id) ON DELETE CASCADE, -- 위임받은 조문
  relation            text NOT NULL CHECK (relation IN ('delegates','implements','references','overrides_in_zone')),
  evidence_text       text,       -- "~도시계획조례로 정하는 비율" 등 근거 문구
  created_by          text NOT NULL CHECK (created_by IN ('parser','ai','human')),
  review_status       rule_review_status NOT NULL DEFAULT 'draft',
  UNIQUE (from_provision_id, to_provision_id, relation)
);

-- ---------------------------------------------------------------------
-- D. 규칙: 조문에서 뽑아낸 "기계가 쓸 수 있는" 기준
-- ---------------------------------------------------------------------
CREATE TABLE rule (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_key           text NOT NULL,          -- 'BCR.ZONE.R2G' 버전 간 동일 규칙 식별자
  category           rule_category NOT NULL,
  layer              law_layer     NOT NULL,
  jurisdiction_code  varchar(10)   NOT NULL REFERENCES jurisdiction(code),
  version_id         uuid NOT NULL REFERENCES document_version(id),
  effective_from     date,                   -- 보통 version 의 시행일 상속
  effective_to       date,
  title              text NOT NULL,          -- '제2종일반주거지역 건폐율'
  automation_level   text NOT NULL CHECK (automation_level IN ('auto','reference_only','manual_check')),
  review_status      rule_review_status NOT NULL DEFAULT 'draft',
  extraction_run_id  uuid,                   -- FK는 아래 extraction_run 생성 후 추가
  supersedes_rule_id uuid REFERENCES rule(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  approved_by        text,
  approved_at        timestamptz
);
CREATE INDEX ON rule (category, jurisdiction_code, review_status);
CREATE INDEX ON rule (rule_key, effective_from);

-- 규칙이 적용되는 조건 (AND 결합). 예: zoning = '제2종일반주거지역'
CREATE TABLE rule_condition (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id     uuid NOT NULL REFERENCES rule(id) ON DELETE CASCADE,
  field       text NOT NULL CHECK (field IN (
                'zoning','district','zone','land_category','building_use',
                'gross_floor_area','households','site_area','road_width','special_zone_type')),
  operator    text NOT NULL CHECK (operator IN ('eq','in','gte','lte','between','exists')),
  value_json  jsonb NOT NULL,       -- "제2종일반주거지역" | ["단독주택","공동주택"] | [200, 1000]
  unit        text
);
CREATE INDEX ON rule_condition (field, (value_json::text));

-- 규칙의 값. 한 규칙이 여러 값(예: 일반형/확장형 주차구획)을 가질 수 있음
CREATE TABLE rule_value (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id      uuid NOT NULL REFERENCES rule(id) ON DELETE CASCADE,
  value_key    text NOT NULL,        -- 'ratio', 'stall_general.width', 'use:공동주택'
  value_type   value_type NOT NULL,
  num_min      numeric,
  num_max      numeric,
  num_exact    numeric,
  enum_value   text,                 -- 용도명
  formula      text,                 -- '시설면적 / 134' 등 (계산식 DSL, 7장 참고)
  unit         text,                 -- '%', 'm', '대/㎡', NULL
  condition_note text,               -- 조건부일 때 조건 설명 (원문 요약)
  UNIQUE (rule_id, value_key)
);

-- 근거: 규칙(값)이 원문의 어느 구간에서 나왔는지. 근거 없는 값은 저장 불가
CREATE TABLE rule_evidence (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id        uuid NOT NULL REFERENCES rule(id) ON DELETE CASCADE,
  rule_value_id  uuid REFERENCES rule_value(id) ON DELETE CASCADE,
  provision_id   uuid NOT NULL REFERENCES provision(id),
  char_start     integer NOT NULL,
  char_end       integer NOT NULL,
  quoted_text    text NOT NULL,      -- provision.text[char_start:char_end] 와 정확히 일치해야 함
  CHECK (char_end > char_start)
);

-- ---------------------------------------------------------------------
-- E. AI 추출 이력과 검수
-- ---------------------------------------------------------------------
CREATE TABLE extraction_run (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id      uuid NOT NULL REFERENCES document_version(id),
  provision_ids   uuid[] NOT NULL,         -- 입력으로 준 조문들
  task            text NOT NULL CHECK (task IN ('rule_extraction','delegation_extraction','explanation')),
  model           text NOT NULL,           -- 사용 모델명
  prompt_version  text NOT NULL,           -- 'rule-extract@v3'
  schema_version  text NOT NULL,           -- 'rule_extraction.schema.json@1.0'
  input_sha256    char(64) NOT NULL,
  output_json     jsonb,
  validation      jsonb,                   -- 스키마/근거/범위 검사 결과
  status          text NOT NULL CHECK (status IN ('ok','schema_error','evidence_error','range_error','failed')),
  created_at      timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE rule ADD CONSTRAINT rule_extraction_run_fk
  FOREIGN KEY (extraction_run_id) REFERENCES extraction_run(id);

CREATE TABLE review_log (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  target_type text NOT NULL CHECK (target_type IN ('rule','delegation','explanation','document_version')),
  target_id   uuid NOT NULL,
  action      text NOT NULL CHECK (action IN ('approve','reject','edit','comment','request_reextract')),
  reviewer    text NOT NULL,
  diff_json   jsonb,
  comment     text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- F. 필지와 지정 정보 (캐시 성격: 외부 API 원천)
-- ---------------------------------------------------------------------
CREATE TABLE parcel (
  pnu               char(19) PRIMARY KEY,   -- 법정동코드10 + 산여부1 + 본번4 + 부번4
  jurisdiction_code varchar(10) NOT NULL REFERENCES jurisdiction(code),
  road_address      text,
  jibun_address     text NOT NULL,
  land_area_m2      numeric,                -- NULL = 미확인
  land_category     text,                   -- 지목 '대', '전' ...
  official_land_price integer,              -- 개별공시지가 (선택)
  geom              geometry(MultiPolygon, 4326),
  centroid          geometry(Point, 4326),
  source_meta       jsonb NOT NULL,         -- {api, fetched_at, response_sha256} 항목별
  fetched_at        timestamptz NOT NULL,
  expires_at        timestamptz NOT NULL
);
CREATE INDEX ON parcel USING gist (geom);

-- 토지이용계획: 용도지역·지구·구역 (한 필지에 여러 개, 저촉 여부 포함)
CREATE TABLE parcel_designation (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  pnu           char(19) NOT NULL REFERENCES parcel(pnu) ON DELETE CASCADE,
  kind          text NOT NULL CHECK (kind IN ('zoning','district','zone','other')), -- 지역/지구/구역
  name          text NOT NULL,          -- '제2종일반주거지역'
  code          text,                   -- 원천 코드 (UQA122 등)
  conflict      text CHECK (conflict IN ('included','partially_included','adjacent','unknown')),
  basis_law     text,                   -- 원천이 표기한 근거 법령명
  fetched_at    timestamptz NOT NULL
);
CREATE INDEX ON parcel_designation (pnu);
CREATE INDEX ON parcel_designation (name);

-- L4 특화구역 경계 (수작업/고시 도면에서 등록)
CREATE TABLE zone_overlay (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id    uuid NOT NULL REFERENCES source_document(id),  -- layer = L4 고시
  zone_type      text NOT NULL,   -- 'district_unit_plan','rnd_special_zone','free_economic_zone','heritage'
  name           text NOT NULL,
  geom           geometry(MultiPolygon, 4326),   -- 도면 미확보 시 NULL (PNU 목록으로 대체)
  pnu_list       char(19)[],
  effective_from date,
  effective_to   date
);
CREATE INDEX ON zone_overlay USING gist (geom);

-- 기존 건축물 (선택)
CREATE TABLE parcel_building (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  pnu              char(19) NOT NULL REFERENCES parcel(pnu) ON DELETE CASCADE,
  main_use         text,
  gross_floor_area numeric,
  building_area    numeric,
  approval_date    date,
  raw              jsonb,
  fetched_at       timestamptz NOT NULL
);

-- ---------------------------------------------------------------------
-- G. 검토 결과와 쉬운 설명
-- ---------------------------------------------------------------------
CREATE TABLE review_request (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  pnu           char(19) NOT NULL REFERENCES parcel(pnu),
  as_of_date    date NOT NULL,                 -- 검토 기준일
  inputs        jsonb NOT NULL DEFAULT '{}',   -- 향후: 계획 용도, 연면적 등
  engine_version text NOT NULL,
  ruleset_hash  char(64) NOT NULL,             -- 사용한 approved 규칙 집합 해시 (재현성)
  status        text NOT NULL CHECK (status IN ('running','done','partial','failed')),
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE review_item (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id     uuid NOT NULL REFERENCES review_request(id) ON DELETE CASCADE,
  category       rule_category NOT NULL,
  status         review_status_label NOT NULL,
  applied_rule_ids uuid[] NOT NULL DEFAULT '{}',  -- L1→L4 순서
  result_json    jsonb NOT NULL,   -- 적용값·참고값·근거트리 (review_result.schema 의 item)
  reason_code    text,             -- 'NO_LOCAL_VALUE', 'ZONE_OVERLAY', 'OUT_OF_RANGE', 'DATA_MISSING'
  sort_order     integer NOT NULL
);

-- 쉬운 설명: (규칙 버전 + 상태 + 대상 독자) 단위로 캐시
CREATE TABLE explanation (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cache_key       char(64) NOT NULL UNIQUE,   -- sha256(category|rule_ids|status|audience|prompt_version)
  category        rule_category NOT NULL,
  audience        text NOT NULL CHECK (audience IN ('owner','architect')),  -- 토지소유주 / 실무자
  explanation_json jsonb NOT NULL,            -- explanation.schema.json 출력
  extraction_run_id uuid REFERENCES extraction_run(id),
  review_status   rule_review_status NOT NULL DEFAULT 'auto_checked',
  created_at      timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 검토 엔진용 뷰: 기준일에 유효하고 승인된 규칙
-- 사용: SELECT * FROM active_rules_at('2026-11-06');
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION active_rules_at(p_date date)
RETURNS SETOF rule LANGUAGE sql STABLE AS $$
  SELECT r.* FROM rule r
  WHERE r.review_status = 'approved'
    AND (r.effective_from IS NOT NULL AND r.effective_from <= p_date)
    AND (r.effective_to   IS NULL     OR  r.effective_to   >= p_date);
$$;

-- =====================================================================
-- H. 사후 검증 (환각 차단) — 2026-11-06 추가
--   환각 = 날조(없는 내용) + 세부 왜곡(값·단위·비교어·성격) + 누락(필수 사실 빠짐)
--   화면에는 verification_report.verdict = 'PASS' 인 설명만 나간다.
-- =====================================================================

-- 사실 원장: 규칙(또는 검토 항목)별 정답지. 사람이 승인해야 사용
CREATE TABLE fact_ledger (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_ids       uuid[] NOT NULL,
  provision_ids  uuid[] NOT NULL,
  review_status  text NOT NULL DEFAULT 'draft' CHECK (review_status IN ('draft','approved','superseded')),
  ledger_hash    char(64) NOT NULL,          -- facts + dismissed 전체 해시 (설명 캐시 키에 포함)
  completeness_gaps jsonb NOT NULL DEFAULT '[]',  -- check_completeness 결과. 비어 있어야 승인 가능
  approved_by    text,
  approved_at    timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (review_status <> 'approved' OR completeness_gaps = '[]'::jsonb)
);

-- 원장의 원자 사실
CREATE TABLE ledger_fact (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ledger_id     uuid NOT NULL REFERENCES fact_ledger(id) ON DELETE CASCADE,
  fact_key      text NOT NULL,              -- 'F_PERIOD' 등 설명 문장이 인용하는 id
  kind          text NOT NULL CHECK (kind IN ('PERIOD','QUANTITY','DATE','REFERENCE','SUBJECT','CONDITION','EXCEPTION','MODALITY','STATUS')),
  origin        text NOT NULL CHECK (origin IN ('provision','engine')),
  provision_id  uuid REFERENCES provision(id),
  quote         text NOT NULL,              -- 원문 그대로 (origin=provision 이면 provision.text 의 부분 문자열)
  canonical     jsonb,                      -- {dim, value, unit, comparator}
  modality      text CHECK (modality IN ('ALLOW','PROHIBIT','OBLIGE','EXEMPT')),
  name          text,
  status_value  text CHECK (status_value IN ('REVIEWABLE','CONDITIONAL','MANUAL_CHECK')),
  key_terms     jsonb NOT NULL DEFAULT '[]',
  must_mention  boolean NOT NULL,
  candidate_ids text[] NOT NULL DEFAULT '{}',
  UNIQUE (ledger_id, fact_key)
);

-- 원문 후보 중 사유를 적어 제외한 것 (사유 없는 제외 불가)
CREATE TABLE ledger_dismissal (
  ledger_id     uuid NOT NULL REFERENCES fact_ledger(id) ON DELETE CASCADE,
  candidate_id  text NOT NULL,
  reason        text NOT NULL CHECK (length(reason) >= 2),
  reviewer      text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ledger_id, candidate_id)
);

-- 검증 보고서: 생성 시도마다 1건 (재생성·템플릿 포함)
CREATE TABLE verification_report (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  explanation_id   uuid REFERENCES explanation(id) ON DELETE CASCADE,
  ledger_id        uuid NOT NULL REFERENCES fact_ledger(id),
  attempt_no       smallint NOT NULL,               -- 1..N (템플릿 대체본은 N+1)
  candidate_source text NOT NULL CHECK (candidate_source IN ('ai','template_fallback')),
  verdict          text NOT NULL CHECK (verdict IN ('PASS','FAIL')),
  error_codes      text[] NOT NULL DEFAULT '{}',
  report_json      jsonb NOT NULL,                  -- verification_report@1.0 전체
  verifier_version text NOT NULL,
  judge_model      text,
  ledger_hash      char(64) NOT NULL,
  explanation_hash char(64) NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON verification_report (verdict, created_at);
CREATE INDEX ON verification_report USING gin (error_codes);

-- 설명 테이블: 화면 노출 상태를 검증 결과에 묶는다
ALTER TABLE explanation
  ADD COLUMN ledger_id         uuid REFERENCES fact_ledger(id),
  ADD COLUMN display_source    text NOT NULL DEFAULT 'basis_only'
                               CHECK (display_source IN ('ai_verified','template_fallback','basis_only')),
  ADD COLUMN passed_report_id  uuid REFERENCES verification_report(id);

-- 통과 보고서 없이는 화면용 설명으로 표시할 수 없다
ALTER TABLE explanation ADD CONSTRAINT explanation_display_requires_pass
  CHECK (display_source = 'basis_only' OR passed_report_id IS NOT NULL);

-- 화면 API 는 이 뷰만 읽는다: PASS 보고서가 연결되고 원장 해시가 일치하는 설명
CREATE OR REPLACE VIEW displayable_explanation AS
SELECT e.*
FROM explanation e
JOIN verification_report vr ON vr.id = e.passed_report_id
JOIN fact_ledger fl ON fl.id = e.ledger_id
WHERE vr.verdict = 'PASS'
  AND fl.review_status = 'approved'
  AND vr.ledger_hash = fl.ledger_hash;     -- 원장이 바뀌면 이전 검증은 무효
