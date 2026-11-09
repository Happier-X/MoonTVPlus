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
  const filteredLines: string[] = [];
  // 中插广告被摘掉后，前后两段分片的 PTS 时间轴是断开的。必须在拼接处补回一个
  // #EXT-X-DISCONTINUITY，否则播放器不知道要重置时间轴：原生 HLS（Safari /
  // 鸿蒙原生 / 电视）会停在断点并触发 ended，页面随即自动跳下一集。
  let needDiscontinuity = false;
  let keptSegmentCount = 0;

  // 日志统计：把移除位置换算成「第几分钟」，方便和用户看到的广告时间对上
  let elapsed = 0;
  let firstAdAt = -1;
  let removedAdDuration = 0;
  let removedAdCount = 0;
  let discontinuityInserted = false;

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    if (line.includes('#EXT-X-DISCONTINUITY')) {
      i++;
      continue;
    }

    const isSegmentLine = line.includes('#EXTINF:');
    const segmentDuration = isSegmentLine
      ? parseFloat(line.slice(line.indexOf(':') + 1)) || 0
      : 0;

    // #EXTINF 下一行是分段地址，命中广告关键字就把两行一起丢
    if (isSegmentLine && i + 1 < lines.length) {
      const nextLine = lines[i + 1];
      const containsAdKeyword = AD_KEYWORDS.some((keyword) =>
        nextLine.toLowerCase().includes(keyword.toLowerCase())
      );
      if (containsAdKeyword) {
        if (firstAdAt < 0) firstAdAt = elapsed;
        removedAdCount++;
        removedAdDuration += segmentDuration;
        elapsed += segmentDuration;
        needDiscontinuity = true;
        i += 2;
        continue;
      }
    }

    // 只有前面已经有正片分片时才补，片头广告被删掉不会留下孤立的时间轴标记
    if (needDiscontinuity && line.trim()) {
      if (keptSegmentCount > 0) {
        filteredLines.push('#EXT-X-DISCONTINUITY');
        discontinuityInserted = true;
      }
      needDiscontinuity = false;
    }

    if (isSegmentLine) {
      keptSegmentCount++;
      elapsed += segmentDuration;
    }

    filteredLines.push(line);
    i++;
  }

  if (removedAdCount > 0) {
    console.log(
      `[去广告] ${type || '(未知源)'} 移除 ${removedAdCount} 个广告分片，起于 ${formatClock(firstAdAt)}，共 ${removedAdDuration.toFixed(1)}s；` +
        `拼接处${discontinuityInserted ? '已补 #EXT-X-DISCONTINUITY' : '未补断点（片头/片尾广告，无需断点）'}`
    );
  }

  // 兜底：关键字误伤把正片全删光时，宁可带着广告播，也不能给播放器一个空清单
  if (keptSegmentCount === 0) {
    console.warn(
      '[去广告] 过滤后没有剩余分片，疑似关键字误伤，已回退为原清单（本次不生效）'
    );
    return m3u8Content;
  }

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
    if (line.includes('#EXT-X-DISCONTINUITY')) {
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
