import { Component, type ErrorInfo, type ReactNode } from 'react';
import { content } from '@/lib/content';
import { Button } from './Button';
import { Icon } from './Icon';
import styles from './AsyncBlock.module.css';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** 바뀌면 오류 상태를 풉니다 (예: 라우트 경로) */
  resetKey?: string;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * 렌더 중 예외가 나도 앱 전체가 빈 화면으로 사라지지 않게 막습니다.
 * 저장소에 남은 손상된 값·예상 밖의 응답 모양 등으로 한 패널이 깨지면 그 자리에 안내와 새로고침 버튼만 보여 줍니다.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[app] 렌더 오류:', error, info.componentStack);
  }

  componentDidUpdate(prev: ErrorBoundaryProps): void {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  render(): ReactNode {
    if (!this.state.error) return this.props.children;
    const s = content.status;
    return (
      <div className={styles.error} role="alert">
        <Icon name="alert-circle" size={22} className={styles.errorIcon} />
        <div className={styles.errorBody}>
          <strong>{s.crashed}</strong>
          <p>{s.crashedDesc}</p>
        </div>
        <Button variant="secondary" size="sm" iconLeft="refresh" onClick={() => window.location.reload()}>
          {s.reload}
        </Button>
      </div>
    );
  }
}
