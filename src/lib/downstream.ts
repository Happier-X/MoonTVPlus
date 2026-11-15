/* eslint-disable @typescript-eslint/no-explicit-any */

import { API_CONFIG, ApiSite, getConfig } from '@/lib/config';
import { EpisodeCache } from '@/lib/episode-freshness';
import { parseJsonLosslessIntegers } from '@/lib/json';
import { SearchResult } from '@/lib/types';
import { cleanHtmlTags } from '@/lib/utils';

interface ApiSearchItem {
  vod_id: string | number;
  vod_name: string;
  vod_pic: string;
  vod_remarks?: string;
  vod_play_url?: string;
  vod_class?: string;
  vod_year?: string;
  vod_content?: string;
  vod_douban_id?: number;
  type_name?: string;
  vod_total?: number;
}

const searchPages = new EpisodeCache<{ results: SearchResult[]; pageCount?: number }>();
const sourceDetails = new EpisodeCache<SearchResult>();

/**
 * 通用的带缓存搜索函数
 */
async function searchWithCache(
  apiSite: ApiSite,
  query: string,
  page: number,
  url: string,
  timeoutMs = 8000,
  refresh = false
): Promise<{ results: SearchResult[]; pageCount?: number }> {
  return searchPages.get(JSON.stringify([apiSite, query.trim(), page]), async () => {
  // 由短期应用缓存控制新鲜度，避免 Next fetch 缓存再次叠加。

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      cache: 'no-store',
      headers: { ...API_CONFIG.search.headers, 'Cache-Control': 'no-cache' },
      signal: controller.signal,
    });

    clearTimeout(timeoutId);

    if (!response.ok) throw new Error(`搜索请求失败: ${response.status}`);

    const data = parseJsonLosslessIntegers<any>(await response.text());
    if (
      !data ||
      !data.list ||
      !Array.isArray(data.list)
    ) {
      throw new Error('搜索结果格式无效');
    }

    // 处理结果数据
    const allResults = data.list.map((item: ApiSearchItem) => {
      let episodes: string[] = [];
      let titles: string[] = [];

      // 使用正则表达式从 vod_play_url 提取 m3u8 链接
      if (item.vod_play_url) {
        // 先用 $$$ 分割
        const vod_play_url_array = item.vod_play_url.split('$$$');
        // 分集之间#分割，标题和播放链接 $ 分割
        vod_play_url_array.forEach((url: string) => {
          const matchEpisodes: string[] = [];
          const matchTitles: string[] = [];
          const title_url_array = url.split('#');
          title_url_array.forEach((title_url: string) => {
            const episode_title_url = title_url.split('$');
            if (
              episode_title_url.length === 2 &&
              episode_title_url[1].endsWith('.m3u8')
            ) {
              matchTitles.push(episode_title_url[0]);
              matchEpisodes.push(episode_title_url[1]);
            }
          });
          if (matchEpisodes.length > episodes.length) {
            episodes = matchEpisodes;
            titles = matchTitles;
          }
        });
      }

      return {
        id: item.vod_id.toString(),
        title: item.vod_name.trim().replace(/\s+/g, ' '),
        poster: item.vod_pic,
        episodes,
        episodes_titles: titles,
        source: apiSite.key,
        source_name: apiSite.name,
        class: item.vod_class,
        year: item.vod_year
          ? item.vod_year.match(/\d{4}/)?.[0] || ''
          : 'unknown',
        desc: cleanHtmlTags(item.vod_content || ''),
        type_name: item.type_name,
        douban_id: item.vod_douban_id,
        vod_remarks: item.vod_remarks,
        vod_total: item.vod_total,
        proxyMode: apiSite.proxyMode || false,
      };
    });

    // 过滤掉集数为 0 的结果
    const results = allResults.filter((result: SearchResult) => result.episodes.length > 0);
    // 空的成功响应不进入缓存：新建条目可能在下一分钟就出现，仍由短重试兜住。
    if (results.length === 0) throw new Error('搜索没有可用结果');

    const pageCount = page === 1 ? data.pagecount || 1 : undefined;
    return { results, pageCount };
  } finally {
    clearTimeout(timeoutId);
  }
  }, refresh);
}

