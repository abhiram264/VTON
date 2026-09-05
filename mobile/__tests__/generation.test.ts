import { createLook } from '../src/features/generation/createLook';
import {
  createGenerationApi,
  createIdempotencyKey,
} from '../src/features/generation/generationApi';
import { createProductUploader } from '../src/features/generation/productUpload';
import {
  clearActiveGeneration,
  getActiveGeneration,
} from '../src/features/generation/activeGeneration';
import {
  classifyGenerationFailure,
  OUTAGE_FAILURE_CODES,
} from '../src/features/generation/failureContract';

const BASE_URL = 'https://api.example.invalid';
const SIGNED_URL =
  'https://signed.example.invalid:4566/bucket/key?X-Amz-Signature=ticket-token';
const LOCAL_URI = 'file:///product.png';
const UPLOAD_ID = '22222222-2222-2222-2222-222222222222';
const GENERATION_ID = '44444444-4444-4444-4444-444444444444';
const LOOK_ID = '55555555-5555-5555-5555-555555555555';
const TOKEN = 'injected-access-token';

const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;

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
    headers: { get: () => contentType },
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

function generationResource(overrides: Record<string, unknown> = {}): unknown {
  return {
    id: GENERATION_ID,
    status: 'QUEUED',
    phase: 'QUEUED',
    statusVersion: 0,
    createdAt: '2026-08-23T12:00:00Z',
    updatedAt: '2026-08-23T12:00:00Z',
    lookId: null,
    failureCode: null,
    ...overrides,
  };
}

function lookResource(url: string): unknown {
  return {
    id: LOOK_ID,
    generationId: GENERATION_ID,
    createdAt: '2026-08-23T12:05:00Z',
    image: { url, expiresAt: '2026-08-23T12:10:00Z' },
  };
}

function config() {
  return { baseUrl: BASE_URL, getAccessToken: () => TOKEN };
}

const CANCELLED_PICKER = () => Promise.resolve(null);
const PRODUCT_PICKER = () => Promise.resolve({ uri: LOCAL_URI });
const noDelay = () => Promise.resolve();

