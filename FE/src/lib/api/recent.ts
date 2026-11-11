import { useCallback, useEffect, useState } from 'react';
import { isRecentSearch } from './guards';

export interface RecentSearch {
  pnu: string;
  label: string;
  at: number;
}

const KEY = 'app:recent-searches';
const MAX = 6;
const EVENT = 'app:recent-changed';

/** 저장소 값 중 모양이 맞는 항목만 (손상된 한 항목이 화면 전체를 멈추지 않도록) */
function read(): RecentSearch[] {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(isRecentSearch).slice(0, MAX) : [];
  } catch {
    return [];
  }
}

function write(list: RecentSearch[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* 저장소 접근 불가 — 무시 */
  }
  window.dispatchEvent(new Event(EVENT));
}

export function addRecentSearch(item: Omit<RecentSearch, 'at'>): void {
  try {
    const list = read().filter((r) => r.pnu !== item.pnu);
    list.unshift({ ...item, at: Date.now() });
    write(list.slice(0, MAX));
  } catch {
    /* 기록 실패가 화면 흐름을 끊지 않도록 */
  }
}

export function clearRecentSearches(): void {
  write([]);
}

/** 최근 검색 목록 (다른 탭·컴포넌트에서 바뀌어도 따라감) */
export function useRecentSearches(): [RecentSearch[], () => void] {
  const [list, setList] = useState<RecentSearch[]>(read);

  useEffect(() => {
    const sync = () => setList(read());
    window.addEventListener(EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  const clear = useCallback(() => clearRecentSearches(), []);
  return [list, clear];
}
