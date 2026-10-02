import { createCollectionsApi } from '../src/features/collections/collectionsApi';
import { ApiProblemError } from '../src/features/my-model/http';

const BASE_URL = 'https://api.example.invalid';
const TOKEN = 'injected-access-token';
const COLLECTION_ID = '11111111-1111-1111-1111-111111111111';
const OTHER_ID = '22222222-2222-2222-2222-222222222222';
const ITEM_ID = '33333333-3333-3333-3333-333333333333';

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

function collectionBody(id: string, name: string, vaultItemIds: string[]) {
  return {
    id,
    name,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    items: vaultItemIds.map(vaultItemId => ({
      vaultItemId,
      createdAt: '2026-09-01T00:00:00Z',
    })),
  };
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

describe('collectionsApi', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  function api() {
    return createCollectionsApi({
      baseUrl: BASE_URL,
      getAccessToken: () => TOKEN,
    });
  }

  function calls() {
    return fetchMock.mock.calls.map(call => ({
      url: requestUrl(call[0] as RequestInfo | URL),
      method: (call[1] as RequestInit).method,
      body: (call[1] as RequestInit).body,
      headers: (call[1] as RequestInit).headers as Record<string, string>,
    }));
  }

  test('create posts the name and parses the created collection', async () => {
    fetchMock.mockImplementationOnce(() =>
      jsonResponse(201, collectionBody(COLLECTION_ID, 'Summer', [])),
    );

    const created = await api().create('Summer');

    expect(created).toEqual({
      id: COLLECTION_ID,
      name: 'Summer',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
      items: [],
    });
    const [call] = calls();
    expect(call.url).toBe(`${BASE_URL}/collections`);
    expect(call.method).toBe('POST');
    expect(JSON.parse(String(call.body))).toEqual({ name: 'Summer' });
    expect(call.headers.Authorization).toBe(`Bearer ${TOKEN}`);
  });

  test('list parses every collection and its members', async () => {
    fetchMock.mockImplementationOnce(() =>
      jsonResponse(200, [
        collectionBody(COLLECTION_ID, 'Summer', [ITEM_ID]),
        collectionBody(OTHER_ID, 'Work', []),
      ]),
    );

    const collections = await api().list();

    expect(collections.map(entry => entry.id)).toEqual([
      COLLECTION_ID,
      OTHER_ID,
    ]);
    expect(collections[0].items.map(item => item.vaultItemId)).toEqual([
      ITEM_ID,
    ]);
    expect(calls()[0]).toMatchObject({
      url: `${BASE_URL}/collections`,
      method: 'GET',
    });
  });

  test('one garment can belong to two collections', async () => {
    fetchMock
      .mockImplementationOnce(() =>
        jsonResponse(200, collectionBody(COLLECTION_ID, 'Summer', [ITEM_ID])),
      )
      .mockImplementationOnce(() =>
        jsonResponse(200, collectionBody(OTHER_ID, 'Work', [ITEM_ID])),
      );

    const client = api();
    const first = await client.addItem(COLLECTION_ID, ITEM_ID);
    const second = await client.addItem(OTHER_ID, ITEM_ID);

    expect(first.items.map(item => item.vaultItemId)).toEqual([ITEM_ID]);
    expect(second.items.map(item => item.vaultItemId)).toEqual([ITEM_ID]);
    expect(calls()[0].url).toBe(
      `${BASE_URL}/collections/${COLLECTION_ID}/items`,
    );
    expect(calls()[1].url).toBe(`${BASE_URL}/collections/${OTHER_ID}/items`);
    expect(JSON.parse(String(calls()[0].body))).toEqual({
      vaultItemId: ITEM_ID,
    });
  });

  test('removing membership deletes only the membership, never the garment', async () => {
    fetchMock.mockImplementationOnce(() => emptyResponse(204));

    await api().removeItem(COLLECTION_ID, ITEM_ID);

    const [call] = calls();
    expect(call.url).toBe(
      `${BASE_URL}/collections/${COLLECTION_ID}/items/${ITEM_ID}`,
    );
    expect(call.method).toBe('DELETE');
    expect(calls().some(entry => entry.url.includes('/vault/'))).toBe(false);
  });

  test('deleting a collection issues no vault-item delete', async () => {
    fetchMock.mockImplementationOnce(() => emptyResponse(204));

    await api().remove(COLLECTION_ID);

    const [call] = calls();
    expect(call.url).toBe(`${BASE_URL}/collections/${COLLECTION_ID}`);
    expect(call.method).toBe('DELETE');
    expect(calls().some(entry => entry.url.includes('/vault/'))).toBe(false);
  });

  test('rename patches the name and returns the updated collection', async () => {
    fetchMock.mockImplementationOnce(() =>
      jsonResponse(200, collectionBody(COLLECTION_ID, 'Autumn', [ITEM_ID])),
    );

    const renamed = await api().rename(COLLECTION_ID, 'Autumn');

    expect(renamed.name).toBe('Autumn');
    expect(calls()[0]).toMatchObject({
      url: `${BASE_URL}/collections/${COLLECTION_ID}`,
      method: 'PATCH',
    });
    expect(JSON.parse(String(calls()[0].body))).toEqual({ name: 'Autumn' });
  });

  test('an owner-scoped 404 surfaces COLLECTION_NOT_FOUND', async () => {
    fetchMock.mockImplementation(() =>
      jsonResponse(404, { code: 'COLLECTION_NOT_FOUND' }),
    );

    await expect(api().rename(COLLECTION_ID, 'X')).rejects.toMatchObject({
      code: 'COLLECTION_NOT_FOUND',
    });
    await expect(api().addItem(COLLECTION_ID, ITEM_ID)).rejects.toBeInstanceOf(
      ApiProblemError,
    );
  });

  test('a malformed collection body is rejected rather than half-parsed', async () => {
    fetchMock.mockImplementationOnce(() =>
      jsonResponse(200, { id: COLLECTION_ID, name: 'Summer' }),
    );

    await expect(api().list()).rejects.toThrow('Invalid Collections response');
  });
});
