export const FEATURE_PERMISSION_OPTIONS = [
  { key: 'ai_ask', label: 'AI问片', description: 'AI 问片与影视问答' },
  { key: 'netdisk_search', label: '网盘搜索', description: 'Pansou 网盘资源搜索' },
  { key: 'magnet_search', label: '磁链搜索', description: '动漫/磁链搜索' },
  { key: 'netdisk_transfer', label: '网盘转存', description: '夸克网盘转存' },
  { key: 'netdisk_temp_play', label: '临时播放', description: '网盘资源临时播放' },
  { key: 'live', label: '电视直播', description: '电视直播频道观看' },
  { key: 'web_live', label: '网络直播', description: '网络直播观看' },
  { key: 'manga', label: '漫画展馆', description: '漫画搜索、阅读与书架' },
] as const;

export type FeaturePermissionKey = (typeof FEATURE_PERMISSION_OPTIONS)[number]['key'];

export const ALL_FEATURE_PERMISSION_KEYS = FEATURE_PERMISSION_OPTIONS.map(
  (item) => item.key
) as FeaturePermissionKey[];

export function sanitizeFeaturePermissions(
  permissions?: string[] | null
): FeaturePermissionKey[] {
  if (!Array.isArray(permissions)) return [];
  const allowed = new Set<FeaturePermissionKey>(ALL_FEATURE_PERMISSION_KEYS);
  return Array.from(
    new Set(
      permissions.filter(
        (item): item is FeaturePermissionKey =>
          typeof item === 'string' && allowed.has(item as FeaturePermissionKey)
      )
    )
  );
}
