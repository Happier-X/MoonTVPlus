import { EpisodeCache, isEpisodeCacheFresh } from '@/lib/episode-freshness';

describe('episode metadata freshness', () => {
  let now = 1_000_000;
  beforeEach(() => {
    now = 1_000_000;
    jest.spyOn(Date, 'now').mockImplementation(() => now);
  });
  afterEach(() => jest.restoreAllMocks());

  it('revalidates N → N+K after one minute without a manual refresh', async () => {
    const cache = new EpisodeCache<number[]>();
    const load = jest.fn().mockResolvedValueOnce([1, 2, 3]).mockResolvedValue([1, 2, 3, 4, 5]);
    expect(await cache.get('series', load)).toHaveLength(3);
    now += 59_999;
    expect(await cache.get('series', load)).toHaveLength(3);
    now += 1;
    expect(await cache.get('series', load)).toHaveLength(5);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('coalesces concurrent requests, including forced refreshes', async () => {
    const cache = new EpisodeCache<number>();
    let finish!: (n: number) => void;
    const load = jest.fn(() => new Promise<number>((resolve) => { finish = resolve; }));
    const requests = [cache.get('same', load), cache.get('same', load, true)];
    await Promise.resolve();
    finish(5);
    expect(await Promise.all(requests)).toEqual([5, 5]);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('keeps usable episodes on failure and backs off instead of retrying every render', async () => {
    const cache = new EpisodeCache<number>();
    const load = jest.fn().mockResolvedValueOnce(3).mockRejectedValue(new Error('offline'));
    await cache.get('series', load);
    now += 60_000;
    expect(await cache.get('series', load)).toBe(3);
    expect(await cache.get('series', load)).toBe(3);
    expect(await cache.get('series', load, true)).toBe(3);
    expect(load).toHaveBeenCalledTimes(2);
    now += 30_000;
    expect(await cache.get('series', load)).toBe(3);
    expect(load).toHaveBeenCalledTimes(3);
  });

  it('manual refresh bypasses a fresh entry, with a rapid-click cooldown', async () => {
    const cache = new EpisodeCache<number>();
    const load = jest.fn().mockResolvedValueOnce(3).mockResolvedValue(5);
    await cache.get('series', load);
    now += 5_000;
    expect(await cache.get('series', load, true)).toBe(5);
    expect(await cache.get('series', load, true)).toBe(5);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('does not cache an outage indefinitely or retain data beyond the fallback window', async () => {
    const cache = new EpisodeCache<number>();
    const load = jest.fn().mockRejectedValue(new Error('offline'));
    await expect(cache.get('empty', load)).rejects.toThrow('offline');
    await expect(cache.get('empty', load)).rejects.toThrow();
    expect(load).toHaveBeenCalledTimes(1);
    now += 30_000;
    load.mockResolvedValueOnce(3);
    expect(await cache.get('empty', load)).toBe(3);
    now += 30 * 60_000;
    await expect(cache.get('empty', load)).rejects.toThrow('offline');
  });

  it('treats missing and future session timestamps as stale', () => {
    expect(isEpisodeCacheFresh(undefined)).toBe(false);
    expect(isEpisodeCacheFresh(now + 1)).toBe(false);
    expect(isEpisodeCacheFresh(now - 60_000)).toBe(false);
    expect(isEpisodeCacheFresh(now - 59_999)).toBe(true);
  });
});
