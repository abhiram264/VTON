import {createShareImportConfirmer} from '../src/features/share/importClient';
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
});
