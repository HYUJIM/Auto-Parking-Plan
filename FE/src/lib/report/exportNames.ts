import type { ExistingBuildingsStatus } from '@/lib/api/types';
import { content, fmt } from '@/lib/content';
import { safeFilename } from '@/lib/export/download';
import { altLetter } from './layoutMetrics';

/**
 * 결과 리포트의 이름들 — 제목 주소, 지역, PDF 저장 시 문서 제목(= 저장 파일 이름), DXF 파일 이름·도면 제목·안내 줄.
 * 파일 이름에는 주소(사용자에게 보이는 값)만 들어가고, 경로 구분자·예약 문자는 safeFilename 이 밑줄로 바꿉니다.
 */

export interface AddressLike {
  road?: string | null;
  jibun?: string | null;
}

/** 제목 주소: 도로명주소 → 지번주소 → '' */
export function reportAddress(address: AddressLike | null | undefined): string {
  return address?.road?.trim() || address?.jibun?.trim() || '';
}

const SIDO_RE = /(특별시|광역시|특별자치시|특별자치도|도)$/;
const SIGUNGU_RE = /(시|군|구)$/;

/**
 * '대구광역시 수성구 범어동 9001' → '대구광역시 수성구', '경기도 성남시 분당구 정자동 1' → '경기도 성남시 분당구'.
 * 주소에서 시군구를 못 찾으면 gu(BE 관할 시군구)로, 둘 다 없으면 ''
 */
export function regionLabel(jibun: string | null | undefined, gu?: string | null): string {
  const parts = (jibun ?? '').trim().split(/\s+/).filter(Boolean);
  const sido = parts.length > 1 && SIDO_RE.test(parts[0]) ? parts[0] : '';
  const sigungu: string[] = [];
  for (const p of (sido ? parts.slice(1) : parts).slice(0, 2)) {
    if (!SIGUNGU_RE.test(p) || SIDO_RE.test(p)) break;
    sigungu.push(p);
  }
  if (sigungu.length === 0 && gu?.trim()) sigungu.push(gu.trim());
  return [sido, ...sigungu].filter(Boolean).join(' ');
}

/** 파일 이름 속 주소 — 비어 있으면 PNU */
function fileAddress(address: string, pnu: string): string {
  return address.trim() || pnu;
}

/** PDF 저장 때 문서 제목 (브라우저가 이것을 저장 파일 이름으로 씁니다): 주차검토_<주소>_<기준일> */
export function pdfTitle(address: string, pnu: string, asOfDate: string | null | undefined): string {
  const t = content.report.export;
  const a = fileAddress(address, pnu);
  return safeFilename(asOfDate ? fmt(t.pdfTitle, { address: a, date: asOfDate }) : fmt(t.pdfTitleNoDate, { address: a }));
}

export interface DxfAlternative {
  code: string;
  stallCount: number;
}

/** <주소>_배치안A.dxf, 철거 가정이면 <주소>_배치안A_철거가정.dxf (배치안이 없으면 <주소>_대지경계.dxf) */
export function dxfFilename(address: string, pnu: string, alt: DxfAlternative | null, demolished = false): string {
  const t = content.report.export;
  const a = fileAddress(address, pnu);
  if (!alt) return safeFilename(fmt(t.dxfFileSite, { address: a }));
  return safeFilename(fmt(demolished ? t.dxfFileDemolished : t.dxfFile, { address: a, letter: altLetter(alt.code) }));
}

/** 도면 제목 줄: '<주소> · 배치안 A · 주차 16대' (배치안이 없으면 주소만) */
export function dxfTitle(address: string, pnu: string, alt: DxfAlternative | null): string {
  const a = fileAddress(address, pnu);
  return alt ? fmt(content.report.export.dxfTitle, { address: a, letter: altLetter(alt.code), n: alt.stallCount }) : a;
}

/** 기존 건물 처리 안내 줄 — 피해서 배치 · 철거 가정 · 정보 없음 (건물이 없으면 없음) */
function buildingsNote(status: ExistingBuildingsStatus | null): string | null {
  const t = content.report.export;
  if (status === 'applied') return t.dxfNoteBuildingsKeep;
  if (status === 'ignored') return t.dxfNoteBuildingsIgnore;
  if (status === 'unavailable') return t.dxfNoteBuildingsUnavailable;
  return null;
}

/** 도면 안내 줄: 좌표계 · PNU·기준일 · (참고 배치) · (기존 건물 처리) · 참고용 고지 */
export function dxfNotes(
  pnu: string,
  asOfDate: string | null | undefined,
  referenceOnly = false,
  buildings: ExistingBuildingsStatus | null = null,
): string[] {
  const t = content.report.export;
  const notes = [t.dxfNoteCrs, asOfDate ? fmt(t.dxfNoteMeta, { pnu, date: asOfDate }) : fmt(t.dxfNoteMetaNoDate, { pnu })];
  if (referenceOnly) notes.push(t.dxfNoteReference);
  const b = buildingsNote(buildings);
  if (b) notes.push(b);
  notes.push(t.dxfNoteDisclaimer);
  return notes;
}
