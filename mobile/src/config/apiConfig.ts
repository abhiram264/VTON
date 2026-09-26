import type {AvatarApiConfig} from '../features/my-model/types';
import {getAuthSurface} from './firebase';

// Single owner of the mounted runtime API configuration for all mobile API
// clients. Fail-closed by design:
//  - The base URL is resolved from the app config seam (EXPO_PUBLIC_API_URL,
//    Expo's runtime env convention; no vendor host is hardcoded).
//  - The access token is the current Firebase ID token from the auth session
//    surface (src/config/firebase.ts, ADR-007 option A). Resolution order:
//    an unavailable surface (no Firebase config) and an available surface
//    with nobody signed in BOTH throw, so no request can silently go out
//    bearer-less (apiRequest omits Authorization when getAccessToken
//    returns null). A signed-in user resolves to the SDK's own ID token.
//    The token is never cached or logged here; the SDK owns refresh and
//    caching, and a re-read per call is what picks up a token refresh.
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
  getAccessToken: async () => {
    const surface = getAuthSurface();
    const user = surface.status === 'available' ? surface.auth.currentUser : null;
    if (user === null) {
      throw new Error('Access token is not configured: no session store exists');
    }
    return user.getIdToken();
  },
};
