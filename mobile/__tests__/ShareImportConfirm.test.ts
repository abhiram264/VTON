import {
  createShareImportConfirmer,
  createShareImportPreviewLoader,
} from '../src/features/share/importClient';
import {ApiProblemError} from '../src/features/my-model/http';
import type {AvatarApiConfig} from '../src/features/my-model/types';

const BASE_URL = 'https://api.example.invalid';
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

describe('createShareImportConfirmer', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  it('persists through POST /share/imports/{id}/confirm and returns the saved item', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {
        id: 'vault-item-1',
        sourceReferenceId: 'source-ref-1',
        name: null,
        brand: null,
        createdAt: '2026-10-03T00:00:00Z',
      }),
    );
    const confirm = createShareImportConfirmer(config());
    const item = await confirm(IMPORT_ID, new AbortController().signal);

    expect(item).toEqual({
      id: 'vault-item-1',
      sourceReferenceId: 'source-ref-1',
      createdAt: '2026-10-03T00:00:00Z',
    });
    const call = fetchMock.mock.calls[0];
    expect(requestUrl(call[0])).toBe(
      `${BASE_URL}/share/imports/${IMPORT_ID}/confirm`,
    );
    expect(call[1].method).toBe('POST');
    expect(call[1].headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  it('rejects a response that is not a persisted item instead of reporting success', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, {status: 'READY'}));
    const confirm = createShareImportConfirmer(config());

    await expect(
      confirm(IMPORT_ID, new AbortController().signal),
    ).rejects.toThrow('Invalid confirm response');
  });

  it('surfaces the server problem code for an import the owner cannot confirm', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(404, {code: 'IMPORT_NOT_FOUND'}),
    );
    const confirm = createShareImportConfirmer(config());

    await expect(
      confirm(IMPORT_ID, new AbortController().signal),
    ).rejects.toBeInstanceOf(ApiProblemError);
  });

  it('sends only the corrections the owner actually made', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {id: 'vault-item-1', sourceReferenceId: 'source-ref-1'}),
    );
    const confirm = createShareImportConfirmer(config());
    await confirm(IMPORT_ID, new AbortController().signal, {category: 'TOP'});

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).toEqual({category: 'TOP'});
    expect(body).not.toHaveProperty('name');
    expect(body).not.toHaveProperty('collectionId');
  });

  it('confirms with no body when the owner made no corrections', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(201, {id: 'vault-item-1', sourceReferenceId: 'source-ref-1'}),
    );
    const confirm = createShareImportConfirmer(config());
    await confirm(IMPORT_ID, new AbortController().signal, {});

    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  });
});

describe('createShareImportPreviewLoader', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  it('reads the owner-scoped preview and its safe source metadata', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        importId: IMPORT_ID,
        previewUrl: `${BASE_URL}/share/imports/${IMPORT_ID}/preview.png`,
        previewExpiresAt: '2026-10-03T00:05:00Z',
        sourceTitle: 'Linen Shirt',
        sourceSiteName: 'example.invalid',
      }),
    );
    const load = createShareImportPreviewLoader(config());
    const result = await load(IMPORT_ID, new AbortController().signal);

    expect(result.previewUrl).toBe(
      `${BASE_URL}/share/imports/${IMPORT_ID}/preview.png`,
    );
    expect(result.sourceTitle).toBe('Linen Shirt');
    const call = fetchMock.mock.calls[0];
    expect(requestUrl(call[0])).toBe(
      `${BASE_URL}/share/imports/${IMPORT_ID}/preview`,
    );
    expect(call[1].method).toBe('GET');
  });

  it('rejects a preview without a signed url or expiry instead of rendering a dead image', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {importId: IMPORT_ID, previewUrl: ''}),
    );
    const load = createShareImportPreviewLoader(config());

    await expect(
      load(IMPORT_ID, new AbortController().signal),
    ).rejects.toThrow('Invalid preview response');
  });

  it('rejects a preview whose expiry is not a timestamp', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        importId: IMPORT_ID,
        previewUrl: `${BASE_URL}/p.png`,
        previewExpiresAt: 'not-a-date',
      }),
    );
    const load = createShareImportPreviewLoader(config());

    await expect(
      load(IMPORT_ID, new AbortController().signal),
    ).rejects.toThrow('Invalid preview response');
  });

  it('rejects when the preview capability is unavailable', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(503, {code: 'DEPENDENCY_UNAVAILABLE'}),
    );
    const load = createShareImportPreviewLoader(config());

    await expect(
      load(IMPORT_ID, new AbortController().signal),
    ).rejects.toBeInstanceOf(ApiProblemError);
  });
});
