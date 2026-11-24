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

interface R2ObjectHead {
  size: number;
  httpEtag: string;
  writeHttpMetadata(headers: Headers): void;
}

interface R2ObjectBody extends R2ObjectHead {
  body: ReadableStream<Uint8Array>;
}

interface R2BucketLike {
  get(key: string, options?: { range?: { offset: number; length: number } }): Promise<R2ObjectBody | null>;
  head(key: string): Promise<R2ObjectHead | null>;
}

interface Env {
  ASSETS: { fetch(input: URL | Request, init?: RequestInit): Promise<Response> };
  DOWNLOADS: R2BucketLike;
  WATCH_ROOM: DurableObjectNamespace;
  CRON_PASSWORD?: string;
  [key: string]: unknown;
}

type RangeResult = { offset: number; length: number } | 'invalid' | null;

function parseRange(header: string | null, size: number): RangeResult {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return null;
  const [, rawStart, rawEnd] = match;
  if (!rawStart && !rawEnd) return null;
  let start = rawStart ? Number(rawStart) : size - Number(rawEnd);
  let end = rawEnd ? Number(rawEnd) : size - 1;
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < start || start >= size) {
    return 'invalid';
  }
  end = Math.min(end, size - 1);
  return { offset: start, length: end - start + 1 };
}

async function serveDownload(request: Request, env: Env, url: URL): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
  }

  const key = url.pathname.slice('/downloads/'.length);
  if (!key || key.includes('/') || key.includes('..')) {
    return new Response('Not Found', { status: 404 });
  }

  const head = await env.DOWNLOADS.head(key);
  if (!head) return new Response('Not Found', { status: 404 });

  const headers = new Headers();
  head.writeHttpMetadata(headers);
  headers.set('ETag', head.httpEtag);
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Cache-Control', key === 'latest.json' ? 'public, max-age=60' : 'public, max-age=300');

  const range = parseRange(request.headers.get('Range'), head.size);
  if (range === 'invalid') {
    return new Response(null, {
      status: 416,
      headers: { 'Content-Range': 'bytes */' + head.size },
    });
  }

  if (range) {
    headers.set('Content-Range', 'bytes ' + range.offset + '-' + (range.offset + range.length - 1) + '/' + head.size);
    headers.set('Content-Length', String(range.length));
  } else {
    headers.set('Content-Length', String(head.size));
  }

  if (request.method === 'HEAD') {
    return new Response(null, { status: range ? 206 : 200, headers });
  }

  const object = range
    ? await env.DOWNLOADS.get(key, { range: { offset: range.offset, length: range.length } })
    : await env.DOWNLOADS.get(key);
  if (!object) return new Response('Not Found', { status: 404 });

  return new Response(object.body, { status: range ? 206 : 200, headers });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    // 站点下载资源：APK 和 latest.json 由 R2 托管，保持 /downloads/* 同域 URL。
    if (url.pathname.startsWith('/downloads/')) {
      return serveDownload(request, env, url);
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
