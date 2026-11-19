/* HLS 去广告（客户端侧）—— /play 和 /duanju/play 共用这一份：
   浏览器直连源站拉 m3u8，用自定义 hls.js loader 在响应里过滤广告分段。
   两个页面都从这里拿 filterAdsFromM3U8 / createAdFilteringHlsLoader /
   loadCustomAdFilterCode，别再各自复制一份。改过滤规则只改这里。 */

const AD_KEYWORDS = [
  'sponsor',
  '/ad/',
  '/ads/',
  'advert',
  'advertisement',
  '/adjump',
  'redtraffic',
];

const DISCONTINUITY_TAG = '#EXT-X-DISCONTINUITY';

/**
 * 是不是真正的断点标记。只认整行等于 #EXT-X-DISCONTINUITY，
 * 不能把头部的 #EXT-X-DISCONTINUITY-SEQUENCE:3 也当成断点（它是标签不是断点）。
 */
function isDiscontinuityLine(line: string): boolean {
  return line.trim() === DISCONTINUITY_TAG;
}

/** 从 #EXTINF:4.000, 里取时长秒数 */
function durationOfExtinf(line: string): number {
  const idx = line.indexOf(':');
  if (idx < 0) return 0;
  return parseFloat(line.slice(idx + 1)) || 0;
}

/**
 * 清单里的一「块」：
 * - segment：一个分片单元（#EXTINF + 可能的中间标签 + 分片地址）
 * - discontinuity：真正的断点标记
 * - line：其它原样透传的行（头部标签、KEY、MAP、注释等）
 */
type PlaylistItem =
  | { kind: 'segment'; lines: string[]; ad: boolean }
  | { kind: 'discontinuity' }
  | { kind: 'line'; line: string };

function parsePlaylist(lines: string[]): PlaylistItem[] {
  const items: PlaylistItem[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    if (isDiscontinuityLine(line)) {
      items.push({ kind: 'discontinuity' });
      i++;
      continue;
    }

    if (line.includes('#EXTINF:')) {
      // 分段单元：从 #EXTINF 到分片地址，中间夹着的标签一并算进这一块，
      // 这样后面整块丢弃时不会留下孤立的标签。
      const block = [line];
      let j = i + 1;
      while (j < lines.length) {
        const next = lines[j];
        const trimmed = next.trim();
        if (!trimmed.startsWith('#')) break;
        // 中间出现断点或下一个 #EXTINF，说明这块不规范，交回主循环
        if (isDiscontinuityLine(next) || next.includes('#EXTINF:')) break;
        block.push(next);
        j++;
      }
      const uri = j < lines.length ? lines[j] : '';
      if (j < lines.length) {
        block.push(uri);
        j++;
      }
      const lowered = uri.toLowerCase();
      const isAd = AD_KEYWORDS.some((keyword) =>
        lowered.includes(keyword.toLowerCase())
      );
      items.push({ kind: 'segment', lines: block, ad: isAd });
      i = j;
      continue;
    }

    items.push({ kind: 'line', line });
    i++;
  }
  return items;
}

