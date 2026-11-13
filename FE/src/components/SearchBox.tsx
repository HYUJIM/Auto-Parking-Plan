import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import type { AddressHit, AddressSearchResult } from '@/lib/api/types';
import { ApiError } from '@/lib/api/types';
import { describeApiError } from '@/lib/api/errors';
import { searchAddress } from '@/lib/api/address';
import { rememberHit } from '@/lib/api/hitCache';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Badge } from './Badge';
import { Button } from './Button';
import { Icon } from './Icon';
import styles from './SearchBox.module.css';

type Status = 'idle' | 'short' | 'loading' | 'done' | 'error';

interface SearchBoxProps {
  /** 이용화면에서 현재 주소를 채워 둘 때 */
  initialQuery?: string;
  autoFocus?: boolean;
  /** 이용화면 상단용 (CTA 없음, 높이 작게) */
  compact?: boolean;
  /** 있으면 입력창 아래에 큰 CTA 버튼을 둡니다 (초기화면 '대지 분석 시작') */
  ctaLabel?: string;
  onSelect: (hit: AddressHit) => void;
  /** 검색 결과가 바뀔 때 (지도에 미리보기 핀을 찍기 위해) */
  onResults?: (hits: AddressHit[]) => void;
}

const EMPTY: AddressSearchResult = { hits: [], total: 0 };
const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 220;

/**
 * 주소 입력창 + 자동완성 목록. BE 의 `GET /v1/address/search?q=` (행정안전부 도로명주소 API 경유) 를 호출합니다.
 * - 결과 0건: 지번 검색 안내 + "지도에서 직접 클릭" 안내 (BE 가 준 안내 문구가 있으면 그것을)
 * - 결과 여러 건(최대 10건): 목록에서 선택 (좌표가 있는 결과는 지도에 미리보기 핀)
 * - 지원 관할(수성구) 밖: 목록에 '수성구 외' 배지
 */
