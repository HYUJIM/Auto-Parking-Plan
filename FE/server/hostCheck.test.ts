import type { IncomingMessage, ServerResponse } from 'node:http';
import { describe, expect, it, vi } from 'vitest';
import { hostnameOf, isAllowedHost } from './hostCheck.ts';
import { createApiMiddleware } from './vitePlugin.ts';

describe('hostnameOf', () => {
  it('포트와 IPv6 대괄호를 떼고 소문자로', () => {
    expect(hostnameOf('localhost:5173')).toBe('localhost');
    expect(hostnameOf('LocalHost')).toBe('localhost');
    expect(hostnameOf('[::1]:5173')).toBe('::1');
    expect(hostnameOf('127.0.0.1:5173')).toBe('127.0.0.1');
    expect(hostnameOf('app.example.com')).toBe('app.example.com');
  });
});

describe('isAllowedHost — DNS 리바인딩 방어', () => {
  it('localhost · *.localhost · IP 주소는 항상 허용', () => {
    expect(isAllowedHost('localhost:5173', undefined)).toBe(true);
    expect(isAllowedHost('app.localhost:5173', undefined)).toBe(true);
    expect(isAllowedHost('127.0.0.1:5173', undefined)).toBe(true);
    expect(isAllowedHost('[::1]:5173', undefined)).toBe(true);
    expect(isAllowedHost('192.168.0.10:5173', undefined)).toBe(true);
  });

  it('모르는 호스트 이름과 빈 Host 는 거부', () => {
    expect(isAllowedHost('evil.example.com', undefined)).toBe(false);
    expect(isAllowedHost('evil.example.com:5173', [])).toBe(false);
    expect(isAllowedHost(undefined, undefined)).toBe(false);
    expect(isAllowedHost('', undefined)).toBe(false);
  });

  it('allowedHosts 항목(정확히 · .접두 하위 도메인)과 설정된 server.host 는 허용, true 면 전부 허용', () => {
    expect(isAllowedHost('app.example.com', ['app.example.com'])).toBe(true);
    expect(isAllowedHost('a.b.example.com', ['.example.com'])).toBe(true);
    expect(isAllowedHost('example.com', ['.example.com'])).toBe(true);
    expect(isAllowedHost('other.com', ['.example.com'])).toBe(false);
    expect(isAllowedHost('dev.local:5173', undefined, 'dev.local')).toBe(true);
    expect(isAllowedHost('dev.local:5173', undefined, '0.0.0.0')).toBe(false);
    expect(isAllowedHost('anything.example.com', true)).toBe(true);
  });
});

describe('createApiMiddleware', () => {
  function fakeReqRes(url: string, host: string | undefined) {
    const headers: Record<string, string> = {};
    let status = 200;
    let body = '';
    const req = { url, headers: host === undefined ? {} : { host } } as unknown as IncomingMessage;
    const res = {
      set statusCode(v: number) {
        status = v;
      },
      get statusCode() {
        return status;
      },
      setHeader: (k: string, v: string) => {
        headers[k] = v;
      },
      end: (chunk?: string) => {
        body = chunk ?? '';
      },
    } as unknown as ServerResponse;
    return { req, res, read: () => ({ status, headers, body }) };
  }

  it('허용되지 않은 Host 의 /api 요청은 핸들러를 부르지 않고 403', () => {
    const handler = vi.fn(async () => {});
    const mw = createApiMiddleware(handler, { allowedHosts: () => undefined, host: () => undefined });
    const { req, res, read } = fakeReqRes('/api/health', 'evil.example.com');
    const next = vi.fn();
    mw(req, res, next);
    expect(handler).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
    expect(read().status).toBe(403);
    expect(JSON.parse(read().body)).toMatchObject({ error: 'FORBIDDEN_HOST' });
  });

  it('localhost 의 /api 요청은 핸들러로, /api 밖 경로는 next 로', () => {
    const handler = vi.fn(async () => {});
    const mw = createApiMiddleware(handler, { allowedHosts: () => undefined, host: () => undefined });
    const ok = fakeReqRes('/api/health', 'localhost:5173');
    mw(ok.req, ok.res, vi.fn());
    expect(handler).toHaveBeenCalledTimes(1);

    const next = vi.fn();
    const page = fakeReqRes('/site?pnu=1', 'evil.example.com');
    mw(page.req, page.res, next);
    expect(next).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
