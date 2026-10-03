export type FeatureFlagName =
  | 'MULTI_ITEM'
  | 'NATURAL_LANGUAGE_STUDIO'
  | 'TRYON_MAX'
  | 'HD_GENERATION'
  | 'SHARE_IMPORT_ANDROID'
  | 'SHARE_IMPORT_IOS'
  | 'NEW_QUALITY_GATE';

// Client mirror of the backend's `FeatureFlagName.supported` bit: a capability
// with no working backing path is refused on the client no matter what a flag
// payload, an offline fetch or a cached value says (guide C22 item 4). The
// backend stays authoritative for everything else (item 5).
//
// Deliberate asymmetries with the backend enum, so the next reader does not
// re-raise them:
//  - MULTI_ITEM is client-gated off by operator ruling (option C, ledger §12):
//    the backend marks it supported (C12 route) but the MOBILE affordance stays
//    off until the owner supplies the real image API key. The client is
//    intentionally stricter here; do not flip without a new ruling.
//  - NATURAL_LANGUAGE_STUDIO has a mounted route (C21) and defers to the
//    backend, which refuses the capability itself when its flag is off. It
//    still defaults OFF.
//  - NEW_QUALITY_GATE is not listed because the backend never emits it enabled:
//    FeatureFlagService.isEnabled returns false for any unsupported name before
//    consulting overrides, and override(name, true) throws. A client guard would
//    be unreachable, so the table omits it rather than adding dead code.
export const UNSUPPORTED_CAPABILITIES: Readonly<
  Record<FeatureFlagName, boolean>
> = {
  MULTI_ITEM: true,
  NATURAL_LANGUAGE_STUDIO: false,
  TRYON_MAX: false,
  HD_GENERATION: true,
  SHARE_IMPORT_ANDROID: false,
  SHARE_IMPORT_IOS: false,
  NEW_QUALITY_GATE: false,
};

export const DEFAULT_FEATURE_FLAGS: Record<FeatureFlagName, boolean> = {
  MULTI_ITEM: false,
  NATURAL_LANGUAGE_STUDIO: false,
  TRYON_MAX: true,
  HD_GENERATION: false,
  SHARE_IMPORT_ANDROID: true,
  SHARE_IMPORT_IOS: true,
  NEW_QUALITY_GATE: false,
};

export const FEATURE_FLAGS: Record<FeatureFlagName, boolean> =
  DEFAULT_FEATURE_FLAGS;

const activeFeatureFlags: Record<FeatureFlagName, boolean> = {
  ...DEFAULT_FEATURE_FLAGS,
};

function isFeatureFlagName(name: string): name is FeatureFlagName {
  return Object.prototype.hasOwnProperty.call(DEFAULT_FEATURE_FLAGS, name);
}

export function isFeatureEnabled(name: FeatureFlagName): boolean {
  if (UNSUPPORTED_CAPABILITIES[name]) {
    return false;
  }
  return activeFeatureFlags[name] ?? DEFAULT_FEATURE_FLAGS[name];
}

function applyFlagEntries(source: Record<string, unknown>): void {
  for (const [key, value] of Object.entries(source)) {
    if (isFeatureFlagName(key) && typeof value === 'boolean') {
      activeFeatureFlags[key] = value;
    }
  }
}

export function applyFeatureFlagPayload(payload: unknown): void {
  if (
    typeof payload !== 'object' ||
    payload === null ||
    Array.isArray(payload)
  ) {
    return;
  }
  const obj = payload as Record<string, unknown>;
  const hasFlags =
    typeof obj.flags === 'object' &&
    obj.flags !== null &&
    !Array.isArray(obj.flags);
  const hasOverrides =
    typeof obj.overrides === 'object' &&
    obj.overrides !== null &&
    !Array.isArray(obj.overrides);

  if (hasFlags) {
    applyFlagEntries(obj.flags as Record<string, unknown>);
  }
  if (hasOverrides) {
    applyFlagEntries(obj.overrides as Record<string, unknown>);
  }
  if (!hasFlags && !hasOverrides) {
    applyFlagEntries(obj);
  }
}

export type FeatureFlagRefreshConfig = {
  baseUrl: string;
  getAccessToken: () => Promise<string | null> | string | null;
};

export async function refreshFeatureFlags(
  config: FeatureFlagRefreshConfig,
): Promise<void> {
  try {
    const headers: Record<string, string> = {
      Accept: 'application/json',
    };
    if (typeof config?.getAccessToken === 'function') {
      const token = await config.getAccessToken();
      if (token) {
        headers.Authorization = `Bearer ${token}`;
      }
    }
    const normalizedBaseUrl = (config?.baseUrl ?? '').replace(/\/+$/, '');
    const url = `${normalizedBaseUrl}/feature-flags`;
    const response = await fetch(url, {
      method: 'GET',
      headers,
    });
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    applyFeatureFlagPayload(data);
  } catch {
    // Fail safe: keep current values, resolve normally
    return;
  }
}