export async function searchFromApi(
  apiSite: ApiSite,
  query: string,
  refresh = false
): Promise<SearchResult[]> {
  try {
    const apiBaseUrl = apiSite.api;
    const apiUrl =
      apiBaseUrl + API_CONFIG.search.path + encodeURIComponent(query);

    // 使用新的缓存搜索函数处理第一页
    const firstPageResult = await searchWithCache(apiSite, query, 1, apiUrl, 8000, refresh);
    const results = [...firstPageResult.results];
    const pageCountFromFirst = firstPageResult.pageCount;

    const config = await getConfig();
    const MAX_SEARCH_PAGES: number = config.SiteConfig.SearchDownstreamMaxPage;

    // 获取总页数
    const pageCount = pageCountFromFirst || 1;
    // 确定需要获取的额外页数
    const pagesToFetch = Math.min(pageCount - 1, MAX_SEARCH_PAGES - 1);

    // 如果有额外页数，获取更多页的结果
    if (pagesToFetch > 0) {
      const additionalPagePromises = [];

      for (let page = 2; page <= pagesToFetch + 1; page++) {
        const pageUrl =
          apiBaseUrl +
          API_CONFIG.search.pagePath
            .replace('{query}', encodeURIComponent(query))
            .replace('{page}', page.toString());

        const pagePromise = (async () => {
          // 使用新的缓存搜索函数处理分页
          const pageResult = await searchWithCache(apiSite, query, page, pageUrl, 8000, refresh);
          return pageResult.results;
        })().catch(() => []);

        additionalPagePromises.push(pagePromise);
      }

      // 等待所有额外页的结果
      const additionalResults = await Promise.all(additionalPagePromises);

      // 合并所有页的结果
      additionalResults.forEach((pageResults) => {
        if (pageResults.length > 0) {
          results.push(...pageResults);
        }
      });
    }

    return results;
  } catch (error) {
    return [];
  }
}

// 匹配 m3u8 链接的正则
const M3U8_PATTERN = /(https?:\/\/[^"'\s]+?\.m3u8)/g;

export async function getDetailFromApi(
  apiSite: ApiSite,
  id: string
): Promise<SearchResult> {
  if (apiSite.detail) {
    return handleSpecialSourceDetail(id, apiSite);
  }

  const detailUrl = `${apiSite.api}${API_CONFIG.detail.path}${id}`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 10000);

  const response = await fetch(detailUrl, {
    cache: 'no-store',
    headers: { ...API_CONFIG.detail.headers, 'Cache-Control': 'no-cache' },
    signal: controller.signal,
  });

  clearTimeout(timeoutId);

  if (!response.ok) {
    throw new Error(`详情请求失败: ${response.status}`);
  }

  const data = parseJsonLosslessIntegers<any>(await response.text());

  if (
    !data ||
    !data.list ||
    !Array.isArray(data.list) ||
    data.list.length === 0
  ) {
    throw new Error('获取到的详情内容无效');
  }

  const videoDetail = data.list[0];
  let episodes: string[] = [];
  let titles: string[] = [];

  // 处理播放源拆分
  if (videoDetail.vod_play_url) {
    // 先用 $$$ 分割
    const vod_play_url_array = videoDetail.vod_play_url.split('$$$');
    // 分集之间#分割，标题和播放链接 $ 分割
    vod_play_url_array.forEach((url: string) => {
      const matchEpisodes: string[] = [];
      const matchTitles: string[] = [];
      const title_url_array = url.split('#');
      title_url_array.forEach((title_url: string) => {
        const episode_title_url = title_url.split('$');
        if (
          episode_title_url.length === 2 &&
          episode_title_url[1].endsWith('.m3u8')
        ) {
          matchTitles.push(episode_title_url[0]);
          matchEpisodes.push(episode_title_url[1]);
        }
      });
      if (matchEpisodes.length > episodes.length) {
        episodes = matchEpisodes;
        titles = matchTitles;
      }
    });
  }

  // 如果播放源为空，则尝试从内容中解析 m3u8
  if (episodes.length === 0 && videoDetail.vod_content) {
    const matches = videoDetail.vod_content.match(M3U8_PATTERN) || [];
    episodes = matches.map((link: string) => link.replace(/^\$/, ''));
  }

  return {
    id: id.toString(),
    title: videoDetail.vod_name,
    poster: videoDetail.vod_pic,
    episodes,
    episodes_titles: titles,
    source: apiSite.key,
    source_name: apiSite.name,
    class: videoDetail.vod_class,
    year: videoDetail.vod_year
      ? videoDetail.vod_year.match(/\d{4}/)?.[0] || ''
      : 'unknown',
    desc: cleanHtmlTags(videoDetail.vod_content),
    type_name: videoDetail.type_name,
    douban_id: videoDetail.vod_douban_id,
    vod_remarks: videoDetail.vod_remarks,
    vod_total: videoDetail.vod_total,
    proxyMode: apiSite.proxyMode || false,
  };
}

export async function getDetailFromApiV2(
  apiSite: ApiSite,
  id: string
): Promise<SearchResult> {
  return sourceDetails.get(JSON.stringify([apiSite, id]), async () => {
    const result = await fetchDetailFromApiV2(apiSite, id);
    if (!result.episodes.length) throw new Error('详情没有可用剧集');
    return result;
  });
}

async function fetchDetailFromApiV2(apiSite: ApiSite, id: string): Promise<SearchResult> {
  const detailUrl = `${apiSite.api}${API_CONFIG.detail.path}${id}`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 10000);

  try {
  const response = await fetch(detailUrl, {
    cache: 'no-store',
    headers: { ...API_CONFIG.detail.headers, 'Cache-Control': 'no-cache' },
    signal: controller.signal,
  });

  clearTimeout(timeoutId);

  if (!response.ok) {
    throw new Error(`详情请求失败: ${response.status}`);
  }

  const data = parseJsonLosslessIntegers<any>(await response.text());

  if (
    !data ||
    !data.list ||
    !Array.isArray(data.list) ||
    data.list.length === 0
  ) {
    throw new Error('获取到的详情内容无效');
  }

  const videoDetail = data.list[0];
  let episodes: string[] = [];
  let titles: string[] = [];

  if (videoDetail.vod_play_url) {
    const vodPlayUrlArray = videoDetail.vod_play_url.split('$$$');
    vodPlayUrlArray.forEach((url: string) => {
      const matchEpisodes: string[] = [];
      const matchTitles: string[] = [];
      const titleUrlArray = url.split('#');
      titleUrlArray.forEach((titleUrl: string) => {
        const episodeTitleUrl = titleUrl.split('$');
        if (
          episodeTitleUrl.length === 2 &&
          episodeTitleUrl[1].endsWith('.m3u8')
        ) {
          matchTitles.push(episodeTitleUrl[0]);
          matchEpisodes.push(episodeTitleUrl[1]);
        }
      });
      if (matchEpisodes.length > episodes.length) {
        episodes = matchEpisodes;
        titles = matchTitles;
      }
    });
  }

  if (episodes.length === 0 && videoDetail.vod_content) {
    const matches = videoDetail.vod_content.match(M3U8_PATTERN) || [];
    episodes = matches.map((link: string) => link.replace(/^\$/, ''));
  }

  return {
    id: id.toString(),
    title: videoDetail.vod_name,
    poster: videoDetail.vod_pic,
    episodes,
    episodes_titles: titles,
    source: apiSite.key,
    source_name: apiSite.name,
    class: videoDetail.vod_class,
    year: videoDetail.vod_year
      ? videoDetail.vod_year.match(/\d{4}/)?.[0] || ''
      : 'unknown',
    desc: cleanHtmlTags(videoDetail.vod_content),
    type_name: videoDetail.type_name,
    douban_id: videoDetail.vod_douban_id,
    vod_remarks: videoDetail.vod_remarks,
    vod_total: videoDetail.vod_total,
    proxyMode: apiSite.proxyMode || false,
  };
  } finally {
    clearTimeout(timeoutId);
  }
}

