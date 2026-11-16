import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BeReviewItem, BeRuleBasis } from './beTypes';
import { evidenceHighlight, toRuleBasisDetail, toRuleSummary } from './beAdapters';
import { getRuleBasis, resetRuleBasisCache } from './rules';
import { ApiError } from './types';

const RULE_ID = '01a1c5ce-9b71-72e8-8ff0-2e9b9740edef';
const PARENT_RULE_ID = '01a1c5ce-9d5c-7123-bf6d-9b43fac20c8a';

const PROVISION = '4. 제2종일반주거지역 : 60퍼센트 이하';
const QUOTE = '제2종일반주거지역 : 60퍼센트 이하';

/** GET /v1/rules/{id}/basis 응답 (BE PublicController.ruleBasis 모양) */
function basisBody(overrides: Partial<BeRuleBasis> = {}): BeRuleBasis {
  return {
    rule_id: RULE_ID,
    rule_key: 'BCR.ZONE.R2G',
    category: 'BUILDING_COVERAGE',
    title: '제2종일반주거지역 건폐율',
    layer: 'L1_NATIONAL',
    review_status: 'approved',
    effective_from: '2026-09-18',
    effective_to: null,
    document: {
      title: '국토의 계획 및 이용에 관한 법률 시행령',
      short_title: '국토계획법 시행령',
      source_type: 'decree',
      promulgation_number: '대통령령 제35000호',
      promulgation_date: '2026-09-10',
      effective_from: '2026-09-18',
      source_url: 'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857',
      fetched_at: '2026-11-10T14:14:54.535555+00:00',
      is_example: false,
    },
    values: [
      {
        value_key: 'ratio',
        value_type: 'max',
        num_min: null,
        num_max: 60,
        num_exact: null,
        enum_value: null,
        formula: null,
        unit: '%',
        condition_note: null,
      },
    ],
    evidence: [
      {
        role: 'value',
        article_label: '제84조제1항제4호',
        article_heading: '용도지역안에서의 건폐율',
        quoted_text: QUOTE,
        char_start: 3,
        char_end: 3 + QUOTE.length,
        provision_text: PROVISION,
        article_full_text: '…',
      },
    ],
    basis_tree: [
      {
        layer: 'L1_NATIONAL',
        document_title: '국토의 계획 및 이용에 관한 법률',
        article_label: '제77조제1항',
        effective_from: '2026-07-01',
        quote: '① 건폐율의 최대한도는 …',
        url: 'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=284013',
        rule_id: null,
      },
      {
        layer: 'L1_NATIONAL',
        document_title: '국토의 계획 및 이용에 관한 법률 시행령',
        article_label: '제84조제1항제4호',
        effective_from: '2026-09-18',
        quote: QUOTE,
        url: 'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857',
        rule_id: RULE_ID,
      },
    ],
    ...overrides,
  };
}

