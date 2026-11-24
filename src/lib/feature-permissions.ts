export const FEATURE_PERMISSION_OPTIONS = [
  { key: 'ai_ask', label: 'AI问片', description: 'AI 问片与影视问答' },
  { key: 'live', label: '电视直播', description: '电视直播频道观看' },
  { key: 'web_live', label: '网络直播', description: '网络直播观看' },
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
