/**
 * 지도 fitBounds 여백을 재기 위해 MapContext 가 찾는 DOM 기준점.
 * MapShell(좌측 패널·모바일 시트)과 MapControls(우측 컨트롤)는 이 속성을 붙이고, getFitPadding 은 같은 이름으로 질의합니다.
 * 이름을 한 곳에서만 관리해 컴포넌트를 옮겨도 여백 계산이 조용히 폴백으로 떨어지지 않게 합니다.
 */
export const MAP_PANEL_ATTR = 'data-map-panel';
export const MAP_CONTROLS_ATTR = 'data-map-controls';

export const mapPanelProps = { [MAP_PANEL_ATTR]: '' } as const;
export const mapControlsProps = { [MAP_CONTROLS_ATTR]: '' } as const;

export function queryAnchor(attr: typeof MAP_PANEL_ATTR | typeof MAP_CONTROLS_ATTR): HTMLElement | null {
  return document.querySelector<HTMLElement>(`[${attr}]`);
}