describe('toRuleBasisDetail — 법규 원문 근거', () => {
  it('문서·값·근거·위임 관계를 FE 모양으로 옮긴다', () => {
    const d = toRuleBasisDetail(basisBody());
    expect(d.ruleId).toBe(RULE_ID);
    expect(d.category).toBe('BUILDING_COVERAGE');
    expect(d.layer).toBe('L1_NATIONAL');
    expect(d.effectiveFrom).toBe('2026-09-18');
    expect(d.effectiveTo).toBeNull();
    expect(d.document).toEqual({
      title: '국토의 계획 및 이용에 관한 법률 시행령',
      shortTitle: '국토계획법 시행령',
      sourceType: 'decree',
      promulgationNumber: '대통령령 제35000호',
      promulgationDate: '2026-09-10',
      effectiveFrom: '2026-09-18',
      url: 'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=288857',
      fetchedAt: '2026-11-10',
      isExample: false,
    });
    expect(d.values).toEqual([
      { key: 'ratio', type: 'max', min: null, max: 60, exact: null, enumValue: null, formula: null, unit: '%', conditionNote: null },
    ]);
    expect(d.evidence).toEqual([
      {
        role: 'value',
        articleLabel: '제84조제1항제4호',
        articleHeading: '용도지역안에서의 건폐율',
        quoted: QUOTE,
        provisionText: PROVISION,
        highlight: [3, 3 + QUOTE.length],
      },
    ]);
    expect(d.tree).toHaveLength(2);
    expect(d.tree[0]).toMatchObject({ documentTitle: '국토의 계획 및 이용에 관한 법률', articleLabel: '제77조제1항', ruleId: null });
    expect(d.tree[1].ruleId).toBe(RULE_ID);
  });

  it('원문 링크는 https 만 남긴다 (http · javascript: · 공백 섞인 주소는 버림)', () => {
    const url = (source_url: string | null) =>
      toRuleBasisDetail(basisBody({ document: { ...basisBody().document, source_url } })).document.url;
    expect(url('https://www.law.go.kr/x')).toBe('https://www.law.go.kr/x');
    expect(url('http://www.law.go.kr/x')).toBeNull();
    expect(url('javascript:alert(1)')).toBeNull();
    expect(url('https://www.law.go.kr/a b')).toBeNull();
    expect(url('https://')).toBeNull();
    expect(url(null)).toBeNull();
  });

  it('위임 관계의 규칙 id 는 UUID 모양만 받는다 (주소·API 경로에 그대로 들어가므로)', () => {
    const tree = basisBody().basis_tree ?? [];
    const d = toRuleBasisDetail(
      basisBody({
        basis_tree: [
          { ...tree[1], rule_id: PARENT_RULE_ID },
          { ...tree[1], rule_id: '../../v1/reviews' },
          { ...tree[1], rule_id: 'not-a-uuid' },
          { ...tree[1], rule_id: null },
        ],
      }),
    );
    expect(d.tree.map((n) => n.ruleId)).toEqual([PARENT_RULE_ID, null, null, null]);
  });

  it('값의 종류별 필드(범위·목록·산식·조건)를 그대로 옮기고, 숫자가 아니면 null', () => {
    const d = toRuleBasisDetail(
      basisBody({
        values: [
          { value_key: 'ratio', value_type: 'range', num_min: 100, num_max: 250, unit: '%' },
          { value_key: 'use:2.1', value_type: 'enum_conditional', enum_value: '제2종 근린생활시설', condition_note: '단란주점 제외' },
          { value_key: 'stall', value_type: 'formula', formula: '시설면적 / 134', unit: '대' },
          { value_key: 'odd', value_type: 'max', num_max: Number.NaN },
        ],
      }),
    );
    expect(d.values[0]).toMatchObject({ type: 'range', min: 100, max: 250, unit: '%' });
    expect(d.values[1]).toMatchObject({
      type: 'enum_conditional',
      enumValue: '제2종 근린생활시설',
      conditionNote: '단란주점 제외',
      unit: null,
    });
    expect(d.values[2]).toMatchObject({ type: 'formula', formula: '시설면적 / 134', unit: '대' });
    expect(d.values[3].max).toBeNull();
  });

  it('근거의 역할이 없으면 value, 원문이 없으면 강조 위치도 없다', () => {
    const d = toRuleBasisDetail(
      basisBody({
        evidence: [
          { role: '', article_label: null, quoted_text: QUOTE, char_start: 0, char_end: 5, provision_text: null },
          { role: 'exception', article_label: '제84조제2항', quoted_text: null, provision_text: PROVISION },
        ],
      }),
    );
    expect(d.evidence[0]).toMatchObject({ role: 'value', articleLabel: null, provisionText: null, highlight: null });
    expect(d.evidence[1]).toMatchObject({ role: 'exception', quoted: null, highlight: null });
  });

  it('빠진 묶음(document·values·evidence·basis_tree)은 빈 값으로', () => {
    const d = toRuleBasisDetail({ rule_id: RULE_ID, category: 'X', title: '', layer: '' });
    expect(d.title).toBe('');
    expect(d.layer).toBe('L1_NATIONAL');
    expect(d.document).toMatchObject({ title: '', url: null, isExample: false, promulgationDate: null });
    expect(d.values).toEqual([]);
    expect(d.evidence).toEqual([]);
    expect(d.tree).toEqual([]);
  });
});

