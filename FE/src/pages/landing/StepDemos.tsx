import type { CSSProperties } from 'react';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Icon } from '@/components/Icon';
import { StatusBadge } from '@/components/RuleItem';
import { MINI_STALL, miniLayout } from './sceneGeometry';
import styles from './LandingPage.module.css';

const MINI = miniLayout(0);
const STATUSES = ['REVIEWABLE', 'CONDITIONAL', 'MANUAL_CHECK'] as const;

function pose(x: number, y: number, rotate: number): CSSProperties {
  return { transform: `translateX(${x}px) translateY(${y}px) rotate(${rotate}deg)` };
}

/** STEP 01 — 검색창에 주소가 입력되고 결과가 올라오는 작은 화면 */
export function SearchDemo() {
  const d = content.landing.how.demo;
  return (
    <div className={styles.demo} data-anim="demo-search" aria-hidden="true">
      <div className={styles.mockField}>
        <Icon name="search" size={16} />
        <span data-anim="typed" data-text={d.query} className={styles.typed}>
          {d.query}
        </span>
        <span className={styles.caret} />
      </div>
      <ul className={styles.mockHits}>
        <li data-anim="hit" className={cx(styles.mockHit, styles.mockHitOn)}>
          <Icon name="map-pin" size={15} />
          <span className={styles.mockHitText}>
            <b>{d.hitRoad}</b>
            <small>{d.hitJibun}</small>
          </span>
        </li>
        <li data-anim="hit" className={styles.mockHit}>
          <span className={styles.skelDot} />
          <span className={styles.mockHitText}>
            <span className={styles.skel} />
            <span className={cx(styles.skel, styles.skelShort)} />
          </span>
        </li>
      </ul>
    </div>
  );
}

/** STEP 02 — 필지 경계가 그려지고 면적·용도지역·검토 상태가 채워지는 작은 화면 */
export function AnalysisDemo() {
  const d = content.landing.how.demo;
  return (
    <div className={styles.demo} data-anim="demo-analysis" aria-hidden="true">
      <div className={styles.miniRow}>
        <svg viewBox="0 0 200 120" className={styles.miniSvg}>
          <polygon className={styles.miniParcelFill} points="38,22 150,14 166,96 30,104" />
          <polygon data-anim="mini-parcel" className={styles.miniParcel} points="38,22 150,14 166,96 30,104" />
        </svg>
        <dl className={styles.miniStats}>
          <div>
            <dt>{d.area}</dt>
            <dd className="numeric">
              <span data-anim="area" data-value={d.areaValue}>
                {d.areaValue.toFixed(1)}
              </span>
              {d.areaUnit}
            </dd>
          </div>
          <div>
            <dt>{d.zoning}</dt>
            <dd>{d.zoningValue}</dd>
          </div>
        </dl>
      </div>
      <div className={styles.miniBadges}>
        {STATUSES.map((s) => (
          <span key={s} data-anim="badge" className={styles.miniBadge}>
            <StatusBadge status={s} />
          </span>
        ))}
      </div>
    </div>
  );
}

/** STEP 03 — 배치안 A·B·C 를 넘겨 보는 작은 화면 */
export function ResultDemo() {
  const d = content.landing.how.demo;
  const visible = MINI.filter((s) => s.visible).length;
  return (
    <div className={styles.demo} data-anim="demo-result" aria-hidden="true">
      <div className={styles.miniTabs}>
        <span data-anim="tab-pill" className={styles.tabPill} />
        {content.landing.hero.layouts.map((l) => (
          <span key={l.code} className={styles.miniTab}>
            {l.code}
          </span>
        ))}
      </div>
      <svg viewBox="0 0 200 120" className={cx(styles.miniSvg, styles.miniSvgWide)}>
        <rect className={styles.miniLot} x={44} y={6} width={112} height={108} rx={4} />
        <rect className={styles.miniAisle} x={50} y={48} width={100} height={24} rx={3} />
        {MINI.map((s, i) => (
          <rect
            key={i}
            data-anim="mini-stall"
            className={styles.miniStall}
            x={-MINI_STALL.w / 2}
            y={-MINI_STALL.h / 2}
            width={MINI_STALL.w}
            height={MINI_STALL.h}
            rx={1.5}
            style={pose(s.x, s.y, s.rotate)}
          />
        ))}
      </svg>
      <p className={styles.miniCount}>
        {d.layoutLabel} <b data-anim="mini-code">{content.landing.hero.layouts[0].code}</b> ·{' '}
        <b className="numeric">
          <span data-anim="mini-count">{visible}</span>
          {d.countUnit}
        </b>
      </p>
    </div>
  );
}
