import { createAvatarApi } from '../src/features/my-model/avatarApi';

const BASE_URL = 'https://api.example.invalid';
const SIGNED_HOST = 'https://signed.example.invalid:4566';
const SIGNED_URL = `${SIGNED_HOST}/bucket/key?X-Amz-Signature=ticket-token`;
const LOCAL_URI = 'file:///photo.jpg';
const UPLOAD_ID = '22222222-2222-2222-2222-222222222222';
const TOKEN = 'injected-access-token';

const AVATAR = {
  id: '11111111-1111-1111-1111-111111111111',
  imageId: '33333333-3333-3333-3333-333333333333',
  revision: 1,
  width: 8,
  height: 6,
  mediaType: 'image/jpeg',
  displayUrl: 'https://signed.example.invalid/read?X-Amz-Signature=read-token',
  displayExpiresAt: '2026-08-23T12:00:00Z',
  warnings: [] as string[],
};

const PHOTO_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]).buffer;

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

function bytesResponse(
  bytes: ArrayBuffer,
  contentType: string,
): Promise<Response> {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? contentType : null,
    },
    json: () => Promise.resolve({}),
    arrayBuffer: () => Promise.resolve(bytes),
    text: () => Promise.resolve(''),
  } as unknown as Response);
}

function emptyResponse(status: number): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: () => Promise.resolve({}),
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

describe('avatarApi', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  function api() {
    return createAvatarApi({
      baseUrl: BASE_URL,
      getAccessToken: () => TOKEN,
    });
  }

  test('empty list is an empty collection', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/avatars`);
      return jsonResponse(200, []);
    });

    await expect(api().list()).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0];
    expect(init?.headers).toEqual(
      expect.objectContaining({
        Authorization: `Bearer ${TOKEN}`,
      }),
    );
  });

  test('create uses purpose=avatar, does not rewrite the signed host, and never sends base64', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();

      if (url === LOCAL_URI) {
        return bytesResponse(PHOTO_BYTES, 'image/jpeg');
      }
      if (url === `${BASE_URL}/uploads?purpose=avatar` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: {
            'Content-Type': 'image/jpeg',
            'Content-Length': '4',
          },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (
        url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete` &&
        method === 'POST'
      ) {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          status: 'READY',
          mediaType: 'image/jpeg',
          contentLength: 4,
          width: 8,
          height: 6,
        });
      }
      if (url === `${BASE_URL}/avatars` && method === 'POST') {
        return jsonResponse(200, AVATAR);
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const created = await api().create({ uri: LOCAL_URI });
    expect(created).toEqual(AVATAR);

    const urls = fetchMock.mock.calls.map(([input]) => requestUrl(input));
    expect(urls).toContain(`${BASE_URL}/uploads?purpose=avatar`);
    expect(urls).toContain(SIGNED_URL);
    expect(
      urls.some(
        url =>
          url.includes('api.example.invalid') && url.includes('bucket/key'),
      ),
    ).toBe(false);

    const putCall = fetchMock.mock.calls.find(
      ([input]) => requestUrl(input) === SIGNED_URL,
    );
    expect(putCall?.[1]?.body).toBe(PHOTO_BYTES);
    expect(putCall?.[1]?.headers).toEqual({
      'Content-Type': 'image/jpeg',
      'Content-Length': '4',
    });

    for (const [, init] of fetchMock.mock.calls) {
      const body = init?.body;
      if (typeof body === 'string') {
        expect(body).not.toMatch(/base64/i);
        expect(body).not.toMatch(/data:/i);
        expect(body).not.toContain(LOCAL_URI);
        const parsed = JSON.parse(body) as Record<string, unknown>;
        expect(JSON.stringify(parsed)).not.toMatch(/base64/i);
      }
    }

    const createUploadCall = fetchMock.mock.calls.find(
      ([input]) => requestUrl(input) === `${BASE_URL}/uploads?purpose=avatar`,
    );
    expect(createUploadCall?.[1]?.body).toBe(
      JSON.stringify({ contentType: 'image/jpeg', contentLength: 4 }),
    );

    const claimCall = fetchMock.mock.calls.find(
      ([input, init]) =>
        requestUrl(input) === `${BASE_URL}/avatars` &&
        (init?.method ?? 'GET').toUpperCase() === 'POST',
    );
    expect(claimCall?.[1]?.body).toBe(JSON.stringify({ uploadId: UPLOAD_ID }));
  });
});
