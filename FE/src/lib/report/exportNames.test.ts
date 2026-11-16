import { describe, expect, it } from 'vitest';
import { dxfFilename, dxfNotes, dxfTitle, pdfTitle, regionLabel, reportAddress } from './exportNames';

const PNU = '2726010100190010000';

describe('리포트 이름', () => {
  it('제목 주소: 도로명 → 지번 → 빈 값', () => {
    expect(reportAddress({ road: '대구광역시 수성구 범어예시로 12', jibun: '대구광역시 수성구 범어동 9001' })).toBe(
      '대구광역시 수성구 범어예시로 12',
    );
    expect(reportAddress({ road: ' ', jibun: '대구광역시 수성구 범어동 9001' })).toBe('대구광역시 수성구 범어동 9001');
    expect(reportAddress(null)).toBe('');
  });

  it('지역: 시도 + 시군구', () => {
    expect(regionLabel('대구광역시 수성구 범어동 9001')).toBe('대구광역시 수성구');
    expect(regionLabel('경기도 성남시 분당구 정자동 1')).toBe('경기도 성남시 분당구');
    expect(regionLabel('서울특별시 강남구 역삼동 1', '강남구')).toBe('서울특별시 강남구');
    expect(regionLabel('범어동 9001', '수성구')).toBe('수성구');
    expect(regionLabel('', null)).toBe('');
  });

  it('PDF 문서 제목 = 저장 파일 이름: 주차검토_<주소>_<기준일>, 쓸 수 없는 문자는 밑줄', () => {
    expect(pdfTitle('대구광역시 수성구 범어동 9001', PNU, '2026-11-10')).toBe('주차검토_대구광역시 수성구 범어동 9001_2026-11-10');
    expect(pdfTitle('범어동 1/2', PNU, null)).toBe('주차검토_범어동 1_2');
    expect(pdfTitle('', PNU, '2026-11-10')).toBe(`주차검토_${PNU}_2026-11-10`);
  });

  it('DXF 파일 이름·도면 제목', () => {
    const alt = { code: 'ALT_A', stallCount: 16 };
    expect(dxfFilename('대구광역시 수성구 범어동 9001', PNU, alt)).toBe('대구광역시 수성구 범어동 9001_배치안A.dxf');
    expect(dxfFilename('범어동 "9001"', PNU, null)).toBe('범어동 _9001__대지경계.dxf');
    expect(dxfFilename('대구광역시 수성구 범어동 9001', PNU, alt, true)).toBe('대구광역시 수성구 범어동 9001_배치안A_철거가정.dxf');
    expect(dxfFilename('범어동 9001', PNU, null, true)).toBe('범어동 9001_대지경계.dxf');
    expect(dxfTitle('범어동 9001', PNU, alt)).toBe('범어동 9001 · 배치안 A · 주차 16대');
    expect(dxfTitle('범어동 9001', PNU, null)).toBe('범어동 9001');
  });

  it('DXF 안내 줄: 좌표계 · PNU·기준일 · (참고 배치) · 고지', () => {
    expect(dxfNotes(PNU, '2026-11-10')).toEqual([
      '좌표계 EPSG:5186 (GRS80 중부원점 TM), 단위 m',
      `PNU ${PNU} · 기준일 2026-11-10`,
      '참고용 · 최종 확인은 인허가 기관',
    ]);
    const ref = dxfNotes(PNU, null, true);
    expect(ref[1]).toBe(`PNU ${PNU}`);
    expect(ref).toHaveLength(4);
    expect(ref[2]).toContain('참고 배치');
  });

  it('DXF 안내 줄: 기존 건물을 피해서 배치했는지 · 철거 가정인지 (고지 바로 앞)', () => {
    expect(dxfNotes(PNU, '2026-11-10', false, 'applied').slice(-2)).toEqual(['기존 건물: 피해서 배치', '참고용 · 최종 확인은 인허가 기관']);
    expect(dxfNotes(PNU, '2026-11-10', true, 'ignored')).toHaveLength(5);
    expect(dxfNotes(PNU, '2026-11-10', true, 'ignored')[3]).toBe('기존 건물: 철거 가정');
    expect(dxfNotes(PNU, '2026-11-10', false, 'unavailable')[2]).toContain('불러오지 못해');
    expect(dxfNotes(PNU, '2026-11-10', false, null)).toHaveLength(3);
  });
});
