// Cloudflare Workers 自定义入口 —— wrangler.toml 的 main 指向本文件。
//
// OpenNext 生成的 .open-next/worker.js 只导出 fetch，而：
//   * Durable Object 类必须由入口 export 出去才会被注册；
//   * Cloudflare 的定时任务要在入口 export 一个 scheduled。
// 所以这里把 OpenNext 的 fetch 复用过来，再补上上面两样。
//
// 参考：https://opennext.js.org/cloudflare/howtos/custom-worker

// @ts-ignore 构建期由 opennextjs-cloudflare 生成
import openNextWorker from './.open-next/worker.js';
import type { DurableObjectNamespace, ExecutionContext } from './src/worker/cf-runtime';

// 转发 OpenNext 自带的具名导出（DOQueueHandler / DOShardedTagCache / BucketCachePurge）。
// 当前 open-next.config.ts 用的是 dummy 缓存/队列，暂时用不到；但一旦换成真缓存，
// 这些类必须在入口注册，所以这里原样转发。
// @ts-ignore 构建期由 opennextjs-cloudflare 生成
export * from './.open-next/worker.js';

export { WatchRoomHub } from './src/worker/watch-room-hub';

interface Env {
  ASSETS: { fetch(input: URL | Request, init?: RequestInit): Promise<Response> };
  WATCH_ROOM: DurableObjectNamespace;
  CRON_PASSWORD?: string;
  [key: string]: unknown;
}

async function serveLatestApk(request: Request, env: Env, url: URL): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  }

  const manifestResponse = await env.ASSETS.fetch(new URL('/downloads/latest.json', url));
  if (!manifestResponse.ok) return new Response('Update manifest unavailable', { status: 503 });
  const manifest = (await manifestResponse.json()) as { version?: string };
  if (!manifest.version || !/^\d+\.\d+\.\d+$/.test(manifest.version)) {
    return new Response('Invalid update manifest', { status: 503 });
  }

  const headers = new Headers();
  const range = request.headers.get('Range');
  if (range) headers.set('Range', range);
  headers.set('User-Agent', 'MoonTVPlus-Android-Updater');

  const assetUrl =
    'https://github.com/Happier-X/MoonTVPlus/releases/download/client-v' + manifest.version +
    '/moontvplus-phone-' + manifest.version + '-release-signed.apk';
  const upstream = await fetch(assetUrl, { method: request.method, headers, redirect: 'follow' });
  const responseHeaders = new Headers(upstream.headers);
  responseHeaders.set('Cache-Control', 'public, max-age=300');
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    // 站点 APK 下载：Cloudflare 静态资产单文件上限 25 MiB，这里从 Release 流式转发。
    // 保持 200/206 直出，避免 302 到 GitHub CDN —— 老版更新器只认站点和 github.com。
    if (url.pathname === '/downloads/moontvplus-phone-latest.apk') {
      return serveLatestApk(request, env, url);
    }

    // 观影室的 Socket.IO 连接：整体交给 hub DO。
    // 客户端在 internal 模式下连同源 /socket.io（server-config 默认就是 internal），
    // 所以这里在进 Next 之前拦下来。
    if (url.pathname === '/socket.io' || url.pathname.startsWith('/socket.io/')) {
      const id = env.WATCH_ROOM.idFromName('hub');
      return env.WATCH_ROOM.get(id).fetch(request);
    }

    return openNextWorker.fetch(request, env, ctx);
  },

  // 定时任务：直接内部打一次 /api/cron/<password>。
  // wrangler.toml 里 CRON_WAIT_FOR_COMPLETION="true"，路由会等任务跑完再返回，
  // 所以这里 await（不要用 ctx.waitUntil —— 那是"先返回后台继续"的语义）。
  async scheduled(_event: unknown, env: Env, ctx: ExecutionContext): Promise<void> {
    const password = env.CRON_PASSWORD || 'mtvpls';
    const url = new URL(`/api/cron/${password}`, 'https://internal.invalid');
    await openNextWorker.fetch(new Request(url), env, ctx);
  },
};
