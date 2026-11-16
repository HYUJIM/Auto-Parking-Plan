import { describe, expect, it } from 'vitest';
import { safeFilename } from './download';

describe('safeFilename', () => {
  it('경로 구분자·예약 문자·제어 문자를 밑줄로 바꾸고 길이를 제한한다', () => {
    expect(safeFilename('범어동 1/2:배치안*A?.dxf')).toBe('범어동 1_2_배치안_A_.dxf');
    expect(safeFilename('..\\..\\x\n.dxf')).toBe('.._.._x_.dxf');
    expect(safeFilename('   ')).toBe('download');
    expect(safeFilename('가'.repeat(200))).toHaveLength(120);
  });
});
