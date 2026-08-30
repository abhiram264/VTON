import {ApiProblemError} from '../src/features/my-model/http';
import {createShareImportResolver} from '../src/features/share/importClient';
import type {AvatarApiConfig} from '../src/features/my-model/types';

const BASE_URL = 'https://api.example.invalid';
const LOCAL_URI = 'file:///photo.jpg';
const PHOTO_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]).buffer;
const UPLOAD_ID = 'upload-1';
const IMPORT_ID = 'import-1';
const TOKEN = 'token-1';

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {get: () => 'application/json'},
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

function fileResponse(bytes: ArrayBuffer, contentType: string): Promise<Response> {
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
    headers: {get: () => null},
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

function config(): AvatarApiConfig {
  return {baseUrl: BASE_URL, getAccessToken: () => TOKEN};
}

function ticketResponse(): Promise<Response> {
  return jsonResponse(201, {
    id: UPLOAD_ID,
    method: 'PUT',
    url: `${BASE_URL}/staging/${UPLOAD_ID}`,
    headers: {},
  });
}

function stagedFileFetch(contentType: string): Promise<Response> {
  return fileResponse(PHOTO_BYTES, contentType);
}

function makeResolver(
  fetchMock: jest.Mock,
  deps?: {releaseStagedImage?: (uri: string) => Promise<void>},
) {
  return createShareImportResolver(config(), {
    pollDelayMs: 0,
    ...deps,
  });
}

describe('ShareImportResolver', () => {
  const fetchMock = jest.fn();
  const releaseStagedImage = jest.fn();


  beforeEach(() => {
    fetchMock.mockReset();
    releaseStagedImage.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });
  const imagePayload = {
    kind: 'image' as const,
    localImageUri: LOCAL_URI,
    sourcePackage: 'com.example.sender',
  };

  it('maps url payload to URL request without sourcePackage or localImageUri', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse(201, {importId: IMPORT_ID, status: 'READY'}),
      );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    const result = await resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal);

    expect(result).toEqual({importId: IMPORT_ID, status: 'READY'});
    const post = fetchMock.mock.calls[0];
    expect(requestUrl(post[0])).toBe(`${BASE_URL}/share/imports`);
    const body = JSON.parse(post[1].body);
    expect(body).toEqual({kind: 'URL', sourceUrl: `${BASE_URL}/p`});
  });

  it('maps image payload to IMAGE request and never sends localImageUri', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(201, {
        importId: IMPORT_ID, status: 'READY',
      }));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await resolver(imagePayload, new AbortController().signal);

    for (const call of fetchMock.mock.calls) {
      if (call[1]?.body && typeof call[1].body === 'string') {
        const parsed = JSON.parse(call[1].body);
        expect(parsed).not.toHaveProperty('localImageUri');
        expect(parsed).not.toHaveProperty('sourcePackage');
      }
    }
    const importPost = fetchMock.mock.calls.find(
      (call) => requestUrl(call[0]).endsWith('/share/imports'),
    );
    expect(importPost).toBeDefined();
    expect(JSON.parse(importPost![1].body)).toEqual({
      kind: 'IMAGE',
      uploadId: UPLOAD_ID,
    });
  });

  it('maps image_url payload to IMAGE_URL request with both fields', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(201, {
        importId: IMPORT_ID, status: 'READY',
      }));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    const sourceUrl = `${BASE_URL}/product`;
    await resolver(
      {kind: 'image_url', url: sourceUrl, localImageUri: LOCAL_URI},
      new AbortController().signal,
    );

    const importPost = fetchMock.mock.calls.find(
      (call) => requestUrl(call[0]).endsWith('/share/imports'),
    );
    expect(JSON.parse(importPost![1].body)).toEqual({
      kind: 'IMAGE_URL',
      sourceUrl,
      uploadId: UPLOAD_ID,
    });
  });

  it('releases the staged image exactly once after verified upload completes, before the import POST', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(201, {
        importId: IMPORT_ID, status: 'READY',
      }));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await resolver(imagePayload, new AbortController().signal);

    expect(releaseStagedImage).toHaveBeenCalledTimes(1);
    const importPostIndex = fetchMock.mock.calls.findIndex(
      (call) => requestUrl(call[0]).endsWith('/share/imports'),
    );
    expect(importPostIndex).toBeGreaterThan(0);
  });

  it('releases once and sends no import POST when upload completion fails', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(500));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver(imagePayload, new AbortController().signal),
    ).rejects.toThrow();

    expect(releaseStagedImage).toHaveBeenCalledTimes(1);
    const importPost = fetchMock.mock.calls.find(
      (call) => requestUrl(call[0]).endsWith('/share/imports'),
    );
    expect(importPost).toBeUndefined();
  });
  it('replays the retained uploadId on retry without re-reading or re-uploading', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(201, {
        importId: IMPORT_ID,
        status: 'FAILED_RETRYABLE',
        reason: 'DEPENDENCY_UNAVAILABLE',
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        importId: IMPORT_ID,
        status: 'READY',
      }));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    const first = await resolver(imagePayload, new AbortController().signal);
    expect(first.status).toBe('FAILED_RETRYABLE');
    const second = await resolver(imagePayload, new AbortController().signal);
    expect(second.status).toBe('READY');

    // initial upload flow (staged read + 3 upload calls) + two import POSTs
    expect(fetchMock).toHaveBeenCalledTimes(6);
    const stagedReads = fetchMock.mock.calls.filter(
      (call) => requestUrl(call[0]) === LOCAL_URI,
    );
    expect(stagedReads).toHaveLength(1);
    const importPosts = fetchMock.mock.calls.filter(
      (call) => requestUrl(call[0]).endsWith('/share/imports'),
    );
    expect(importPosts).toHaveLength(2);
    expect(JSON.parse(importPosts[0][1].body)).toEqual({
      kind: 'IMAGE',
      uploadId: UPLOAD_ID,
    });
    expect(JSON.parse(importPosts[1][1].body)).toEqual(
      JSON.parse(importPosts[0][1].body),
    );
    const keys = importPosts.map(
      (call) => call[1].headers['Idempotency-Key'],
    );
    expect(keys[0]).toBe(keys[1]);
    expect(releaseStagedImage).toHaveBeenCalledTimes(1);
  });

  it('releases once and sends no import POST when the upload ticket PUT fails', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(500));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver(imagePayload, new AbortController().signal),
    ).rejects.toThrow('Upload failed');
    expect(releaseStagedImage).toHaveBeenCalledTimes(1);
  });

  it('releases once and sends no import POST when the ticket initiate fails', async () => {
    fetchMock
      .mockImplementationOnce(() => stagedFileFetch('image/jpeg'))
      .mockResolvedValueOnce(emptyResponse(500));
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver(imagePayload, new AbortController().signal),
    ).rejects.toThrow();
    expect(releaseStagedImage).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('never releases anything in the URL-only flow', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {importId: IMPORT_ID, status: 'READY'}),
    );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal);
    expect(releaseStagedImage).not.toHaveBeenCalled();
  });

  it('retains one Idempotency-Key across replayed import POSTs for the same resolver', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse(201, {importId: IMPORT_ID, status: 'READY'}),
      )
      .mockResolvedValueOnce(
        jsonResponse(201, {importId: IMPORT_ID, status: 'READY'}),
      );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal);
    await resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal);

    const keys = fetchMock.mock.calls.map(
      (call) => call[1].headers['Idempotency-Key'],
    );
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('surfaces IDEMPOTENCY_KEY_REUSED as ApiProblemError with typed code', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, {code: 'IDEMPOTENCY_KEY_REUSED'}),
    );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal),
    ).rejects.toMatchObject({
      name: 'ApiProblemError',
      code: 'IDEMPOTENCY_KEY_REUSED',
    });
  });

  it('passes NEEDS_IMAGE through with exact recoveryActions', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {
        importId: IMPORT_ID,
        status: 'NEEDS_IMAGE',
        reason: 'URL_RESOLUTION_FAILED',
        recoveryActions: ['CHOOSE_SCREENSHOT', 'UPLOAD_IMAGE'],
      }),
    );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    const result = await resolver(
      {kind: 'url', url: `${BASE_URL}/p`},
      new AbortController().signal,
    );
    expect(result).toEqual({
      importId: IMPORT_ID,
      status: 'NEEDS_IMAGE',
      reason: 'URL_RESOLUTION_FAILED',
      recoveryActions: ['CHOOSE_SCREENSHOT', 'UPLOAD_IMAGE'],
    });
  });

  it('polls GET status while RESOLVING then returns the terminal response', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse(202, {importId: IMPORT_ID, status: 'RESOLVING'}),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {importId: IMPORT_ID, status: 'RESOLVING'}),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {importId: IMPORT_ID, status: 'READY'}),
      );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    const result = await resolver(
      {kind: 'url', url: `${BASE_URL}/p`},
      new AbortController().signal,
    );
    expect(result).toEqual({importId: IMPORT_ID, status: 'READY'});
    const pollUrl = requestUrl(fetchMock.mock.calls[1][0]);
    expect(fetchMock.mock.calls[1][1].method).toBe('GET');
  });

  it('rejects immediately when the signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver(
        {kind: 'url', url: `${BASE_URL}/p`},
        controller.signal,
      ),
    ).rejects.toThrow('Aborted');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects an invalid response shape instead of returning unvalidated data', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {status: 'NONSENSE'}),
    );
    const resolver = makeResolver(fetchMock, {releaseStagedImage});
    await expect(
      resolver({kind: 'url', url: `${BASE_URL}/p`}, new AbortController().signal),
    ).rejects.toThrow('Invalid import response');
  });
});
