import {
  clearActiveGeneration,
  getActiveGeneration,
} from '../../src/features/generation/activeGeneration';
import { createLook } from '../../src/features/generation/createLook';
import { createGenerationApi } from '../../src/features/generation/generationApi';
import { createProductUploader } from '../../src/features/generation/productUpload';

const BASE = 'https://api.example.invalid';
const LOCAL = 'file:///selected-product.png';
const PUT = 'https://private.invalid/upload?signature=write-only';
const UPLOAD = '22222222-2222-2222-2222-222222222222';
const GENERATION = '33333333-3333-3333-3333-333333333333';
const LOOK = '44444444-4444-4444-4444-444444444444';
const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;

function response(
  status: number,
  body?: unknown,
  contentType = 'application/json',
): Promise<Response> {
  const headers = new Headers({ 'content-type': contentType });
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(body == null ? '' : JSON.stringify(body)),
    arrayBuffer: () => Promise.resolve(body as ArrayBuffer),
  } as Response);
}

function generation(overrides: Record<string, unknown> = {}) {
  return {
    id: GENERATION,
    status: 'QUEUED',
    phase: 'QUEUED',
    statusVersion: 0,
    createdAt: '2026-08-29T12:00:00Z',
    updatedAt: '2026-08-29T12:00:00Z',
    ...overrides,
  };
}

function url(input: RequestInfo | URL): string {
  return typeof input === 'string'
    ? input
    : input instanceof URL
    ? input.toString()
    : input.url;
}

describe('first vertical slice mobile e2e', () => {
  const fetchMock = jest.fn();
  const config = { baseUrl: BASE, getAccessToken: () => 'access-token' };

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    clearActiveGeneration();
  });

  test('picker cancellation is a complete no-op', async () => {
    const upload = jest.fn(createProductUploader(config));
    const api = createGenerationApi(config);

    await expect(
      createLook({ picker: () => Promise.resolve(null), upload, api }),
    ).resolves.toEqual({ outcome: 'cancelled' });
    expect(upload).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(getActiveGeneration()).toBeNull();
  });

  test('picker to private upload, create, canonical poll, and private Look follows the complete sequence', async () => {
    let polls = 0;
    fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) => {
        const target = url(input);
        const method = (init?.method ?? 'GET').toUpperCase();
        if (target === LOCAL) return response(200, bytes, 'image/png');
        if (target === `${BASE}/uploads` && method === 'POST')
          return response(200, {
            id: UPLOAD,
            method: 'PUT',
            url: PUT,
            headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          });
        if (target === PUT && method === 'PUT') return response(200);
        if (
          target === `${BASE}/uploads/${UPLOAD}/complete` &&
          method === 'POST'
        )
          return response(200, {
            id: UPLOAD,
            status: 'READY',
            mediaType: 'image/png',
            contentLength: 4,
            width: 2,
            height: 2,
          });
        if (target === `${BASE}/generations` && method === 'POST')
          return response(202, generation());
        if (
          target === `${BASE}/generations/${GENERATION}` &&
          method === 'GET'
        ) {
          polls += 1;
          return response(
            200,
            polls === 1
              ? generation({
                  status: 'QUALITY_CHECK',
                  phase: 'CHECKING_QUALITY',
                  statusVersion: 1,
                })
              : generation({
                  status: 'COMPLETED',
                  phase: 'READY',
                  statusVersion: 2,
                  lookId: LOOK,
                }),
          );
        }
        if (target === `${BASE}/looks/${LOOK}` && method === 'GET')
          return response(200, {
            id: LOOK,
            generationId: GENERATION,
            createdAt: '2026-08-29T12:00:02Z',
            image: {
              url: 'https://private.invalid/read?signature=short-lived',
              expiresAt: '2026-08-29T12:05:02Z',
            },
          });
        throw new Error(`unexpected request ${method} ${target}`);
      },
    );

    await expect(
      createLook({
        picker: () => Promise.resolve({ uri: LOCAL }),
        upload: createProductUploader(config),
        api: createGenerationApi(config),
        delay: () => Promise.resolve(),
      }),
    ).resolves.toEqual({ outcome: 'ready', lookId: LOOK });

    expect(fetchMock.mock.calls.map(([input]) => url(input))).toEqual([
      LOCAL,
      `${BASE}/uploads`,
      PUT,
      `${BASE}/uploads/${UPLOAD}/complete`,
      `${BASE}/generations`,
      `${BASE}/generations/${GENERATION}`,
      `${BASE}/generations/${GENERATION}`,
      `${BASE}/looks/${LOOK}`,
    ]);
    const create = fetchMock.mock.calls[4][1] as RequestInit;
    expect(create.headers).toEqual(
      expect.objectContaining({
        Authorization: 'Bearer access-token',
        'Idempotency-Key': expect.any(String),
      }),
    );
    expect(create.body).toBe(JSON.stringify({ productUploadId: UPLOAD }));
    expect(fetchMock.mock.calls[2][1]).toEqual(
      expect.objectContaining({ method: 'PUT', body: bytes }),
    );
    expect(getActiveGeneration()).toBe(GENERATION);
  });
});
