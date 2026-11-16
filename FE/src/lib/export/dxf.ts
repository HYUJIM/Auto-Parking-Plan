import type { TmPoint } from './tm';

/**
 * 아주 작은 DXF(AutoCAD R12, ASCII) 작성기.
 * R12 는 거의 모든 CAD(AutoCAD · ZWCAD · BricsCAD · LibreCAD · QCAD)가 읽는 가장 오래된 공통 형식입니다.
 * 좌표 단위는 m. 한글은 R12 의 코드페이지 문제를 피하려고 `\U+XXXX` 유니코드 표기로 씁니다 (AutoCAD 계열이 그대로 한글로 보여 줍니다).
 */

/** AutoCAD 색 번호 (ACI): 1 빨강 · 2 노랑 · 3 초록 · 4 하늘 · 5 파랑 · 6 자홍 · 7 흰/검 · 8 회색 */
export interface DxfLayer {
  /** 영문 대문자·숫자·밑줄만 (R12 계층 이름 규칙) */
  name: string;
  color: number;
}

export type DxfEntity =
  | { kind: 'polyline'; layer: string; points: TmPoint[]; closed: boolean }
  | { kind: 'text'; layer: string; at: TmPoint; height: number; text: string }
  | { kind: 'circle'; layer: string; center: TmPoint; radius: number };

const LAYER_NAME = /^[A-Z0-9_]{1,31}$/;

/** 숫자는 mm 까지 (도면 좌표로 충분하고 파일이 작아집니다) */
function num(n: number): string {
  if (!Number.isFinite(n)) throw new Error('DXF 좌표가 유한한 수가 아닙니다');
  const s = n.toFixed(3);
  return s === '-0.000' ? '0.000' : s;
}

/** 줄바꿈·제어 문자를 지우고, ASCII 밖의 글자는 \U+XXXX 로 (DXF 값은 한 줄이어야 합니다) */
export function dxfText(text: string): string {
  let out = '';
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp < 0x20 || cp === 0x7f) {
      out += ' ';
    } else if (cp < 0x80) {
      out += ch;
    } else if (cp <= 0xffff) {
      out += `\\U+${cp.toString(16).toUpperCase().padStart(4, '0')}`;
    } else {
      out += '?';
    }
  }
  return out.trim();
}

function pair(code: number, value: string | number): string {
  return `${code}\n${value}\n`;
}

function extents(entities: DxfEntity[]): { min: TmPoint; max: TmPoint } {
  // 큰 배치(주차구획 수천 개)에서도 인자 개수 제한에 걸리지 않게 반복문으로 셉니다
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  const add = (x: number, y: number) => {
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
  };
  for (const e of entities) {
    if (e.kind === 'polyline') {
      for (const p of e.points) add(p.x, p.y);
    } else if (e.kind === 'text') {
      add(e.at.x, e.at.y);
    } else {
      add(e.center.x - e.radius, e.center.y - e.radius);
      add(e.center.x + e.radius, e.center.y + e.radius);
    }
  }
  if (minX === Infinity) return { min: { x: 0, y: 0 }, max: { x: 0, y: 0 } };
  return { min: { x: minX, y: minY }, max: { x: maxX, y: maxY } };
}

export function writeDxf(layers: DxfLayer[], entities: DxfEntity[]): string {
  const names = new Set<string>();
  for (const l of layers) {
    if (!LAYER_NAME.test(l.name)) throw new Error(`DXF 계층 이름이 올바르지 않습니다: ${l.name}`);
    if (!Number.isInteger(l.color) || l.color < 1 || l.color > 255) throw new Error(`DXF 색 번호가 올바르지 않습니다: ${l.color}`);
    names.add(l.name);
  }
  for (const e of entities) {
    if (!names.has(e.layer)) throw new Error(`정의하지 않은 계층입니다: ${e.layer}`);
  }
  const ext = extents(entities);

  let s = '';
  s += pair(0, 'SECTION') + pair(2, 'HEADER');
  s += pair(9, '$ACADVER') + pair(1, 'AC1009');
  s += pair(9, '$INSBASE') + pair(10, num(0)) + pair(20, num(0)) + pair(30, num(0));
  s += pair(9, '$EXTMIN') + pair(10, num(ext.min.x)) + pair(20, num(ext.min.y)) + pair(30, num(0));
  s += pair(9, '$EXTMAX') + pair(10, num(ext.max.x)) + pair(20, num(ext.max.y)) + pair(30, num(0));
  s += pair(0, 'ENDSEC');

  s += pair(0, 'SECTION') + pair(2, 'TABLES');
  s += pair(0, 'TABLE') + pair(2, 'LTYPE') + pair(70, 1);
  s += pair(0, 'LTYPE') + pair(2, 'CONTINUOUS') + pair(70, 0) + pair(3, 'Solid line') + pair(72, 65) + pair(73, 0) + pair(40, num(0));
  s += pair(0, 'ENDTAB');
  s += pair(0, 'TABLE') + pair(2, 'LAYER') + pair(70, layers.length + 1);
  s += pair(0, 'LAYER') + pair(2, '0') + pair(70, 0) + pair(62, 7) + pair(6, 'CONTINUOUS');
  for (const l of layers) s += pair(0, 'LAYER') + pair(2, l.name) + pair(70, 0) + pair(62, l.color) + pair(6, 'CONTINUOUS');
  s += pair(0, 'ENDTAB');
  s += pair(0, 'ENDSEC');

  s += pair(0, 'SECTION') + pair(2, 'ENTITIES');
  for (const e of entities) {
    if (e.kind === 'polyline') {
      if (e.points.length < 2) continue;
      s +=
        pair(0, 'POLYLINE') +
        pair(8, e.layer) +
        pair(66, 1) +
        pair(10, num(0)) +
        pair(20, num(0)) +
        pair(30, num(0)) +
        pair(70, e.closed ? 1 : 0);
      for (const p of e.points) s += pair(0, 'VERTEX') + pair(8, e.layer) + pair(10, num(p.x)) + pair(20, num(p.y)) + pair(30, num(0));
      s += pair(0, 'SEQEND') + pair(8, e.layer);
    } else if (e.kind === 'text') {
      const text = dxfText(e.text);
      if (!text) continue;
      s +=
        pair(0, 'TEXT') +
        pair(8, e.layer) +
        pair(10, num(e.at.x)) +
        pair(20, num(e.at.y)) +
        pair(30, num(0)) +
        pair(40, num(e.height)) +
        pair(1, text);
    } else {
      s +=
        pair(0, 'CIRCLE') +
        pair(8, e.layer) +
        pair(10, num(e.center.x)) +
        pair(20, num(e.center.y)) +
        pair(30, num(0)) +
        pair(40, num(e.radius));
    }
  }
  s += pair(0, 'ENDSEC');
  s += pair(0, 'EOF');
  return s;
}
