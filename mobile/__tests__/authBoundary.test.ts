import { ApiProblemError } from '../src/features/my-model/http';
import { avatarApi as mountedAvatarApi, createAvatarApi } from '../src/features/my-model/avatarApi';
import {
  createGenerationApi,
  generationApi as mountedGenerationApi,
} from '../src/features/generation/generationApi';
import { createShareImportResolver } from '../src/features/share/importClient';
import { apiConfig as shareImportApiConfig } from '../src/screens/share-import/apiConfig';
import type { AvatarApiConfig } from '../src/features/my-model/types';
import type { GenerationApiConfig } from '../src/features/generation/types';
import type { SharePayload } from '../src/features/share/types';

const BASE_URL = 'https://api.example.invalid';
const SYNC_TOKEN = 'sync-auth-token-xyz';
const ASYNC_TOKEN = 'async-auth-token-abc';

const GENERATION_PAYLOAD = {
  id: 'test-gen-id',
  status: 'COMPLETED',
  phase: 'READY',
  statusVersion: 1,
  createdAt: '2026-09-05T00:00:00Z',
  updatedAt: '2026-09-05T00:00:00Z',
};

const SHARE_PAYLOAD: SharePayload = {
  kind: 'url',
  url: 'https://brand.example.com/product/123',
};

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/json' : null,
    },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

describe('Auth token boundary', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  // The request that actually reached the transport, as a consumer would
  // observe it. Call counts are never asserted: a queued single-use response
  // already fails the run on any retry, so a count adds no information.
  function issuedRequest(): { url: string; init: RequestInit } {
    const call = fetchMock.mock.calls[0];
    if (call === undefined) {
      throw new Error('no request was issued');
    }
    return { url: String(call[0]), init: (call[1] ?? {}) as RequestInit };
  }

  describe.each([
    {
      clientName: 'AvatarApi',
      successPayload: [],
      invokeClient: async (config: AvatarApiConfig) => {
        const client = createAvatarApi(config);
        return client.list();
      },
    },
    {
      clientName: 'GenerationApi',
      successPayload: GENERATION_PAYLOAD,
      invokeClient: async (config: GenerationApiConfig) => {
        const client = createGenerationApi(config);
        return client.get('test-gen-id');
      },
    },
    {
      clientName: 'ShareImportResolver',
      successPayload: {
        importId: 'test-import-id',
        status: 'READY',
      },
      invokeClient: async (config: AvatarApiConfig) => {
        const resolver = createShareImportResolver(config, { pollDelayMs: 0 });
        const controller = new AbortController();
        return resolver(SHARE_PAYLOAD, controller.signal);
      },
    },
  ])('$clientName', ({ successPayload, invokeClient }) => {
    test('issues a request to the configured base URL carrying a real bearer header', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

      await invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: () => SYNC_TOKEN,
      });

      const { url, init } = issuedRequest();
      expect(url.startsWith(`${BASE_URL}/`)).toBe(true);
      expect((init.headers as Record<string, string>).Authorization).toBe(
        `Bearer ${SYNC_TOKEN}`,
      );
    });

    test('issues a request carrying the bearer header from an asynchronous getAccessToken', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

      await invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: async () => ASYNC_TOKEN,
      });

      const { url, init } = issuedRequest();
      expect(url.startsWith(`${BASE_URL}/`)).toBe(true);
      expect((init.headers as Record<string, string>).Authorization).toBe(
        `Bearer ${ASYNC_TOKEN}`,
      );
    });

    // The F02 class: a client that cannot authenticate must not reach the
    // transport and must not resolve as if it had. Silently omitting the
    // header (features/my-model/http.ts:48-51) is the defect, not the
    // contract, so the absent/empty token is required to be refused.
    test.each([
      ['null', null],
      ['an empty string', ''],
    ])(
      'refuses the request instead of silently succeeding when getAccessToken yields %s',
      async (_label, token: string | null) => {
        fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

        await expect(
          invokeClient({
            baseUrl: BASE_URL,
            getAccessToken: () => token,
          }),
        ).rejects.toThrow(Error);
      },
    );

    test('typed 401 raises ApiProblemError with the backend code', async () => {
      fetchMock.mockReturnValueOnce(
        jsonResponse(401, {
          code: 'UNAUTHENTICATED_SESSION_EXPIRED',
          message: 'Session has expired',
        }),
      );

      const promise = invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: () => SYNC_TOKEN,
      });

      await expect(promise).rejects.toThrow(ApiProblemError);
      await expect(promise).rejects.toMatchObject({
        code: 'UNAUTHENTICATED_SESSION_EXPIRED',
      });
    });

    test('tokens never appear in console output on success or 401 failure', async () => {
      const logSpy = jest.spyOn(console, 'log');
      const infoSpy = jest.spyOn(console, 'info');
      const warnSpy = jest.spyOn(console, 'warn');
      const errorSpy = jest.spyOn(console, 'error');

      try {
        fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

        await invokeClient({
          baseUrl: BASE_URL,
          getAccessToken: () => SYNC_TOKEN,
        });

        fetchMock.mockReturnValueOnce(
          jsonResponse(401, {
            code: 'TOKEN_REVOKED',
          }),
        );

        await expect(
          invokeClient({
            baseUrl: BASE_URL,
            getAccessToken: async () => ASYNC_TOKEN,
          }),
        ).rejects.toThrow(ApiProblemError);

        const emitted = [logSpy, infoSpy, warnSpy, errorSpy]
          .flatMap(spy => spy.mock.calls)
          .map(call => call.map(String).join(' '));

        for (const line of emitted) {
          expect(line).not.toContain(SYNC_TOKEN);
          expect(line).not.toContain(ASYNC_TOKEN);
        }
      } finally {
        logSpy.mockRestore();
        infoSpy.mockRestore();
        warnSpy.mockRestore();
        errorSpy.mockRestore();
      }
    });
  });

  // The eight sites in mobile/src set baseUrl: '' with a null or throwing
  // access token. Mounted with no session and no backend, a client must fail
  // visibly instead of resolving a 200-shaped success from a bare relative
  // path (features/my-model/http.ts:52).
  describe('mounted clients with no configured session', () => {
    test('mounted AvatarApi refuses instead of appearing to succeed', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, []));

      await expect(mountedAvatarApi.list()).rejects.toThrow(Error);
    });

    test('mounted GenerationApi refuses instead of appearing to succeed', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, GENERATION_PAYLOAD));

      await expect(mountedGenerationApi.get('test-gen-id')).rejects.toThrow(
        Error,
      );
    });

    // screens/share-import/apiConfig.ts:8-10 is the one mount whose
    // getAccessToken throws, so it fails closed loudly inside the token read
    // at features/my-model/http.ts:48 and never reaches URL construction.
    // Pinned to that throw so it cannot pass for the same reason as the
    // relative-URL mounts above.
    test('share import config fails closed on the configuration error', async () => {
      const resolver = createShareImportResolver(shareImportApiConfig, {
        pollDelayMs: 0,
      });
      const controller = new AbortController();

      await expect(
        resolver(SHARE_PAYLOAD, controller.signal),
      ).rejects.toThrow(/not configured/i);
    });
  });
});
