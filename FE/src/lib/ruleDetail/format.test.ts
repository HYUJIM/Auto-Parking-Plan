import { describe, expect, it } from 'vitest';
import type { BasisNode, RuleSummary, RuleValue } from '@/lib/api/types';
import { formatRuleValue, highlightParts, ruleNotApprovedNotices, sentenceRoleLabel, summaryKind, uniqueRuleIds } from './format';

describe('highlightParts — 원문을 [앞, 인용, 뒤] 로', () => {
  const text = '4. 제2종일반주거지역 : 60퍼센트 이하';

  it('범위대로 세 조각으로 나누고, 이어 붙이면 원문 그대로', () => {
    const p = highlightParts(text, [3, text.length]);
    expect(p).toEqual({ before: '4. ', mark: '제2종일반주거지역 : 60퍼센트 이하', after: '' });
    expect(`${p?.before}${p?.mark}${p?.after}`).toBe(text);
    expect(highlightParts(text, [0, 2])).toEqual({ before: '', mark: '4.', after: text.slice(2) });
  });

  it('꺾쇠·따옴표가 든 원문도 글자 그대로 둔다 (HTML 로 해석하지 않음)', () => {
    const raw = '<개정 2023. 5. 15.> “건축제한”이라 한다';
    expect(highlightParts(raw, [0, 17])?.mark).toBe('<개정 2023. 5. 15.>');
  });

  it('위치가 없거나 원문 범위를 벗어나거나 비어 있으면 null', () => {
    expect(highlightParts(text, null)).toBeNull();
    expect(highlightParts(null, [0, 3])).toBeNull();
    expect(highlightParts('', [0, 0])).toBeNull();
    expect(highlightParts(text, [-1, 3])).toBeNull();
    expect(highlightParts(text, [3, text.length + 1])).toBeNull();
    expect(highlightParts(text, [5, 5])).toBeNull();
    expect(highlightParts(text, [6, 3])).toBeNull();
    expect(highlightParts(text, [1.5, 3])).toBeNull();
  });
});

function value(partial: Partial<RuleValue>): RuleValue {
  return {
    key: 'ratio',
    type: 'max',
    min: null,
    max: null,
    exact: null,
    enumValue: null,
    formula: null,
    unit: null,
    conditionNote: null,
    ...partial,
  };
}

