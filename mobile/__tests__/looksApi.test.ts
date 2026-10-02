import { createLooksApi } from '../src/features/looks/looksApi';

const BASE_URL = 'https://api.example.invalid';
const LOOK_ID = '55555555-5555-5555-5555-555555555555';
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

function noContentResponse(): Promise<Response> {
  return Promise.resolve({
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: () => Promise.reject(new Error('no body')),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(''),
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

describe('looksApi detail and delete', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  test('the detail read carries the owner-scoped provenance from the wire', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/looks/${LOOK_ID}`);
      return jsonResponse(200, {
        id: LOOK_ID,
        generationId: '44444444-4444-4444-4444-444444444444',
        createdAt: '2026-08-23T12:05:00Z',
        provider: 'fashn',
        providerModel: 'tryon-v1.6',
        qualityStatus: 'PASS',
        items: [
          { vaultItemId: '11111111-1111-1111-1111-111111111111', slot: 'UPPER_BODY' },
        ],
        image: {
          url: 'https://signed.example.invalid/read?X-Amz-Signature=read-token',
          expiresAt: '2026-08-23T12:10:00Z',
        },
      });
    });

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    const look = await api.get(LOOK_ID);

    expect(look.items).toEqual([
      { vaultItemId: '11111111-1111-1111-1111-111111111111', slot: 'UPPER_BODY' },
    ]);
    expect(look.qualityStatus).toBe('PASS');
    expect(look.provider).toBe('fashn');
    expect(look.image.expiresAt).toBe('2026-08-23T12:10:00Z');

    const [, init] = fetchMock.mock.calls[0];
    expect(init?.method).toBe('GET');
    expect(init?.headers).toEqual({
      Accept: 'application/json',
      Authorization: `Bearer ${TOKEN}`,
    });
  });

  test('a malformed provenance row is rejected rather than rendered as blank', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(200, {
        id: LOOK_ID,
        generationId: '44444444-4444-4444-4444-444444444444',
        createdAt: '2026-08-23T12:05:00Z',
        items: [{ slot: 'UPPER_BODY' }],
        image: {
          url: 'https://signed.example.invalid/read?X-Amz-Signature=read-token',
          expiresAt: '2026-08-23T12:10:00Z',
        },
      }),
    );

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    await expect(api.get(LOOK_ID)).rejects.toThrow('Invalid Look response');
  });

  test('delete issues DELETE to the mounted route and accepts the empty 204 body', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/looks/${LOOK_ID}`);
      return noContentResponse();
    });

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    await expect(api.remove(LOOK_ID)).resolves.toBeUndefined();

    const [, init] = fetchMock.mock.calls[0];
    expect(init?.method).toBe('DELETE');
  });

  test('a delete the server refuses surfaces the problem code instead of looking successful', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(404, { code: 'UNAUTHORIZED_OR_NOT_FOUND' }),
    );

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    await expect(api.remove(LOOK_ID)).rejects.toThrow('UNAUTHORIZED_OR_NOT_FOUND');
  });

  // C22 "Look exported": the export acknowledgement posts to the mounted ack route.
  test('ackExport posts to the mounted export-ack route and accepts the empty 204 body', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/looks/${LOOK_ID}/export-ack`);
      return noContentResponse();
    });

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    await expect(api.ackExport(LOOK_ID)).resolves.toBeUndefined();

    const [, init] = fetchMock.mock.calls[0];
    expect(init?.method).toBe('POST');
  });

  test('an ack the server refuses rejects so the caller can stay fire-and-forget', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(404, { code: 'UNAUTHORIZED_OR_NOT_FOUND' }),
    );

    const api = createLooksApi({ baseUrl: BASE_URL, getAccessToken: () => TOKEN });
    await expect(api.ackExport(LOOK_ID)).rejects.toThrow('UNAUTHORIZED_OR_NOT_FOUND');
  });
});
