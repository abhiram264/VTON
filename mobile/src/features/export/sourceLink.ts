import {Linking} from 'react-native';

/**
 * Safe external navigation for a Look's original product link (C16 item 5).
 *
 * A source link is untrusted text that arrived from a shared page or an
 * import resolver. It is handed to the OS URL handler, so it must never be
 * able to smuggle a non-web scheme (`javascript:`, `data:`, `file:`,
 * `content:`, `intent:`), a relative path, or embedded credentials
 * (`https://user:pass@host`) into that handler. Only absolute http/https URLs
 * with a host and no userinfo are accepted; anything else is refused without
 * touching the OS.
 */

export type OpenSourceResult =
  | {opened: true}
  | {opened: false; reason: 'UNSAFE_URL' | 'CANNOT_OPEN'};

const MAX_URL_LENGTH = 2048;

export function isSafeExternalUrl(raw: unknown): raw is string {
  if (typeof raw !== 'string') {
    return false;
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_URL_LENGTH) {
    return false;
  }
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    // Relative or malformed input: no absolute URL to inspect.
    return false;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return false;
  }
  if (url.hostname.length === 0) {
    return false;
  }
  if (url.username.length > 0 || url.password.length > 0) {
    return false;
  }
  return true;
}

/**
 * Opens a source link only when it passes {@link isSafeExternalUrl}. Returns a
 * discriminated result instead of throwing so callers can show an honest
 * "link unavailable" state; the URL is never logged (ADR-015).
 */
export async function openSourceLink(
  raw: unknown,
  open: (url: string) => Promise<unknown> = url => Linking.openURL(url),
): Promise<OpenSourceResult> {
  if (!isSafeExternalUrl(raw)) {
    return {opened: false, reason: 'UNSAFE_URL'};
  }
  try {
    await open(raw.trim());
    return {opened: true};
  } catch {
    // No handler, or the OS refused: harmless, reported distinctly.
    return {opened: false, reason: 'CANNOT_OPEN'};
  }
}
