/* eslint-disable @typescript-eslint/no-explicit-any */

import { NextRequest, NextResponse } from 'next/server';

import { getAuthInfoFromCookie } from '@/lib/auth';
import { getAvailableApiSites, getConfig } from '@/lib/config';
import { getDetailFromApiV2 } from '@/lib/downstream';
import { EPISODE_RESPONSE_HEADERS } from '@/lib/episode-freshness';
import {
  executeSavedSourceScript,
  normalizeScriptDetailResult,
  normalizeScriptSources,
  parseScriptSourceValue,
} from '@/lib/source-script';

export const runtime = 'nodejs';

/**
 * 解析站点 origin。
 * 优先级：SITE_BASE（站点 url 环境变量）> NEXT_PUBLIC_SITE_URL > 请求头 Host。
 */
function getRequestSiteOrigin(request: NextRequest): string {
  const fromEnv =
    (process.env.SITE_BASE || '').trim() ||
    (process.env.NEXT_PUBLIC_SITE_URL || '').trim();

  if (fromEnv) {
    return fromEnv.replace(/\/$/, '');
  }

  let host =
    request.headers.get('host') || request.headers.get('x-forwarded-host');

  if (host && !/^[a-zA-Z0-9.-]+(:\d+)?$/.test(host)) {
    host = null;
  }

  if (!host) {
    try {
      host = new URL(request.url).host;
    } catch {
      host = 'localhost';
    }
  }

  const proto =
    request.headers.get('x-forwarded-proto') ||
    (host.includes('localhost') || host.includes('127.0.0.1')
      ? 'http'
      : 'https');

  return `${proto}://${host}`.replace(/\/$/, '');
}

/**
 * MoonTVPlus APP / OrionTV 客户端：对配置的视频源 m3u8 套一层去广告代理。
 * UA 小写包含 "moontvplus app" 或 "oriontv" 时生效（不匹配仅含 moontvplus 的其它客户端）。
 */
function applyClientAdProxyToEpisodes(
  request: NextRequest,
  sourceCode: string,
  episodes: string[] | undefined,
  clientAdSourceApis: string[] | undefined
): string[] | undefined {
  if (!episodes || episodes.length === 0) return episodes;
  if (!clientAdSourceApis || !clientAdSourceApis.includes(sourceCode)) {
    return episodes;
  }

  const ua = (request.headers.get('user-agent') || '').toLowerCase();
  if (!ua.includes('moontvplus app') && !ua.includes('oriontv')) {
    return episodes;
  }

  const origin = getRequestSiteOrigin(request);
  return episodes.map((episode) => {
    if (!episode || typeof episode !== 'string') return episode;
    if (
      episode.includes('/api/proxy-m3u8') ||
      episode.includes('/api/proxy/vod/m3u8')
    ) {
      return episode;
    }
    // 仅处理 http(s) 直链 m3u8，站内相对播放地址不改写
    if (!/^https?:\/\//i.test(episode)) return episode;
    return `${origin}/api/proxy-m3u8?url=${encodeURIComponent(episode)}`;
  });
}

/**
 * 根据 source 和 id 直接获取视频详情
 * 这个API专门用于play页面快速获取当前源的详情
 */
export async function GET(request: NextRequest) {
  const authInfo = getAuthInfoFromCookie(request);
  if (!authInfo || !authInfo.username) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const { searchParams } = new URL(request.url);
  const id = searchParams.get('id');
  const sourceCode = searchParams.get('source');
  const specialOnly = searchParams.get('special') === '1';

  if (!id || !sourceCode) {
    return NextResponse.json({ error: '缺少必要参数' }, { status: 400 });
  }

  const parsedScriptSource = parseScriptSourceValue(sourceCode);
  if (parsedScriptSource) {
    try {
      const sourcesExecution = await executeSavedSourceScript({
        key: parsedScriptSource.scriptKey,
        hook: 'getSources',
        payload: {},
      });
      const sources = normalizeScriptSources(sourcesExecution.result);
      const sourceInfo = sources.find(
        (item) => item.id === parsedScriptSource.sourceId
      ) || {
        id: parsedScriptSource.sourceId,
        name: parsedScriptSource.sourceId,
      };

      const detailExecution = await executeSavedSourceScript({
        key: parsedScriptSource.scriptKey,
        hook: 'detail',
        payload: {
          id,
          sourceId: parsedScriptSource.sourceId,
        },
      });

      const normalized = normalizeScriptDetailResult({
        source: sourceCode,
        scriptKey: parsedScriptSource.scriptKey,
        scriptName: detailExecution.meta?.name || parsedScriptSource.scriptKey,
        sourceId: parsedScriptSource.sourceId,
        sourceName: sourceInfo.name,
        detailId: id,
        result: detailExecution.result,
      });

      return NextResponse.json(normalized, { headers: EPISODE_RESPONSE_HEADERS });
    } catch (error) {
      return NextResponse.json(
        { error: (error as Error).message },
        { status: 500 }
      );
    }
  }

  if (!/^[\w-]+$/.test(id)) {
    return NextResponse.json({ error: '无效的视频ID格式' }, { status: 400 });
  }

  // 对于其他采集源，直接按 id 获取详情。
  try {
    const apiSites = await getAvailableApiSites(authInfo.username, specialOnly);
    const apiSite = apiSites.find((site) => site.key === sourceCode);

    if (!apiSite) {
      return NextResponse.json({ error: '无效的API来源' }, { status: 400 });
    }

    const result = await getDetailFromApiV2(apiSite, id);

    // 添加 proxyMode 到返回结果
    const resultWithProxy = {
      ...result,
      proxyMode: apiSite.proxyMode || false,
    };

    // 客户端广告配置：指定源 + APP/OrionTV UA 时 m3u8 套 proxy-m3u8
    const adminConfig = await getConfig();
    const clientAdEnabled = (adminConfig.ClientAdSourceApis || []).includes(
      sourceCode
    );
    resultWithProxy.episodes =
      applyClientAdProxyToEpisodes(
        request,
        sourceCode,
        resultWithProxy.episodes,
        adminConfig.ClientAdSourceApis
      ) || resultWithProxy.episodes;

    // 缓存原始详情一分钟；用户权限校验和 UA 广告代理每次请求分别处理。
    // 不让浏览器/CDN 在应用缓存外再叠加两小时缓存。
    return NextResponse.json(resultWithProxy, {
      headers: {
        ...EPISODE_RESPONSE_HEADERS,
        ...(clientAdEnabled ? { Vary: 'User-Agent' } : {}),
      },
    });
  } catch (error) {
    return NextResponse.json(
      { error: (error as Error).message },
      { status: 500 }
    );
  }
}
