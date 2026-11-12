import { NextRequest, NextResponse } from 'next/server';

import { getAuthInfoFromCookie } from '@/lib/auth';
import { getAvailableApiSites, getCacheTime } from '@/lib/config';
import { getDetailFromApi } from '@/lib/downstream';
import {
  executeSavedSourceScript,
  normalizeScriptDetailResult,
  normalizeScriptSources,
  parseScriptSourceValue,
} from '@/lib/source-script';

export const runtime = 'nodejs';

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
      const sourceInfo =
        sources.find((item) => item.id === parsedScriptSource.sourceId) || {
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

      return NextResponse.json(normalized);
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

  try {
    const apiSites = await getAvailableApiSites(authInfo.username, specialOnly);
    const apiSite = apiSites.find((site) => site.key === sourceCode);

    if (!apiSite) {
      return NextResponse.json({ error: '无效的API来源' }, { status: 400 });
    }

    const result = await getDetailFromApi(apiSite, id);

    // 添加 proxyMode 到返回结果
    const resultWithProxy = {
      ...result,
      proxyMode: apiSite.proxyMode || false,
    };

    const cacheTime = await getCacheTime();

    return NextResponse.json(resultWithProxy, {
      headers: {
        'Cache-Control': `public, max-age=${cacheTime}, s-maxage=${cacheTime}`,
        'CDN-Cache-Control': `public, s-maxage=${cacheTime}`,
        'Vercel-CDN-Cache-Control': `public, s-maxage=${cacheTime}`,
        'Netlify-Vary': 'query',
      },
    });
  } catch (error) {
    return NextResponse.json(
      { error: (error as Error).message },
      { status: 500 }
    );
  }
}
