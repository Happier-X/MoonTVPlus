/* eslint-disable @typescript-eslint/no-explicit-any,no-console */

import { NextRequest, NextResponse } from 'next/server';

import { getAuthInfoFromCookie } from '@/lib/auth';
import { getAvailableApiSites, getConfig } from '@/lib/config';
import { searchFromApi } from '@/lib/downstream';
import { yellowWords } from '@/lib/yellow';
import {
  executeSavedSourceScript,
  listEnabledSourceScripts,
  normalizeScriptSearchResults,
  normalizeScriptSources,
} from '@/lib/source-script';

export const runtime = 'nodejs';

export async function GET(request: NextRequest) {
  const authInfo = getAuthInfoFromCookie(request);
  if (!authInfo || !authInfo.username) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const { searchParams } = new URL(request.url);
  const query = searchParams.get('q');
  const specialOnly = searchParams.get('special') === '1';

  if (!query) {
    return new Response(
      JSON.stringify({ error: '搜索关键词不能为空' }),
      {
        status: 400,
        headers: {
          'Content-Type': 'application/json',
        },
      }
    );
  }

  const config = await getConfig();
  const apiSites = await getAvailableApiSites(authInfo.username, specialOnly);

  // 创建权重映射表
  const weightMap = new Map<string, number>();
  config.SourceConfig.forEach(source => {
    weightMap.set(source.key, source.weight ?? 0);
  });

  // 按权重降序排序 apiSites
  const sortedApiSites = [...apiSites].sort((a, b) => {
    const weightA = weightMap.get(a.key) ?? 0;
    const weightB = weightMap.get(b.key) ?? 0;
    return weightB - weightA;
  });

  const enabledScripts = await listEnabledSourceScripts();

  // 共享状态
  let streamClosed = false;

  // 创建可读流
  const stream = new ReadableStream({
    async start(controller) {
      const encoder = new TextEncoder();

      // 辅助函数：安全地向控制器写入数据
      const safeEnqueue = (data: Uint8Array) => {
        try {
          if (streamClosed || (!controller.desiredSize && controller.desiredSize !== 0)) {
            // 流已标记为关闭或控制器已关闭
            return false;
          }
          controller.enqueue(data);
          return true;
        } catch (error) {
          // 控制器已关闭或出现其他错误
          console.warn('Failed to enqueue data:', error);
          streamClosed = true;
          return false;
        }
      };

      const totalSourceCount = sortedApiSites.length + enabledScripts.length;

      // 发送开始事件
      const startEvent = `data: ${JSON.stringify({
        type: 'start',
        query,
        totalSources: totalSourceCount,
        timestamp: Date.now()
      })}\n\n`;

      if (!safeEnqueue(encoder.encode(startEvent))) {
        return; // 连接已关闭，提前退出
      }

      // 记录已完成的源数量
      let completedSources = 0;
      const allResults: any[] = [];

      const maybeComplete = () => {
        if (completedSources !== totalSourceCount || streamClosed) return;
        const completeEvent = `data: ${JSON.stringify({
          type: 'complete',
          totalResults: allResults.length,
          completedSources,
          timestamp: Date.now()
        })}\n\n`;

        if (safeEnqueue(encoder.encode(completeEvent))) {
          streamClosed = true;
          try {
            controller.close();
          } catch (error) {
            console.warn('Failed to close controller:', error);
          }
        }
      };

      if (totalSourceCount === 0) {
        maybeComplete();
        return;
      }

      // 为每个源创建搜索 Promise
      const searchPromises = sortedApiSites.map(async (site) => {
        try {
          // 添加超时控制
          const searchPromise = Promise.race([
            searchFromApi(site, query),
            new Promise((_, reject) =>
              setTimeout(() => reject(new Error(`${site.name} timeout`)), 20000)
            ),
          ]);

          const results = await searchPromise as any[];

          // 添加安全检查，确保结果是数组
          const safeResults = Array.isArray(results) ? results : [];

          // 过滤黄色内容
          let filteredResults = safeResults;
          if (!config.SiteConfig.DisableYellowFilter) {
            filteredResults = safeResults.filter((result) => {
              const typeName = result.type_name || '';
              return !yellowWords.some((word: string) => typeName.includes(word));
            });
          }

          filteredResults = filteredResults.map((result) => ({
            ...result,
            weight: result.weight ?? (weightMap.get(result.source) ?? 0),
          }));

          // 发送该源的搜索结果
          completedSources++;

          if (!streamClosed) {
            const sourceEvent = `data: ${JSON.stringify({
              type: 'source_result',
              source: site.key,
              sourceName: site.name,
              results: filteredResults,
              timestamp: Date.now()
            })}\n\n`;

            if (!safeEnqueue(encoder.encode(sourceEvent))) {
              streamClosed = true;
              return; // 连接已关闭，停止处理
            }
          }

          if (filteredResults.length > 0) {
            allResults.push(...filteredResults);
          }

        } catch (error) {
          console.warn(`搜索失败 ${site.name}:`, error);

          // 发送源错误事件
          completedSources++;

          if (!streamClosed) {
            const errorEvent = `data: ${JSON.stringify({
              type: 'source_error',
              source: site.key,
              sourceName: site.name,
              error: error instanceof Error ? error.message : '搜索失败',
              timestamp: Date.now()
            })}\n\n`;

            if (!safeEnqueue(encoder.encode(errorEvent))) {
              streamClosed = true;
              return; // 连接已关闭，停止处理
            }
          }
        }

        // 检查是否所有源都已完成
        if (completedSources === totalSourceCount) {
          if (!streamClosed) {
            // 发送最终完成事件
            const completeEvent = `data: ${JSON.stringify({
              type: 'complete',
              totalResults: allResults.length,
              completedSources,
              timestamp: Date.now()
            })}\n\n`;

            if (safeEnqueue(encoder.encode(completeEvent))) {
              // 只有在成功发送完成事件后才关闭流
              streamClosed = true;
              try {
                controller.close();
              } catch (error) {
                console.warn('Failed to close controller:', error);
              }
            }
          }
        }
      });

      const scriptPromises = enabledScripts.map(async (script) => {
        try {
          const sourcesExecution = await Promise.race([
            executeSavedSourceScript({
              key: script.key,
              hook: 'getSources',
              payload: {},
            }),
            new Promise((_, reject) =>
              setTimeout(() => reject(new Error(`${script.name} timeout`)), 20000)
            ),
          ]);

          const sources = normalizeScriptSources((sourcesExecution as any).result);
          const sourceResults = await Promise.all(
            sources.map(async (source) => {
              const execution = await Promise.race([
                executeSavedSourceScript({
                  key: script.key,
                  hook: 'search',
                  payload: {
                    keyword: query,
                    page: 1,
                    sourceId: source.id,
                  },
                }),
                new Promise((_, reject) =>
                  setTimeout(() => reject(new Error(`${script.name}/${source.name} timeout`)), 20000)
                ),
              ]);

              return normalizeScriptSearchResults({
                scriptKey: script.key,
                scriptName: script.name,
                sourceId: source.id,
                sourceName: source.name,
                result: (execution as any).result,
              });
            })
          );

          let filteredResults = sourceResults.flat();
          if (!config.SiteConfig.DisableYellowFilter) {
            filteredResults = filteredResults.filter((result) => {
              const typeName = result.type_name || '';
              return !yellowWords.some((word: string) => typeName.includes(word));
            });
          }

          completedSources++;

          if (!streamClosed) {
            const sourceEvent = `data: ${JSON.stringify({
              type: 'source_result',
              source: `script:${script.key}`,
              sourceName: script.name,
              results: filteredResults,
              timestamp: Date.now()
            })}\n\n`;

            if (!safeEnqueue(encoder.encode(sourceEvent))) {
              streamClosed = true;
              return;
            }
          }

          if (filteredResults.length > 0) {
            allResults.push(...filteredResults);
          }
        } catch (error) {
          console.warn(`搜索脚本失败 ${script.name}:`, error);

          completedSources++;

          if (!streamClosed) {
            const errorEvent = `data: ${JSON.stringify({
              type: 'source_error',
              source: `script:${script.key}`,
              sourceName: script.name,
              error: error instanceof Error ? error.message : '搜索失败',
              timestamp: Date.now()
            })}\n\n`;

            if (!safeEnqueue(encoder.encode(errorEvent))) {
              streamClosed = true;
              return;
            }
          }
        }

        if (completedSources === totalSourceCount) {
          if (!streamClosed) {
            const completeEvent = `data: ${JSON.stringify({
              type: 'complete',
              totalResults: allResults.length,
              completedSources,
              timestamp: Date.now()
            })}\n\n`;

            if (safeEnqueue(encoder.encode(completeEvent))) {
              streamClosed = true;
              try {
                controller.close();
              } catch (error) {
                console.warn('Failed to close controller:', error);
              }
            }
          }
        }
      });

      // 等待所有搜索完成
      await Promise.allSettled([...searchPromises, ...scriptPromises]);
    },

    cancel() {
      // 客户端断开连接时，标记流已关闭
      streamClosed = true;
      console.log('Client disconnected, cancelling search stream');
    },
  });

  // 返回流式响应
  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET',
      'Access-Control-Allow-Headers': 'Content-Type',
    },
  });
}
