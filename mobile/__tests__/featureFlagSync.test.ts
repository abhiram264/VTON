/**
 * C22 item 5/6 (mobile integration): the backend capability snapshot must be
 * authoritative at runtime. These tests drive the real sync seam — the auth
 * subscription plus the real `refreshFeatureFlags` fetch-and-apply — and assert
 * the observable result: after a signed-in session, `isFeatureEnabled` reflects
 * the backend payload; before one (and on failure) the declared defaults stand.
 */
import { startFeatureFlagSync } from '../src/app/featureFlagSync';
import {
  DEFAULT_FEATURE_FLAGS,
  applyFeatureFlagPayload,
  isFeatureEnabled,
} from '../src/config/featureFlags';
import type { AuthSession } from '../src/config/firebase';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

const CONFIG = {
  baseUrl: 'https://api.example.test',
  getAccessToken: () => Promise.resolve('token-1'),
};

describe('startFeatureFlagSync', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
  });

  afterEach(() => {
    applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
  });

  test('signed-in session fetches the backend snapshot and applies it', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        flags: { SHARE_IMPORT_ANDROID: false, TRYON_MAX: false },
        overrides: {},
      }),
    );

    let listener: ((session: AuthSession) => void) | undefined;
    startFeatureFlagSync({
      subscribe: cb => {
        listener = cb;
        return () => {};
      },
      config: CONFIG,
    });

    expect(fetchMock).not.toHaveBeenCalled();
    listener?.({ status: 'signed-in', uid: 'u1', getIdToken: () => Promise.resolve('token-1') });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(requestUrl(fetchMock.mock.calls[0][0])).toBe('https://api.example.test/feature-flags');
    // The operator's server-side disable reached the client.
    expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(false);
    expect(isFeatureEnabled('TRYON_MAX')).toBe(false);
  });

  test('loading, signed-out and unavailable sessions never fetch', () => {
    let listener: ((session: AuthSession) => void) | undefined;
    startFeatureFlagSync({
      subscribe: cb => {
        listener = cb;
        return () => {};
      },
      config: CONFIG,
    });

    listener?.({ status: 'loading' });
    listener?.({ status: 'signed-out' });
    listener?.({ status: 'unavailable' });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('a failed fetch leaves the declared defaults intact', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));

    let listener: ((session: AuthSession) => void) | undefined;
    startFeatureFlagSync({
      subscribe: cb => {
        listener = cb;
        return () => {};
      },
      config: CONFIG,
    });

    listener?.({ status: 'signed-in', uid: 'u1', getIdToken: () => Promise.resolve('token-1') });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    // Offline cannot enable an unsupported capability.
    expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
    expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
    expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(true);
  });

  test('the returned unsubscribe stops further refreshes', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { flags: {}, overrides: {} }));

    let listener: ((session: AuthSession) => void) | undefined;
    const unsubscribe = startFeatureFlagSync({
      subscribe: cb => {
        listener = cb;
        return () => {
          listener = undefined;
        };
      },
      config: CONFIG,
    });

    unsubscribe();
    expect(listener).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('a rejecting refresh does not surface as an unhandled rejection', async () => {
    let listener: ((session: AuthSession) => void) | undefined;
    startFeatureFlagSync({
      subscribe: cb => {
        listener = cb;
        return () => {};
      },
      refresh: () => Promise.reject(new Error('boom')),
      config: CONFIG,
    });

    listener?.({ status: 'signed-in', uid: 'u1', getIdToken: () => Promise.resolve('token-1') });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
