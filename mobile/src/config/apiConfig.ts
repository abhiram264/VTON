import type {AvatarApiConfig} from '../features/my-model/types';
import {getAuthSurface, isReleaseBuild} from './firebase';

// The placeholder written into .env templates; a real value must replace it.
export const API_URL_PLACEHOLDER_PREFIX = 'REPLACE_WITH_';

// RFC1918 private ranges, loopback and unspecified addresses. A release build
// must never ship pointed at one of these: they only resolve on a developer
// machine or an emulator (e.g. the Android emulator alias 10.0.2.2).
const LOOPBACK_OR_LAN_HOSTNAME =
  /^(localhost|0\.0\.0\.0|127(\.\d{1,3}){3}|10(\.\d{1,3}){3}|192\.168(\.\d{1,3}){2}|172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2}|::1|0:0:0:0:0:0:0:1)$/i;

/**
 * Host component of an absolute URL. Parsed by hand rather than with `URL`,
 * whose React Native polyfill is incomplete.
 */
function hostnameOf(url: string): string | null {
  const match = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]*)/i.exec(url);
  if (match === null) {
    return null;
  }
  let authority = match[1];
  const at = authority.lastIndexOf('@');
  if (at >= 0) {
    authority = authority.slice(at + 1);
  }
  if (authority.startsWith('[')) {
    const end = authority.indexOf(']');
    return end >= 0 ? authority.slice(1, end) : null;
  }
  const colon = authority.indexOf(':');
  return colon >= 0 ? authority.slice(0, colon) : authority;
}

/** Dotted quad for a 32-bit IPv4 integer (e.g. 2130706433 -> 127.0.0.1). */
function ipv4FromInteger(value: string): string | null {
  if (!/^\d{1,10}$/.test(value)) {
    return null;
  }
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 0 || n > 0xffffffff) {
    return null;
  }
  return [n >>> 24, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff].join('.');
}

/** Dotted quad for the low 32 bits of an IPv6 address, when they form IPv4. */
function ipv4FromIpv6Tail(host: string): string | null {
  const parts = host.split(':');
  const last = parts[parts.length - 1];
  if (last.includes('.')) {
    return last;
  }
  const groups = host.split(':').filter(part => part.length > 0);
  const tail = groups.slice(-2);
  if (tail.length !== 2 || !tail.every(g => /^[0-9a-f]{1,4}$/.test(g))) {
    return null;
  }
  const n = (parseInt(tail[0], 16) << 16) | parseInt(tail[1], 16);
  return [n >>> 24, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff].join('.');
}

/**
 * True for a host that only resolves on the developer's own machine or local
 * network, including alternate spellings a release bundle must not ship:
 * numeric IPv4 (2130706433), IPv4-mapped IPv6 (::ffff:127.0.0.1) and a
 * trailing-dot FQDN (localhost.).
 */
function isLocalHost(host: string): boolean {
  const normalized = host.trim().toLowerCase().replace(/\.$/, '');
  if (normalized.length === 0) {
    return false;
  }
  if (LOOPBACK_OR_LAN_HOSTNAME.test(normalized)) {
    return true;
  }
  const asInteger = ipv4FromInteger(normalized);
  if (asInteger !== null && LOOPBACK_OR_LAN_HOSTNAME.test(asInteger)) {
    return true;
  }
  if (normalized.includes(':')) {
    const mapped = ipv4FromIpv6Tail(normalized);
    if (mapped !== null && LOOPBACK_OR_LAN_HOSTNAME.test(mapped)) {
      return true;
    }
  }
  return false;
}

/**
 * Normalizes the API base URL and, for a release build, refuses a target that
 * cannot serve a shipped app: missing, placeholder, non-https, localhost or a
 * LAN address. Development builds keep the permissive behavior so an emulator
 * can talk to a local backend.
 */
export function resolveApiBaseUrl(raw: string, isRelease: boolean): string {
  const trimmed = (raw ?? '').trim().replace(/\/+$/, '');
  if (trimmed.length === 0) {
    throw new Error('API base URL is not configured: set EXPO_PUBLIC_API_URL');
  }
  if (trimmed.startsWith(API_URL_PLACEHOLDER_PREFIX)) {
    throw new Error(
      'API base URL is a placeholder: set a real EXPO_PUBLIC_API_URL',
    );
  }
  if (isRelease) {
    if (!/^https:\/\//i.test(trimmed)) {
      throw new Error(
        `Release API base URL must be an absolute https URL: ${trimmed}`,
      );
    }
    const host = hostnameOf(trimmed);
    if (host === null || host.length === 0) {
      throw new Error(`Release API base URL has no host: ${trimmed}`);
    }
    if (isLocalHost(host)) {
      throw new Error(
        `Release API base URL must not target localhost or a LAN address: ${trimmed}`,
      );
    }
  }
  return trimmed;
}

// Single owner of the mounted runtime API configuration for all mobile API
// clients. Fail-closed by design:
//  - The base URL is resolved from the app config seam (EXPO_PUBLIC_API_URL,
//    Expo's runtime env convention; no vendor host is hardcoded). In a release
//    build a missing, placeholder, non-https, localhost or LAN target is
//    rejected rather than silently shipped.
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
    // EXPO_PUBLIC_* globals are populated by the bundler at runtime; they are
    // not part of the TypeScript lib, hence the named cast.
    const runtimeGlobals = globalThis as {EXPO_PUBLIC_API_URL?: string};
    const raw =
      process.env.EXPO_PUBLIC_API_URL ?? runtimeGlobals.EXPO_PUBLIC_API_URL ?? '';
    return resolveApiBaseUrl(raw, isReleaseBuild());
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
