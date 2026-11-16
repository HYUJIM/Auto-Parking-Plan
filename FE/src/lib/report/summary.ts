import type { BadgeVariant } from '@/components/Badge';
import type { IconName } from '@/design/icons';
import type { DataNotice, Parcel, ReviewProgress, RuleReview, SummarySource } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import type { ParkingVerdict } from './verdict';

/** 결과 리포트의 짧은 표시 값들 — 주차장 설치 판단 칩 · 요약 출처 이름 · 데이터 안내 합치기 · 출처 줄 */

export interface VerdictChip {
  label: string;
  variant: BadgeVariant;
  icon: IconName;
}

/**
 * '주차장 설치 판단' 칩 — 판정은 BE(배치 계산의 eligibility)가 한 것을 그대로 옮깁니다.
 * 아직 계산 전이면 '확인 중', 계산하지 못했으면(검토 실패·경계 없음·시범 지역 밖) '판단 보류'.
 */
export function verdictChip(verdict: ParkingVerdict, checking: boolean): VerdictChip {
  const t = content.report.rules.verdict;
  if (verdict.kind === 'not_permitted') return { label: t.notPermitted, variant: 'danger', icon: 'alert-circle' };
  if (verdict.kind === 'possible') {
    switch (verdict.status) {
      case 'REVIEWABLE':
        return { label: t.possible, variant: 'ok', icon: 'check-circle' };
      case 'CONDITIONAL':
        return { label: t.conditional, variant: 'check', icon: 'alert-circle' };
      default:
        return { label: t.manual, variant: 'manual', icon: 'info' };
    }
  }
  return checking ? { label: t.checking, variant: 'neutral', icon: 'loader' } : { label: t.unavailable, variant: 'neutral', icon: 'info' };
}

/**
 * 요약 문장의 출처 이름. AI 를 쓰지 않은(또는 검증을 통과하지 못한) 원문 인용 요약을 'AI' 라고 부르지 않습니다.
 */
export function summaryLabel(source: SummarySource): string {
  return source === 'ai_verified' ? content.report.print.summaryAi : content.report.print.summaryTemplate;
}

/** 검토·필지의 데이터 안내를 합칩니다 — 같은 문구는 한 번만 */
export function mergeNotices(...lists: (DataNotice[] | null | undefined)[]): DataNotice[] {
  const seen = new Set<string>();
  const out: DataNotice[] = [];
  for (const list of lists) {
    for (const n of list ?? []) {
      const key = n.message.trim();
      if (!key || seen.has(key)) continue;
      seen.add(key);
      out.push(n);
    }
  }
  return out;
}

/** 필지 출처 줄: '출처 vworld · data.go.kr · 조회 2026-11-10' (예시 데이터면 그 안내) */
export function parcelSourceLine(parcel: Pick<Parcel, 'isExample' | 'source'>): string {
  const t = content.site;
  if (parcel.isExample) return t.sourceParcelExample;
  const providers = parcel.source.providers.join(' · ') || t.rules.none;
  return parcel.source.queriedAt
    ? fmt(t.sourceParcel, { providers, date: parcel.source.queriedAt })
    : fmt(t.sourceParcelNoDate, { providers });
}

/** 법규 검토 폴링 중 지금 단계 — '법규 검토 중 — 용도지역 확인' (단계 정보가 아직 없으면 '법규 검토 중…') */
export function reviewProgressText(progress: ReviewProgress | null): string {
  const r = content.site.rules;
  const step = progress?.steps.find((s) => s.state === 'running') ?? progress?.steps.find((s) => s.state === 'pending');
  return step ? fmt(r.reviewingStep, { label: step.label }) : r.reviewing;
}

/** 검토 출처 줄: '검토 기준일 2026-11-10 · 검토 엔진 0.1.0' */
export function reviewSourceLine(review: Pick<RuleReview, 'asOfDate' | 'engineVersion'>): string {
  const r = content.site.rules;
  return fmt(r.source, { date: review.asOfDate || r.none, version: review.engineVersion ?? r.none });
}
