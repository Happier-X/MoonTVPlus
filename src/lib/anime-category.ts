/** 判断 CMS / 分类文案是否为动漫（客户端/服务端均可） */
export function isAnimeCategoryText(
  ...parts: Array<string | undefined | null>
): boolean {
  const text = parts.filter(Boolean).join(' ');
  if (!text) return false;
  return /动画|動漫|动漫|anime|アニメ/i.test(text);
}
