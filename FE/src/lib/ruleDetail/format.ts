import { isUuid } from '@/lib/api/beAdapters';
import type { BasisNode, DataNotice, RuleSummary, RuleValue } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { formatNumber } from '@/lib/format';

/**
 * 법규 상세 페이지의 순수 도우미 — 화면(DOM)·네트워크를 모릅니다. 단위 테스트: format.test.ts
 */

// ── 원문 인용 강조 ─────────────────────────────────────────────────

export interface HighlightParts {
  before: string;
  mark: string;
  after: string;
}

/**
 * 조문 원문을 [앞, 인용, 뒤] 세 조각으로 나눕니다. 화면은 가운데 조각만 <mark> 로 감싸 텍스트로 그립니다
 * (HTML 로 끼워 넣지 않으므로 원문에 꺾쇠가 있어도 안전합니다).
 * 위치가 없거나 원문 범위를 벗어나면 null — 그때는 인용문을 따로 보여 줍니다.
 */
export function highlightParts(text: string | null, range: readonly [number, number] | null): HighlightParts | null {
  if (!text || !range) return null;
  const [start, end] = range;
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end > text.length || start >= end) return null;
  return { before: text.slice(0, start), mark: text.slice(start, end), after: text.slice(end) };
}

// ── 규칙 값 ─────────────────────────────────────────────────────────

/** 목록형 값의 구분 — 화면은 tone 으로 색을 고릅니다 (허용=초록 · 조건부=노랑 · 금지=빨강) */
export type RuleValueTone = 'allowed' | 'conditional' | 'prohibited';

export interface FormattedRuleValue {
  /** 목록형 값의 구분 (허용 · 조건부 · 금지) — 수치·산식·글이면 null */
  tag: string | null;
  tone: RuleValueTone | null;
  text: string;
}

function n(v: number): string {
  return formatNumber(v, 2);
}

/** BE rule_value.value_type 중 목록형 3가지 (DB 제약 ck_rule_value_value_type) */
const ENUM_TONES: Record<string, RuleValueTone> = {
  enum_allowed: 'allowed',
  enum_conditional: 'conditional',
  enum_prohibited: 'prohibited',
};

function plain(text: string): FormattedRuleValue {
  return { tag: null, tone: null, text };
}

/**
 * 규칙 값 한 줄 → 화면 문구.
 *  max 50 % → '50% 이하', min 2.5 m → '2.5m 이상', range 100~250 % → '100~250%', exact → '6m',
 *  enum_allowed → [허용] 단독주택, enum_conditional → [조건부] 제2종 근린생활시설, enum_prohibited → [금지] 숙박시설,
 *  formula → '산식 …', text_only → 용도명·산식·조건 설명 중 있는 것
 * value_type 이 낯설면 있는 숫자로 짐작하고, 그것도 없으면 용도명 → 산식 → 조건 설명 → '—'.
 */
export function formatRuleValue(v: RuleValue): FormattedRuleValue {
  const t = content.ruleDetail.values;
  const unit = v.unit ?? '';
  const tone = Object.hasOwn(ENUM_TONES, v.type) ? ENUM_TONES[v.type] : null;
  if (tone) {
    const tag = tone === 'allowed' ? t.enumAllowed : tone === 'conditional' ? t.enumConditional : t.enumProhibited;
    return { tag, tone, text: v.enumValue ?? content.site.rules.none };
  }
  if (v.type === 'formula' && v.formula) return plain(fmt(t.formula, { formula: v.formula }));
  if (v.exact !== null) return plain(fmt(t.exact, { v: n(v.exact), unit }));
  if (v.type === 'max' && v.max !== null) return plain(fmt(t.max, { v: n(v.max), unit }));
  if (v.type === 'min' && v.min !== null) return plain(fmt(t.min, { v: n(v.min), unit }));
  if (v.min !== null && v.max !== null) return plain(fmt(t.range, { min: n(v.min), max: n(v.max), unit }));
  if (v.max !== null) return plain(fmt(t.max, { v: n(v.max), unit }));
  if (v.min !== null) return plain(fmt(t.min, { v: n(v.min), unit }));
  if (v.enumValue) return plain(v.enumValue);
  if (v.formula) return plain(fmt(t.formula, { formula: v.formula }));
  // text_only 처럼 글로만 된 값 — 화면은 같은 조건 설명을 아래에 한 번 더 쓰지 않습니다
  if (v.conditionNote) return plain(v.conditionNote);
  return plain(content.site.rules.none);
}

