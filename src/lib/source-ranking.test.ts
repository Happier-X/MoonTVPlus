import {
  compareSourceRecommendation,
  sortSourcesByRecommendation,
} from './source-ranking';
import type { SearchResult } from './types';

function makeSource(overrides: Partial<SearchResult>): SearchResult {
  return {
    id: '1',
    title: '测试影片',
    poster: '',
    episodes: ['https://example.com/1.m3u8'],
    episodes_titles: ['第1集'],
    source: 'source-a',
    source_name: '来源 A',
    year: '2025',
    ...overrides,
  };
}

describe('source-ranking', () => {
  it('按权重降序排列，权重缺失按 0 处理', () => {
    const result = sortSourcesByRecommendation([
      makeSource({ source: 'low', source_name: '低权重', weight: 1 }),
      makeSource({ source: 'none', source_name: '无权重' }),
      makeSource({ source: 'high', source_name: '高权重', weight: 10 }),
    ]);

    expect(result.map((item) => item.source)).toEqual([
      'high',
      'low',
      'none',
    ]);
  });

  it('同权重时标题、来源名、source/id 依次兜底，顺序稳定', () => {
    const items = [
      makeSource({
        source: 'b',
        source_name: 'B 来源',
        title: '同名片',
        weight: 5,
      }),
      makeSource({
        source: 'a',
        source_name: 'A 来源',
        title: '同名片',
        weight: 5,
      }),
      makeSource({
        source: 'c',
        source_name: 'A 来源',
        title: '同名片',
        weight: 5,
      }),
    ];

    expect(
      sortSourcesByRecommendation(items).map((item) => item.source)
    ).toEqual(['a', 'c', 'b']);
    expect(
      [...items].sort(compareSourceRecommendation).map((item) => item.source)
    ).toEqual(['a', 'c', 'b']);
  });

  it('不修改传入数组', () => {
    const items = [
      makeSource({ source: 'low', weight: 1 }),
      makeSource({ source: 'high', weight: 9 }),
    ];
    const original = [...items];

    sortSourcesByRecommendation(items);

    expect(items).toEqual(original);
  });
});