describe('createLook', () => {
  const fetchMock = jest.fn();
  const api = () => createGenerationApi(config());
  const upload = () => createProductUploader(config());

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
    clearActiveGeneration();
  });

  test('picker cancellation creates no upload and no generation', async () => {
    const result = await createLook({
      picker: CANCELLED_PICKER,
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({ outcome: 'cancelled' });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(getActiveGeneration()).toBeNull();
  });

  test('valid path runs initiate, direct PUT, complete, async create, then polling to the look', async () => {
    let polls = 0;
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();

      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
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
          mediaType: 'image/png',
          contentLength: 4,
          width: 8,
          height: 6,
        });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        expect(init?.headers).toEqual(
          expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
        );
        expect(init?.headers.Authorization).toBe(`Bearer ${TOKEN}`);
        expect(init?.body).toBe(JSON.stringify({ productUploadId: UPLOAD_ID }));
        return jsonResponse(202, generationResource());
      }
      if (
        url === `${BASE_URL}/generations/${GENERATION_ID}` &&
        method === 'GET'
      ) {
        polls += 1;
        if (polls === 1) {
          return jsonResponse(
            200,
            generationResource({
              status: 'GENERATING',
              phase: 'GENERATING',
              statusVersion: 1,
            }),
          );
        }
        return jsonResponse(
          200,
          generationResource({
            status: 'COMPLETED',
            phase: 'READY',
            statusVersion: 2,
            lookId: LOOK_ID,
          }),
        );
      }
      if (url === `${BASE_URL}/looks/${LOOK_ID}`) {
        return jsonResponse(
          200,
          lookResource('https://signed.example.invalid/read?token=a'),
        );
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const waits: number[] = [];
    const result = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
      delay: ms => {
        waits.push(ms);
        return Promise.resolve();
      },
    });

    expect(result).toEqual({ outcome: 'ready', lookId: LOOK_ID });

    const urls = fetchMock.mock.calls.map(([input]) => requestUrl(input));
    expect(urls.indexOf(`${BASE_URL}/uploads`)).toBeLessThan(
      urls.indexOf(SIGNED_URL),
    );
    expect(urls.indexOf(SIGNED_URL)).toBeLessThan(
      urls.indexOf(`${BASE_URL}/uploads/${UPLOAD_ID}/complete`),
    );
    expect(
      urls.indexOf(`${BASE_URL}/uploads/${UPLOAD_ID}/complete`),
    ).toBeLessThan(urls.indexOf(`${BASE_URL}/generations`));
    // Generic product purpose: no avatar purpose parameter.
    expect(urls).toContain(`${BASE_URL}/uploads`);

    const putCall = fetchMock.mock.calls.find(
      ([input]) => requestUrl(input) === SIGNED_URL,
    );
    expect(putCall?.[1]?.body).toBe(PNG_BYTES);
    expect(putCall?.[1]?.headers).toEqual({
      'Content-Type': 'image/png',
      'Content-Length': '4',
    });

    // Canonical polling only: one wait between GENERATING and COMPLETED.
    expect(waits).toEqual([2000]);

    // The generation ID is persisted for canonical polling.
    expect(getActiveGeneration()).toBe(GENERATION_ID);

    for (const [, init] of fetchMock.mock.calls) {
      const body = init?.body;
      if (typeof body === 'string') {
        expect(body).not.toMatch(/base64/i);
        expect(body).not.toContain(LOCAL_URI);
      }
    }
  });

  test.each([
    [
      'unsupported type is rejected before any upload',
      'file:///product.gif',
      'image/gif',
      PNG_BYTES,
      'UNSUPPORTED_IMAGE_TYPE',
    ],
    [
      'empty image is rejected before any upload',
      'file:///product.png',
      'image/png',
      new ArrayBuffer(0),
      'IMAGE_EMPTY',
    ],
  ])('%s', async (_name, uri, contentType, bytes, code) => {
    fetchMock.mockImplementation(() => bytesResponse(bytes, contentType));

    const result = await createLook({
      picker: () => Promise.resolve({ uri }),
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({ outcome: 'failed', reason: 'rejected', code });
    expect(fetchMock).toHaveBeenCalledTimes(1); // only the local file read
    expect(
      fetchMock.mock.calls.some(([input]) =>
        requestUrl(input).includes('/uploads'),
      ),
    ).toBe(false);
  });

  test('oversized image is rejected before any upload', async () => {
    const oversized = new ArrayBuffer(30 * 1024 * 1024 + 1);
    fetchMock.mockImplementation(() => bytesResponse(oversized, 'image/png'));

    const result = await createLook({
      picker: () => Promise.resolve({ uri: LOCAL_URI }),
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({
      outcome: 'failed',
      reason: 'rejected',
      code: 'IMAGE_TOO_LARGE',
    });
    expect(
      fetchMock.mock.calls.some(([input]) =>
        requestUrl(input).includes('/uploads'),
      ),
    ).toBe(false);
  });

  test('a lost push never stops polling, and a push hint only skips one wait', async () => {
    let polls = 0;
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete`) {
        return jsonResponse(200, { id: UPLOAD_ID, status: 'READY' });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        return jsonResponse(202, generationResource());
      }
      if (url === `${BASE_URL}/generations/${GENERATION_ID}`) {
        polls += 1;
        if (polls === 1) {
          return jsonResponse(
            200,
            generationResource({
              status: 'GENERATING',
              phase: 'GENERATING',
              statusVersion: 1,
            }),
          );
        }
        return jsonResponse(
          200,
          generationResource({
            status: 'COMPLETED',
            phase: 'READY',
            statusVersion: 2,
            lookId: LOOK_ID,
          }),
        );
      }
      if (url === `${BASE_URL}/looks/${LOOK_ID}`) {
        return jsonResponse(
          200,
          lookResource('https://signed.example.invalid/read?token=b'),
        );
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    // Push is lost entirely: polling still reaches the terminal state.
    const waits: number[] = [];
    const withoutPush = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
      delay: ms => {
        waits.push(ms);
        return Promise.resolve();
      },
    });
    expect(withoutPush).toEqual({ outcome: 'ready', lookId: LOOK_ID });
    expect(waits).toEqual([2000]);

    // A delivered push is only a refresh hint: it skips one wait, then
    // resets, and polling remains the truth.
    clearActiveGeneration();
    polls = 0;
    const hint = { current: true };
    const hintWaits: number[] = [];
    const withHint = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
      delay: ms => {
        hintWaits.push(ms);
        return Promise.resolve();
      },
      hint,
    });
    expect(withHint).toEqual({ outcome: 'ready', lookId: LOOK_ID });
    expect(hintWaits).toEqual([]);
    expect(hint.current).toBe(false);
  });

  test('failed generation surfaces the safe failure code and never reads a look', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete`) {
        return jsonResponse(200, { id: UPLOAD_ID, status: 'READY' });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        return jsonResponse(202, generationResource());
      }
      if (url === `${BASE_URL}/generations/${GENERATION_ID}`) {
        return jsonResponse(
          200,
          generationResource({
            status: 'FAILED',
            phase: 'FAILED',
            failureCode: 'QUALITY_REJECTED',
          }),
        );
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const result = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
      delay: noDelay,
    });

    expect(result).toEqual({
      outcome: 'failed',
      reason: 'error',
      code: 'QUALITY_REJECTED',
    });
    expect(
      fetchMock.mock.calls.some(([input]) =>
        requestUrl(input).includes('/looks/'),
      ),
    ).toBe(false);
  });

  test('idempotency key reuse surfaces the server problem code', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete`) {
        return jsonResponse(200, { id: UPLOAD_ID, status: 'READY' });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        return jsonResponse(409, { code: 'IDEMPOTENCY_KEY_REUSED' });
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const result = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({
      outcome: 'failed',
      reason: 'error',
      code: 'IDEMPOTENCY_KEY_REUSED',
    });
  });

  test('malformed generation response fails without inventing state', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete`) {
        return jsonResponse(200, { id: UPLOAD_ID, status: 'READY' });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        return jsonResponse(202, { id: GENERATION_ID, status: 'QUEUED' });
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const result = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({ outcome: 'failed', reason: 'error' });
  });
  test('uncoded fetch network rejection sets networkLevel truthful origin signal', async () => {
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return Promise.reject(new TypeError('Network request failed'));
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const result = await createLook({
      picker: PRODUCT_PICKER,
      upload: upload(),
      api: api(),
    });

    expect(result).toEqual({
      outcome: 'failed',
      reason: 'error',
      networkLevel: true,
    });
  });

  test('retained photo input bypasses picker and proceeds with upload and generation', async () => {
    const pickerMock = jest.fn();
    fetchMock.mockImplementation((input, init) => {
      const url = requestUrl(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url === LOCAL_URI) {
        return bytesResponse(PNG_BYTES, 'image/png');
      }
      if (url === `${BASE_URL}/uploads` && method === 'POST') {
        return jsonResponse(200, {
          id: UPLOAD_ID,
          method: 'PUT',
          url: SIGNED_URL,
          headers: { 'Content-Type': 'image/png', 'Content-Length': '4' },
          expiresAt: '2026-08-23T12:00:00Z',
        });
      }
      if (url === SIGNED_URL && method === 'PUT') {
        return emptyResponse(200);
      }
      if (url === `${BASE_URL}/uploads/${UPLOAD_ID}/complete` && method === 'POST') {
        return jsonResponse(200, { id: UPLOAD_ID, status: 'READY' });
      }
      if (url === `${BASE_URL}/generations` && method === 'POST') {
        return jsonResponse(202, generationResource());
      }
      if (url === `${BASE_URL}/generations/${GENERATION_ID}`) {
        return jsonResponse(
          200,
          generationResource({ status: 'COMPLETED', phase: 'READY', lookId: LOOK_ID }),
        );
      }
      if (url === `${BASE_URL}/looks/${LOOK_ID}`) {
        return jsonResponse(
          200,
          lookResource('https://signed.example.invalid/read?token=a'),
        );
      }
      throw new Error(`unexpected fetch ${method} ${url}`);
    });

    const result = await createLook({
      picker: pickerMock,
      upload: upload(),
      api: api(),
      delay: noDelay,
      photo: { uri: LOCAL_URI },
    });

    expect(result).toEqual({ outcome: 'ready', lookId: LOOK_ID });
    expect(pickerMock).not.toHaveBeenCalled();
  });
});

