import { useCallback, useEffect, useState, type DependencyList } from 'react';

export type AsyncStatus = 'idle' | 'loading' | 'success' | 'error';

export interface AsyncState<T> {
  status: AsyncStatus;
  data: T | null;
  error: Error | null;
  /** 같은 인자로 다시 불러오기 (오류 카드의 '다시 시도') */
  reload: () => void;
}

interface Request {
  deps: DependencyList;
  tick: number;
  /** 요청의 신원표 — deps 나 재시도 횟수가 바뀔 때마다 새 객체 */
  key: object;
}

interface Snapshot<T> {
  key: object;
  status: 'success' | 'error';
  data: T | null;
  error: Error | null;
}

function sameDeps(a: DependencyList, b: DependencyList): boolean {
  return a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
}

/**
 * 비동기 데이터 블록 하나를 관리합니다.
 * 카드마다 따로 쓰면 하나가 실패해도 나머지 화면은 멈추지 않습니다.
 *
 * deps 가 바뀌면 새 key 가 만들어지고, 마지막 결과의 key 와 다르면 '불러오는 중'으로 봅니다.
 * 그래서 이전 요청의 늦은 응답은 key 가 달라 무시되고, effect 안에서 상태를 동기적으로 바꿀 필요도 없습니다.
 *
 * fn 은 AbortSignal 을 받습니다. deps 가 바뀌거나 화면을 떠나면 진행 중인 요청을 중단하므로, 오래 걸리는 작업(법규 검토 폴링)이
 * 떠난 화면을 위해 계속 서버를 부르지 않습니다. fn 이 signal 을 fetch 에 넘겨야 실제로 끊깁니다.
 */
export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: DependencyList, enabled = true): AsyncState<T> {
  const [tick, setTick] = useState(0);
  const [request, setRequest] = useState<Request>(() => ({ deps, tick, key: {} }));
  const [snapshot, setSnapshot] = useState<Snapshot<T> | null>(null);

  // deps 또는 tick 이 바뀌면 새 요청 — 렌더 중 상태 조정 (effect 안의 setState 를 피합니다)
  let current = request;
  if (request.tick !== tick || !sameDeps(request.deps, deps)) {
    current = { deps, tick, key: {} };
    setRequest(current);
  }
  const key = current.key;

  useEffect(() => {
    if (!enabled) return;
    let alive = true;
    const controller = new AbortController();
    fn(controller.signal).then(
      (result) => {
        if (alive) setSnapshot({ key, status: 'success', data: result, error: null });
      },
      (err: unknown) => {
        if (alive) setSnapshot({ key, status: 'error', data: null, error: err instanceof Error ? err : new Error(String(err)) });
      },
    );
    return () => {
      alive = false;
      controller.abort();
    };
    // fn 은 매 렌더마다 새 함수지만, 요청의 신원은 key 가 대표합니다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled]);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  if (!enabled) return { status: 'idle', data: null, error: null, reload };
  if (!snapshot || snapshot.key !== key) return { status: 'loading', data: null, error: null, reload };
  return { status: snapshot.status, data: snapshot.data, error: snapshot.error, reload };
}
