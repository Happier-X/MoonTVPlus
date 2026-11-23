/**
 * 将 m3u8 中的相对链接转换为绝对链接，并把子播放列表/分片改为站点代理地址。
 * 覆盖 EXT-X-STREAM-INF、EXT-X-KEY、EXT-X-MAP、EXT-X-MEDIA 和分片行。
 */
export function resolveM3u8Links(
  m3u8Content: string,
  baseUrl: string,
  source: string,
  proxyOrigin: string,
  token: string,
  adBlockEnabled: boolean,
  proxySegments: boolean
): string {
  const lines = m3u8Content.split('\n');
  const resolvedLines: string[] = [];
  const base = new URL(baseUrl);
  const baseDir = base.href.substring(0, base.href.lastIndexOf('/') + 1);

  const absolute = (uri: string): string => {
    if (uri.startsWith('http://') || uri.startsWith('https://')) return uri;
    if (uri.startsWith('/')) return base.protocol + '//' + base.host + uri;
    return new URL(uri, baseDir).href;
  };

  const playlistProxy = (url: string): string => {
    const tokenParam = token ? '&token=' + encodeURIComponent(token) : '';
    const adBlockParam = adBlockEnabled ? '' : '&adblock=false';
    const proxySegmentsParam = proxySegments ? '&proxySegments=true' : '';
    return proxyOrigin + '/api/proxy-m3u8?url=' + encodeURIComponent(url) +
      (source ? '&source=' + encodeURIComponent(source) : '') +
      tokenParam + adBlockParam + proxySegmentsParam;
  };

  const segmentProxy = (url: string): string =>
    proxyOrigin + '/api/proxy/vod/segment?url=' + encodeURIComponent(url) +
      '&source=' + encodeURIComponent(source);

  let isNextLineUrl = false;

  for (const rawLine of lines) {
    let line = rawLine;

    if (line.startsWith('#EXT-X-KEY:') || line.startsWith('#EXT-X-MAP:')) {
      const uriMatch = line.match(/URI="([^"]+)"/);
      if (uriMatch?.[1]) {
        const uri = absolute(uriMatch[1]);
        const next = source === 'directplay' || proxySegments ? segmentProxy(uri) : uri;
        line = line.replace(/URI="[^"]+"/, 'URI="' + next + '"');
      }
      resolvedLines.push(line);
      continue;
    }

    if (line.startsWith('#EXT-X-MEDIA:')) {
      const uriMatch = line.match(/URI="([^"]+)"/);
      if (uriMatch?.[1]) {
        const uri = absolute(uriMatch[1]);
        const isPlaylist = uri.includes('.m3u8');
        const next = isPlaylist
          ? playlistProxy(uri)
          : source === 'directplay' || proxySegments
            ? segmentProxy(uri)
            : uri;
        line = line.replace(/URI="[^"]+"/, 'URI="' + next + '"');
      }
      resolvedLines.push(line);
      continue;
    }

    if (line.startsWith('#')) {
      resolvedLines.push(line);
      if (line.startsWith('#EXT-X-STREAM-INF:')) isNextLineUrl = true;
      continue;
    }

    if (line.trim() === '') {
      resolvedLines.push(line);
      continue;
    }

    let url = absolute(line.trim());
    if (url.includes('.m3u8') || isNextLineUrl) {
      url = playlistProxy(url);
    } else if (source === 'directplay' || proxySegments) {
      url = segmentProxy(url);
    }
    resolvedLines.push(url);
    isNextLineUrl = false;
  }

  return resolvedLines.join('\n');
}
