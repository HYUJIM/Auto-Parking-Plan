import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { AddressHit } from '@/lib/api/types';
import { getExampleParcel } from '@/lib/api/address';
import { useRecentSearches, type RecentSearch } from '@/lib/api/recent';
import { useMap } from '@/lib/map/MapContext';
import { content } from '@/lib/content';
import { isIconName, type IconName } from '@/design/icons';
import { Button } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { SearchBox } from '@/components/SearchBox';
import { useToast } from '@/components/Toast';
import styles from './HomePanel.module.css';

/**
 * 1단계 — 초기화면 (주소 입력 전) 좌측 패널.
 * 헤드라인 · 주소 검색 + CTA · 보조 버튼 3개 · 01 서비스 · 02 이용 흐름.
 * 지도는 수성구 전체 + 검증 필지 10개 점 (점 클릭 시 바로 2단계).
 */
export function HomePanel() {
  const navigate = useNavigate();
  const toast = useToast();
  const { setScene, setPreviewPins, locate } = useMap();
  const [recent, clearRecent] = useRecentSearches();
  const [recentOpen, setRecentOpen] = useState(false);
  const [locating, setLocating] = useState(false);

  useEffect(() => {
    setScene({ kind: 'overview' });
  }, [setScene]);

  useEffect(() => () => setPreviewPins([]), [setPreviewPins]);

  const goTo = useCallback((pnu: string) => navigate(`/site?pnu=${encodeURIComponent(pnu)}`), [navigate]);
  const onSelect = useCallback((hit: AddressHit) => goTo(hit.pnu), [goTo]);
  const onResults = useCallback((hits: AddressHit[]) => setPreviewPins(hits), [setPreviewPins]);

  const onLocate = () => {
    if (locating) return;
    setLocating(true);
    locate()
      .catch((err: Error) => toast.show(err.message))
      .finally(() => setLocating(false));
  };
  const onExample = () => goTo(getExampleParcel().pnu);

  const h = content.home;

  return (
    <div className={styles.panel}>
      <section className={styles.hero}>
        <span className={styles.eyebrow}>{h.eyebrow}</span>
        <h1 className={styles.headline}>{h.headline}</h1>
        <p className={styles.sub}>{h.sub}</p>
      </section>

      <section className={styles.search}>
        <SearchBox autoFocus ctaLabel={h.cta} onSelect={onSelect} onResults={onResults} />

        <div className={styles.helpers}>
          <Button variant="secondary" size="sm" iconLeft="locate" loading={locating} onClick={onLocate}>
            {h.helpers.location}
          </Button>
          <Button variant="secondary" size="sm" iconLeft="map-pin" onClick={onExample}>
            {h.helpers.example}
          </Button>
          <div className={styles.recentWrap}>
            <Button
              variant="secondary"
              size="sm"
              iconLeft="history"
              aria-expanded={recentOpen}
              aria-haspopup="true"
              onClick={() => setRecentOpen((o) => !o)}
            >
              {h.helpers.recent}
            </Button>
            {recentOpen && (
              <RecentList
                items={recent}
                onPick={(pnu) => {
                  setRecentOpen(false);
                  goTo(pnu);
                }}
                onClear={clearRecent}
                onClose={() => setRecentOpen(false)}
              />
            )}
          </div>
        </div>
      </section>

      <section className={styles.block}>
        <header className={styles.blockHead}>
          <span className={styles.no}>{h.service.no}</span>
          <h2>{h.service.title}</h2>
        </header>
        <ul className={styles.cards}>
          {h.service.items.map((item) => (
            <li key={item.title} className={styles.card}>
              <span className={styles.cardIcon}>
                <Icon name={toIcon(item.icon)} size={22} />
              </span>
              <div className={styles.cardBody}>
                <h3>{item.title}</h3>
                <p>{item.desc}</p>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section className={styles.block}>
        <header className={styles.blockHead}>
          <span className={styles.no}>{h.flow.no}</span>
          <h2>{h.flow.title}</h2>
        </header>
        <ol className={styles.flow}>
          {h.flow.steps.map((step, i) => (
            <li key={step} className={styles.flowStep}>
              <span className={styles.stepNo}>{i + 1}</span>
              <span className={styles.stepLabel}>{step}</span>
              {i < h.flow.steps.length - 1 && <Icon name="chevron-right" size={16} className={styles.flowArrow} />}
            </li>
          ))}
        </ol>
      </section>

      <p className={styles.pilot}>{h.pilotNote}</p>
    </div>
  );
}

function toIcon(name: string): IconName {
  return isIconName(name) ? name : 'info';
}

/** 최근 검색 팝오버 — Esc 또는 바깥 클릭으로 닫힘 */
function RecentList({
  items,
  onPick,
  onClear,
  onClose,
}: {
  items: RecentSearch[];
  onPick: (pnu: string) => void;
  onClear: () => void;
  onClose: () => void;
}) {
  const h = content.home;
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    const onPointer = (e: PointerEvent) => {
      const root = ref.current;
      if (!root) return;
      const target = e.target as Node;
      // 토글 버튼(바로 앞 형제)은 자체 onClick 으로 처리
      if (root.contains(target) || root.parentElement?.contains(target)) return;
      onClose();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onPointer);
    };
  }, [onClose]);

  return (
    <div ref={ref} className={styles.recent} role="group" aria-label={h.recentTitle}>
      {items.length === 0 ? (
        <p className={styles.recentEmpty}>{h.recentEmpty}</p>
      ) : (
        <ul className={styles.recentList}>
          {items.map((r) => (
            <li key={r.pnu}>
              <button type="button" className={styles.recentItem} onClick={() => onPick(r.pnu)}>
                <Icon name="history" size={16} />
                <span>{r.label}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {items.length > 0 && (
        <Button variant="ghost" size="sm" iconLeft="x" onClick={onClear} className={styles.recentClear}>
          {h.recentClear}
        </Button>
      )}
    </div>
  );
}
