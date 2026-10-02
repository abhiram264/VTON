import { act, fireEvent, render, screen } from '@testing-library/react-native';
import ShareImportScreen from '../src/screens/ShareImportScreen';
import { collectionsApi } from '../src/features/collections/collectionsApi';
import { createShareImportConfirmer } from '../src/features/share/importClient';
import type {
  ShareImportConfirmer,
  ShareImportResolver,
} from '../src/features/share/importTypes';
import type { SharePayload } from '../src/features/share/types';

jest.mock('../src/features/collections/collectionsApi', () => ({
  collectionsApi: {
    list: jest.fn(),
    create: jest.fn(),
    rename: jest.fn(),
    remove: jest.fn(),
    addItem: jest.fn(),
    removeItem: jest.fn(),
  },
}));

const IMPORT_ID = 'import-1';
const COLLECTION_ID = 'collection-1';
const PAYLOAD: SharePayload = {
  kind: 'url',
  url: 'https://shop.example.invalid/p/123456',
  sourcePackage: 'com.example.shop',
};

const readyResolver: ShareImportResolver = async () => ({
  importId: IMPORT_ID,
  status: 'READY',
});

async function mount(element: React.ReactElement) {
  await act(async () => {
    render(element);
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function interact(work: () => void) {
  await act(async () => {
    work();
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(collectionsApi.list).mockResolvedValue([
    {
      id: COLLECTION_ID,
      name: 'Work',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
      items: [],
    },
  ]);
});

describe('ShareImportScreen collection selection', () => {
  test('saving without choosing a collection confirms with no corrections', async () => {
    const confirmer = jest.fn().mockResolvedValue({
      id: 'item-1',
      sourceReferenceId: 'source-1',
    }) as unknown as ShareImportConfirmer;
    await mount(
      <ShareImportScreen
        payload={PAYLOAD}
        resolver={readyResolver}
        confirmer={confirmer}
      />,
    );

    await interact(() =>
      fireEvent.press(screen.getByRole('button', { name: 'Save to Vault' })),
    );

    // A body-less confirm keeps today's server behavior: the caller supplied
    // no correction, so the third argument is not passed at all.
    expect(confirmer).toHaveBeenCalledTimes(1);
    expect(jest.mocked(confirmer).mock.calls[0]).toHaveLength(2);
    expect(jest.mocked(confirmer).mock.calls[0][0]).toBe(IMPORT_ID);
    expect(screen.getByText('Saved to your Vault')).toBeTruthy();
  });

  test('the chosen collection travels to the confirm call', async () => {
    const confirmer = jest.fn().mockResolvedValue({
      id: 'item-1',
      sourceReferenceId: 'source-1',
    }) as unknown as ShareImportConfirmer;
    await mount(
      <ShareImportScreen
        payload={PAYLOAD}
        resolver={readyResolver}
        confirmer={confirmer}
      />,
    );

    // Selection mode: choosing a collection reports the id and writes nothing.
    await interact(() =>
      fireEvent.press(screen.getByLabelText('Membership Work')),
    );
    expect(collectionsApi.addItem).not.toHaveBeenCalled();

    await interact(() =>
      fireEvent.press(screen.getByRole('button', { name: 'Save to Vault' })),
    );

    expect(confirmer).toHaveBeenCalledWith(IMPORT_ID, expect.anything(), {
      collectionId: COLLECTION_ID,
    });
  });

  test('a collection list that cannot be read does not block saving', async () => {
    jest
      .mocked(collectionsApi.list)
      .mockRejectedValue(new Error('collections unavailable'));
    const confirmer = jest.fn().mockResolvedValue({
      id: 'item-1',
      sourceReferenceId: 'source-1',
    }) as unknown as ShareImportConfirmer;
    await mount(
      <ShareImportScreen
        payload={PAYLOAD}
        resolver={readyResolver}
        confirmer={confirmer}
      />,
    );

    await interact(() =>
      fireEvent.press(screen.getByRole('button', { name: 'Save to Vault' })),
    );

    expect(jest.mocked(confirmer).mock.calls[0]).toHaveLength(2);
    expect(screen.getByText('Saved to your Vault')).toBeTruthy();
  });
});

describe('share-import confirm request body', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      headers: { get: () => 'application/json' },
      json: () =>
        Promise.resolve({ id: 'item-1', sourceReferenceId: 'source-1' }),
    } as unknown as Response);
  });

  const api = () => ({
    baseUrl: 'https://api.example.invalid',
    getAccessToken: () => 'token',
  });

  test('omits the body entirely when no correction was supplied', async () => {
    await createShareImportConfirmer(api())(IMPORT_ID, new AbortController().signal);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(init.body).toBeUndefined();
  });

  test('sends exactly the supplied correction as JSON', async () => {
    await createShareImportConfirmer(api())(
      IMPORT_ID,
      new AbortController().signal,
      { collectionId: COLLECTION_ID },
    );

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({
      collectionId: COLLECTION_ID,
    });
    expect((init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
  });
});
