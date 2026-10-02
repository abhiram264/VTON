'use strict';

/**
 * Build-time release guard for public mobile API configuration (C02).
 *
 * Public env values are inlined from mobile/.env by the plugin in
 * babel.config.js. A production bundle must never ship a developer target:
 * a missing value, a placeholder, plain HTTP, or a loopback/emulator/LAN host
 * would send a store user's traffic (and their Firebase ID token) to the
 * wrong machine. Metro runs release bundles with BABEL_ENV=production
 * (node_modules/metro-babel-transformer/src/index.js), so babel.config.js
 * calls this at transform time and the release build fails instead of
 * shipping a misconfigured app.
 */

const PLACEHOLDER_MARKER = /REPLACE_WITH_|PLACEHOLDER/i;

// Loopback, unspecified, link-local and RFC1918 ranges. 10/8 already covers
// the Android emulator host alias 10.0.2.2, which is called out separately
// only because it is the most common accidental target.
const PRIVATE_HOST = /^(localhost|127(\.\d{1,3}){3}|0\.0\.0\.0|10(\.\d{1,3}){3}|192\.168(\.\d{1,3}){2}|172\.(1[6-9]|2\d|3[01])(\.\d{1,3}){2}|169\.254(\.\d{1,3}){2}|\[::1\])$/i;

/**
 * Describe why a configured API target is unusable for a release build.
 *
 * @param {string | undefined} raw the resolved EXPO_PUBLIC_API_URL value.
 * @returns {string | null} a human-readable problem, or null when acceptable.
 */
function describeReleaseApiTargetProblem(raw) {
  const value = typeof raw === 'string' ? raw.trim() : '';
  if (value.length === 0) {
    return 'EXPO_PUBLIC_API_URL is missing';
  }
  if (PLACEHOLDER_MARKER.test(value)) {
    return 'EXPO_PUBLIC_API_URL is a placeholder';
  }
  let url;
  try {
    url = new URL(value);
  } catch {
    return `EXPO_PUBLIC_API_URL is not an absolute URL: ${value}`;
  }
  if (url.protocol !== 'https:') {
    return `EXPO_PUBLIC_API_URL must use https in release builds: ${value}`;
  }
  if (PRIVATE_HOST.test(url.hostname) || url.hostname.toLowerCase().endsWith('.local')) {
    return `EXPO_PUBLIC_API_URL points at a local, emulator or LAN host: ${url.hostname}`;
  }
  return null;
}

/**
 * Throw when the configured API target must not ship in a release build.
 *
 * @param {string | undefined} raw the resolved EXPO_PUBLIC_API_URL value.
 * @returns {string} the accepted value.
 */
function assertReleaseApiTarget(raw) {
  const problem = describeReleaseApiTargetProblem(raw);
  if (problem !== null) {
    throw new Error(`Release build configuration rejected: ${problem}`);
  }
  return raw;
}

module.exports = { describeReleaseApiTargetProblem, assertReleaseApiTarget };
