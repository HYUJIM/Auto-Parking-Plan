import { describe, expect, it } from 'vitest';
import { dxfText, writeDxf, type DxfEntity, type DxfLayer } from './dxf';

/** DXF 를 (그룹 코드, 값) 쌍으로 읽습니다 — 두 줄씩 짝이 맞아야 올바른 파일입니다 */
function pairs(dxf: string): [number, string][] {
  const lines = dxf.split('\n');
  expect(lines.at(-1)).toBe('');
  lines.pop();
  expect(lines.length % 2).toBe(0);
  const out: [number, string][] = [];
  for (let i = 0; i < lines.length; i += 2) {
    const code = Number(lines[i]);
    expect(Number.isInteger(code)).toBe(true);
    out.push([code, lines[i + 1]]);
  }
  return out;
}

const LAYERS: DxfLayer[] = [
  { name: 'BOUNDARY', color: 5 },
  { name: 'TEXT', color: 7 },
];

describe('writeDxf', () => {
  it('R12 머리말·계층 표·도형·끝 표시를 순서대로 쓴다', () => {
    const entities: DxfEntity[] = [
      {
        kind: 'polyline',
        layer: 'BOUNDARY',
        closed: true,
        points: [
          { x: 0, y: 0 },
          { x: 10, y: 0 },
          { x: 10, y: 5 },
        ],
      },
      { kind: 'text', layer: 'TEXT', at: { x: 1, y: 1 }, height: 1.5, text: '대지 경계' },
    ];
    const p = pairs(writeDxf(LAYERS, entities));
    const values = p.map(([, v]) => v);
    expect(p[0]).toEqual([0, 'SECTION']);
    expect(values).toContain('AC1009');
    expect(p.at(-1)).toEqual([0, 'EOF']);
    expect(values.filter((v) => v === 'SECTION')).toHaveLength(3);
    expect(values.filter((v) => v === 'ENDSEC')).toHaveLength(3);
    // 계층 표에 0 + 정의한 2개
    expect(p.filter(([c, v]) => c === 0 && v === 'LAYER')).toHaveLength(3);
    // 닫힌 폴리라인 1개 + 꼭짓점 3개 + SEQEND
    expect(values.filter((v) => v === 'POLYLINE')).toHaveLength(1);
    expect(values.filter((v) => v === 'VERTEX')).toHaveLength(3);
    expect(values.filter((v) => v === 'SEQEND')).toHaveLength(1);
    const polyIdx = p.findIndex(([c, v]) => c === 0 && v === 'POLYLINE');
    expect(p.slice(polyIdx, polyIdx + 8).find(([c]) => c === 70)?.[1]).toBe('1');
    // 범위
    const extMax = p.findIndex(([c, v]) => c === 9 && v === '$EXTMAX');
    expect(p[extMax + 1]).toEqual([10, '10.000']);
    expect(p[extMax + 2]).toEqual([20, '5.000']);
    // 한글은 \U+ 표기
    expect(values).toContain('\\U+B300\\U+C9C0 \\U+ACBD\\U+ACC4');
  });

  it('한 줄 값만 쓴다: 줄바꿈·제어 문자는 공백, ASCII 밖은 \\U+ 표기', () => {
    expect(dxfText('A\nB\r\tC')).toBe('A B  C');
    expect(dxfText('주차 12면')).toBe('\\U+C8FC\\U+CC28 12\\U+BA74');
    expect(dxfText('😀x')).toBe('?x');
  });

  it('계층 이름·색·정의하지 않은 계층·유한하지 않은 좌표는 거절한다', () => {
    expect(() => writeDxf([{ name: 'bad name', color: 1 }], [])).toThrow();
    expect(() => writeDxf([{ name: 'OK', color: 0 }], [])).toThrow();
    expect(() => writeDxf(LAYERS, [{ kind: 'circle', layer: 'NOPE', center: { x: 0, y: 0 }, radius: 1 }])).toThrow();
    expect(() => writeDxf(LAYERS, [{ kind: 'circle', layer: 'TEXT', center: { x: Number.NaN, y: 0 }, radius: 1 }])).toThrow();
  });

  it('빈 도형 목록도 올바른 파일이다', () => {
    const p = pairs(writeDxf(LAYERS, []));
    expect(p.at(-1)).toEqual([0, 'EOF']);
  });
});
