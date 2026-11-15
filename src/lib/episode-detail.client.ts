import { EpisodeCache } from '@/lib/episode-freshness';
import { SearchResult } from '@/lib/types';

const details = new EpisodeCache<SearchResult>();

/** Read-only metadata cache. Never writes a play record or changes watch progress. */
export function getFreshEpisodeDetail(source: string, id: string, special = false) {
  return details.get(JSON.stringify([source, id, special]), async () => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 9_000);
    try {
      const params = new URLSearchParams({ source, id, fresh: '1' });
      if (special) params.set('special', '1');
      const response = await fetch(`/api/source-detail?${params}`, {
        cache: 'no-store', signal: controller.signal,
      });
      if (!response.ok) throw new Error('获取视频详情失败');
      const detail = await response.json() as SearchResult;
      if (!detail.episodes?.length) throw new Error('详情没有可用剧集');
      return detail;
    } finally {
      clearTimeout(timeout);
    }
  });
}