// ── 요약 ─────────────────────────────────────────────────────────────

/** 요약 문장의 역할 → 작은 라벨 (핵심 · 요약 · 의미 · 조건 · 예외 · 주의). 모르는 역할이면 null */
export function sentenceRoleLabel(role: string): string | null {
  const roles: Record<string, string> = content.ruleDetail.summary.roles;
  return Object.hasOwn(roles, role) ? roles[role] : null;
}

/**
 * 요약의 종류
 *  - ai: AI 가 쓰고 원문 대조 검증을 통과한 문장
 *  - quote: AI 를 쓰지 않았거나 통과하지 못해 원문을 그대로 인용한 문장 — 'AI' 라고 부르지 않습니다
 *  - none: 요약 없음
 */
export type SummaryKind = 'ai' | 'quote' | 'none';

export function summaryKind(summary: RuleSummary | null): SummaryKind {
  if (!summary || summary.sentences.length === 0) return 'none';
  return summary.source === 'ai_verified' ? 'ai' : 'quote';
}

// ── 근거 ─────────────────────────────────────────────────────────────

/** 근거 트리에 걸린 규칙 id — 순서를 지키고, 겹치거나 모양이 틀린 것은 뺍니다 */
export function uniqueRuleIds(nodes: readonly BasisNode[]): string[] {
  const out: string[] = [];
  for (const node of nodes) {
    if (isUuid(node.ruleId) && !out.includes(node.ruleId)) out.push(node.ruleId);
  }
  return out;
}

/** 시행일(YYYY-MM-DD)로 시작하는지 — 문서 단위 안내 '<문서> <시행일> 시행 버전의 …' 의 뒷부분 */
const STARTS_WITH_DATE = /^\d{4}-\d{2}-\d{2}\b/;

/**
 * '검수 승인된 법규 데이터 없음'(RULE_NOT_APPROVED) 안내 중 이 항목 것만 고릅니다 (BE ReviewEngine 의 두 문구).
 *  - 항목 안내: '<항목 제목>: 이 지역·용도지역에 대해 검수 승인된 법규 데이터가 아직 없습니다.'
 *  - 문서 안내: '<문서 제목> <시행일> 시행 버전의 규칙이 아직 검수되지 않아 …' — 이 항목 근거 트리에 그 문서가 있을 때만
 * 어느 쪽에도 맞지 않는 안내(다른 항목·다른 문서 것)는 빼고, 맞는 것이 없으면 빈 목록입니다.
 * '주차장법' 과 '주차장법 시행령' 처럼 앞부분이 같은 제목을 헷갈리지 않도록 제목 뒤의 구분(':' · 시행일)까지 봅니다.
 */
export function ruleNotApprovedNotices(
  notices: readonly DataNotice[],
  itemTitle: string,
  documentTitles: readonly string[] = [],
): DataNotice[] {
  const docs = documentTitles.filter((d) => d.length > 0);
  return notices.filter((n) => {
    if (n.code !== 'RULE_NOT_APPROVED') return false;
    if (itemTitle && n.message.startsWith(`${itemTitle}:`)) return true;
    return docs.some((d) => n.message.startsWith(`${d} `) && STARTS_WITH_DATE.test(n.message.slice(d.length + 1)));
  });
}
