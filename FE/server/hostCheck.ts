import { isIP } from 'node:net';

/**
 * Host 헤더 검사 — DNS 리바인딩 방어.
 *
 * Vite 는 자기 라우트에 같은 검사를 하지만(server.allowedHosts), 플러그인이 configureServer 에서 바로 붙인 미들웨어는
 * 그 검사보다 앞에서 실행됩니다. 그래서 `/api/*` 는 여기서 따로 검사합니다.
 * 허용: localhost · *.localhost · IP 주소(리바인딩은 호스트 이름이 필요하므로) · 설정된 server.host · allowedHosts 항목.
 */
export type AllowedHosts = readonly string[] | true | undefined;

/** Host 헤더에서 호스트 이름만 — 포트와 IPv6 대괄호를 뗍니다 */
export function hostnameOf(hostHeader: string): string {
  const h = hostHeader.trim().toLowerCase();
  const bracket = /^\[([^\]]+)\](?::\d+)?$/.exec(h);
  if (bracket) return bracket[1];
  const colon = h.indexOf(':');
  if (colon !== -1 && h.indexOf(':', colon + 1) === -1) return h.slice(0, colon);
  return h;
}

export function isAllowedHost(hostHeader: string | undefined, allowedHosts: AllowedHosts, configuredHost?: string | boolean): boolean {
  if (allowedHosts === true) return true;
  if (!hostHeader) return false;
  const name = hostnameOf(hostHeader);
  if (!name) return false;
  if (name === 'localhost' || name.endsWith('.localhost')) return true;
  if (isIP(name)) return true;
  if (typeof configuredHost === 'string') {
    const host = configuredHost.trim().toLowerCase();
    if (host && host !== '0.0.0.0' && host !== '::' && name === host) return true;
  }
  for (const entry of allowedHosts ?? []) {
    const e = entry.trim().toLowerCase();
    if (!e) continue;
    if (e.startsWith('.')) {
      if (name === e.slice(1) || name.endsWith(e)) return true;
    } else if (name === e) {
      return true;
    }
  }
  return false;
}
