import type { CSSProperties } from 'react';
import { content } from '@/lib/content';
import styles from './LandingPage.module.css';

/** 법률 → 시행령 → 조례 로 내려가는 근거 계단. 스크롤에 맞춰 연결선이 그려집니다 (useLandingMotion) */
export function BasisTree() {
  const levels = content.landing.basis.levels;
  return (
    <ol className={styles.tree} data-anim="tree">
      {levels.map((lv, i) => (
        <li key={lv.level} className={styles.treeItem} style={{ '--depth': i } as CSSProperties}>
          {i > 0 && (
            <svg className={styles.treeLink} viewBox="0 0 48 72" aria-hidden="true">
              <path data-anim="tree-link" d="M 14 -18 V 36 H 48" />
            </svg>
          )}
          <div data-anim="tree-node" className={styles.treeNode}>
            <span className={styles.treeLevel}>{lv.level}</span>
            <span className={styles.treeBody}>
              <strong>{lv.title}</strong>
              <span>{lv.example}</span>
            </span>
          </div>
        </li>
      ))}
    </ol>
  );
}
