/** @jest-environment node */
import { getDetailFromApiV2, searchFromApi } from '@/lib/downstream';

jest.mock('@/lib/config', () => ({
  API_CONFIG: {
    search: { path: '?wd=', pagePath: '?wd={query}&pg={page}', headers: {} },
    detail: { path: '?ids=', headers: {} },
  },
  getConfig: async () => ({ SiteConfig: { SearchDownstreamMaxPage: 2 } }),
}));

function response(episodes: number, pagecount = 1) {
  return {
    ok: true,
    text: async () => JSON.stringify({ pagecount, list: [{
      vod_id: '1', vod_name: '缓存回归剧', vod_pic: '', vod_content: '',
      vod_remarks: `更新至${episodes}集`,
      vod_play_url: Array.from({ length: episodes }, (_, i) => `第${i + 1}集$https://fixture.invalid/${i + 1}.m3u8`).join('#'),
    }] }),
  };
}

describe('source search/detail cache chain', () => {
  let now = 1_000_000;
  const originalFetch = global.fetch;
  beforeEach(() => {
    now = 1_000_000;
    jest.spyOn(Date, 'now').mockImplementation(() => now);
    global.fetch = jest.fn() as typeof fetch;
  });
  afterEach(() => { jest.restoreAllMocks(); global.fetch = originalFetch; });

  it('search and detail observe new episodes independently after expiry', async () => {
    const site = { key: 'update', api: 'https://fixture.invalid', name: 'fixture' };
    (global.fetch as jest.Mock).mockResolvedValue(response(3));
    expect((await searchFromApi(site, 'series'))[0].episodes).toHaveLength(3);
    expect((await getDetailFromApiV2(site, '1')).episodes).toHaveLength(3);
    (global.fetch as jest.Mock).mockResolvedValue(response(5));
    now += 60_001;
    expect((await searchFromApi(site, 'series'))[0].episodes).toHaveLength(5);
    expect((await getDetailFromApiV2(site, '1')).episodes).toHaveLength(5);
    expect(global.fetch).toHaveBeenCalledTimes(4);
    expect(global.fetch).toHaveBeenLastCalledWith(expect.any(String), expect.objectContaining({ cache: 'no-store' }));
  });

  it('refresh reaches the source and a source outage retains the last good search/detail', async () => {
    const site = { key: 'refresh', api: 'https://fixture.invalid', name: 'fixture' };
    (global.fetch as jest.Mock).mockResolvedValue(response(3));
    await searchFromApi(site, 'series');
    await getDetailFromApiV2(site, '1');
    now += 5_001;
    (global.fetch as jest.Mock).mockResolvedValue(response(5));
    expect((await searchFromApi(site, 'series', true))[0].episodes).toHaveLength(5);
    now += 60_001;
    (global.fetch as jest.Mock).mockRejectedValue(new Error('source offline'));
    expect((await searchFromApi(site, 'series'))[0].episodes).toHaveLength(5);
    expect((await getDetailFromApiV2(site, '1')).episodes).toHaveLength(3);
    const attempts = (global.fetch as jest.Mock).mock.calls.length;
    await searchFromApi(site, 'series');
    await getDetailFromApiV2(site, '1');
    expect(global.fetch).toHaveBeenCalledTimes(attempts);
  });

  it('does not mistake an unchanged upstream for stale project data', async () => {
    const site = { key: 'unchanged', api: 'https://fixture.invalid', name: 'fixture' };
    (global.fetch as jest.Mock).mockResolvedValue(response(3));
    await searchFromApi(site, 'series');
    now += 60_001;
    expect((await searchFromApi(site, 'series'))[0].episodes).toHaveLength(3);
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  it('never mutates a cached first page while appending extra search pages', async () => {
    const site = { key: 'paging', api: 'https://fixture.invalid', name: 'fixture' };
    (global.fetch as jest.Mock).mockResolvedValue(response(3, 2));
    expect(await searchFromApi(site, 'series')).toHaveLength(2);
    expect(await searchFromApi(site, 'series')).toHaveLength(2);
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });
});
