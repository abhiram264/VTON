import type {AvatarApiConfig} from '../features/my-model/types';

// Single owner of the mounted runtime API configuration for all mobile API
// clients. Fail-closed by design:
//  - The base URL is resolved from the app config seam (EXPO_PUBLIC_API_URL,
//    Expo's runtime env convention; no vendor host is hardcoded).
//  - A missing token THROWS so no request can silently go out bearer-less
//    (apiRequest omits Authorization when getAccessToken returns null).
// This is the module screens/share-import/apiConfig.ts should consume.
export const productionApiConfig: AvatarApiConfig = {
  get baseUrl(): string {
    const raw =
      process.env.EXPO_PUBLIC_API_URL ??
      (globalThis as {EXPO_PUBLIC_API_URL?: string}).EXPO_PUBLIC_API_URL ??
      '';
    const trimmed = raw.trim().replace(/\/+$/, '');
    if (trimmed.length === 0) {
      throw new Error(
        'API base URL is not configured: set EXPO_PUBLIC_API_URL',
      );
    }
    return trimmed;
  },
  getAccessToken: () => {
    throw new Error('Access token is not configured: no session store exists');
  },
};
