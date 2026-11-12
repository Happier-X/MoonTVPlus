import { getAvailableApiSites } from '@/lib/config';
import { SearchResult } from '@/lib/types';

import { getDetailFromApiV2 } from './downstream';

interface FetchVideoDetailOptions {
  source: string;
  id: string;
  fallbackTitle?: string;
}

/**
 * 根据 source 与 id 获取视频详情。
 * 直接调用详情接口，避免依赖搜索接口。
 */
export async function fetchVideoDetail({
  source,
  id,
  fallbackTitle: _fallbackTitle = '',
}: FetchVideoDetailOptions): Promise<SearchResult> {
  // 源已由调用方（收藏、定时任务）持有，此处按 key 直查两个分区，不做入口隔离
  const [normalSites, specialSites] = await Promise.all([
    getAvailableApiSites(),
    getAvailableApiSites(undefined, true),
  ]);
  const apiSite = [...normalSites, ...specialSites].find(
    (site) => site.key === source
  );
  if (!apiSite) {
    throw new Error('无效的API来源');
  }

  const detail = await getDetailFromApiV2(apiSite, id);
  if (!detail) {
    throw new Error('获取视频详情失败');
  }

  return detail;
}
