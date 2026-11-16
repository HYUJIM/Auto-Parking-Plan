import type { BasisNode, LandUse, ParkingSimulation, RuleItem, RuleReview, RuleStatus } from '@/lib/api/types';
import { isRuleStatus } from '@/lib/api/types';
import { isParkingNotPermitted } from '@/lib/api/review';

/**
 * 주차장을 둘 수 있는지에 대한 결과 페이지의 판정과 그 근거.
 *
 * 판정은 BE 가 합니다(배치 계산 응답의 eligibility). 화면은 판정을 다시 하지 않고, 그 판정이 어디서 왔는지를
 * 검토 당시의 필지 스냅숏(지목·토지이용계획)과 '검토 가능한 주요 용도' 항목의 근거로 설명만 붙입니다.
 *  - 지목 때문: 지목이 도로·하천 같은 공공시설 용지 (BE ParkingBridge.PUBLIC_FACILITY_LAND 와 같은 목록)
 *  - 용도지역 때문: 그 용도지역에서 건축할 수 있는 용도 목록에 주차장 관련 용도가 없음
 */

/** BE ParkingBridge.PUBLIC_FACILITY_LAND 와 같아야 합니다 */
export const PUBLIC_FACILITY_LAND: readonly string[] = ['도로', '철도용지', '하천', '제방', '구거', '유지', '수도용지'];

/** 지목의 법적 구분 (공간정보의 구축 및 관리 등에 관한 법률 제67조 지목의 종류) */
export const LAND_CATEGORY_LAW = {
  title: '공간정보의 구축 및 관리 등에 관한 법률',
  article: '제67조(지목의 종류)',
  url: 'https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EA%B3%B5%EA%B0%84%EC%A0%95%EB%B3%B4%EC%9D%98%EA%B5%AC%EC%B6%95%EB%B0%8F%EA%B4%80%EB%A6%AC%EB%93%B1%EC%97%90%EA%B4%80%ED%95%9C%EB%B2%95%EB%A5%A0/%EC%A0%9C67%EC%A1%B0',
} as const;

export type ParkingVerdict =
  | {
      kind: 'not_permitted';
      /** land_category: 지목 · zoning: 용도지역의 허용 용도 */
      cause: 'land_category' | 'zoning';
      reason: string | null;
      landCategory: string | null;
      zoning: string[];
      landUses: LandUse[] | null;
      /** 용도지역 근거 (검토 가능한 주요 용도 항목의 근거 트리) */
      zoningBasis: BasisNode[];
      zoningItem: RuleItem | null;
    }
  | {
      kind: 'possible';
      status: RuleStatus;
      /** 주차장 관련으로 판단한 용도 이름 */
      useName: string | null;
      reason: string | null;
      /** 확인이 끝나지 않아 참고용 배치만 */
      referenceOnly: boolean;
    }
  | { kind: 'unknown' };

export function zoningNames(landUses: LandUse[] | null): string[] {
  const out: string[] = [];
  for (const u of landUses ?? []) if (u.kind === 'zoning' && u.relation !== '접함' && !out.includes(u.name)) out.push(u.name);
  return out;
}

export function parkingVerdict(review: RuleReview | null, sim: ParkingSimulation | null): ParkingVerdict {
  if (!sim) return { kind: 'unknown' };
  const zoningItem = review?.items.find((i) => i.id === 'ZONING_USE') ?? null;
  if (isParkingNotPermitted(sim)) {
    const landCategory = review?.parcel.landCategory ?? null;
    const byLand = landCategory !== null && PUBLIC_FACILITY_LAND.includes(landCategory.trim());
    const landUses = review?.parcel.landUses ?? null;
    return {
      kind: 'not_permitted',
      cause: byLand ? 'land_category' : 'zoning',
      reason: sim.eligibility.reason,
      landCategory,
      zoning: zoningNames(landUses),
      landUses,
      zoningBasis: zoningItem?.basis ?? [],
      zoningItem,
    };
  }
  const status = isRuleStatus(sim.eligibility.status) ? sim.eligibility.status : 'MANUAL_CHECK';
  return {
    kind: 'possible',
    status,
    useName: sim.eligibility.useName,
    reason: sim.eligibility.reason,
    referenceOnly: sim.referenceOnly || status !== 'REVIEWABLE',
  };
}
