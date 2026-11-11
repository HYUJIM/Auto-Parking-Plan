import { useEffect, type RefObject } from 'react';
import { animate, createScope, createTimeline, createTimer, onScroll, spring, stagger, svg, utils, type Scope } from 'animejs';
import { content } from '@/lib/content';
import { DOT_GRID, LAYOUT_KINDS, miniLayout, stallLayout, type LayoutKind, type StallPose } from './sceneGeometry';

/**
 * 소개 페이지의 모든 움직임 (anime.js v4).
 *
 * - 마크업은 그대로 "완성된 정지 화면"이고, 여기서 data-anim / data-reveal / data-count 표식을 찾아 움직임을 입힙니다.
 * - createScope 하나에 담아 페이지를 떠날 때 revert() 한 번으로 전부 되돌립니다.
 * - 운영체제에서 '동작 줄이기'를 켠 사용자에게는 아무것도 움직이지 않습니다 (정지 화면 그대로).
 * - 페이지가 body 가 아니라 자체 스크롤 영역(root)이라 스크롤 관찰자에는 항상 container: root 를 넘깁니다.
 */

type El = HTMLElement | SVGElement;
type Playable = { play(): unknown; pause(): unknown };

const REDUCE_MOTION = '(prefers-reduced-motion: reduce)';
/** 배치안 하나를 보여 주는 시간 · 다음 안으로 옮겨 가는 시간 (ms) */
const HOLD = 1500;
const MOVE = 900;
/** A → B → C → A */
const SEQUENCE: readonly LayoutKind[] = [1, 2, 0];

/** 구획들을 한 배치안의 자리로 옮기는 값 (대상 순서 = 구획 순서). anime.js 의 함수 값은 (대상, 순번) 을 받습니다 */
function poseParams(poses: StallPose[]) {
  return {
    translateX: (_?: unknown, i = 0) => poses[i].x,
    translateY: (_?: unknown, i = 0) => poses[i].y,
    rotate: (_?: unknown, i = 0) => poses[i].rotate,
    opacity: (_?: unknown, i = 0) => (poses[i].visible ? 1 : 0),
    scale: (_?: unknown, i = 0) => (poses[i].visible ? 1 : 0.4),
  };
}

export function useLandingMotion(rootRef: RefObject<HTMLElement | null>, onLayout: (kind: LayoutKind) => void): void {
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const scope = createScope({ root, mediaQueries: { reduce: REDUCE_MOTION } }).add((self) => {
      if (!self || self.matches.reduce) return;
      return build(root, self, onLayout);
    });
    return () => {
      scope.revert();
    };
  }, [rootRef, onLayout]);
}

