import { describe, expect, it } from 'vitest';
import { isRuleCategory, reportHref, ruleDetailHref, siteHref } from './links';

const PNU = '2726010100190010000';
const ID = '01a1c5cf-d680-719d-a82c-612c939d4dbd';

describe('리포트 주소', () => {
  it('결과 리포트: 검사한 PNU·검토 id 만 붙인다', () => {
    expect(reportHref(PNU, ID)).toBe(`/report?pnu=${PNU}&review=${ID}`);
    expect(reportHref(PNU)).toBe(`/report?pnu=${PNU}`);
    expect(reportHref(PNU, 'not-a-uuid')).toBe(`/report?pnu=${PNU}`);
    expect(reportHref('123<script>', ID)).toBe(`/report?review=${ID}`);
  });

  it('법규 상세: 항목 코드는 대문자·숫자·밑줄만', () => {
    expect(ruleDetailHref('ZONING_USE', PNU, ID)).toBe(`/report/rules/ZONING_USE?pnu=${PNU}&review=${ID}`);
    expect(ruleDetailHref('ROAD_ACCESS', PNU, null)).toBe(`/report/rules/ROAD_ACCESS?pnu=${PNU}`);
    expect(ruleDetailHref('../x', PNU, ID)).toBeNull();
    expect(isRuleCategory('zoning_use')).toBe(false);
    expect(isRuleCategory('SOMETHING_ELSE')).toBe(false);
    expect(ruleDetailHref('UNKNOWN_CODE', PNU, ID)).toBeNull();
  });

  it('이용화면: PNU 가 이상하면 처음 화면', () => {
    expect(siteHref(PNU)).toBe(`/site?pnu=${PNU}`);
    expect(siteHref('x')).toBe('/app');
  });
});
