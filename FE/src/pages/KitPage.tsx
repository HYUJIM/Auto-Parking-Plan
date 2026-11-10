import { useState } from 'react';
import { Link } from 'react-router-dom';
import { icons } from '@/design/icons';
import { activeTheme, getCurrentTheme, previewTheme, themes, type ThemeId } from '@/design/theme.config';
import { readToken } from '@/lib/map/mapStyle';
import { content, fmt } from '@/lib/content';
import { cx } from '@/lib/cx';
import { Badge } from '@/components/Badge';
import { Button, type ButtonSize, type ButtonVariant } from '@/components/Button';
import { Icon } from '@/components/Icon';
import { StatCard } from '@/components/StatCard';
import styles from './KitPage.module.css';

const COLOR_TOKENS = [
  '--color-primary',
  '--color-primary-hover',
  '--color-primary-soft',
  '--color-accent',
  '--color-accent-soft',
  '--color-bg',
  '--color-surface',
  '--color-surface-2',
  '--color-text',
  '--color-text-2',
  '--color-text-muted',
  '--color-border',
  '--color-border-strong',
  '--color-success',
  '--color-success-soft',
  '--color-warning',
  '--color-warning-soft',
  '--color-neutral',
  '--color-neutral-soft',
  '--color-danger',
  '--map-site-stroke',
  '--map-road-stroke',
  '--map-marker',
  '--map-marker-preview',
];

const TYPE_TOKENS = ['--text-xs', '--text-sm', '--text-md', '--text-lg', '--text-xl', '--text-2xl', '--text-3xl', '--text-stat'];
const VARIANTS: ButtonVariant[] = ['primary', 'secondary', 'ghost', 'icon'];
const SIZES: ButtonSize[] = ['sm', 'md', 'lg'];

/**
 * /kit — 디자인 키트.
 * 색·글꼴 크기·아이콘 전체·버튼 전 상태·배지·지도 경계선 샘플을 한 화면에 모으고,
 * 상단에서 A/B/C 안을 바꿔 볼 수 있습니다. 토큰 파일을 고치면 이 페이지에서 바로 확인합니다.
 */
