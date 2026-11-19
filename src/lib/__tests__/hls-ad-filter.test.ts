import { filterAdsFromM3U8 } from '@/lib/hls-ad-filter.client';

const DISC = '#EXT-X-DISCONTINUITY';

/** 把若干行拼成清单，方便逐行对比 */
const list = (...lines: string[]) => lines.join('\n');

/** 数断点个数（只认真正的断点标记，不含 #EXT-X-DISCONTINUITY-SEQUENCE） */
const countDiscontinuities = (content: string) =>
  content.split('\n').filter((line) => line.trim() === DISC).length;

/** 线上后台配置的旧自定义规则：见到断点就跳过 */
const LEGACY_CUSTOM_CODE = `
function filterAdsFromM3U8(type: string, m3u8Content: string): string {
  if (!m3u8Content) return '';
  const adKeywords = ['sponsor', '/ad/', '/ads/', 'advert', 'advertisement', '/adjump', 'redtraffic'];
  const lines = m3u8Content.split('\n');
  const filteredLines = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.includes('#EXT-X-DISCONTINUITY')) { i++; continue; }
    if (line.includes('#EXTINF:') && i + 1 < lines.length) {
      const nextLine = lines[i + 1];
      if (adKeywords.some((k) => nextLine.toLowerCase().includes(k.toLowerCase()))) {
        i += 2;
        continue;
      }
    }
    filteredLines.push(line);
    i++;
  }
  return filteredLines.join('\n');
}
`;

/** 某个分片地址上方（跳过它自己的 #EXTINF）那一行，用来检查拼缝断点 */
const lineAboveSegment = (lines: string[], uri: string) =>
  lines[lines.indexOf(uri) - 2];

/** 分片数 / 总时长 */
const summarize = (content: string) => {
  let segments = 0;
  let duration = 0;
  for (const line of content.split('\n')) {
    if (line.includes('#EXTINF:')) {
      segments++;
      duration += parseFloat(line.slice(line.indexOf(':') + 1)) || 0;
    }
  }
  return { segments, duration };
};

describe('filterAdsFromM3U8 断点处理', () => {
  let consoleLog: jest.SpyInstance;
  let consoleWarn: jest.SpyInstance;

  beforeEach(() => {
    consoleLog = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    consoleWarn = jest
      .spyOn(console, 'warn')
      .mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleLog.mockRestore();
    consoleWarn.mockRestore();
  });

  // 线上真实故障形状：2769.6s / 1370 个分片 / 5 个合法断点，没有任何广告分片。
  // 旧实现会把 5 个断点全部丢掉，播放器时间轴因此断裂。
  it('没有广告但有 5 个断点时，输出与输入完全一致', () => {
    const lines: string[] = [
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '#EXT-X-TARGETDURATION:4',
      '#EXT-X-MEDIA-SEQUENCE:0',
    ];
    const breakpoints = new Set([137, 411, 686, 960, 1234]);
    for (let i = 0; i < 1370; i++) {
      if (breakpoints.has(i)) lines.push(DISC);
      lines.push('#EXTINF:2.022,');
      lines.push(`hls/seg-${i}.ts`);
    }
    lines.push('#EXT-X-ENDLIST');
    const source = list(...lines);

    const output = filterAdsFromM3U8('测试源', source);

    expect(output).toBe(source);
    expect(countDiscontinuities(output)).toBe(5);
    expect(consoleLog).not.toHaveBeenCalled();
  });

  it('中插广告：保留原有断点，拼缝处恰好一个断点', () => {
    const source = list(
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '#EXT-X-TARGETDURATION:6',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      '/ad/ad-2.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-3.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-4.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);
    const lines = output.split('\n');

    // 广告分片被摘掉
    expect(output).not.toContain('/ad/ad-1.ts');
    expect(output).not.toContain('/ad/ad-2.ts');
    // 原有 2 个断点 + 拼缝 1 个，且没有重复
    expect(countDiscontinuities(output)).toBe(3);
    expect(output).not.toContain(`${DISC}\n${DISC}`);
    // 拼缝处正好一个断点，顺序是 seg-2 → 断点 → seg-3
    expect(lines[lines.indexOf('seg-2.ts') + 1]).toBe(DISC);
    expect(lineAboveSegment(lines, 'seg-3.ts')).toBe(DISC);
    // 分片数与时长：只剩 4 个 6s 正片
    expect(summarize(output)).toEqual({ segments: 4, duration: 24 });
  });

  it('中插广告两侧没有源断点时，在拼缝补一个断点', () => {
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXTINF:6.0,',
      '/adjump/ad-1.ts',
      '#EXTINF:6.0,',
      'seg-3.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);
    const lines = output.split('\n');

    expect(countDiscontinuities(output)).toBe(1);
    expect(lines[lines.indexOf('seg-2.ts') + 1]).toBe(DISC);
    expect(lineAboveSegment(lines, 'seg-3.ts')).toBe(DISC);
  });

  it('片头广告：删掉后不留孤立断点', () => {
    const source = list(
      '#EXTM3U',
      '#EXT-X-TARGETDURATION:6',
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-1.ts',
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);
    const lines = output.split('\n');

    expect(countDiscontinuities(output)).toBe(0);
    // 第一行媒体行必须是正片分片，不是断点
    const firstMedia = lines.find(
      (line) => line === DISC || line.endsWith('.ts')
    );
    expect(firstMedia).toBe('seg-1.ts');
    expect(summarize(output)).toEqual({ segments: 2, duration: 12 });
  });

  it('片尾广告：删掉后不留孤立断点', () => {
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      '/ad/ad-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);
    const lines = output.split('\n').filter((line) => line.trim());

    expect(countDiscontinuities(output)).toBe(0);
    expect(lines[lines.length - 1]).toBe('#EXT-X-ENDLIST');
    expect(lines[lines.length - 2]).toBe('seg-1.ts');
  });

  it('连续广告分片只补一个断点', () => {
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      '/ad/ad-2.ts',
      '#EXTINF:6.0,',
      '/ad/ad-3.ts',
      '#EXTINF:6.0,',
      '/ad/ad-4.ts',
      DISC,
      DISC,
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);

    expect(countDiscontinuities(output)).toBe(1);
    expect(output).not.toContain('/ad/ad-');
    // 断点紧贴拼缝，且只有一个
    const lines = output.split('\n');
    expect(lines[lines.indexOf('seg-1.ts') + 1]).toBe(DISC);
    expect(lineAboveSegment(lines, 'seg-2.ts')).toBe(DISC);
  });

  it('关键字误伤把正片全删光时回退为原清单', () => {
    const source = list(
      '#EXTM3U',
      '#EXT-X-TARGETDURATION:6',
      '#EXTINF:6.0,',
      '/ads/seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      '/ads/seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);

    expect(output).toBe(source);
    expect(countDiscontinuities(output)).toBe(1);
    expect(consoleWarn).toHaveBeenCalled();
  });

  it('头部的 #EXT-X-DISCONTINUITY-SEQUENCE 不会被当成断点删掉', () => {
    const source = list(
      '#EXTM3U',
      '#EXT-X-DISCONTINUITY-SEQUENCE:2',
      '#EXTINF:6.0,',
      'seg-1.ts',
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source);

    expect(output).toContain('#EXT-X-DISCONTINUITY-SEQUENCE:2');
    expect(countDiscontinuities(output)).toBe(1);
  });

  it('没有任何广告时不做任何改写（含 CRLF 与空行）', () => {
    const source = list(
      '#EXTM3U',
      '#EXT-X-VERSION:3',
      '',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-2.ts',
      ''
    );

    expect(filterAdsFromM3U8('测试源', source)).toBe(source);
    expect(filterAdsFromM3U8('测试源', '')).toBe('');
  });
});