function build(root: HTMLElement, scope: Scope, onLayout: (kind: LayoutKind) => void): () => void {
  const all = <T extends El = El>(selector: string) => Array.from(root.querySelectorAll<T>(selector));
  const one = <T extends El = El>(selector: string) => root.querySelector<T>(selector);
  /** revert() 가 되돌리지 못하는 것(글자 내용·이벤트)을 되돌리는 함수들 */
  const restore: Array<() => void> = [];

  // ── 히어로 ───────────────────────────────────────────────────────
  const hero = one<HTMLElement>('[data-anim="hero"]');
  const stalls = all('[data-anim="stall"]');
  const dots = all('[data-anim="dot"]');
  const ambient: Playable[] = [];
  let heroVisible = true;

  const chars = all('[data-anim="hero-char"]');
  const fades = all('[data-anim="hero-fade"]');
  const ticks = all('[data-anim="tick"]');
  const soft = all(
    '[data-anim="parcel-fill"], [data-anim="aisle"], [data-anim="road"], [data-anim="gate"], [data-anim="car"], [data-anim="callout-text"], [data-anim="chip"]',
  );
  const arcs = svg.createDrawable(all('[data-anim="arc"]'));
  const parcel = svg.createDrawable(all('[data-anim="parcel"]'));
  const calloutLines = svg.createDrawable(all('[data-anim="callout-line"]'));

  // 첫 화면: 전부 감춘 데서 시작 (움직이기 전에 완성 화면이 잠깐 보이지 않게)
  utils.set(chars, { translateY: '110%', opacity: 0 });
  utils.set(fades, { opacity: 0, translateY: 16 });
  utils.set(ticks, { opacity: 0 });
  utils.set(dots, { opacity: 0, scale: 0 });
  utils.set(stalls, { opacity: 0, scale: 0.2 });
  utils.set(soft, { opacity: 0 });
  // 출입구 물결은 반복 움직임(startLoops)에서만 보입니다
  utils.set(all('[data-anim="gate-halo"]'), { opacity: 0 });
  utils.set([...arcs, ...parcel, ...calloutLines], { draw: '0 0' });

  /** 등장이 끝난 뒤에 만드는 반복 움직임 — 같은 대상·속성을 등장 애니메이션과 겹쳐 만들면 서로를 덮어씁니다 */
  const startLoops = () => {
    const scrubber = one<HTMLElement>('[data-anim="scrubber"]');
    const layouts = LAYOUT_KINDS.map(stallLayout);
    const step = HOLD + MOVE;

    // 배치안 A → B → C → A (구획이 자리를 옮기고, 쓰지 않는 구획은 사라짐)
    const loop = createTimeline({
      loop: true,
      defaults: { ease: 'inOutQuart', duration: MOVE },
      onUpdate: (tl) => scrubber?.style.setProperty('--p', tl.iterationProgress.toFixed(4)),
    });
    loop.call(() => onLayout(0), 0);
    SEQUENCE.forEach((kind, n) => {
      const at = HOLD + n * step;
      loop.add(stalls, { ...poseParams(layouts[kind]), delay: stagger(20, { from: 'center' }) }, at);
      loop.call(() => onLayout(kind), at + MOVE);
    });
    ambient.push(loop);

    // 측량 점 격자: 가운데에서 바깥으로 퍼지는 물결
    ambient.push(
      animate(dots, {
        scale: [
          { to: 2.1, duration: 380, ease: 'outQuad' },
          { to: 1, duration: 720, ease: 'inOutQuad' },
        ],
        opacity: [
          { to: 1, duration: 380, ease: 'outQuad' },
          { to: 0.55, duration: 720, ease: 'inOutQuad' },
        ],
        delay: stagger(70, { grid: [DOT_GRID.cols, DOT_GRID.rows], from: 'center' }),
        loop: true,
        loopDelay: 1600,
      }),
    );

    // 출입구 표시가 퍼졌다 사라짐
    const halo = one('[data-anim="gate-halo"]');
    if (halo) ambient.push(animate(halo, { scale: [1, 2.6], opacity: [0.7, 0], duration: 1700, ease: 'outQuad', loop: true }));

    // 차가 도로에서 출입구를 지나 차로로 들어옴 (SVG 경로를 따라 이동·회전)
    const car = one('[data-anim="car"]');
    const carPath = one<SVGPathElement>('[data-anim="car-path"]');
    if (car && carPath) {
      ambient.push(
        animate(car, {
          ...svg.createMotionPath(carPath),
          opacity: [
            { from: 0, to: 1, duration: 350 },
            { to: 1, duration: 3250 },
            { to: 0, duration: 600 },
          ],
          duration: 4200,
          ease: 'inOutSine',
          loop: true,
          loopDelay: 2200,
        }),
      );
    }

    if (!heroVisible) ambient.forEach((a) => a.pause());
  };

  // 등장: 글자 → 눈금 고리 → 색 호 → 점 격자 → 대지 경계 → 구획 → 지시선
  createTimeline({
    defaults: { ease: 'outExpo', duration: 900 },
    onComplete: () => scope.execute(startLoops),
  })
    .add(ticks, { opacity: 1, duration: 500, delay: stagger(7) }, 0)
    .add(chars, { translateY: 0, opacity: 1, duration: 1000, delay: stagger(26) }, 100)
    .add(arcs, { draw: '0 1', duration: 1400, ease: 'inOutQuart', delay: stagger(120) }, 200)
    .add(
      dots,
      { opacity: 0.55, scale: 1, duration: 700, delay: stagger(16, { grid: [DOT_GRID.cols, DOT_GRID.rows], from: 'center' }) },
      300,
    )
    .add(fades, { opacity: 1, translateY: 0, delay: stagger(110) }, 500)
    .add(parcel, { draw: '0 1', duration: 1200, ease: 'inOutQuad' }, 700)
    .add(soft, { opacity: 1, duration: 700, delay: stagger(40) }, 1300)
    .add(stalls, { opacity: 1, scale: 1, duration: 800, ease: 'outBack(1.6)', delay: stagger(34, { from: 'center' }) }, 1500)
    .add(calloutLines, { draw: '0 1', duration: 900, ease: 'inOutQuad', delay: stagger(120) }, 1900);

  // 눈금 고리와 색 호는 서로 반대로 천천히 돕니다 (등장과 겹치지 않는 속성이라 바로 시작)
  const ring = one('[data-anim="ring"]');
  const arcGroup = one('[data-anim="arcs"]');
  if (ring) ambient.push(animate(ring, { rotate: 360, duration: 120000, ease: 'linear', loop: true }));
  if (arcGroup) ambient.push(animate(arcGroup, { rotate: -360, duration: 80000, ease: 'linear', loop: true }));

  if (hero) {
    // 히어로가 화면 밖이면 반복 움직임을 멈춥니다
    onScroll({
      container: root,
      target: hero,
      onEnter: () => {
        heroVisible = true;
        ambient.forEach((a) => a.play());
      },
      onLeave: () => {
        heroVisible = false;
        ambient.forEach((a) => a.pause());
      },
    });

    // 스크롤에 맞춰 그림은 내려가며 작아지고, 글은 조금 올라가며 흐려집니다
    const scrub = () => onScroll({ container: root, target: hero, enter: 'top top', leave: 'top bottom', sync: 0.6 });
    const visual = one('[data-anim="hero-visual"]');
    const copy = one('[data-anim="hero-copy"]');
    if (visual) animate(visual, { translateY: [0, 90], scale: [1, 0.9], opacity: [1, 0.2], ease: 'linear', autoplay: scrub() });
    if (copy) animate(copy, { translateY: [0, -50], opacity: [1, 0], ease: 'linear', autoplay: scrub() });
  }

  // 상단의 읽은 만큼 차는 막대
  const bar = one('[data-anim="progress"]');
  const page = one('[data-anim="content"]');
  if (bar && page) {
    animate(bar, {
      scaleX: [0, 1],
      ease: 'linear',
      autoplay: onScroll({ container: root, target: page, enter: 'top top', leave: 'bottom bottom', sync: true }),
    });
  }

  // ── 스크롤하면 나타나는 요소 ─────────────────────────────────────
  const revealOnce = (target: El) => onScroll({ container: root, target, enter: 'bottom-=8% top', sync: 'play', repeat: false });

  all('[data-reveal]').forEach((el) => {
    utils.set(el, { opacity: 0, translateY: 28 });
    animate(el, { opacity: 1, translateY: 0, duration: 900, ease: 'outExpo', autoplay: revealOnce(el) });
  });
  all('[data-reveal-group]').forEach((group) => {
    const items = Array.from(group.querySelectorAll<HTMLElement>('[data-reveal-item]'));
    if (items.length === 0) return;
    utils.set(items, { opacity: 0, translateY: 32 });
    animate(items, { opacity: 1, translateY: 0, duration: 900, ease: 'outExpo', delay: stagger(110), autoplay: revealOnce(group) });
  });

  // 숫자 세기
  all<HTMLElement>('[data-count]').forEach((el) => {
    const to = Number(el.dataset.count);
    if (!Number.isFinite(to)) return;
    const state = { v: 0 };
    animate(state, {
      v: to,
      duration: 1300,
      ease: 'outExpo',
      modifier: utils.round(0),
      onUpdate: () => {
        el.textContent = String(state.v);
      },
      autoplay: onScroll({ container: root, target: el, sync: 'play', repeat: false }),
    });
    restore.push(() => {
      el.textContent = String(to);
    });
  });

  // ── 01 동작 방식: 화면에 보이는 동안만 도는 작은 시연 ─────────────
  const whileVisible = (target: El) => onScroll({ container: root, target });

  // STEP 01 — 주소가 한 글자씩 입력되고 결과가 올라옴
  const searchDemo = one('[data-anim="demo-search"]');
  const typed = one<HTMLElement>('[data-anim="typed"]');
  if (searchDemo && typed) {
    const full = typed.dataset.text ?? typed.textContent ?? '';
    const hits = all('[data-anim="hit"]');
    const caret = { n: 0 };
    const tl = createTimeline({ loop: true, loopDelay: 1500, autoplay: whileVisible(searchDemo) })
      .set(hits, { opacity: 0, translateY: 10 }, 0)
      .add(
        caret,
        {
          n: [0, full.length],
          duration: full.length * 70,
          ease: 'linear',
          modifier: utils.round(0),
          onUpdate: () => {
            typed.textContent = full.slice(0, caret.n);
          },
        },
        200,
      )
      .add(hits, { opacity: 1, translateY: 0, duration: 520, ease: 'outExpo', delay: stagger(140) }, '+=180');
    if (hits.length > 0) tl.add(hits[0], { scale: [1, 1.03, 1], duration: 520, ease: 'inOutQuad' }, '+=250');
    restore.push(() => {
      typed.textContent = full;
    });
  }

  // STEP 02 — 필지 경계가 그려지고, 면적이 올라가고, 검토 상태가 튀어나옴
  const analysisDemo = one('[data-anim="demo-analysis"]');
  if (analysisDemo) {
    const badges = all('[data-anim="badge"]');
    const outline = svg.createDrawable(all('[data-anim="mini-parcel"]'));
    const areaEl = one<HTMLElement>('[data-anim="area"]');
    const areaTo = Number(areaEl?.dataset.value ?? 0);
    const area = { v: 0 };
    const tl = createTimeline({ loop: true, loopDelay: 1700, autoplay: whileVisible(analysisDemo) })
      .set(badges, { opacity: 0, scale: 0.4 }, 0)
      .add(outline, { draw: ['0 0', '0 1'], duration: 1100, ease: 'inOutQuad' }, 100);
    if (areaEl) {
      tl.add(
        area,
        {
          v: [0, areaTo],
          duration: 1000,
          ease: 'outExpo',
          onUpdate: () => {
            areaEl.textContent = area.v.toFixed(1);
          },
        },
        '<<+=350',
      );
      restore.push(() => {
        areaEl.textContent = areaTo.toFixed(1);
      });
    }
    tl.add(badges, { opacity: 1, scale: 1, ease: spring({ stiffness: 240, damping: 15 }), delay: stagger(150) }, '-=350');
  }

  // STEP 03 — 배치안 A·B·C 를 넘겨 봄
  const resultDemo = one('[data-anim="demo-result"]');
  if (resultDemo) {
    const mini = all('[data-anim="mini-stall"]');
    const pill = one('[data-anim="tab-pill"]');
    const codeEl = one<HTMLElement>('[data-anim="mini-code"]');
    const countEl = one<HTMLElement>('[data-anim="mini-count"]');
    const codes = content.landing.hero.layouts.map((l) => l.code);
    const layouts = LAYOUT_KINDS.map(miniLayout);
    const label = (kind: LayoutKind) => () => {
      if (codeEl) codeEl.textContent = codes[kind];
      if (countEl) countEl.textContent = String(layouts[kind].filter((p) => p.visible).length);
    };
    const tl = createTimeline({ loop: true, defaults: { ease: 'inOutQuart', duration: 700 }, autoplay: whileVisible(resultDemo) });
    tl.call(label(0), 0);
    SEQUENCE.forEach((kind, n) => {
      const at = 1100 + n * 1800;
      tl.add(mini, { ...poseParams(layouts[kind]), delay: stagger(24) }, at);
      if (pill) tl.add(pill, { translateX: `${kind * 100}%` }, at);
      tl.call(label(kind), at + 350);
    });
    restore.push(label(0));
  }

  // ── 02 법규 근거: 스크롤한 만큼 법률 → 시행령 → 조례 가 이어짐 ─────
  const tree = one('[data-anim="tree"]');
  if (tree) {
    const nodes = all('[data-anim="tree-node"]');
    const links = svg.createDrawable(all('[data-anim="tree-link"]'));
    utils.set(nodes, { opacity: 0, translateX: -24 });
    utils.set(links, { draw: '0 0' });
    createTimeline({
      defaults: { ease: 'outQuad' },
      autoplay: onScroll({ container: root, target: tree, enter: 'bottom-=10% top', leave: '55% bottom', sync: 0.5 }),
    })
      .add(nodes, { opacity: 1, translateX: 0, duration: 400 }, stagger(450))
      .add(links, { draw: '0 1', duration: 350, ease: 'linear' }, stagger(450, { start: 250 }));
  }

  // ── 03 기능: 카드에 올리면 아이콘이 까딱 ──────────────────────────
  const features = all<HTMLElement>('[data-anim="feature"]');
  const wiggle = (event: Event) => {
    const icon = (event.currentTarget as HTMLElement).querySelector<HTMLElement>('[data-anim="feature-icon"]');
    if (icon) animate(icon, { rotate: [0, -10, 8, 0], scale: [1, 1.14, 1], duration: 700, ease: 'outQuad' });
  };
  features.forEach((f) => f.addEventListener('pointerenter', wiggle));
  restore.push(() => {
    features.forEach((f) => f.removeEventListener('pointerenter', wiggle));
    const icons = all<HTMLElement>('[data-anim="feature-icon"]');
    utils.remove(icons);
    icons.forEach((icon) => icon.style.removeProperty('transform'));
  });

  // ── 마지막 구역: 포인터가 지나간 칸에서 퍼지는 격자 물결 ───────────
  const grid = one<HTMLElement>('[data-anim="ripple"]');
  const final = one<HTMLElement>('[data-anim="final"]');
  if (grid && final) {
    const cells = () => grid.querySelectorAll<HTMLElement>('[data-anim="ripple-dot"]');
    const size = () => ({ cols: Number(grid.dataset.cols), rows: Number(grid.dataset.rows) });
    const ripple = (index: number) => {
      const { cols, rows } = size();
      const targets = cells();
      if (!cols || !rows || targets.length !== cols * rows) return;
      animate(targets, {
        scale: [
          { to: 2.4, duration: 200, ease: 'outQuad' },
          { to: 1, duration: 620, ease: 'inOutQuad' },
        ],
        opacity: [
          { to: 1, duration: 200, ease: 'outQuad' },
          { to: 0.3, duration: 620, ease: 'inOutQuad' },
        ],
        delay: stagger(26, { grid: [cols, rows], from: index }),
      });
    };
    const rippleRandom = () => {
      const { cols, rows } = size();
      if (cols && rows) ripple(utils.random(0, cols * rows - 1));
    };

    let last = 0;
    const onMove = (event: PointerEvent) => {
      const now = performance.now();
      if (now - last < 180) return;
      last = now;
      const { cols, rows } = size();
      const rect = grid.getBoundingClientRect();
      if (!cols || !rows || rect.width === 0 || rect.height === 0) return;
      const col = Math.min(cols - 1, Math.max(0, Math.floor(((event.clientX - rect.left) / rect.width) * cols)));
      const row = Math.min(rows - 1, Math.max(0, Math.floor(((event.clientY - rect.top) / rect.height) * rows)));
      ripple(row * cols + col);
    };
    final.addEventListener('pointermove', onMove);

    // 가만히 두어도 보이는 동안에는 가끔 물결이 생깁니다
    createTimer({
      duration: 2800,
      loop: true,
      onLoop: rippleRandom,
      autoplay: onScroll({ container: root, target: final, onEnter: rippleRandom }),
    });

    restore.push(() => {
      final.removeEventListener('pointermove', onMove);
      const targets = cells();
      utils.remove(targets);
      targets.forEach((c) => {
        c.style.removeProperty('transform');
        c.style.removeProperty('opacity');
      });
    });
  }

  return () => restore.forEach((fn) => fn());
}
