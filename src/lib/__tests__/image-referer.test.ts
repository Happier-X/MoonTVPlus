import { refererForImage } from '../image-referer';

describe('refererForImage', () => {
  it('uses movie.douban.com for douban posters', () => {
    expect(refererForImage('https://img2.doubanio.com/view/photo/a.jpg')).toBe(
      'https://movie.douban.com/'
    );
    expect(refererForImage('https://movie.douban.com/a.jpg')).toBe(
      'https://movie.douban.com/'
    );
  });

  it('uses bgm.tv for bangumi images', () => {
    expect(refererForImage('https://lain.bgm.tv/pic/cover/a.jpg')).toBe(
      'https://bgm.tv/'
    );
    expect(refererForImage('https://r.bgm.tv/a.jpg')).toBe('https://bgm.tv/');
  });

  it('uses the image origin for other hosts', () => {
    expect(refererForImage('https://tu.iqiyizyimg.com/upload/a.jpg')).toBe(
      'https://tu.iqiyizyimg.com/'
    );
  });

  it('falls back to douban for invalid urls', () => {
    expect(refererForImage('not-a-url')).toBe('https://movie.douban.com/');
  });
});
