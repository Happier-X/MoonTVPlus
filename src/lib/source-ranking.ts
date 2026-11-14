import type { SearchResult } from './types';

const textCollator = new Intl.Collator('zh-CN', {
  numeric: true,
  sensitivity: 'base',
});

function sourceWeight(source: SearchResult): number {
  return Number.isFinite(source.weight) ? (source.weight as number) : 0;
}

function compareText(a: string | undefined, b: string | undefined): number {
  return textCollator.compare(a ?? '', b ?? '');
}

/**
 * 换源列表的推荐排序：后台权重高的在前。
 * 同权重时用标题、来源名和 source/id 兜底，保证顺序稳定且不受原始搜索返回顺序影响。
 */
export function compareSourceRecommendation(
  a: SearchResult,
  b: SearchResult
): number {
  const weightDiff = sourceWeight(b) - sourceWeight(a);
  if (weightDiff !== 0) return weightDiff;

  const titleDiff = compareText(a.title, b.title);
  if (titleDiff !== 0) return titleDiff;

  const sourceNameDiff = compareText(
    a.source_name || a.source,
    b.source_name || b.source
  );
  if (sourceNameDiff !== 0) return sourceNameDiff;

  const sourceDiff = compareText(a.source, b.source);
  if (sourceDiff !== 0) return sourceDiff;

  return compareText(a.id, b.id);
}

export function sortSourcesByRecommendation(
  sources: readonly SearchResult[]
): SearchResult[] {
  return [...sources].sort(compareSourceRecommendation);
}
