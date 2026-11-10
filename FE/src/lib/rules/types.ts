import type { ManualCheck, RuleEvidence } from '@/lib/api/types';

/**
 * src/mock/rules.json 의 모양. `rulesJson satisfies RulesFile` 로 읽어 키 오타·누락을 컴파일 때 잡습니다.
 * 판정에 쓰는 숫자(폭·접도 길이·주차 1면 크기 …)는 전부 여기에 두고, 문구는 ko.json 템플릿이 같은 값을 받아 채웁니다.
 */
export interface ZoningRule {
  /** 건폐율 상한 (%) */
  bcr: number;
  /** 용적률 상한 (%) */
  far: number;
  /** 'decree' = 국토계획법 시행령 상한값 (대구 조례 확인 전 → '확인 필요') */
  basis?: string;
}

export interface RoadAccessRule {
  /** 토지특성 '도로접면' 문구에 포함되면 적용 (광대·중로·소로·(가)·(불)·맹지) */
  match: string;
  /** 추정 도로 폭 (m) */
  width: number;
  label: string;
}

export interface RulesFile {
  _note?: string;
  queriedAt: string;
  providers: string[];
  zoning: Record<string, ZoningRule>;
  parking: {
    slot: { width: number; length: number };
    /** 차로 포함 1면당 면적 (㎡) */
    slotAreaIncludingLane: number;
    /** 대지면적 중 주차에 쓸 수 있다고 보는 비율 (0~1) */
    usableRatio: number;
    /** 직각주차 차로 폭 (m) */
    laneWidthRequired: number;
    /** 이 폭 이상이면 간선도로로 보고 진출입 허가 검토 (m) */
    arterialWidth: number;
  };
  road: {
    /** 건축법상 도로 최소 폭 (m) */
    minWidth: number;
    /** 대지가 도로에 접해야 하는 최소 길이 (m) */
    minFrontage: number;
    /** 도로접면으로 추정할 때 접도·차로 폭을 적합으로 보는 폭 (m) */
    inferredOkWidth: number;
    /** 교차로·횡단보도에서 출입구를 떨어뜨려야 하는 거리 (m) — 문구용 */
    intersectionClearance: number;
  };
  roadAccess: RoadAccessRule[];
  evidence: Record<'bcr' | 'far' | 'bcrDecree' | 'farDecree' | 'road' | 'parking' | 'laneWidth' | 'access', RuleEvidence>;
  manualChecks: ManualCheck[];
}
