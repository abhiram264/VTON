import { ApiProblemError } from '../src/features/my-model/http';
import { createAvatarApi } from '../src/features/my-model/avatarApi';
import { createGenerationApi } from '../src/features/generation/generationApi';
import { createShareImportResolver } from '../src/features/share/importClient';
import type { AvatarApiConfig } from '../src/features/my-model/types';
import type { GenerationApiConfig } from '../src/features/generation/types';
import type { SharePayload } from '../src/features/share/types';

const BASE_URL = 'https://api.example.invalid';
const SYNC_TOKEN = 'sync-auth-token-xyz';
const ASYNC_TOKEN = 'async-auth-token-abc';

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
      successPayload: {
        id: 'test-gen-id',
        status: 'COMPLETED',
        phase: 'READY',
        statusVersion: 1,
        createdAt: '2026-09-05T00:00:00Z',
        updatedAt: '2026-09-05T00:00:00Z',
      },
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
        const payload: SharePayload = {
          kind: 'url',
          url: 'https://brand.example.com/product/123',
        };
        const controller = new AbortController();
        return resolver(payload, controller.signal);
      },
    },
  ])('$clientName', ({ successPayload, invokeClient }) => {
    test('attaches Authorization header with synchronous getAccessToken', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

      await invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: () => SYNC_TOKEN,
      });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [, init] = fetchMock.mock.calls[0];
      expect(init?.headers?.Authorization).toBe(`Bearer ${SYNC_TOKEN}`);
    });

    test('attaches Authorization header with asynchronous getAccessToken', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

      await invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: async () => ASYNC_TOKEN,
      });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [, init] = fetchMock.mock.calls[0];
      expect(init?.headers?.Authorization).toBe(`Bearer ${ASYNC_TOKEN}`);
    });

    test('omits Authorization header when getAccessToken returns null', async () => {
      fetchMock.mockReturnValueOnce(jsonResponse(200, successPayload));

      await invokeClient({
        baseUrl: BASE_URL,
        getAccessToken: () => null,
      });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [, init] = fetchMock.mock.calls[0];
      expect(init?.headers?.Authorization).toBeUndefined();
    });

    test('typed 401 raises ApiProblemError with backend code and exactly one request (no retry)', async () => {
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
      expect(fetchMock).toHaveBeenCalledTimes(1);
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
});