describe('generationApi', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  test('idempotency keys are bounded and nonblank', () => {
    const key = createIdempotencyKey();
    expect(key.length).toBeGreaterThan(0);
    expect(key.length).toBeLessThanOrEqual(64);
    expect(key.trim()).toBe(key);
  });

  test('blank idempotency keys fail before any request', async () => {
    const api = createGenerationApi(config());
    await expect(api.create(UPLOAD_ID, '  ')).rejects.toThrow(
      'Idempotency key is required',
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('polling treats unknown server status as a contract breach', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(200, generationResource({ status: 'SOME_NEW_STATE' })),
    );
    const api = createGenerationApi(config());
    await expect(api.get(GENERATION_ID)).rejects.toThrow(
      'Unknown generation status',
    );
  });
});

describe('classifyGenerationFailure', () => {
  test.each(OUTAGE_FAILURE_CODES)(
    'maps outage code %s to OUTAGE when networkLevel is false',
    code => {
      expect(classifyGenerationFailure(code, false)).toBe('OUTAGE');
    },
  );

  test('maps network-level failures to OUTAGE with precedence over codes', () => {
    expect(classifyGenerationFailure(undefined, true)).toBe('OUTAGE');
    expect(classifyGenerationFailure('UNKNOWN_CODE', true)).toBe('OUTAGE');
    expect(classifyGenerationFailure('QUALITY_REJECTED', true)).toBe('OUTAGE');
    expect(classifyGenerationFailure('MY_MODEL_MISSING', true)).toBe('OUTAGE');
  });

  test('maps QUALITY_REJECTED to QUALITY', () => {
    expect(classifyGenerationFailure('QUALITY_REJECTED', false)).toBe('QUALITY');
  });

  test('maps MY_MODEL_MISSING to MODEL_MISSING', () => {
    expect(classifyGenerationFailure('MY_MODEL_MISSING', false)).toBe(
      'MODEL_MISSING',
    );
  });

  test.each([
    'RATE_LIMITED',
    'RATE_LIMIT',
    'PROVIDER_RATE_LIMIT_EXHAUSTED',
    'PROVIDER_RATE_LIMIT',
    'TOO_MANY_REQUESTS',
    '429',
  ])('maps rate limit code %s to RATE_LIMITED', code => {
    expect(classifyGenerationFailure(code, false)).toBe('RATE_LIMITED');
  });

  test('maps unknown codes and undefined to GENERIC', () => {
    expect(classifyGenerationFailure('UNKNOWN_ERROR', false)).toBe('GENERIC');
    expect(classifyGenerationFailure('FOO_BAR', false)).toBe('GENERIC');
    expect(classifyGenerationFailure(undefined, false)).toBe('GENERIC');
  });
});
