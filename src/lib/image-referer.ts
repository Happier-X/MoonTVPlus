const BANGUMI_SUFFIXES = ['.bgm.tv', '.bangumi.tv', '.bangumi.lol'];
const BANGUMI_HOSTS = ['lain.bgm.tv', 'r.bgm.tv', 'bangumi.lol'];

function isBangumiHost(hostname: string): boolean {
  return (
    BANGUMI_HOSTS.includes(hostname) ||
    BANGUMI_SUFFIXES.some((suffix) => hostname.endsWith(suffix))
  );
}

/**
 * Referer sent when proxying an image.
 * Douban rejects hotlinks without movie.douban.com, Bangumi wants bgm.tv,
 * and other hosts get their own origin so CDNs that check it accept the request.
 */
export function refererForImage(imageUrl: string): string {
  try {
    const url = new URL(imageUrl);
    const hostname = url.hostname.toLowerCase();
    if (isBangumiHost(hostname)) return 'https://bgm.tv/';
    if (
      hostname === 'douban.com' ||
      hostname.endsWith('.douban.com') ||
      hostname.endsWith('.doubanio.com')
    ) {
      return 'https://movie.douban.com/';
    }
    return `${url.origin}/`;
  } catch {
    return 'https://movie.douban.com/';
  }
}
