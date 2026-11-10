import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin, PreviewServer, ViteDevServer } from 'vite';
import { createApiHandler, writeInternalError, type ApiHandler, type ApiOptions } from './api.ts';
import { isAllowedHost, type AllowedHosts } from './hostCheck.ts';

/**
 * 개발(`vite`)·미리보기(`vite preview`) 서버에 `/api/*` 를 붙입니다.
 * 운영에서는 같은 핸들러를 standalone.ts(또는 Lambda 등)로 띄우고 VITE_API_BASE 로 가리킵니다.
 *
 * configureServer 에서 붙인 미들웨어는 Vite 의 allowedHosts 검사보다 먼저 실행되므로,
 * DNS 리바인딩으로 승인키가 달린 프록시를 쓰지 못하게 Host 헤더를 여기서 직접 검사합니다 (hostCheck.ts).
 */
export interface ApiMiddlewareOptions {
  allowedHosts: () => AllowedHosts;
  host: () => string | boolean | undefined;
}

type NextFn = (err?: unknown) => void;

export function createApiMiddleware(handler: ApiHandler, opts: ApiMiddlewareOptions) {
  return (req: IncomingMessage, res: ServerResponse, next: NextFn): void => {
    if (!req.url?.startsWith('/api/')) return next();
    if (!isAllowedHost(req.headers.host, opts.allowedHosts(), opts.host())) {
      res.statusCode = 403;
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end(JSON.stringify({ error: 'FORBIDDEN_HOST', message: '허용되지 않은 Host 헤더입니다.' }));
      return;
    }
    handler(req, res).catch((err: unknown) => writeInternalError(res, err));
  };
}

export function apiPlugin(opts: ApiOptions): Plugin {
  const handler = createApiHandler(opts);

  return {
    name: 'app-api',
    configureServer(server: ViteDevServer) {
      server.middlewares.use(
        createApiMiddleware(handler, {
          allowedHosts: () => server.config.server.allowedHosts,
          host: () => server.config.server.host,
        }),
      );
    },
    configurePreviewServer(server: PreviewServer) {
      server.middlewares.use(
        createApiMiddleware(handler, {
          allowedHosts: () => server.config.preview.allowedHosts,
          host: () => server.config.preview.host,
        }),
      );
    },
  };
}
