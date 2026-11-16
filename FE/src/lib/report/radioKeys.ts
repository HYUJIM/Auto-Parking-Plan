/**
 * 라디오 그룹(배치안 A/B/C 전환)의 키보드 이동 — WAI-ARIA APG 라디오 그룹 패턴.
 * 화살표 오른쪽·아래 = 다음(끝에서 처음으로), 왼쪽·위 = 이전(처음에서 끝으로), Home·End = 처음·끝.
 * 그룹은 Tab 한 번으로 들어오고(고른 항목만 tabIndex 0), 안에서는 화살표로 옮기며 바로 고릅니다.
 */
export function radioKeyTarget(key: string, index: number, count: number): number | null {
  if (count <= 0) return null;
  const last = count - 1;
  switch (key) {
    case 'ArrowRight':
    case 'ArrowDown':
      return index >= last ? 0 : index + 1;
    case 'ArrowLeft':
    case 'ArrowUp':
      return index <= 0 ? last : index - 1;
    case 'Home':
      return 0;
    case 'End':
      return last;
    default:
      return null;
  }
}
