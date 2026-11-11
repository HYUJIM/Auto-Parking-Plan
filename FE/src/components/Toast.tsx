import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { cx } from '@/lib/cx';
import { Icon } from './Icon';
import styles from './Toast.module.css';

type ToastVariant = 'info' | 'warning';

interface ToastItem {
  id: number;
  message: string;
  variant: ToastVariant;
}

interface ToastApi {
  /** variant 'warning' 은 눈에 띄는 색으로 더 오래(6초) 보여 줍니다 (예: 주차장 건설 불가 경고) */
  show: (message: string, variant?: ToastVariant) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

/** 짧은 안내 메시지 (위치 권한 거부, 준비 중 기능 등). 화면 흐름을 막지 않습니다. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);

  const show = useCallback((message: string, variant: ToastVariant = 'info') => {
    const id = ++seq.current;
    setItems((list) => [...list.slice(-2), { id, message, variant }]);
    setTimeout(() => setItems((list) => list.filter((t) => t.id !== id)), variant === 'warning' ? 6000 : 3600);
  }, []);

  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={styles.stack} role="status" aria-live="polite">
        {items.map((t) => (
          <div
            key={t.id}
            className={cx(styles.toast, t.variant === 'warning' && styles.warning)}
            role={t.variant === 'warning' ? 'alert' : undefined}
          >
            <Icon name={t.variant === 'warning' ? 'alert-circle' : 'info'} size={18} />
            <span>{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast 는 ToastProvider 안에서만 쓸 수 있습니다.');
  return ctx;
}
