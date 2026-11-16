import { describe, expect, it } from 'vitest';
import { isDetailCategory, isManualCategory, isRuleCardHash, parseRuleDetailParams, ruleCardId } from './params';
import { reportHref, ruleDetailHref } from '@/lib/report/links';

const PNU = '2726010100190010000';
const REVIEW = '01a1c5cf-d680-719d-a82c-612c939d4dbd';

describe('법규 상세 주소 검사', () => {
  it('항목 이름은 핵심 6항목 + 별도 확인 4항목만', () => {
    for (const c of [
      'ZONING_USE',
      'BUILDING_COVERAGE',
      'FLOOR_AREA_RATIO',
      'PARKING_REQUIREMENT',
      'PARKING_STALL_SIZE',
      'DRIVE_AISLE_WIDTH',
    ]) {
      expect(isDetailCategory(c)).toBe(true);
      expect(isManualCategory(c)).toBe(false);
    }
    for (const c of ['ROAD_ACCESS', 'SETBACK', 'HEIGHT_SUNLIGHT', 'SPECIAL_ZONE']) {
      expect(isDetailCategory(c)).toBe(true);
      expect(isManualCategory(c)).toBe(true);
    }
    for (const c of ['zoning_use', 'ZONING_USE ', '<script>', '', undefined, null, 'toString']) expect(isDetailCategory(c)).toBe(false);
  });

  it('모양이 맞는 값만 남기고 나머지는 null', () => {
    expect(parseRuleDetailParams('BUILDING_COVERAGE', new URLSearchParams({ pnu: PNU, review: REVIEW }))).toEqual({
      category: 'BUILDING_COVERAGE',
      pnu: PNU,
      reviewId: REVIEW,
    });
    expect(parseRuleDetailParams('<b>x</b>', new URLSearchParams({ pnu: '123', review: 'abc' }))).toEqual({
      category: null,
      pnu: null,
      reviewId: null,
    });
    expect(parseRuleDetailParams(undefined, new URLSearchParams())).toEqual({ category: null, pnu: null, reviewId: null });
  });
});

describe('원문 카드 앵커 (#rule-<규칙 id>)', () => {
  it('카드 id 와 주소의 # 가 같은 모양', () => {
    expect(ruleCardId(REVIEW)).toBe(`rule-${REVIEW}`);
    expect(isRuleCardHash(`#${ruleCardId(REVIEW)}`)).toBe(true);
  });

  it('UUID 가 아니거나 다른 # 이면 원문 카드가 아니다', () => {
    for (const h of ['', '#', '#rule-', '#rule-abc', `#rule-${REVIEW}x`, `rule-${REVIEW}`, `#top`, `#rule-<img src=x>`]) {
      expect(isRuleCardHash(h)).toBe(false);
    }
  });
});

describe('링크 만들기 — 모양이 맞는 값만 싣는다', () => {
  it('결과 리포트', () => {
    expect(reportHref(PNU, REVIEW)).toBe(`/report?pnu=${PNU}&review=${REVIEW}`);
    expect(reportHref(PNU, 'javascript:alert(1)')).toBe(`/report?pnu=${PNU}`);
    expect(reportHref('', REVIEW)).toBe(`/report?review=${REVIEW}`);
    expect(reportHref('')).toBe('/report');
  });

  it('법규 상세', () => {
    expect(ruleDetailHref('SETBACK', PNU, REVIEW)).toBe(`/report/rules/SETBACK?pnu=${PNU}&review=${REVIEW}`);
    expect(ruleDetailHref('ZONING_USE', PNU)).toBe(`/report/rules/ZONING_USE?pnu=${PNU}`);
    expect(ruleDetailHref('../admin', PNU)).toBeNull();
  });
});