export function KitPage() {
  const [theme, setTheme] = useState<ThemeId>(getCurrentTheme);
  const k = content.kit;

  // previewTheme 이 data-theme 을 먼저 바꾸므로, 다음 렌더에서 readToken 이 새 값을 읽습니다.
  const choose = (id: ThemeId) => {
    previewTheme(id);
    setTheme(id);
  };

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <div>
            <h1 className={styles.title}>
              <Icon name="palette" size={22} />
              {k.title}
            </h1>
            <p className={styles.desc}>{k.desc}</p>
          </div>
          <Link to="/" className={styles.back}>
            <Icon name="arrow-left" size={16} />
            {k.backHome}
          </Link>
        </div>
        <div className={styles.themeRow}>
          <span className={styles.themeLabel}>{k.themeLabel}</span>
          {(Object.keys(themes) as ThemeId[]).map((id) => (
            <Button key={id} variant="secondary" size="sm" active={theme === id} onClick={() => choose(id)}>
              {themes[id].label}
              {id === activeTheme && <Badge variant="primary">{k.activeBadge}</Badge>}
            </Button>
          ))}
          {theme !== activeTheme && <Badge variant="check">{k.previewBadge}</Badge>}
        </div>
      </header>

      <main className={styles.main}>
        {/* 색 */}
        <Section title={k.sections.colors} hint={k.tokenHint}>
          <ul className={styles.swatches}>
            {COLOR_TOKENS.map((token) => (
              <li key={token} className={styles.swatch}>
                <span className={styles.swatchColor} style={{ background: `var(${token})` }} />
                <span className={styles.swatchName}>{token}</span>
                <span className={cx(styles.swatchValue, 'numeric')}>{readToken(token)}</span>
              </li>
            ))}
          </ul>
        </Section>

        {/* 글꼴 크기 */}
        <Section title={k.sections.type}>
          <ul className={styles.typeList}>
            {TYPE_TOKENS.map((token) => (
              <li key={token} className={styles.typeRow}>
                <span className={cx(styles.typeMeta, 'numeric')}>
                  {token} · {readToken(token)}
                </span>
                <span className={styles.typeSample} style={{ fontSize: `var(${token})` }}>
                  {k.sampleText}
                </span>
              </li>
            ))}
          </ul>
        </Section>

        {/* 아이콘 */}
        <Section title={k.sections.icons}>
          <ul className={styles.iconGrid}>
            {icons.map((name) => (
              <li key={name} className={styles.iconCell}>
                <Icon name={name} size={24} />
                <span>{name}</span>
              </li>
            ))}
          </ul>
        </Section>

        {/* 버튼 */}
        <Section title={k.sections.buttons}>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th />
                  <th>{k.states.default}</th>
                  <th>{k.states.hover}</th>
                  <th>{k.states.disabled}</th>
                  <th>{k.states.loading}</th>
                </tr>
              </thead>
              <tbody>
                {VARIANTS.map((variant) =>
                  SIZES.map((size) => (
                    <tr key={`${variant}-${size}`}>
                      <th scope="row" className={styles.rowHead}>
                        {variant} · {size}
                      </th>
                      {(['default', 'hover', 'disabled', 'loading'] as const).map((state) => (
                        <td key={state}>
                          <Button
                            variant={variant}
                            size={size}
                            icon="search"
                            iconLeft={variant === 'icon' ? undefined : 'search'}
                            forceHover={state === 'hover'}
                            disabled={state === 'disabled'}
                            loading={state === 'loading'}
                          >
                            {k.sampleButton}
                          </Button>
                        </td>
                      ))}
                    </tr>
                  )),
                )}
              </tbody>
            </table>
          </div>
        </Section>

        {/* 배지 */}
        <Section title={k.sections.badges}>
          <div className={styles.row}>
            <Badge variant="ok" icon="check-circle">
              {content.status.ok}
            </Badge>
            <Badge variant="check" icon="alert-circle">
              {content.status.check}
            </Badge>
            <Badge variant="manual" icon="info">
              {content.status.manual}
            </Badge>
            <Badge variant="info">{k.sampleLabel}</Badge>
            <Badge variant="primary">{k.sampleLabel}</Badge>
            <Badge variant="neutral">{k.sampleLabel}</Badge>
          </div>
        </Section>

        {/* 지도 경계선 */}
        <Section title={k.sections.map}>
          <div className={styles.mapRow}>
            <svg viewBox="0 0 360 220" className={styles.mapSample} role="img" aria-label={k.sections.map}>
              <rect width="360" height="220" style={{ fill: 'var(--map-bg-satellite)' }} />
              <rect x="40" y="30" width="110" height="70" rx="2" style={{ fill: 'var(--map-bg-satellite-2)' }} />
              <rect x="230" y="120" width="90" height="60" rx="2" style={{ fill: 'var(--map-bg-satellite-2)' }} />
              <path
                d="M20 190 H340"
                style={{
                  stroke: 'var(--map-road-stroke)',
                  strokeWidth: 'calc(var(--map-road-stroke-width) * 1px)',
                  strokeLinecap: 'round',
                  fill: 'none',
                }}
              />
              <path
                d="M110 60 L250 66 L246 170 L114 166 Z"
                style={{
                  fill: 'var(--map-site-fill)',
                  fillOpacity: 'var(--map-site-fill-opacity)',
                  stroke: 'var(--map-site-stroke)',
                  strokeWidth: 'calc(var(--map-site-stroke-width) * 1px)',
                  strokeLinejoin: 'round',
                }}
              />
              <circle cx="300" cy="50" r="7" style={{ fill: 'var(--map-marker)', stroke: 'var(--map-marker-ring)', strokeWidth: 2.5 }} />
              <circle
                cx="60"
                cy="150"
                r="7"
                style={{ fill: 'var(--map-marker-preview)', stroke: 'var(--map-marker-ring)', strokeWidth: 2.5 }}
              />
            </svg>
            <ul className={styles.legend}>
              <li>
                <span className={styles.legendLine} style={{ background: 'var(--map-site-stroke)' }} />
                {k.mapSample.site} · <code>--map-site-*</code>
              </li>
              <li>
                <span className={styles.legendLine} style={{ background: 'var(--map-road-stroke)' }} />
                {k.mapSample.road} · <code>--map-road-*</code>
              </li>
              <li>
                <span className={styles.legendDot} style={{ background: 'var(--map-marker)' }} />
                {k.mapSample.marker} · <code>--map-marker</code>
              </li>
            </ul>
          </div>
        </Section>

        {/* 대지 요약 카드 */}
        <Section title={k.sections.cards}>
          <div className={styles.cards}>
            <StatCard label={content.site.summary.area} value="1,203.0" unit={content.site.summary.areaUnit} numeric />
            <StatCard label={content.site.summary.zoning} value="일반상업지역" />
            <StatCard label={content.site.summary.jimok} value="대" />
            <StatCard
              label={content.site.summary.landPrice}
              hint={fmt(content.site.summary.baseYear, { year: 2026 })}
              value="6,120,000"
              unit={content.site.summary.landPriceUnit}
              numeric
            />
          </div>
        </Section>
      </main>
    </div>
  );
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>
        {title}
        {hint && <span className={styles.sectionHint}>{hint}</span>}
      </h2>
      {children}
    </section>
  );
}