async function handleSpecialSourceDetail(
  id: string,
  apiSite: ApiSite
): Promise<SearchResult> {
  const detailUrl = `${apiSite.detail}/index.php/vod/detail/id/${id}.html`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 10000);

  const response = await fetch(detailUrl, {
    cache: 'no-store',
    headers: { ...API_CONFIG.detail.headers, 'Cache-Control': 'no-cache' },
    signal: controller.signal,
  });

  clearTimeout(timeoutId);

  if (!response.ok) {
    throw new Error(`详情页请求失败: ${response.status}`);
  }

  const html = await response.text();
  let matches: string[] = [];

  if (apiSite.key === 'ffzy') {
    const ffzyPattern =
      /\$(https?:\/\/[^"'\s]+?\/\d{8}\/\d+_[a-f0-9]+\/index\.m3u8)/g;
    matches = html.match(ffzyPattern) || [];
  }

  if (matches.length === 0) {
    const generalPattern = /\$(https?:\/\/[^"'\s]+?\.m3u8)/g;
    matches = html.match(generalPattern) || [];
  }

  // 去重并清理链接前缀
  matches = Array.from(new Set(matches)).map((link: string) => {
    link = link.substring(1); // 去掉开头的 $
    const parenIndex = link.indexOf('(');
    return parenIndex > 0 ? link.substring(0, parenIndex) : link;
  });

  // 根据 matches 数量生成剧集标题
  const episodes_titles = Array.from({ length: matches.length }, (_, i) =>
    (i + 1).toString()
  );

  // 提取标题
  const titleMatch = html.match(/<h1[^>]*>([^<]+)<\/h1>/);
  const titleText = titleMatch ? titleMatch[1].trim() : '';

  // 提取描述
  const descMatch = html.match(
    /<div[^>]*class=["']sketch["'][^>]*>([\s\S]*?)<\/div>/
  );
  const descText = descMatch ? cleanHtmlTags(descMatch[1]) : '';

  // 提取封面
  const coverMatch = html.match(/(https?:\/\/[^"'\s]+?\.jpg)/g);
  const coverUrl = coverMatch ? coverMatch[0].trim() : '';

  // 提取年份
  const yearMatch = html.match(/>(\d{4})</);
  const yearText = yearMatch ? yearMatch[1] : 'unknown';

  return {
    id,
    title: titleText,
    poster: coverUrl,
    episodes: matches,
    episodes_titles,
    source: apiSite.key,
    source_name: apiSite.name,
    class: '',
    year: yearText,
    desc: descText,
    type_name: '',
    douban_id: 0,
    vod_remarks: undefined,
    vod_total: undefined,
    proxyMode: apiSite.proxyMode || false,
  };
}
