import { createGenerationApi } from '../src/features/generation/generationApi';
import {
  clearActiveGeneration,
  getActiveGeneration,
} from '../src/features/generation/activeGeneration';

const BASE_URL = 'https://api.example.invalid';
const LOOK_ID = '55555555-5555-5555-5555-555555555555';
const GENERATION_ID = '44444444-4444-4444-4444-444444444444';
const TOKEN = 'injected-access-token';

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
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

describe('lookApi', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    clearActiveGeneration();
  });

  test('look read consumes the exact owner-scoped shape', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/looks/${LOOK_ID}`);
      return jsonResponse(200, {
        id: LOOK_ID,
        generationId: GENERATION_ID,
        createdAt: '2026-08-23T12:05:00Z',
        image: {
          url: 'https://signed.example.invalid/read?X-Amz-Signature=read-token',
          expiresAt: '2026-08-23T12:10:00Z',
        },
      });
    });

    const api = createGenerationApi({
      baseUrl: BASE_URL,
      getAccessToken: () => TOKEN,
    });
    const look = await api.getLook(LOOK_ID);

    expect(look).toEqual({
      id: LOOK_ID,
      generationId: GENERATION_ID,
      createdAt: '2026-08-23T12:05:00Z',
      image: {
        url: 'https://signed.example.invalid/read?X-Amz-Signature=read-token',
        expiresAt: '2026-08-23T12:10:00Z',
      },
    });

    const [, init] = fetchMock.mock.calls[0];
    expect(init?.headers).toEqual(
      expect.objectContaining({ Authorization: `Bearer ${TOKEN}` }),
    );

    // A look read never persists anything, least of all the short-lived URL.
    expect(getActiveGeneration()).toBeNull();
  });

  test('each read mints a fresh capability; the URL is never cached identity', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(200, {
        id: LOOK_ID,
        generationId: GENERATION_ID,
        createdAt: '2026-08-23T12:05:00Z',
        image: {
          url: `https://signed.example.invalid/read?token=${fetchMock.mock.calls.length}`,
          expiresAt: '2026-08-23T12:10:00Z',
        },
      }),
    );

    const api = createGenerationApi({
      baseUrl: BASE_URL,
      getAccessToken: () => TOKEN,
    });

    const first = await api.getLook(LOOK_ID);
    const second = await api.getLook(LOOK_ID);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(first.image.url).not.toBe(second.image.url);
    expect(second.image.url).toBe(
      'https://signed.example.invalid/read?token=2',
    );
  });

  test('malformed look response is rejected', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(200, {
        id: LOOK_ID,
        generationId: GENERATION_ID,
        createdAt: '2026-08-23T12:05:00Z',
        image: { url: 'https://signed.example.invalid/read?token=a' },
      }),
    );

    const api = createGenerationApi({
      baseUrl: BASE_URL,
      getAccessToken: () => TOKEN,
    });
    await expect(api.getLook(LOOK_ID)).rejects.toThrow('Invalid look response');
  });
});