describe('evidenceHighlight — 원문 안의 인용 위치', () => {
  it('BE 의 [시작, 끝) 이 원문과 맞으면 그대로', () => {
    expect(evidenceHighlight(PROVISION, QUOTE, 3, 3 + QUOTE.length)).toEqual([3, 3 + QUOTE.length]);
  });

  it('위치가 틀리거나 없으면 인용문을 원문에서 찾아 쓴다', () => {
    expect(evidenceHighlight(PROVISION, QUOTE, 0, QUOTE.length)).toEqual([3, 3 + QUOTE.length]);
    expect(evidenceHighlight(PROVISION, QUOTE, null, null)).toEqual([3, 3 + QUOTE.length]);
    expect(evidenceHighlight(PROVISION, QUOTE, -1, 999)).toEqual([3, 3 + QUOTE.length]);
    expect(evidenceHighlight(PROVISION, QUOTE, '3', '20')).toEqual([3, 3 + QUOTE.length]);
  });

  it('인용문이 원문에 없으면 null (엉뚱한 곳을 강조하지 않음)', () => {
    expect(evidenceHighlight(PROVISION, '제3종일반주거지역', 3, 12)).toBeNull();
    expect(evidenceHighlight(PROVISION, null, 3, 10)).toBeNull();
    expect(evidenceHighlight(null, QUOTE, 3, 10)).toBeNull();
    expect(evidenceHighlight('', QUOTE, 0, 0)).toBeNull();
  });
});

describe('toRuleSummary — 항목 요약의 출처', () => {
  const sentences = [
    { sid: 's1', role: 'headline', text: '원문 기준은 “60퍼센트 이하”예요.' },
    { sid: 's2', text: '역할 없는 문장' },
    { sid: 's3', role: 'caution', text: '' },
  ];

  it('ai_verified: 문장·용어(최대 3개)·검증일·필수 사실 수', () => {
    const s = toRuleSummary({
      display_source: 'ai_verified',
      content: {
        sentences,
        glossary: [
          { term: '건폐율', plain: '대지면적 대비 건축면적' },
          { term: '용적률', plain: '대지면적 대비 연면적' },
          { term: '', plain: '빈 용어' },
          { term: '대지', plain: '건축할 땅' },
          { term: '넷째', plain: '잘림' },
        ],
      },
      verification: { verdict: 'PASS', checked_at: '2026-11-10T14:14:55+00:00', must_mention_total: 5, must_mention_covered: 4 },
    });
    expect(s).toEqual({
      source: 'ai_verified',
      sentences: [
        { role: 'headline', text: '원문 기준은 “60퍼센트 이하”예요.' },
        { role: 'summary', text: '역할 없는 문장' },
      ],
      glossary: [
        { term: '건폐율', plain: '대지면적 대비 건축면적' },
        { term: '용적률', plain: '대지면적 대비 연면적' },
        { term: '대지', plain: '건축할 땅' },
      ],
      checkedAt: '2026-11-10',
      mustMention: { total: 5, covered: 4 },
    });
  });

  it('template_fallback: 원문 인용 요약 — 검증 정보가 없으면 null', () => {
    const s = toRuleSummary({ display_source: 'template_fallback', content: { sentences, glossary: null }, verification: null });
    expect(s?.source).toBe('template_fallback');
    expect(s?.glossary).toEqual([]);
    expect(s?.checkedAt).toBeNull();
    expect(s?.mustMention).toBeNull();
  });

  it('basis_only · 모르는 출처 · 문장 없음 · 설명 없음은 null', () => {
    const empty: BeReviewItem['explanation'] = { display_source: 'ai_verified', content: { sentences: [{ text: '' }] } };
    expect(toRuleSummary({ display_source: 'basis_only', content: null, verification: null })).toBeNull();
    expect(toRuleSummary({ display_source: 'llm_raw', content: { sentences } })).toBeNull();
    expect(toRuleSummary(empty)).toBeNull();
    expect(toRuleSummary({ display_source: 'template_fallback', content: null })).toBeNull();
    expect(toRuleSummary(null)).toBeNull();
  });
});

// ── getRuleBasis: 탭 안 기억 + 중단 ───────────────────────────────────

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** fetch 를 가짜로 — respond 가 돌려주는 Promise 로 응답 시점을 테스트가 정합니다 */
function stubFetch(respond: (url: string) => Promise<Response> | Response) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      calls.push(url);
      return respond(url);
    }),
  );
  return calls;
}

