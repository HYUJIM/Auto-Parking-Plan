/**
 * 브라우저에서 만든 내용을 파일로 저장합니다 (서버 왕복 없음).
 * blob URL 은 저장이 시작된 뒤 바로 해제합니다.
 */
export function downloadText(filename: string, text: string, mime: string): void {
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = safeFilename(filename);
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 파일 이름에 쓸 수 없는 문자(경로 구분자·제어 문자·예약 문자)를 밑줄로 */
export function safeFilename(name: string): string {
  const cleaned = Array.from(name)
    .map((ch) => {
      const cp = ch.codePointAt(0) ?? 0;
      return cp < 0x20 || cp === 0x7f || '\\/:*?"<>|'.includes(ch) ? '_' : ch;
    })
    .join('')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.slice(0, 120) || 'download';
}
