// Build-time feature flag defaults per plan §66. Remote kill-switch is future work.
export type FeatureFlagName = 'SHARE_IMPORT_IOS' | 'SHARE_IMPORT_ANDROID';

export const FEATURE_FLAGS: Record<FeatureFlagName, boolean> = {
  SHARE_IMPORT_IOS: true,
  SHARE_IMPORT_ANDROID: true,
};

export function isFeatureEnabled(name: FeatureFlagName): boolean {
  return FEATURE_FLAGS[name];
}
