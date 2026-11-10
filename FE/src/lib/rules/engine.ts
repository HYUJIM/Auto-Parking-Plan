import rulesJson from '@/mock/rules.json';
import type { ManualCheckResult, Parcel, RuleEvidence, RuleItem, RuleReview } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { formatArea, formatInt, formatRoad } from '@/lib/format';
import type { RulesFile } from './types';

/**
 * 법규 검토 엔진 — 순수 함수. 필지 값 + 기준표(rules.json) → 핵심 6항목 · 별도 확인 4항목.
 *
 *  - 판정 숫자는 rules.json 에서만 읽고, 사람이 읽는 문구는 ko.json `site.rules.items` 템플릿에 같은 숫자를 넣어 만듭니다.
 *    그래서 기준값을 바꾸면 판정과 문구가 함께 바뀌고, 둘이 어긋날 수 없습니다.
 *  - 폭·접도 길이가 없는 브이월드 필지는 토지특성 '도로접면'으로 폭을 추정하고 '확인 필요' + 추정 단서를 붙입니다.
 *  - 대구 조례 표에 없는 용도지역은 값 없이 '확인 필요' 만, basis=decree(조례 값 미확인) 항목은 시행령 상한값 + '확인 필요'.
 *  이후: 자체 법규 DB + BE 판정 결과(POST /v1/reviews)로 교체.
 */
export const rules = rulesJson satisfies RulesFile;

export interface RoadFacts {
  /** 폭 (m). 모르면 null */
  width: number | null;
  frontage: number | null;
  /** 설명 문구 */
  label: string;
  /** 도로접면에서 추정한 값인지 */
  inferred: boolean;
}

export function roadFacts(parcel: Parcel, r: RulesFile = rules): RoadFacts {
  const t = content.site.rules.items.road;
  const road = parcel.road;
  const measured = formatRoad(road);
  if (measured !== null && typeof road.width === 'number') {
    return { width: road.width, frontage: road.frontage ?? null, label: measured, inferred: false };
  }
  const access = road.access;
  if (access) {
    const rule = r.roadAccess.find((a) => access.includes(a.match));
    if (rule) return { width: rule.width, frontage: null, label: fmt(t.inferred, { access, label: rule.label }), inferred: true };
    return { width: null, frontage: null, label: fmt(t.accessOnly, { access }), inferred: true };
  }
  return { width: null, frontage: null, label: t.unknown, inferred: true };
}

export function buildRuleItems(parcel: Parcel, r: RulesFile = rules): RuleItem[] {
  const t = content.site.rules.items;
  const { area } = parcel;
  const { parking } = r;
  const zoning = parcel.zoning ? r.zoning[parcel.zoning] : undefined;
  const decree = !zoning || zoning.basis === 'decree';
  const road = roadFacts(parcel, r);
  const inferredNote = road.inferred ? t.road.inferredNote : undefined;
  const lane = parking.laneWidthRequired.toFixed(1);

  const slots = Math.max(1, Math.floor((area * parking.usableRatio) / parking.slotAreaIncludingLane));

  const roadStatus: RuleItem['status'] =
    road.width === null
      ? 'check'
      : road.inferred
        ? road.width >= r.road.inferredOkWidth
          ? 'ok'
          : 'check'
        : road.width >= r.road.minWidth && (road.frontage ?? 0) >= r.road.minFrontage
          ? 'ok'
          : 'check';
  // 측정 폭이 기준 폭과 같거나 작으면 막다른 도로·확폭 여부를 현장에서 확인
  const narrowNote = !road.inferred && road.width !== null && road.width <= r.road.minWidth ? t.road.narrowNote : undefined;

  const laneOk = road.width !== null && road.width >= (road.inferred ? r.road.inferredOkWidth : parking.laneWidthRequired);

  const accessIssue =
    parcel.nearIntersection === true
      ? t.access.nearIntersection
      : road.width !== null && road.width >= parking.arterialWidth
        ? fmt(t.access.arterial, { width: road.width })
        : parcel.nearIntersection === undefined
          ? t.access.unknownIntersection
          : null;

  const ratioItem = (id: 'bcr' | 'far', value: number | undefined, ev: RuleEvidence, evDecree: RuleEvidence): RuleItem => {
    const title = t[id].title;
    if (!zoning || value === undefined) {
      return {
        id,
        title,
        standard: t.ratio.standardUnknown,
        applied: parcel.zoning ? fmt(t.ratio.appliedNotInTable, { zoning: parcel.zoning }) : t.ratio.appliedZoningUnknown,
        status: 'check',
        evidence: evDecree,
      };
    }
    return {
      id,
      title,
      standard: fmt(decree ? t.ratio.standardDecree : t.ratio.standard, { value }),
      applied: fmt(t.ratio.applied, { areaLabel: t[id].areaLabel, area: formatArea((area * value) / 100) }),
      status: decree ? 'check' : 'ok',
      evidence: decree ? evDecree : ev,
      note: decree ? t.ratio.decreeNote : undefined,
    };
  };

  return [
    ratioItem('bcr', zoning?.bcr, r.evidence.bcr, r.evidence.bcrDecree),
    ratioItem('far', zoning?.far, r.evidence.far, r.evidence.farDecree),
    {
      id: 'road',
      title: t.road.title,
      standard: fmt(t.road.standard, { minWidth: r.road.minWidth, minFrontage: r.road.minFrontage }),
      applied: road.label,
      status: roadStatus,
      evidence: r.evidence.road,
      note: inferredNote ?? narrowNote,
    },
    {
      id: 'parking',
      title: t.parking.title,
      standard: fmt(t.parking.standard, {
        slotWidth: parking.slot.width,
        slotLength: parking.slot.length.toFixed(1),
        slotArea: parking.slotAreaIncludingLane,
      }),
      applied: fmt(t.parking.applied, { slots: formatInt(slots), usablePct: Math.round(parking.usableRatio * 100) }),
      status: 'ok',
      evidence: r.evidence.parking,
    },
    {
      id: 'lane-width',
      title: t.laneWidth.title,
      standard: fmt(t.laneWidth.standard, { lane }),
      applied: laneOk
        ? fmt(t.laneWidth.appliedOk, { lane })
        : road.width === null
          ? fmt(t.laneWidth.appliedUnknown, { lane })
          : fmt(t.laneWidth.appliedNarrow, { width: road.width, lane }),
      status: laneOk ? 'ok' : 'check',
      evidence: r.evidence.laneWidth,
      note: inferredNote,
    },
    {
      id: 'access',
      title: t.access.title,
      standard: fmt(t.access.standard, { clearance: r.road.intersectionClearance, arterial: parking.arterialWidth }),
      applied: accessIssue ?? t.access.none,
      status: accessIssue ? 'check' : 'ok',
      evidence: r.evidence.access,
    },
  ];
}

/** 별도 확인 4항목 — 자동 판정 없이 토지이용계획에서 관련 항목만 찾아 붙입니다 */
export function buildManualChecks(parcel: Parcel, r: RulesFile = rules): ManualCheckResult[] {
  const uses = parcel.landUses ?? [];
  return r.manualChecks.map((check) => ({
    ...check,
    hits: uses.filter((u) => (check.keywords ?? []).some((k) => u.name.includes(k))),
  }));
}

export function buildRuleReview(parcel: Parcel, r: RulesFile = rules): RuleReview {
  const source = { providers: r.providers, date: parcel.source?.queriedAt ?? r.queriedAt };
  if (!parcel.inSuseong) return { supported: false, items: [], manual: [], source };
  return { supported: true, items: buildRuleItems(parcel, r), manual: buildManualChecks(parcel, r), source };
}
