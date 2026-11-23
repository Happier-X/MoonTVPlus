import { resolveM3u8Links } from './proxy-rewrite';

const ORIGIN = 'https://site.example.com';
const SOURCE = 'directplay';

describe('resolveM3u8Links', () => {
  it('rewrites relative EXT-X-MEDIA playlist URIs to the m3u8 proxy', () => {
    const input = [
      '#EXTM3U',
      '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"',
      '#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="aud"',
      'video/720.m3u8',
    ].join('\n');
    const out = resolveM3u8Links(input, 'https://cdn.example.com/vod/master.m3u8', SOURCE, ORIGIN, '', true, false);
    expect(out).toContain('URI="' + ORIGIN + '/api/proxy-m3u8?url=' + encodeURIComponent('https://cdn.example.com/vod/audio/en.m3u8'));
    expect(out).toContain(ORIGIN + '/api/proxy-m3u8?url=' + encodeURIComponent('https://cdn.example.com/vod/video/720.m3u8'));
  });

  it('rewrites relative EXT-X-MAP URIs to the segment proxy', () => {
    const input = ['#EXTM3U', '#EXT-X-MAP:URI="init.mp4",BYTERANGE="719@0"', '#EXTINF:6,', 'seg1.m4s'].join('\n');
    const out = resolveM3u8Links(input, 'https://cdn.example.com/vod/v2/index.m3u8', SOURCE, ORIGIN, '', true, false);
    expect(out).toContain('URI="' + ORIGIN + '/api/proxy/vod/segment?url=' + encodeURIComponent('https://cdn.example.com/vod/v2/init.mp4'));
    expect(out).toContain('BYTERANGE="719@0"');
    expect(out).toContain(ORIGIN + '/api/proxy/vod/segment?url=' + encodeURIComponent('https://cdn.example.com/vod/v2/seg1.m4s'));
  });

  it('keeps EXT-X-BYTERANGE lines intact for fMP4 segments', () => {
    const input = ['#EXTM3U', '#EXT-X-MAP:URI="main.mp4"', '#EXTINF:6,', '#EXT-X-BYTERANGE:274201@719', 'main.mp4'].join('\n');
    const out = resolveM3u8Links(input, 'https://cdn.example.com/vod/v2/index.m3u8', SOURCE, ORIGIN, '', true, false);
    expect(out).toContain('#EXT-X-BYTERANGE:274201@719');
  });

  it('leaves absolute URIs alone except wrapping them in the right proxy', () => {
    const input = ['#EXTM3U', '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",URI="https://cdn.example.com/sub/en.m3u8"'].join('\n');
    const out = resolveM3u8Links(input, 'https://cdn.example.com/vod/master.m3u8', SOURCE, ORIGIN, 'abc', true, false);
    expect(out).toContain(ORIGIN + '/api/proxy-m3u8?url=' + encodeURIComponent('https://cdn.example.com/sub/en.m3u8') + '&source=directplay&token=abc');
  });
});
