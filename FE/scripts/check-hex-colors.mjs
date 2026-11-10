/**
 * 검수용: 컴포넌트·페이지·라이브러리 코드에 색 코드(#rrggbb)가 직접 들어갔는지 찾습니다.
 * 색은 src/design/themes/*.css 에서만 정의해야 합니다.
 *
 *   npm run check:colors
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// 한글·공백이 든 경로에서도 안전하도록 URL → 파일 경로 변환을 씁니다
const ROOT = fileURLToPath(new URL('../src', import.meta.url));
const ALLOWED_DIR = join(ROOT, 'design', 'themes');
const HEX = /#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/g;
const EXT = new Set(['.ts', '.tsx', '.css', '.json']);

const hits = [];

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    const ext = name.slice(name.lastIndexOf('.'));
    if (!EXT.has(ext)) continue;
    if (full.startsWith(ALLOWED_DIR + sep)) continue;
    const text = readFileSync(full, 'utf8');
    text.split('\n').forEach((line, i) => {
      const matches = line.match(HEX);
      if (matches) hits.push({ file: relative(ROOT, full), line: i + 1, matches });
    });
  }
}

walk(ROOT);

if (hits.length === 0) {
  console.log('OK — 테마 파일 밖에 직접 쓴 색 코드가 없습니다.');
} else {
  console.error(`직접 쓴 색 코드 ${hits.length}곳:`);
  for (const h of hits) console.error(`  src/${h.file}:${h.line}  ${h.matches.join(', ')}`);
  process.exit(1);
}
