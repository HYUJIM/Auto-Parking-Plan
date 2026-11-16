import { describe, expect, it } from 'vitest';
import { fromTm, toTm } from './tm';

/**
 * 기준값: Snyder(1987) 의 횡메르카토르 급수식으로 따로 구현해 계산한 EPSG:5186 좌표.
 * 두 식은 원점에서 2° 안에서 1cm 안쪽으로 일치해야 합니다 (Snyder 식 자체의 오차가 그 정도).
 */
const REFERENCE: [number, number, number, number][] = [
  [38, 127, 200000.0, 600000.0],
  [35.8582, 128.6305, 347280.9155175888, 363538.6254551561],
  [35.842, 128.648, 348892.0967785759, 361767.4011078151],
  [37.5665, 126.978, 198056.36673702704, 551885.0305822184],
  [33.5, 126.5, 153538.4958065813, 100816.37630224926],
];

describe('위경도 → EPSG:5186 (중부원점 TM)', () => {
  it('원점은 가산값 그대로', () => {
    const p = toTm({ lat: 38, lng: 127 });
    expect(p.x).toBeCloseTo(200000, 6);
    expect(p.y).toBeCloseTo(600000, 6);
  });

  it.each(REFERENCE)('%f, %f → 독립 구현과 1cm 안쪽으로 일치', (lat, lng, x, y) => {
    const p = toTm({ lat, lng });
    expect(Math.abs(p.x - x)).toBeLessThan(0.01);
    expect(Math.abs(p.y - y)).toBeLessThan(0.01);
  });

  it('역변환하면 원래 위경도로 돌아온다 (1e-9° ≈ 0.1mm)', () => {
    for (const [lat, lng] of REFERENCE) {
      const back = fromTm(toTm({ lat, lng }));
      expect(Math.abs(back.lat - lat)).toBeLessThan(1e-9);
      expect(Math.abs(back.lng - lng)).toBeLessThan(1e-9);
    }
  });

  it('가까운 두 점의 TM 거리는 실제 거리와 같다 (수성구, 축척계수 오차 0.05% 안)', () => {
    const a = toTm({ lat: 35.8582, lng: 128.6305 });
    const b = toTm({ lat: 35.8582, lng: 128.6315 }); // 경도 0.001° ≈ 90.2m
    const d = Math.hypot(b.x - a.x, b.y - a.y);
    const expected =
      ((Math.PI / 180) * 0.001 * 6378137 * Math.cos((35.8582 * Math.PI) / 180)) /
      Math.sqrt(1 - 0.00669438 * Math.sin((35.8582 * Math.PI) / 180) ** 2);
    expect(Math.abs(d - expected) / expected).toBeLessThan(5e-4);
  });
});
