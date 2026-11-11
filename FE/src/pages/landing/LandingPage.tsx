import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { isRuleStatus } from '@/lib/api/types';
import { content } from '@/lib/content';
import { cx } from '@/lib/cx';
import { isIconName } from '@/design/icons';
import { Icon } from '@/components/Icon';
import { StatusBadge } from '@/components/RuleItem';
import { BasisTree } from './BasisTree';
import { HeroScene } from './HeroScene';
import { RippleGrid } from './RippleGrid';
import { AnalysisDemo, ResultDemo, SearchDemo } from './StepDemos';
import { useLandingMotion } from './useLandingMotion';
import type { LayoutKind } from './sceneGeometry';
import styles from './LandingPage.module.css';

/** '시작하기' 가 여는 서비스 화면 (주소 검색 + 지도) */
const SERVICE_PATH = '/app';

const STEP_DEMOS = [<SearchDemo key="search" />, <AnalysisDemo key="analysis" />, <ResultDemo key="result" />];

/**
 * 소개 페이지(/) — 제품 소개와 '시작하기'.
 * 구성: 히어로(도면 그림) · 숫자 · 01 동작 방식 · 02 법규 근거 · 03 기능 · 시작하기.
 * 움직임은 전부 useLandingMotion(anime.js)이 맡고, 이 파일은 구조와 문구만 가집니다.
 */