describe('formatRuleValue — 규칙 값 한 줄', () => {
  it('최대·최소·범위·정확값', () => {
    expect(formatRuleValue(value({ type: 'max', max: 60, unit: '%' }))).toEqual({ tag: null, tone: null, text: '60% 이하' });
    expect(formatRuleValue(value({ type: 'min', min: 2.5, unit: 'm' }))).toEqual({ tag: null, tone: null, text: '2.5m 이상' });
    expect(formatRuleValue(value({ type: 'range', min: 100, max: 250, unit: '%' }))).toEqual({ tag: null, tone: null, text: '100~250%' });
    expect(formatRuleValue(value({ type: 'exact', exact: 6, unit: 'm' }))).toEqual({ tag: null, tone: null, text: '6m' });
  });

  it('큰 수는 천 단위 쉼표, 소수는 2자리까지', () => {
    expect(formatRuleValue(value({ type: 'max', max: 1500, unit: '%' })).text).toBe('1,500% 이하');
    expect(formatRuleValue(value({ type: 'min', min: 3.333, unit: 'm' })).text).toBe('3.33m 이상');
  });

  it('단위가 없으면 숫자만', () => {
    expect(formatRuleValue(value({ type: 'max', max: 4 })).text).toBe('4 이하');
  });

  it('목록형: 허용 · 조건부 · 금지 구분과 색(tone)을 따로 돌려준다', () => {
    expect(formatRuleValue(value({ type: 'enum_allowed', enumValue: '단독주택' }))).toEqual({
      tag: '허용',
      tone: 'allowed',
      text: '단독주택',
    });
    expect(formatRuleValue(value({ type: 'enum_conditional', enumValue: '제2종 근린생활시설' }))).toEqual({
      tag: '조건부',
      tone: 'conditional',
      text: '제2종 근린생활시설',
    });
    expect(formatRuleValue(value({ type: 'enum_allowed' }))).toEqual({ tag: '허용', tone: 'allowed', text: '—' });
  });

  it('금지 용도(enum_prohibited)는 꼬리표 없이 나오지 않는다 — 허용으로 오해하지 않게 [금지]', () => {
    expect(formatRuleValue(value({ type: 'enum_prohibited', enumValue: '숙박시설' }))).toEqual({
      tag: '금지',
      tone: 'prohibited',
      text: '숙박시설',
    });
    expect(formatRuleValue(value({ type: 'enum_prohibited' }))).toEqual({ tag: '금지', tone: 'prohibited', text: '—' });
  });

  it('산식', () => {
    expect(formatRuleValue(value({ type: 'formula', formula: '시설면적 / 134' })).text).toBe('산식 시설면적 / 134');
  });

  it('글로만 된 값(text_only): 용도명 → 산식 → 조건 설명 순으로, 없으면 —', () => {
    expect(formatRuleValue(value({ type: 'text_only', conditionNote: '도시계획위원회 심의를 거쳐 정함' }))).toEqual({
      tag: null,
      tone: null,
      text: '도시계획위원회 심의를 거쳐 정함',
    });
    expect(formatRuleValue(value({ type: 'text_only', enumValue: '공동주택', conditionNote: '세대수 기준' })).text).toBe('공동주택');
    expect(formatRuleValue(value({ type: 'text_only', formula: 'ceil(households * 1)' })).text).toBe('산식 ceil(households * 1)');
    expect(formatRuleValue(value({ type: 'text_only' }))).toEqual({ tag: null, tone: null, text: '—' });
  });

  it('종류와 숫자가 어긋나거나 낯선 종류면 있는 값으로 짐작하고, 아무것도 없으면 —', () => {
    expect(formatRuleValue(value({ type: 'max', min: 3 })).text).toBe('3 이상');
    expect(formatRuleValue(value({ type: 'weird', min: 1, max: 2, unit: 'm' })).text).toBe('1~2m');
    expect(formatRuleValue(value({ type: 'weird', max: 2, unit: 'm' })).text).toBe('2m 이하');
    expect(formatRuleValue(value({ type: 'weird', enumValue: '주차장' })).text).toBe('주차장');
    expect(formatRuleValue(value({ type: 'weird', formula: 'a+b' })).text).toBe('산식 a+b');
    expect(formatRuleValue(value({ type: 'toString', enumValue: '주차장' }))).toEqual({ tag: null, tone: null, text: '주차장' });
    expect(formatRuleValue(value({ type: 'weird' }))).toEqual({ tag: null, tone: null, text: '—' });
  });
});

describe('sentenceRoleLabel — 요약 문장의 역할 라벨', () => {
  it('여섯 역할', () => {
    expect(['headline', 'summary', 'meaning', 'condition', 'exception', 'caution'].map(sentenceRoleLabel)).toEqual([
      '핵심',
      '요약',
      '의미',
      '조건',
      '예외',
      '주의',
    ]);
  });

  it('모르는 역할 · 객체 기본 속성 이름은 null', () => {
    expect(sentenceRoleLabel('other')).toBeNull();
    expect(sentenceRoleLabel('toString')).toBeNull();
    expect(sentenceRoleLabel('__proto__')).toBeNull();
    expect(sentenceRoleLabel('')).toBeNull();
  });
});

describe('summaryKind — 요약 출처', () => {
  const base: RuleSummary = {
    source: 'ai_verified',
    sentences: [{ role: 'headline', text: '…' }],
    glossary: [],
    checkedAt: null,
    mustMention: null,
  };

  it('ai_verified 만 AI 요약, template_fallback 은 원문 인용, 없거나 문장이 없으면 none', () => {
    expect(summaryKind(base)).toBe('ai');
    expect(summaryKind({ ...base, source: 'template_fallback' })).toBe('quote');
    expect(summaryKind({ ...base, sentences: [] })).toBe('none');
    expect(summaryKind(null)).toBe('none');
  });
});