export function SearchBox({ initialQuery = '', autoFocus, compact, ctaLabel, onSelect, onResults }: SearchBoxProps) {
  const [query, setQuery] = useState(initialQuery);
  const [touched, setTouched] = useState(false);
  const [result, setResult] = useState<AddressSearchResult>(EMPTY);
  const [status, setStatus] = useState<Status>('idle');
  const [errorText, setErrorText] = useState<string | null>(null);
  /** 주소 검색만 막혔을 때의 대안 안내 (지도에서 직접 선택) */
  const [errorSub, setErrorSub] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [submitted, setSubmitted] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const requestId = useRef(0);
  /** 마지막으로 검색한 문자열 — Enter 로 바로 검색한 뒤 디바운스가 같은 검색을 반복하지 않도록 */
  const lastSearched = useRef('');
  const onResultsRef = useRef(onResults);
  useEffect(() => {
    onResultsRef.current = onResults;
  }, [onResults]);
  const listId = useId();
  const hits = result.hits;

  // 다른 필지로 이동해 initialQuery 가 바뀌면(사용자가 입력 중이 아닐 때만) 입력값을 맞춥니다 — 렌더 중 상태 조정
  const [syncedInitial, setSyncedInitial] = useState(initialQuery);
  if (initialQuery !== syncedInitial) {
    setSyncedInitial(initialQuery);
    if (!touched) {
      setQuery(initialQuery);
      setOpen(false);
      setStatus('idle');
      setSubmitted(false);
      setActive(-1);
      setResult(EMPTY);
    }
  }

  // 키보드로 고른 항목이 목록 밖에 있으면 보이게 스크롤
  useEffect(() => {
    if (open && active >= 0) document.getElementById(`${listId}-${active}`)?.scrollIntoView({ block: 'nearest' });
  }, [active, open, listId]);

  const applyResult = (q: string, next: AddressSearchResult) => {
    lastSearched.current = q;
    setResult(next);
    setStatus('done');
    setOpen(next.hits.length > 0);
    setActive(next.hits.length === 1 ? 0 : -1);
    onResultsRef.current?.(next.hits);
  };

  const applyError = (err: unknown) => {
    setResult(EMPTY);
    setStatus('error');
    // 서버가 준 고정 안내(ApiError)만 그대로 보여 주고, 브라우저 내부 오류 문구는 숨깁니다 (errors.ts 와 같은 규칙)
    const described = describeApiError(err);
    // 도로명주소 API 를 쓸 수 없을 때(503 UPSTREAM_UNAVAILABLE): 지도 클릭은 다른 경로라 계속 쓸 수 있으므로 그쪽을 안내합니다
    const unavailable = err instanceof ApiError && err.code === 'UPSTREAM';
    setErrorText(
      unavailable ? content.search.unavailable : err instanceof ApiError ? (described.desc ?? described.title) : content.search.error,
    );
    setErrorSub(unavailable ? content.search.unavailableMap : null);
    onResultsRef.current?.([]);
  };

  // 입력 중 디바운스 검색 — 상태 변경은 입력 핸들러와 비동기 콜백에서만
  useEffect(() => {
    if (!touched) return;
    const q = query.trim();
    if (q.length < MIN_QUERY_LENGTH || q === lastSearched.current) return;
    const id = ++requestId.current;
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const next = await searchAddress(q, controller.signal);
        if (requestId.current !== id) return;
        applyResult(q, next);
      } catch (err) {
        if (requestId.current !== id || (err instanceof DOMException && err.name === 'AbortError')) return;
        applyError(err);
      }
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, touched]);

  const onChange = (value: string) => {
    setQuery(value);
    setTouched(true);
    setSubmitted(false);
    const q = value.trim();
    if (q.length < MIN_QUERY_LENGTH) {
      ++requestId.current;
      lastSearched.current = '';
      setResult(EMPTY);
      setStatus(q.length ? 'short' : 'idle');
      setOpen(false);
      setActive(-1);
      onResultsRef.current?.([]);
    } else if (q !== lastSearched.current) {
      setStatus('loading');
    }
  };

  const select = (hit: AddressHit) => {
    rememberHit(hit);
    ++requestId.current;
    lastSearched.current = '';
    setOpen(false);
    setSubmitted(false);
    setActive(-1);
    setQuery(hit.roadAddress);
    setTouched(false);
    onResultsRef.current?.([]);
    onSelect(hit);
  };

  const submit = async () => {
    const q = query.trim();
    setSubmitted(true);
    if (q.length < MIN_QUERY_LENGTH) {
      setStatus('short');
      inputRef.current?.focus();
      return;
    }
    if (active >= 0 && hits[active]) {
      select(hits[active]);
      return;
    }
    if (status === 'done' && touched) {
      if (hits.length === 1) select(hits[0]);
      else if (hits.length > 1) setOpen(true);
      return;
    }
    // 아직 검색 전(초기값 그대로 Enter 등)이면 즉시 검색
    const id = ++requestId.current;
    setStatus('loading');
    try {
      const next = await searchAddress(q);
      if (requestId.current !== id) return;
      setTouched(true);
      if (next.hits.length === 1) {
        lastSearched.current = q;
        setResult(next);
        setStatus('done');
        select(next.hits[0]);
      } else {
        applyResult(q, next);
      }
    } catch (err) {
      if (requestId.current !== id) return;
      applyError(err);
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (hits.length) {
        setOpen(true);
        setActive((i) => (i + 1) % hits.length);
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (hits.length) {
        setOpen(true);
        setActive((i) => (i <= 0 ? hits.length - 1 : i - 1));
      }
    } else if (e.key === 'Enter') {
      e.preventDefault();
      void submit();
    } else if (e.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    }
  };

  const clear = () => {
    ++requestId.current;
    lastSearched.current = '';
    setQuery('');
    setTouched(true);
    setResult(EMPTY);
    setStatus('idle');
    setOpen(false);
    setActive(-1);
    setSubmitted(false);
    onResultsRef.current?.([]);
    inputRef.current?.focus();
  };

  const showList = open && hits.length > 0;
  const message = (() => {
    if (status === 'loading') return { kind: 'muted', text: content.search.searching };
    if (status === 'error') return { kind: 'warn', text: errorText ?? content.search.error, sub: errorSub ?? undefined };
    if (status === 'short' && submitted) return { kind: 'warn', text: content.search.minChars };
    if (status === 'done' && hits.length === 0 && (touched || submitted)) {
      if (result.notice) return { kind: 'warn', text: result.notice };
      return { kind: 'warn', text: content.search.noResults, sub: content.search.noResultsMap };
    }
    if (status === 'done' && hits.length > 1 && showList) {
      return { kind: 'muted', text: fmt(content.search.multiple, { n: hits.length }) };
    }
    return null;
  })();

  return (
    <div
      className={cx(styles.root, compact && styles.compact)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpen(false);
      }}
    >
      <div className={styles.field}>
        <Icon name="search" size={compact ? 18 : 20} className={styles.leading} />
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-label={content.search.label}
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="search"
          maxLength={100}
          className={styles.input}
          placeholder={content.search.placeholder}
          value={query}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => {
            if (hits.length && touched) setOpen(true);
          }}
          onKeyDown={onKeyDown}
        />
        {query && (
          <Button variant="ghost" size="sm" iconLeft="x" aria-label={content.search.clear} className={styles.clear} onClick={clear} />
        )}
        {!compact && !query && (
          <kbd className={styles.kbd} title={content.search.hintEnter} aria-label={content.search.hintEnter}>
            Enter
          </kbd>
        )}

        {showList && (
          <div className={styles.dropdown}>
            <ul id={listId} role="listbox" className={styles.list} aria-label={content.search.resultsLabel}>
              {hits.map((hit, i) => (
                <li
                  key={hit.pnu}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className={cx(styles.option, i === active && styles.optionActive)}
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => select(hit)}
                >
                  <Icon name="map-pin" size={18} className={styles.optionIcon} />
                  <span className={styles.optionText}>
                    <span className={styles.road}>{hit.roadAddress}</span>
                    <span className={styles.jibun}>{hit.jibunAddress}</span>
                  </span>
                  {!hit.inSuseong && <Badge variant="manual">{content.search.outside}</Badge>}
                </li>
              ))}
            </ul>
            <p className={styles.sourceLine}>
              <Icon name="info" size={12} />
              {content.search.source}
            </p>
          </div>
        )}
      </div>

      {message && (
        <p className={cx(styles.message, message.kind === 'warn' && styles.messageWarn)} aria-live="polite">
          {message.text}
          {message.sub && <span className={styles.messageSub}>{message.sub}</span>}
        </p>
      )}

      {ctaLabel && (
        <Button
          size="lg"
          block
          iconRight="arrow-right"
          className={styles.cta}
          loading={status === 'loading' && submitted}
          onClick={() => void submit()}
        >
          {ctaLabel}
        </Button>
      )}
    </div>
  );
}