describe('getRuleBasis — 같은 규칙·기준일은 한 번만 받고, 화면이 떠나면 기다림만 끊는다', () => {
  beforeEach(() => resetRuleBasisCache());
  afterEach(() => {
    vi.unstubAllGlobals();
    resetRuleBasisCache();
  });

  it('GET /v1/rules/{id}/basis?as_of_date= 를 부르고 결과를 변환한다', async () => {
    const calls = stubFetch(() => json(200, basisBody()));
    const d = await getRuleBasis(RULE_ID, '2026-11-10');
    expect(d.ruleId).toBe(RULE_ID);
    expect(d.evidence[0].highlight).toEqual([3, 3 + QUOTE.length]);
    expect(calls).toHaveLength(1);
    const url = new URL(calls[0]);
    expect(url.pathname).toBe(`/v1/rules/${RULE_ID}/basis`);
    expect(url.searchParams.get('as_of_date')).toBe('2026-11-10');
  });

  it('같은 규칙·기준일은 동시에 불러도, 다시 불러도 요청 1번', async () => {
    const calls = stubFetch(() => json(200, basisBody()));
    const [a, b] = await Promise.all([getRuleBasis(RULE_ID, '2026-11-10'), getRuleBasis(RULE_ID, '2026-11-10')]);
    const c = await getRuleBasis(RULE_ID, '2026-11-10');
    expect(a).toBe(b);
    expect(c).toBe(a);
    expect(calls).toHaveLength(1);
  });

  it('기준일이 다르면 따로 받는다 · 모양이 틀린 기준일은 빼고 보낸다', async () => {
    const calls = stubFetch(() => json(200, basisBody()));
    await getRuleBasis(RULE_ID, '2026-11-10');
    await getRuleBasis(RULE_ID, '2026-01-01');
    await getRuleBasis(RULE_ID, '2026/11/10');
    await getRuleBasis(RULE_ID, null);
    expect(calls).toHaveLength(3);
    expect(new URL(calls[2]).searchParams.has('as_of_date')).toBe(false);
  });

  it('UUID 가 아닌 규칙 id 는 서버를 부르지 않고 BAD_REQUEST', async () => {
    const calls = stubFetch(() => json(200, basisBody()));
    await expect(getRuleBasis('../reviews', '2026-11-10')).rejects.toMatchObject({ code: 'BAD_REQUEST' });
    await expect(getRuleBasis('', null)).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(0);
  });

  it('실패한 요청은 기억하지 않는다 — 다시 부르면 새로 받는다', async () => {
    let fail = true;
    const calls = stubFetch(() =>
      fail ? json(404, { error: { code: 'RULE_NOT_FOUND', message: '규칙을 찾을 수 없습니다.' } }) : json(200, basisBody()),
    );
    await expect(getRuleBasis(RULE_ID, '2026-11-10')).rejects.toMatchObject({ code: 'NOT_FOUND', beCode: 'RULE_NOT_FOUND' });
    fail = false;
    const d = await getRuleBasis(RULE_ID, '2026-11-10');
    expect(d.ruleId).toBe(RULE_ID);
    expect(calls).toHaveLength(2);
  });

  it('중단하면 그 화면은 AbortError 로 끝나지만, 받는 중인 원문은 버리지 않고 다음 화면이 이어받는다', async () => {
    let release: (res: Response) => void = () => {};
    const calls = stubFetch(() => new Promise<Response>((resolve) => (release = resolve)));
    const controller = new AbortController();
    const first = getRuleBasis(RULE_ID, '2026-11-10', controller.signal);
    controller.abort();
    await expect(first).rejects.toMatchObject({ name: 'AbortError' });

    const second = getRuleBasis(RULE_ID, '2026-11-10');
    release(json(200, basisBody()));
    expect((await second).ruleId).toBe(RULE_ID);
    expect(calls).toHaveLength(1);
  });

  it('이미 중단된 신호로 부르면 바로 AbortError', async () => {
    stubFetch(() => json(200, basisBody()));
    const controller = new AbortController();
    controller.abort();
    await expect(getRuleBasis(RULE_ID, '2026-11-10', controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
  });
});