function node(ruleId: string | null): BasisNode {
  return { layer: 'L1_NATIONAL', documentTitle: '주차장법', articleLabel: null, effectiveFrom: null, quote: null, url: null, ruleId };
}

describe('uniqueRuleIds — 원문 카드를 만들 규칙', () => {
  const a = '01a1c5ce-9b71-72e8-8ff0-2e9b9740edef';
  const b = '01a1c5ce-9d5c-7123-bf6d-9b43fac20c8a';

  it('순서를 지키고 겹침·null·모양이 틀린 id 는 뺀다', () => {
    expect(uniqueRuleIds([node(null), node(a), node(b), node(a), node('nope'), node(null)])).toEqual([a, b]);
    expect(uniqueRuleIds([])).toEqual([]);
  });
});

describe('ruleNotApprovedNotices — 승인된 데이터가 없다는 BE 안내 중 이 항목 것만', () => {
  // BE ReviewEngine 이 만드는 두 문구 (항목 안내 · 문서 안내) + 다른 종류 안내
  const parking = {
    code: 'RULE_NOT_APPROVED',
    message: '주차대수 (부설주차장): 이 지역·용도지역에 대해 검수 승인된 법규 데이터가 아직 없습니다.',
  };
  const coverage = { code: 'RULE_NOT_APPROVED', message: '건폐율: 이 지역·용도지역에 대해 검수 승인된 법규 데이터가 아직 없습니다.' };
  const lawDoc = {
    code: 'RULE_NOT_APPROVED',
    message: '주차장법 2026-03-01 시행 버전의 규칙이 아직 검수되지 않아 이전 버전 기준을 쓰지 않았습니다.',
  };
  const decreeDoc = {
    code: 'RULE_NOT_APPROVED',
    message: '주차장법 시행령 2026-03-01 시행 버전의 규칙이 아직 검수되지 않아 이전 버전 기준을 쓰지 않았습니다.',
  };
  const pending = { code: 'PENDING_AMENDMENT', message: '주차대수 (부설주차장) 조례 개정분은 반영하지 않았습니다.' };
  const notices = [parking, coverage, lawDoc, decreeDoc, pending];

  it('이 항목 제목으로 시작하는 안내만', () => {
    expect(ruleNotApprovedNotices(notices, '주차대수 (부설주차장)')).toEqual([parking]);
    expect(ruleNotApprovedNotices(notices, '건폐율')).toEqual([coverage]);
  });

  it('맞는 안내가 없으면 다른 항목 것을 빌려 오지 않고 빈 목록', () => {
    expect(ruleNotApprovedNotices(notices, '차로 폭')).toEqual([]);
    expect(ruleNotApprovedNotices([pending], '주차대수 (부설주차장)')).toEqual([]);
    expect(ruleNotApprovedNotices(notices, '')).toEqual([]);
  });

  it('문서 안내는 이 항목 근거 트리에 그 문서가 있을 때만 — 앞부분이 같은 다른 문서와 헷갈리지 않는다', () => {
    expect(ruleNotApprovedNotices(notices, '차로 폭', ['주차장법'])).toEqual([lawDoc]);
    expect(ruleNotApprovedNotices(notices, '차로 폭', ['주차장법 시행령'])).toEqual([decreeDoc]);
    expect(ruleNotApprovedNotices(notices, '주차대수 (부설주차장)', ['주차장법', '주차장법 시행령', ''])).toEqual([
      parking,
      lawDoc,
      decreeDoc,
    ]);
    expect(ruleNotApprovedNotices(notices, '차로 폭', ['대구광역시 주차장 조례'])).toEqual([]);
  });
});