export function LandingPage() {
  const rootRef = useRef<HTMLDivElement>(null);
  const [layout, setLayout] = useState<LayoutKind>(0);
  const [scrolled, setScrolled] = useState(false);
  useLandingMotion(rootRef, setLayout);

  // 이 페이지는 body 대신 자체 영역이 스크롤됩니다 (서비스 화면은 지도 때문에 body 스크롤을 막아 둠)
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const onScroll = () => setScrolled(el.scrollTop > 24);
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  const goTo = (id: string) => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    document.getElementById(id)?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
  };

  const t = content.landing;
  const h = t.hero;

  return (
    <div ref={rootRef} className={styles.page}>
      <header className={cx(styles.nav, scrolled && styles.navSolid)}>
        <Link to="/" className={styles.brand}>
          <img src="/brand/logo.svg" alt={content.brand.logoAlt} width={28} height={28} />
          <span className={styles.brandName}>{content.brand.name}</span>
          <span className={styles.brandTagline}>{content.brand.tagline}</span>
        </Link>
        <nav className={styles.navLinks} aria-label={t.nav.label}>
          <button type="button" onClick={() => goTo('how')}>
            {t.nav.how}
          </button>
          <button type="button" onClick={() => goTo('basis')}>
            {t.nav.basis}
          </button>
          <button type="button" onClick={() => goTo('features')}>
            {t.nav.features}
          </button>
        </nav>
        <Link to={SERVICE_PATH} className={cx(styles.cta, styles.ctaPrimary, styles.ctaSmall)}>
          {t.nav.start}
          <Icon name="arrow-right" size={16} />
        </Link>
        <span className={styles.progress} data-anim="progress" aria-hidden="true" />
      </header>

      <div data-anim="content">
        <main>
          <section className={styles.hero} data-anim="hero">
            <div className={styles.heroInner}>
              <div className={styles.heroCopy} data-anim="hero-copy">
                <p className={cx(styles.eyebrow, styles.eyebrowDark)} data-anim="hero-fade">
                  {h.eyebrow}
                </p>
                <h1 className={styles.headline} aria-label={h.headline.join(' ')}>
                  {h.headline.map((line, li) => (
                    <span key={li} className={cx(styles.line, li === 1 && styles.lineAccent)} aria-hidden="true">
                      {line.split(' ').map((word, wi) => (
                        <span key={wi} className={styles.word}>
                          {Array.from(word).map((ch, ci) => (
                            <span key={ci} data-anim="hero-char" className={styles.char}>
                              {ch}
                            </span>
                          ))}
                        </span>
                      ))}
                    </span>
                  ))}
                </h1>
                <p className={styles.heroSub} data-anim="hero-fade">
                  {h.sub}
                </p>
                <div className={styles.heroActions} data-anim="hero-fade">
                  <Link to={SERVICE_PATH} className={cx(styles.cta, styles.ctaPrimary)}>
                    {h.start}
                    <Icon name="arrow-right" size={18} />
                  </Link>
                  <button type="button" className={cx(styles.cta, styles.ctaGhost)} onClick={() => goTo('how')}>
                    {h.more}
                    <Icon name="chevron-down" size={18} />
                  </button>
                </div>
                <p className={styles.heroNote} data-anim="hero-fade">
                  {h.note}
                </p>
              </div>
              <div className={styles.heroVisual} data-anim="hero-visual">
                <HeroScene layout={layout} />
              </div>
            </div>

            <div className={styles.scrubber} data-anim="scrubber" aria-hidden="true">
              <span className={styles.scrubMarks}>
                {[...h.layouts, h.layouts[0]].map((l, i) => (
                  <span key={i}>{l.code}</span>
                ))}
              </span>
              <span className={styles.scrubTrack}>
                <span className={styles.playhead} />
              </span>
            </div>
          </section>

          <section className={styles.stats}>
            <ul className={styles.statList} data-reveal-group>
              {t.stats.map((s) => (
                <li key={s.label} className={styles.stat} data-reveal-item>
                  <p className={styles.statValue}>
                    <span className="numeric" data-count={s.value}>
                      {s.value}
                    </span>
                    <small>{s.unit}</small>
                  </p>
                  <strong>{s.label}</strong>
                  <span>{s.desc}</span>
                </li>
              ))}
            </ul>
          </section>

          <section id="how" className={styles.section}>
            <SectionHead no={t.how.no} tag={t.how.tag} title={t.how.title} desc={t.how.desc} />
            <ol className={styles.steps} data-reveal-group>
              {t.how.steps.map((s, i) => (
                <li key={s.step} className={styles.step} data-reveal-item>
                  <span className={styles.stepNo}>{s.step}</span>
                  <h3>{s.title}</h3>
                  <p>{s.desc}</p>
                  {STEP_DEMOS[i]}
                  <span className={styles.sample}>{t.how.sample}</span>
                </li>
              ))}
            </ol>
          </section>

          <section id="basis" className={cx(styles.section, styles.sectionAlt)}>
            <div className={styles.split}>
              <div>
                <SectionHead no={t.basis.no} tag={t.basis.tag} title={t.basis.title} desc={t.basis.desc} />
                <div className={styles.statuses} data-reveal-group>
                  <p className={styles.statusesTitle} data-reveal-item>
                    {t.basis.statusTitle}
                  </p>
                  <ul>
                    {t.basis.statuses.map((s) => (
                      <li key={s.status} data-reveal-item>
                        {isRuleStatus(s.status) && <StatusBadge status={s.status} />}
                        <span>{s.desc}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
              <BasisTree />
            </div>
          </section>

          <section id="features" className={styles.section}>
            <SectionHead no={t.features.no} tag={t.features.tag} title={t.features.title} desc={t.features.desc} />
            <ul className={styles.features} data-reveal-group>
              {t.features.items.map((f) => (
                <li key={f.title} className={styles.feature} data-reveal-item data-anim="feature">
                  <span className={styles.featureIcon} data-anim="feature-icon">
                    {isIconName(f.icon) && <Icon name={f.icon} size={24} />}
                  </span>
                  <h3>{f.title}</h3>
                  <p>{f.desc}</p>
                </li>
              ))}
            </ul>
          </section>

          <section className={styles.final} data-anim="final">
            <RippleGrid />
            <div className={styles.finalInner} data-reveal>
              <p className={cx(styles.eyebrow, styles.eyebrowDark)}>{t.cta.tag}</p>
              <h2>{t.cta.title}</h2>
              <p>{t.cta.desc}</p>
              <Link to={SERVICE_PATH} className={cx(styles.cta, styles.ctaPrimary, styles.ctaLarge)}>
                {t.cta.start}
                <Icon name="arrow-right" size={20} />
              </Link>
              <span className={styles.finalHint}>{t.cta.hint}</span>
            </div>
          </section>
        </main>

        <footer className={styles.footer}>
          <span className={styles.footerBrand}>
            <img src="/brand/logo.svg" alt="" width={20} height={20} />
            {content.brand.name} · {content.brand.tagline}
          </span>
          <span>{t.footer.disclaimer}</span>
          <span>{t.footer.sources}</span>
        </footer>
      </div>
    </div>
  );
}

function SectionHead({ no, tag, title, desc }: { no: string; tag: string; title: string; desc: string }) {
  return (
    <div className={styles.head} data-reveal>
      <p className={styles.eyebrow}>
        <span className={styles.eyebrowNo}>{no}</span>
        <span className={styles.eyebrowRule} aria-hidden="true" />
        {tag}
      </p>
      <h2>{title}</h2>
      <p className={styles.headDesc}>{desc}</p>
    </div>
  );
}