describe('filterAdsFromM3U8 后台自定义规则兼容', () => {
  let consoleLog: jest.SpyInstance;
  let consoleWarn: jest.SpyInstance;
  let consoleError: jest.SpyInstance;

  beforeEach(() => {
    consoleLog = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    consoleWarn = jest
      .spyOn(console, 'warn')
      .mockImplementation(() => undefined);
    consoleError = jest
      .spyOn(console, 'error')
      .mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleLog.mockRestore();
    consoleWarn.mockRestore();
    consoleError.mockRestore();
  });

  it('旧自定义规则删光断点但没删分片时，源清单原样返回', () => {
    const lines: string[] = ['#EXTM3U', '#EXT-X-TARGETDURATION:4'];
    const breakpoints = new Set([2, 5, 8, 11, 14]);
    for (let i = 0; i < 20; i++) {
      if (breakpoints.has(i)) lines.push(DISC);
      lines.push('#EXTINF:2,');
      lines.push(`seg-${i}.ts`);
    }
    const source = list(...lines);

    const output = filterAdsFromM3U8('测试源', source, LEGACY_CUSTOM_CODE);

    expect(output).toBe(source);
    expect(countDiscontinuities(output)).toBe(5);
  });

  it('旧自定义规则删广告时，仍保留源断点并在拼缝补一个', () => {
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      'seg-3.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-4.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source, LEGACY_CUSTOM_CODE);
    const lines = output.split('\n');

    expect(output).not.toContain('/ad/ad-1.ts');
    expect(countDiscontinuities(output)).toBe(3);
    expect(output).not.toContain(`${DISC}\n${DISC}`);
    expect(lineAboveSegment(lines, 'seg-3.ts')).toBe(DISC);
  });

  it('自定义规则自己正确处理断点时不改写它的结果', () => {
    const goodCode = `
function filterAdsFromM3U8(type: string, m3u8Content: string): string {
  return m3u8Content.replace('#EXTINF:6.0,\n/ad/ad-1.ts\n', '');
}
`;
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      '/ad/ad-1.ts',
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source, goodCode);

    expect(countDiscontinuities(output)).toBe(1);
    expect(output).not.toContain('/ad/ad-1.ts');
    expect(output).toContain('seg-2.ts');
  });

  it('自定义规则改写了分片地址时保持它的结果，不崩', () => {
    const rewriteCode = `
function filterAdsFromM3U8(type: string, m3u8Content: string): string {
  return m3u8Content.replace(/seg-1[.]ts/g, 'rewritten.ts');
}
`;
    const source = list(
      '#EXTM3U',
      '#EXTINF:6.0,',
      'seg-1.ts',
      DISC,
      '#EXTINF:6.0,',
      'seg-2.ts',
      '#EXT-X-ENDLIST'
    );

    const output = filterAdsFromM3U8('测试源', source, rewriteCode);

    expect(output).toContain('rewritten.ts');
    expect(consoleError).not.toHaveBeenCalled();
  });
});