export function filterAdsFromM3U8(
  type: string,
  m3u8Content: string,
  customCode?: string
): string {
  // 自定义去广告代码优先（管理员在后台配的，按源区分）
  if (customCode && customCode.trim()) {
    try {
      // 去掉 TypeScript 类型注解，当纯 JS 执行
      const jsCode = customCode
        .replace(
          /(\w+)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*([,)])/g,
          '$1$3'
        )
        .replace(
          /\)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*\{/g,
          ') {'
        )
        .replace(
          /(const|let|var)\s+(\w+)\s*:\s*(string|number|boolean|any|void|never|unknown|object)\s*=/g,
          '$1 $2 ='
        );
      const customFunction = new Function(
        'type',
        'm3u8Content',
        jsCode + '\nreturn filterAdsFromM3U8(type, m3u8Content);'
      );
      return customFunction(type, m3u8Content);
    } catch (err) {
      console.error('执行自定义去广告代码失败，使用默认规则:', err);
    }
  }

  // 默认规则：按关键字丢广告分段
  if (!m3u8Content) return '';

  const lines = m3u8Content.split('\n');
  const items = parsePlaylist(lines);
  const segmentItems = items.filter(
    (item): item is { kind: 'segment'; lines: string[]; ad: boolean } =>
      item.kind === 'segment'
  );
  const keptItems = segmentItems.filter((item) => !item.ad);
  const removedItems = segmentItems.filter((item) => item.ad);

  // 没删任何分片就别碰清单：断点数量、顺序、每一个字节都保持原样。
  // 之前这里无条件把所有 #EXT-X-DISCONTINUITY 丢掉，没有广告的清单会被削成
  // 0 个断点，播放器时间轴因此断裂（卡住 / 自动跳集 / 起播失败）。
  if (removedItems.length === 0) return m3u8Content;

  // 兜底：关键字误伤把正片全删光时，宁可带着广告播，也不能给播放器一个空清单
  if (keptItems.length === 0) {
    console.warn(
      '[去广告] 过滤后没有剩余分片，疑似关键字误伤，已回退为原清单（本次不生效）'
    );
    return m3u8Content;
  }

  // 正片分片的位置范围，用来判断某个断点是不是落在片头 / 片尾（孤立断点要丢掉）
  const firstKeptIndex = items.findIndex(
    (item) => item.kind === 'segment' && !item.ad
  );
  const lastKeptIndex = items.reduce(
    (acc, item, index) =>
      item.kind === 'segment' && !item.ad ? index : acc,
    -1
  );

  const filteredLines: string[] = [];
  // 刚删完一段广告，拼缝处需要一个断点；但只有「前面有正片、后面还有正片」才需要
  // —— 片头 / 片尾广告删掉后不该留下孤立断点。
  let pendingSplice = false;
  let spliceCount = 0;

  // 日志统计：把移除位置换算成「第几分钟」，方便和用户看到的广告时间对上
  let elapsed = 0;
  let firstAdAt = -1;
  let removedAdDuration = 0;

  // 输出缓冲里最后一条有内容的行，用来避免拼出连续两个断点
  const lastMeaningfulLine = () => {
    for (let k = filteredLines.length - 1; k >= 0; k--) {
      const trimmed = filteredLines[k].trim();
      if (trimmed) return trimmed;
    }
    return '';
  };

  const flushSplice = () => {
    if (!pendingSplice) return;
    pendingSplice = false;
    // 上一个保留的断点已经贴在这个拼缝上时不再重复补
    if (isDiscontinuityLine(lastMeaningfulLine())) return;
    filteredLines.push(DISCONTINUITY_TAG);
    spliceCount++;
  };

  items.forEach((item, index) => {
    if (item.kind === 'segment') {
      const segmentDuration = durationOfExtinf(item.lines[0]);

      if (item.ad) {
        if (firstAdAt < 0) firstAdAt = elapsed;
        removedAdDuration += segmentDuration;
        elapsed += segmentDuration;
        // 片头广告（前面没正片）和片尾广告（后面没正片）不需要拼缝断点
        if (index > firstKeptIndex && index < lastKeptIndex) {
          pendingSplice = true;
        }
        return;
      }

      flushSplice();
      filteredLines.push(...item.lines);
      elapsed += segmentDuration;
      return;
    }

    if (item.kind === 'discontinuity') {
      // 落在片头之前或片尾之后的断点没有相邻正片，留着就是孤立断点
      if (index < firstKeptIndex || index > lastKeptIndex) return;
      // 连续两个断点合并成一个（广告边界常常自带一个）
      if (isDiscontinuityLine(lastMeaningfulLine())) {
        pendingSplice = false;
        return;
      }
      filteredLines.push(DISCONTINUITY_TAG);
      pendingSplice = false;
      return;
    }

    filteredLines.push(item.line);
  });

  console.log(
    `[去广告] ${type || '(未知源)'} 移除 ${removedItems.length} 个广告分片，起于 ${formatClock(firstAdAt)}，共 ${removedAdDuration.toFixed(1)}s；` +
      `补齐拼缝断点 ${spliceCount} 个`
  );

  return filteredLines.join('\n');
}

