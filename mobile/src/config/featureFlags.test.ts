import {
  DEFAULT_FEATURE_FLAGS,
  FEATURE_FLAGS,
  UNSUPPORTED_CAPABILITIES,
  FeatureFlagName,
  FeatureFlagRefreshConfig,
  applyFeatureFlagPayload,
  isFeatureEnabled,
  refreshFeatureFlags,
} from './featureFlags';

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

describe('mobile feature flags', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
  });

  afterEach(() => {
    applyFeatureFlagPayload(DEFAULT_FEATURE_FLAGS);
  });

  describe('D1 code defaults and frozen key set', () => {
    test('exact seven-name key set and D1 defaults', () => {
      const expectedKeys: FeatureFlagName[] = [
        'MULTI_ITEM',
        'NATURAL_LANGUAGE_STUDIO',
        'TRYON_MAX',
        'HD_GENERATION',
        'SHARE_IMPORT_ANDROID',
        'SHARE_IMPORT_IOS',
        'NEW_QUALITY_GATE',
      ];

      const actualKeys = Object.keys(DEFAULT_FEATURE_FLAGS).sort();
      expect(actualKeys).toEqual([...expectedKeys].sort());

      expect(DEFAULT_FEATURE_FLAGS.MULTI_ITEM).toBe(false);
      expect(DEFAULT_FEATURE_FLAGS.NATURAL_LANGUAGE_STUDIO).toBe(false);
      expect(DEFAULT_FEATURE_FLAGS.TRYON_MAX).toBe(true);
      expect(DEFAULT_FEATURE_FLAGS.HD_GENERATION).toBe(false);
      expect(DEFAULT_FEATURE_FLAGS.SHARE_IMPORT_ANDROID).toBe(true);
      expect(DEFAULT_FEATURE_FLAGS.SHARE_IMPORT_IOS).toBe(true);
      expect(DEFAULT_FEATURE_FLAGS.NEW_QUALITY_GATE).toBe(false);

      expect(FEATURE_FLAGS).toBe(DEFAULT_FEATURE_FLAGS);
    });

    test('isFeatureEnabled returns D1 defaults synchronously', () => {
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NATURAL_LANGUAGE_STUDIO')).toBe(false);
      expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
      expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(true);
      expect(isFeatureEnabled('SHARE_IMPORT_IOS')).toBe(true);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('preserves backwards compatibility for share-import flags', () => {
      expect(isFeatureEnabled('SHARE_IMPORT_IOS')).toBe(true);
      expect(isFeatureEnabled('SHARE_IMPORT_ANDROID')).toBe(true);
    });
  });

  describe('unsupported capabilities fail closed (C22)', () => {
    // NATURAL_LANGUAGE_STUDIO is excluded: C21 mounted a backing path, so the
    // backend (not the client hard-block) is the authority for it. It still
    // defaults OFF, but a fetch may enable it.
    const unsupported: FeatureFlagName[] = ['MULTI_ITEM', 'HD_GENERATION'];

    test('default off in code', () => {
      for (const name of unsupported) {
        expect(DEFAULT_FEATURE_FLAGS[name]).toBe(false);
        expect(isFeatureEnabled(name)).toBe(false);
      }
      expect(UNSUPPORTED_CAPABILITIES.MULTI_ITEM).toBe(true);
      expect(UNSUPPORTED_CAPABILITIES.HD_GENERATION).toBe(true);
      expect(UNSUPPORTED_CAPABILITIES.TRYON_MAX).toBe(false);
      expect(UNSUPPORTED_CAPABILITIES.SHARE_IMPORT_ANDROID).toBe(false);
      expect(UNSUPPORTED_CAPABILITIES.SHARE_IMPORT_IOS).toBe(false);
      // NL is server-authoritative now; it is not a client hard-block.
      expect(UNSUPPORTED_CAPABILITIES.NATURAL_LANGUAGE_STUDIO).toBe(false);
    });

    test('a flag payload cannot enable them (offline or remote)', () => {
      applyFeatureFlagPayload({
        flags: {
          MULTI_ITEM: true,
          NATURAL_LANGUAGE_STUDIO: true,
          HD_GENERATION: true,
          TRYON_MAX: true,
        },
        overrides: {
          MULTI_ITEM: true,
          NATURAL_LANGUAGE_STUDIO: true,
          HD_GENERATION: true,
        },
      });

      for (const name of unsupported) {
        expect(isFeatureEnabled(name)).toBe(false);
      }
      // Supported capabilities are unaffected by the guard.
      expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
    });

    test('a flat payload cannot enable them either', () => {
      applyFeatureFlagPayload({
        MULTI_ITEM: true,
        NATURAL_LANGUAGE_STUDIO: true,
        HD_GENERATION: true,
      });

      for (const name of unsupported) {
        expect(isFeatureEnabled(name)).toBe(false);
      }
    });

    test('a capability with a mounted backing path (NL) defers to the backend', () => {
      // C21 mounted the NL route; the client must not permanently veto it. A
      // fetched flag can enable it, and the server refuses the capability when
      // its own flag is off, so the backend stays authoritative.
      applyFeatureFlagPayload({ flags: { NATURAL_LANGUAGE_STUDIO: true } });
      expect(isFeatureEnabled('NATURAL_LANGUAGE_STUDIO')).toBe(true);
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
    });

    test('a remote or offline flag fetch cannot enable them', async () => {
      const fetchResponse = jest.fn().mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: () =>
          Promise.resolve({
            flags: {
              MULTI_ITEM: true,
              NATURAL_LANGUAGE_STUDIO: true,
              HD_GENERATION: true,
              TRYON_MAX: true,
            },
          }),
      });
      const originalFetch = globalThis.fetch;
      globalThis.fetch = fetchResponse as unknown as typeof fetch;
      try {
        await refreshFeatureFlags({
          baseUrl: 'https://api.aifashionvault.com',
          getAccessToken: () => null,
        });
      } finally {
        globalThis.fetch = originalFetch;
      }

      for (const name of unsupported) {
        expect(isFeatureEnabled(name)).toBe(false);
      }
      expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
    });
  });

  describe('applyFeatureFlagPayload', () => {
    test('applies S3 wire shape with flags and overrides', () => {
      applyFeatureFlagPayload({
        flags: {
          MULTI_ITEM: true,
          NATURAL_LANGUAGE_STUDIO: false,
          TRYON_MAX: true,
          HD_GENERATION: true,
          SHARE_IMPORT_ANDROID: true,
          SHARE_IMPORT_IOS: true,
          NEW_QUALITY_GATE: true,
        },
        overrides: {
          HD_GENERATION: false,
        },
      });

      expect(isFeatureEnabled('NATURAL_LANGUAGE_STUDIO')).toBe(false);
      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(true);
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
    });

    test('valid partial payload updates only known boolean entries and retains prior state', () => {
      applyFeatureFlagPayload({
        flags: {
          HD_GENERATION: false,
        },
      });

      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
      expect(isFeatureEnabled('SHARE_IMPORT_IOS')).toBe(true);
    });

    test('flat payload shape updates known boolean flags directly', () => {
      applyFeatureFlagPayload({
        MULTI_ITEM: false,
        NEW_QUALITY_GATE: true,
      });

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(true);
      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
    });

    test('ignores unknown flag names without corrupting known state', () => {
      applyFeatureFlagPayload({
        flags: {
          UNKNOWN_EXPERIMENTAL_FLAG: true,
          SOME_OTHER_FLAG: false,
        },
      });

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('ignores non-boolean values in payload', () => {
      applyFeatureFlagPayload({
        flags: {
          MULTI_ITEM: 'false',
          HD_GENERATION: 0,
          NEW_QUALITY_GATE: null,
          TRYON_MAX: undefined,
          NATURAL_LANGUAGE_STUDIO: {},
        },
      });

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
      expect(isFeatureEnabled('TRYON_MAX')).toBe(true);
      expect(isFeatureEnabled('NATURAL_LANGUAGE_STUDIO')).toBe(false);
    });

    test('never throws on non-object or nullish payloads', () => {
      expect(() => applyFeatureFlagPayload(null)).not.toThrow();
      expect(() => applyFeatureFlagPayload(undefined)).not.toThrow();
      expect(() => applyFeatureFlagPayload('string')).not.toThrow();
      expect(() => applyFeatureFlagPayload(12345)).not.toThrow();
      expect(() => applyFeatureFlagPayload(true)).not.toThrow();
      expect(() => applyFeatureFlagPayload([])).not.toThrow();

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });
  });

  describe('refreshFeatureFlags', () => {
    const config: FeatureFlagRefreshConfig = {
      baseUrl: 'https://api.aifashionvault.com',
      getAccessToken: async () => 'test-firebase-token',
    };

    test('fetches normalized {baseUrl}/feature-flags and sends bearer token', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(200, {
          flags: {
            MULTI_ITEM: false,
            NATURAL_LANGUAGE_STUDIO: true,
            TRYON_MAX: true,
            HD_GENERATION: false,
            SHARE_IMPORT_ANDROID: true,
            SHARE_IMPORT_IOS: true,
            NEW_QUALITY_GATE: true,
          },
          overrides: {},
        }),
      );

      await refreshFeatureFlags(config);

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0];
      expect(requestUrl(url)).toBe(
        'https://api.aifashionvault.com/feature-flags',
      );
      expect(init?.method).toBe('GET');
      expect(init?.headers?.Accept).toBe('application/json');
      expect(init?.headers?.Authorization).toBe('Bearer test-firebase-token');

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('HD_GENERATION')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(true);
    });

    test('normalizes trailing slashes on baseUrl', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(200, {
          flags: { MULTI_ITEM: true },
        }),
      );

      await refreshFeatureFlags({
        baseUrl: 'https://api.aifashionvault.com///',
        getAccessToken: () => null,
      });

      const [url] = fetchMock.mock.calls[0];
      expect(requestUrl(url)).toBe(
        'https://api.aifashionvault.com/feature-flags',
      );
    });

    test('omits Authorization header when getAccessToken returns null or empty', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(200, {
          flags: { MULTI_ITEM: true },
        }),
      );

      await refreshFeatureFlags({
        baseUrl: 'https://api.aifashionvault.com',
        getAccessToken: () => null,
      });

      const [, init] = fetchMock.mock.calls[0];
      expect(init?.headers?.Authorization).toBeUndefined();
    });

    test('supports synchronous getAccessToken returning token string', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(200, {
          flags: { MULTI_ITEM: true },
        }),
      );

      await refreshFeatureFlags({
        baseUrl: 'https://api.aifashionvault.com',
        getAccessToken: () => 'sync-token',
      });

      const [, init] = fetchMock.mock.calls[0];
      expect(init?.headers?.Authorization).toBe('Bearer sync-token');
    });

    test('fail-safe: preserves state and never throws on non-2xx response', async () => {
      applyFeatureFlagPayload({
        flags: { MULTI_ITEM: false },
      });

      fetchMock.mockResolvedValueOnce(
        jsonResponse(401, { error: 'Unauthorized' }),
      );

      await expect(refreshFeatureFlags(config)).resolves.toBeUndefined();
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('fail-safe: preserves state and never throws on 500 server error', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(500, { error: 'Internal Server Error' }),
      );

      await expect(refreshFeatureFlags(config)).resolves.toBeUndefined();
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('fail-safe: preserves state and never throws on network error (fetch rejects)', async () => {
      fetchMock.mockRejectedValueOnce(new Error('Network request failed'));

      await expect(refreshFeatureFlags(config)).resolves.toBeUndefined();
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('fail-safe: preserves state and never throws on malformed JSON response', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: () => Promise.reject(new SyntaxError('Unexpected token < in JSON')),
      } as unknown as Response);

      await expect(refreshFeatureFlags(config)).resolves.toBeUndefined();
      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
      expect(isFeatureEnabled('NEW_QUALITY_GATE')).toBe(false);
    });

    test('fail-safe: preserves state and never throws when getAccessToken throws', async () => {
      await expect(
        refreshFeatureFlags({
          baseUrl: 'https://api.aifashionvault.com',
          getAccessToken: () => {
            throw new Error('Token store locked');
          },
        }),
      ).resolves.toBeUndefined();

      expect(isFeatureEnabled('MULTI_ITEM')).toBe(false);
    });
  });
});
