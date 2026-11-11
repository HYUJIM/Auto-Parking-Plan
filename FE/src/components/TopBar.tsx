import { Link } from 'react-router-dom';
import { content } from '@/lib/content';
import { Button } from './Button';
import { useToast } from './Toast';
import styles from './TopBar.module.css';

/** 상단 바 56px: 로고 APP · 이용가이드 · 공지사항 · 로그인 (로그인은 버튼만, 기능은 다음 단계) */
export function TopBar() {
  const toast = useToast();
  const comingSoon = () => toast.show(content.nav.comingSoon);

  return (
    <header className={styles.topbar}>
      <Link to="/" className={styles.brand}>
        <img src="/brand/logo.svg" alt={content.brand.logoAlt} width={28} height={28} />
        <span className={styles.name}>{content.brand.name}</span>
        <span className={styles.tagline}>{content.brand.tagline}</span>
      </Link>

      <nav className={styles.nav}>
        <Button variant="ghost" size="sm" iconLeft="book-open" onClick={comingSoon} className={styles.navItem}>
          {content.nav.guide}
        </Button>
        <Button variant="ghost" size="sm" iconLeft="bell" onClick={comingSoon} className={styles.navItem}>
          {content.nav.notice}
        </Button>
        <Button variant="secondary" size="sm" iconLeft="user" onClick={comingSoon}>
          {content.nav.login}
        </Button>
      </nav>
    </header>
  );
}