/** 把秒数格式化成 m:ss，日志里看得更直观 */
function formatClock(seconds: number): string {
  const total = Math.max(0, Math.round(seconds || 0));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

/** 清单概要：分片数 / 总时长 / 断点标记数，用于对比过滤前后的形状 */
function summarizePlaylist(content: string): string {
  let segments = 0;
  let duration = 0;
  let discontinuities = 0;
  for (const line of content.split('\n')) {
    if (isDiscontinuityLine(line)) {
      discontinuities++;
    } else if (line.includes('#EXTINF:')) {
      segments++;
      duration += parseFloat(line.slice(line.indexOf(':') + 1)) || 0;
    }
  }
  return `分片 ${segments} / ${duration.toFixed(1)}s / 断点 ${discontinuities}`;
}

function shortenUrl(url?: string): string {
  if (!url) return '(未知)';
  const clean = url.split('?')[0];
  return clean.length > 80 ? `…${clean.slice(-80)}` : clean;
}

/**
 * 生成去广告用的 hls.js loader 类。getSource/getCustomCode 用闭包传，
 * 这样 loader 每次请求都能拿到最新的源名和自定义规则。
 * 返回类型写成 any：HlsLib 是动态 import 的，基类拿不到类型，
 * 硬标成 hls.js 的 Loader 会因为缺成员而编译不过。
 */
export function createAdFilteringHlsLoader(
  HlsLib: any,
  getSource: () => string,
  getCustomCode: () => string
): any {
  return class CustomHlsJsLoader extends HlsLib.DefaultConfig.loader {
    constructor(config: any) {
      super(config);
      const load = this.load.bind(this);
      this.load = function (context: any, config: any, callbacks: any) {
        // 只拦 manifest 和 level，分片本身不需要处理
        if (context?.type === 'manifest' || context?.type === 'level') {
          const onSuccess = callbacks.onSuccess;
          callbacks.onSuccess = function (
            response: any,
            stats: any,
            ctx: any,
            networkDetails: any
          ) {
            if (response.data && typeof response.data === 'string') {
              const before = response.data;
              response.data = filterAdsFromM3U8(
                getSource(),
                response.data,
                getCustomCode()
              );
              // 只记录媒体清单；把过滤前后播放器实际拿到的清单形状打出来，
              // 便于判断「跳集」是清单被改坏了还是播放器侧的问题
              if (ctx?.type === 'level' && before !== response.data) {
                console.log(
                  `[去广告] 清单已改写 url=${shortenUrl(ctx?.url)} ${summarizePlaylist(
                    before
                  )} → ${summarizePlaylist(response.data)}`
                );
              }
            }
            return onSuccess(response, stats, ctx, networkDetails);
          };
        }
        load(context, config, callbacks);
      };
    }
  };
}

/**
 * 取后台配置的自定义去广告代码（带 localStorage 缓存 + 版本号校验）。
 * 取不到就返回空串，走默认关键字规则。
 */
export async function loadCustomAdFilterCode(): Promise<string> {
  if (typeof window === 'undefined') return '';
  try {
    const cachedCode = localStorage.getItem('custom_ad_filter_code_cache');
    const cachedVersion = localStorage.getItem('custom_ad_filter_version_cache');
    const version =
      (window as any).RUNTIME_CONFIG?.CUSTOM_AD_FILTER_VERSION || 0;

    // 版本 0 = 后台没配，清缓存跳过
    if (version === 0) {
      localStorage.removeItem('custom_ad_filter_code_cache');
      localStorage.removeItem('custom_ad_filter_version_cache');
      return '';
    }

    if (cachedCode && cachedVersion && parseInt(cachedVersion) === version) {
      return cachedCode;
    }

    const res = await fetch('/api/ad-filter?full=true');
    if (!res.ok) return cachedCode || '';
    const { code } = await res.json();
    if (code) {
      localStorage.setItem('custom_ad_filter_code_cache', code);
      localStorage.setItem('custom_ad_filter_version_cache', version.toString());
      return code;
    }
    return cachedCode || '';
  } catch {
    return '';
  }
}
