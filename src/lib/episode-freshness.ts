// Episode metadata changes independently of watch progress. Keep a short freshness
// window, coalesce concurrent reads and retain usable data during source outages.
export const EPISODE_FRESH_MS = 60_000;
export const EPISODE_CHECK_LIMIT = 6;
const RETAIN_MS = 30 * 60_000;
const RETRY_MS = 30_000;

export const EPISODE_RESPONSE_HEADERS = {
  'Cache-Control': 'private, no-store',
  'CDN-Cache-Control': 'no-store',
  'Vercel-CDN-Cache-Control': 'no-store',
};

export class EpisodeCache<T> {
  private entries = new Map<string, { value?: T; savedAt: number; retryAt: number }>();
  private pending = new Map<string, Promise<T>>();

  async get(key: string, load: () => Promise<T>, force = false): Promise<T> {
    const pending = this.pending.get(key);
    if (pending) return pending;
    const now = Date.now();
    const entry = this.entries.get(key);
    const usable = entry?.value !== undefined && now - entry.savedAt < RETAIN_MS;
    // Forced refresh still coalesces rapid repeated clicks (5 seconds).
    if (usable && now - entry.savedAt < (force ? 5_000 : EPISODE_FRESH_MS)) {
      return entry.value as T;
    }
    if (entry && now < entry.retryAt) {
      if (usable) return entry.value as T;
      throw new Error('源请求失败，稍后重试');
    }
    const request = Promise.resolve().then(load).then((value) => {
      this.entries.delete(key);
      this.entries.set(key, { value, savedAt: Date.now(), retryAt: 0 });
      return value;
    }).catch((error) => {
      this.entries.set(key, {
        value: usable ? entry?.value : undefined,
        savedAt: entry?.savedAt ?? 0,
        retryAt: Date.now() + RETRY_MS,
      });
      if (usable) return entry?.value as T;
      throw error;
    }).finally(() => {
      this.pending.delete(key);
      while (this.entries.size > 1000) {
        const oldest = this.entries.keys().next().value;
        if (oldest === undefined) break;
        this.entries.delete(oldest);
      }
    });
    this.pending.set(key, request);
    return request;
  }
}

export function isEpisodeCacheFresh(updatedAt: number | undefined): boolean {
  const age = Date.now() - (updatedAt ?? 0);
  return age >= 0 && age < EPISODE_FRESH_MS;
}
