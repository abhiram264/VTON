import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import App from '../App';
import { Linking, NativeModules } from 'react-native';
import ShareImportScreen from '../src/screens/ShareImportScreen';
import type { ShareImportResolver } from '../src/features/share/importTypes';
import type { ShareImportResponse } from '../src/features/share/importTypes';
import { validateSharePayload } from '../src/features/share/types';
import { startShareLinkHandling } from '../src/features/share/linkHandler';

// Lowercase canonical UUIDs per the frozen handoff URI seam. linkHandler
// remembers consumed ids for the process lifetime (spec dedupe), so every
// routing test draws fresh ids via freshShareId() to stay order-independent.
let shareIdCounter = 0;
function freshShareId(): string {
  shareIdCounter += 1;
  const hex = shareIdCounter.toString(16).padStart(8, '0');
  return `9e000000-7c1d-4b2a-8f3e-${hex}0000`;
}
const SOURCE_URL = 'https://shop.example.invalid/p/123456';
const STAGED_URI =
  'file:///data/user/0/com.aifashionvault.app/cache/share-staged-1.png';
const IMPORT_ID = 'import-1';
const UPLOAD_ID = 'upload-1';
const PHOTO_URI = 'file:///chosen-photo.png';
const PHOTO_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47]).buffer;
const BASE_URL = 'https://api.example.invalid';

type UrlListener = (event: { url: string }) => void;

const urlListeners: UrlListener[] = [];
// @react-navigation's own (inert) useLinking subscription also registers a
// 'url' listener; only handlers registered from src/features/share count.
const linkHandlerListeners: UrlListener[] = [];
const addEventListenerMock = Linking.addEventListener as unknown as jest.Mock;
const getInitialURLMock = Linking.getInitialURL as unknown as jest.Mock;

function emitLink(url: string) {
  for (const listener of [...linkHandlerListeners]) listener({ url });
}

function handoffUri(shareId: string) {
  return `aifashionvault://share-import/${shareId}`;
}

function consumeShareMock(): jest.Mock {
  return (
    NativeModules as unknown as {
      ShareHandoff: { consumeShare: jest.Mock };
    }
  ).ShareHandoff.consumeShare;
}

// Drains every pending promise chain (native consume -> navigate) so
async function flush() {
  const flushPromise = new Promise<void>(resolve => {
    setTimeout(resolve, 0);
  });
  await act(async () => {
    await flushPromise;
  });
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

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: new Map([
      ['content-type', 'application/json'],
    ]) as unknown as Headers,
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

function emptyResponse(status: number): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: new Map() as unknown as Headers,
    json: () => Promise.resolve(null),
  } as unknown as Response);
}

function fileResponse(
  bytes: ArrayBuffer,
  contentType: string,
): Promise<Response> {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: new Map([
      ['content-type', contentType],
    ]) as unknown as Headers,
    arrayBuffer: () => Promise.resolve(bytes),
  } as unknown as Response);
}

function ticketResponse(): Promise<Response> {
  return jsonResponse(200, {
    id: UPLOAD_ID,
    method: 'PUT',
    url: 'https://uploads.example.invalid/u1',
    headers: {},
  });
}

beforeEach(() => {
  // Routing tests use the mounted fail-closed API config; do not fabricate
  // configured credentials or a pending authenticated request.
  globalThis.fetch = (() => new Promise<Response>(() => {})) as typeof fetch;
  urlListeners.length = 0;
  linkHandlerListeners.length = 0;
  addEventListenerMock.mockReset();
  addEventListenerMock.mockImplementation(
    (eventType: string, listener: UrlListener) => {
      if (eventType !== 'url') return { remove: () => undefined };
      urlListeners.push(listener);
      const stack = new Error().stack ?? '';
      if (!stack.includes('useLinking')) {
        linkHandlerListeners.push(listener);
      }
      return {
        remove: () => {
          const urlIndex = urlListeners.indexOf(listener);
          if (urlIndex >= 0) urlListeners.splice(urlIndex, 1);
          const linkIndex = linkHandlerListeners.indexOf(listener);
          if (linkIndex >= 0) linkHandlerListeners.splice(linkIndex, 1);
        },
      };
    },
  );
  getInitialURLMock.mockReset();
  getInitialURLMock.mockResolvedValue(null);
  (NativeModules as unknown as Record<string, unknown>).ShareHandoff = {
    consumeShare: jest.fn(),
  };
});

afterEach(() => {
  delete (NativeModules as unknown as Record<string, unknown>).ShareHandoff;
});

describe('share handoff routing', () => {
  test('cold start registers the url listener before resolving the initial URL, consumes once, and navigates once', async () => {
    const shareId = freshShareId();
    let listenersAtInitialUrl = -1;
    getInitialURLMock.mockImplementation(async () => {
      listenersAtInitialUrl = linkHandlerListeners.length;
      return handoffUri(shareId);
    });
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: { kind: 'url', url: SOURCE_URL },
    });

    await render(<App />);
    expect(await screen.findByText('Could not prepare the shared item')).toBeTruthy();

    // Ordering property: our linkHandler listener was registered before the
    // initial URL was resolved (exactly one — @react-navigation's inert
    // useLinking subscription is excluded).
    expect(listenersAtInitialUrl).toBe(1);
    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(screen.getAllByText('Could not prepare the shared item')).toHaveLength(1);
  });

  test('warm start handles the url event and routes the image_url payload to ShareImport', async () => {
    const shareId = freshShareId();
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: {
        kind: 'image_url',
        url: SOURCE_URL,
        localImageUri: STAGED_URI,
      },
    });

    await render(<App />);
    await waitFor(() => expect(linkHandlerListeners).toHaveLength(1));
    expect(screen.queryByText('Preparing shared item')).toBeNull();

    await act(async () => {
      emitLink(handoffUri(shareId));
    });
    await flush();
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
  });

  test('concurrent duplicate delivery consumes and navigates at most once, and replay is ignored', async () => {
    let resolveConsume!: (value: unknown) => void;
    const consumePending = new Promise<unknown>(resolve => {
      resolveConsume = resolve;
    });
    consumeShareMock().mockReturnValue(consumePending);
    const shareId = freshShareId();

    await render(<App />);
    await act(async () => {
      emitLink(handoffUri(shareId));
      emitLink(handoffUri(shareId));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(1));
    resolveConsume({ ok: true, payload: { kind: 'url', url: SOURCE_URL } });
    expect(await screen.findByText('Could not prepare the shared item')).toBeTruthy();
    await act(async () => {
      emitLink(handoffUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(screen.getAllByText('Could not prepare the shared item')).toHaveLength(1);
  });

  test('two distinct share ids are kept distinct and each consumes once', async () => {
    consumeShareMock()
      .mockResolvedValueOnce({
        ok: true,
        payload: { kind: 'url', url: SOURCE_URL },
      })
      .mockResolvedValueOnce({
        ok: true,
        payload: { kind: 'image', localImageUri: STAGED_URI },
      });

    const idA = freshShareId();
    const idB = freshShareId();

    await render(<App />);

    await act(async () => {
      emitLink(handoffUri(idA));
    });
    expect(await screen.findByText('Could not prepare the shared item')).toBeTruthy();

    await act(async () => {
      emitLink(handoffUri(idB));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(2));
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(2);
    expect(consumeShareMock().mock.calls).toEqual([[idA], [idB]]);
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();
  });

  test('malformed, noncanonical, and query-carrying links never reach the native bridge or route state', async () => {
    const shareId = freshShareId();
    await render(<App />);

    await act(async () => {
      emitLink(handoffUri('not-a-uuid'));
      emitLink(handoffUri(shareId.toUpperCase()));
      emitLink(`${handoffUri(shareId)}/extra`);
      emitLink(
        `${handoffUri(shareId)}?url=${encodeURIComponent(SOURCE_URL)}`,
      );
      emitLink(`aifashionvault://other/${shareId}`);
      emitLink('aifashionvault://share-import/');
      emitLink(`https://example.invalid/share-import/${shareId}`);
    });
    await flush();

    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByText('Home')).toBeTruthy();
  });

  test('a payload violating its kind-specific shape at the bridge boundary never navigates', async () => {
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: { kind: 'url', url: SOURCE_URL, localImageUri: STAGED_URI },
    });

    const shareId = freshShareId();
    await render(<App />);
    await act(async () => {
      emitLink(handoffUri(shareId));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(1));
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByText('Home')).toBeTruthy();
  });

  test('a typed consume failure stays on the current screen without reconstructing or replaying data', async () => {
    consumeShareMock().mockResolvedValue({ ok: false, code: 'share_expired' });

    const shareId = freshShareId();
    await render(<App />);
    await act(async () => {
      emitLink(handoffUri(shareId));
    });
    await flush();

    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByText('Home')).toBeTruthy();
  });

  test('every typed consume failure code never navigates and is never replayed', async () => {
    await render(<App />);
    for (const code of [
      'invalid_share_id',
      'share_not_found',
      'share_expired',
      'invalid_payload',
    ] as const) {
      consumeShareMock().mockResolvedValue({ ok: false, code });
      const shareId = freshShareId();

      await act(async () => {
        emitLink(handoffUri(shareId));
      });
      await flush();

      expect(screen.queryByText('Preparing shared item')).toBeNull();
      expect(screen.getByText('Home')).toBeTruthy();
    }

    // Each id was consumed exactly once: a typed failure is final, so the
    // replayed link after each failure above never re-consumed.
    expect(consumeShareMock()).toHaveBeenCalledTimes(4);
  });

  test('remount updates link callbacks without replacing the active subscription', async () => {
    const oldNavigate = jest.fn();
    const oldLookNavigate = jest.fn();
    const newNavigate = jest.fn();
    const newLookNavigate = jest.fn();
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: { kind: 'url', url: SOURCE_URL },
    });

    const stopOld = startShareLinkHandling(oldNavigate, oldLookNavigate);
    const stopNew = startShareLinkHandling(newNavigate, newLookNavigate);
    try {
      stopOld();
      const shareId = freshShareId();
      await act(async () => {
        emitLink(handoffUri(shareId));
      });
      await flush();

      expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
      expect(newNavigate).toHaveBeenCalledWith('ShareImport', {
        payload: { kind: 'url', url: SOURCE_URL },
      });
      expect(oldNavigate).not.toHaveBeenCalled();
      expect(linkHandlerListeners).toHaveLength(1);

      await act(async () => {
        emitLink(handoffUri(shareId));
      });
      await flush();
      expect(consumeShareMock()).toHaveBeenCalledTimes(1);
      expect(newNavigate).toHaveBeenCalledTimes(1);
    } finally {
      stopNew();
      stopOld();
    }
  });

  test('no diagnostics emit shared URLs, URIs, share ids, or payload data', async () => {
    const logSpy = jest.spyOn(console, 'log');
    const infoSpy = jest.spyOn(console, 'info');
    const warnSpy = jest.spyOn(console, 'warn');
    const errorSpy = jest.spyOn(console, 'error');

    consumeShareMock()
      .mockResolvedValueOnce({ ok: false, code: 'share_expired' })
      .mockResolvedValueOnce({
        ok: true,
        payload: {
          kind: 'image_url',
          url: SOURCE_URL,
          localImageUri: STAGED_URI,
          sourcePackage: 'com.other.sender',
        },
      });

    const idA = freshShareId();
    const idB = freshShareId();
    await render(<App />);
    await waitFor(() => expect(linkHandlerListeners).toHaveLength(1));
    await act(async () => {
      emitLink(handoffUri(idA));
    });
    await act(async () => {
      emitLink(handoffUri(idB));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(2));
    await flush();
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();

    const emitted = [logSpy, infoSpy, warnSpy, errorSpy]
      .flatMap(spy => spy.mock.calls)
      .map(call => call.map(String).join(' '));
    for (const line of emitted) {
      expect(line).not.toContain(idA);
      expect(line).not.toContain(idB);
      expect(line).not.toContain('com.other.sender');
    }

    logSpy.mockRestore();
    infoSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });
});

describe('validateSharePayload', () => {
  test('accepts kind-valid payloads with optional fields omitted', () => {
    expect(validateSharePayload({ kind: 'url', url: SOURCE_URL })).toEqual({
      kind: 'url',
      url: SOURCE_URL,
    });
    expect(
      validateSharePayload({ kind: 'image', localImageUri: STAGED_URI }),
    ).toEqual({ kind: 'image', localImageUri: STAGED_URI });
    expect(
      validateSharePayload({
        kind: 'image_url',
        url: SOURCE_URL,
        localImageUri: STAGED_URI,
        sourcePackage: 'com.other.sender',
      }),
    ).toEqual({
      kind: 'image_url',
      url: SOURCE_URL,
      localImageUri: STAGED_URI,
      sourcePackage: 'com.other.sender',
    });
  });

  test('rejects payloads with forbidden or missing kind-specific fields', () => {
    expect(
      validateSharePayload({
        kind: 'url',
        url: SOURCE_URL,
        localImageUri: STAGED_URI,
      }),
    ).toBeNull();
    expect(validateSharePayload({ kind: 'image' })).toBeNull();
    expect(
      validateSharePayload({
        kind: 'image',
        url: SOURCE_URL,
        localImageUri: STAGED_URI,
      }),
    ).toBeNull();
    expect(
      validateSharePayload({ kind: 'image_url', url: SOURCE_URL }),
    ).toBeNull();
    expect(
      validateSharePayload({ kind: 'image_url', localImageUri: STAGED_URI }),
    ).toBeNull();
  });

  test('rejects null optionals, unknown fields, wrong types, and non-objects', () => {
    expect(
      validateSharePayload({
        kind: 'url',
        url: SOURCE_URL,
        sourcePackage: null,
      }),
    ).toBeNull();
    expect(
      validateSharePayload({ kind: 'url', url: SOURCE_URL, extra: 1 }),
    ).toBeNull();
    expect(validateSharePayload({ kind: 'url', url: null })).toBeNull();
    expect(
      validateSharePayload({ kind: 'banner', url: SOURCE_URL }),
    ).toBeNull();
    expect(validateSharePayload(null)).toBeNull();
    expect(validateSharePayload('url')).toBeNull();
    expect(validateSharePayload(undefined)).toBeNull();
  });
});

// Full observable screen flow: every SharePayload kind through the real U4
// client (fetch-mocked), NEEDS_IMAGE recovery via the attach endpoint,
// retryable-failure retry, terminal failure, and abort on unmount.
describe('ShareImportScreen flow', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  function api() {
    return { baseUrl: BASE_URL, getAccessToken: () => null };
  }

  function ready(id = IMPORT_ID): ShareImportResponse {
    return { importId: id, status: 'READY' };
  }

  function needsImage(id = IMPORT_ID): ShareImportResponse {
    return {
      importId: id,
      status: 'NEEDS_IMAGE',
      reason: 'URL_RESOLUTION_FAILED',
      recoveryActions: ['CHOOSE_SCREENSHOT', 'UPLOAD_IMAGE'],
    };
  }

  // Happy-path fetch chain for an image-bearing payload through the real
  // U4 client: staged-file read, upload ticket, PUT, complete, import POST.
  function imageHappyPath(final: ShareImportResponse) {
    fetchMock
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(201, final));
  }

  function importPostCall() {
    return fetchMock.mock.calls.find(call =>
      requestUrl(call[0]).endsWith('/share/imports'),
    );
  }

  function preview(id = IMPORT_ID) {
    return {
      importId: id,
      previewUrl: `${BASE_URL}/share/imports/${id}/preview.png`,
      previewExpiresAt: '2026-10-03T00:05:00Z',
      sourceTitle: 'Linen Shirt',
      sourceSiteName: 'example.invalid',
    };
  }

  test('READY previews the item, sends owner corrections, and never claims saved before persistence', async () => {
    const confirmed = {
      id: 'vault-item-1',
      sourceReferenceId: 'source-ref-1',
      createdAt: '2026-10-03T00:00:00Z',
    };
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, ready()))
      .mockResolvedValueOnce(jsonResponse(200, preview()))
      .mockResolvedValueOnce(jsonResponse(201, confirmed));
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Shared item ready')).toBeTruthy();

    // The owner-scoped preview is shown, with its safe source metadata.
    const image = await view.findByLabelText('Shared item preview');
    expect(image.props.source.uri).toBe(
      `${BASE_URL}/share/imports/${IMPORT_ID}/preview.png`,
    );
    expect(view.getByText('Linen Shirt')).toBeTruthy();
    expect(view.getByText('example.invalid')).toBeTruthy();

    // Corrected metadata is editable before the item is persisted.
    await act(async () => {
      fireEvent.changeText(view.getByLabelText('Item name'), '  Blue Shirt  ');
    });
    await act(async () => {
      fireEvent.press(view.getByLabelText('TOP'));
    });

    await act(async () => {
      fireEvent.press(await view.findByText('Save to Vault'));
    });

    // Persistence goes through the mounted confirm route, bound to the
    // resolved importId — never by replaying resolution — and carries only the
    // corrections the owner actually made (the name is trimmed).
    const confirmPosts = fetchMock.mock.calls.filter(call =>
      requestUrl(call[0]).endsWith(`/share/imports/${IMPORT_ID}/confirm`),
    );
    expect(confirmPosts).toHaveLength(1);
    expect(confirmPosts[0][1].method).toBe('POST');
    expect(JSON.parse(confirmPosts[0][1].body)).toEqual({
      name: 'Blue Shirt',
      category: 'TOP',
    });
    // Only the persisted confirm response may present the saved state.
    expect(await view.findByText('Saved to your Vault')).toBeTruthy();
    // One share still creates at most one import: the save path never POSTs
    // /share/imports again.
    expect(
      fetchMock.mock.calls.filter(call =>
        requestUrl(call[0]).endsWith('/share/imports'),
      ),
    ).toHaveLength(1);
    expect(view.queryByText(/https?:\/\//)).toBeNull();
  });

  test('a preview failure does not block saving the resolved item', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, ready()))
      .mockRejectedValueOnce(new Error('preview unavailable'))
      .mockResolvedValueOnce(
        jsonResponse(201, {id: 'vault-item-1', sourceReferenceId: 'source-ref-1'}),
      );
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Shared item ready')).toBeTruthy();
    expect(view.queryByLabelText('Shared item preview')).toBeNull();

    await act(async () => {
      fireEvent.press(await view.findByText('Save to Vault'));
    });
    expect(await view.findByText('Saved to your Vault')).toBeTruthy();
    // No correction was made, so the confirm request carries no body.
    const confirm = fetchMock.mock.calls.find(call =>
      requestUrl(call[0]).endsWith(`/share/imports/${IMPORT_ID}/confirm`),
    );
    expect(confirm?.[1].body).toBeUndefined();
  });

  test('choosing a collection sends exactly that one collectionId, and a second choice replaces it', async () => {
    const collections = [
      {id: 'col-1', name: 'Work', items: []},
      {id: 'col-2', name: 'Weekend', items: []},
    ];
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = requestUrl(input);
      if (url.endsWith('/share/imports')) {
        return jsonResponse(201, ready());
      }
      if (url.endsWith(`/share/imports/${IMPORT_ID}/preview`)) {
        return jsonResponse(200, preview());
      }
      if (url.endsWith('/collections')) {
        return jsonResponse(200, collections);
      }
      if (url.endsWith(`/share/imports/${IMPORT_ID}/confirm`)) {
        return jsonResponse(201, {
          id: 'vault-item-1',
          sourceReferenceId: 'source-ref-1',
        });
      }
      return Promise.reject(new Error(`unexpected request: ${url}`));
    });
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Shared item ready')).toBeTruthy();

    // The picker fetches on reveal, so an untouched screen issues no
    // collections request at all.
    expect(
      fetchMock.mock.calls.filter(call => requestUrl(call[0]).endsWith('/collections')),
    ).toHaveLength(0);

    await act(async () => {
      fireEvent.press(await view.findByText('Add to a collection'));
    });
    expect(await view.findByLabelText('Membership Work')).toBeTruthy();

    await act(async () => {
      fireEvent.press(view.getByLabelText('Membership Work'));
    });
    await act(async () => {
      fireEvent.press(view.getByLabelText('Membership Weekend'));
    });

    await act(async () => {
      fireEvent.press(await view.findByText('Save to Vault'));
    });

    // The confirm contract takes one collectionId; the later choice wins
    // instead of the first being silently kept or both being dropped.
    const confirm = fetchMock.mock.calls.find(call =>
      requestUrl(call[0]).endsWith(`/share/imports/${IMPORT_ID}/confirm`),
    );
    expect(JSON.parse(confirm?.[1].body)).toEqual({collectionId: 'col-2'});
  });

  test('a transient confirm failure keeps READY and a retry converges on one saved item', async () => {
    const confirmed = {id: 'vault-item-1', sourceReferenceId: 'source-ref-1'};
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, ready()))
      .mockResolvedValueOnce(jsonResponse(200, preview()))
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(jsonResponse(201, confirmed));
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Shared item ready')).toBeTruthy();

    await act(async () => {
      fireEvent.press(await view.findByText('Save to Vault'));
    });
    // A failed confirm must not claim the item was kept.
    expect(await view.findByText('Could not save this item to your Vault. Try again.')).toBeTruthy();
    expect(view.queryByText('Saved to your Vault')).toBeNull();

    await act(async () => {
      fireEvent.press(await view.findByText('Save to Vault'));
    });
    expect(await view.findByText('Saved to your Vault')).toBeTruthy();

    // Both attempts targeted the SAME importId: the server derives saved-item
    // identity from (owner, import), so a lost-response retry cannot create a
    // second item — and no new import was created either.
    const confirmPaths = fetchMock.mock.calls
      .map(call => requestUrl(call[0]))
      .filter(url => url.includes('/confirm'));
    expect(confirmPaths).toEqual([
      `${BASE_URL}/share/imports/${IMPORT_ID}/confirm`,
      `${BASE_URL}/share/imports/${IMPORT_ID}/confirm`,
    ]);
    expect(
      fetchMock.mock.calls.filter(call =>
        requestUrl(call[0]).endsWith('/share/imports'),
      ),
    ).toHaveLength(1);
  });

  test('URL payload renders READY with the exact URL request mapping', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, ready()));
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Shared item ready')).toBeTruthy();

    expect(requestUrl(fetchMock.mock.calls[0][0])).toBe(
      `${BASE_URL}/share/imports`,
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      kind: 'URL',
      sourceUrl: SOURCE_URL,
    });
    expect(view.queryByText(/https?:\/\//)).toBeNull();
  });

  test('IMAGE payload uploads through the upload boundary then imports with only uploadId', async () => {
    imageHappyPath(ready());
    await render(
      <ShareImportScreen
        payload={{ kind: 'image', localImageUri: STAGED_URI }}
        api={api()}
      />,
    );
    await waitFor(() =>
      expect(screen.getByText('Shared item ready')).toBeTruthy(),
    );

    const ticketPost = fetchMock.mock.calls.find(
      call => requestUrl(call[0]).includes('/uploads') && call[1]?.method === 'POST',
    );
    expect(ticketPost).toBeDefined();
    expect(JSON.parse(importPostCall()![1].body)).toEqual({
      kind: 'IMAGE',
      uploadId: UPLOAD_ID,
    });
  });

  test('IMAGE_URL payload sends both sourceUrl and uploadId', async () => {
    imageHappyPath(ready());
    await render(
      <ShareImportScreen
        payload={{
          kind: 'image_url',
          url: SOURCE_URL,
          localImageUri: STAGED_URI,
        }}
        api={api()}
      />,
    );
    await waitFor(() =>
      expect(screen.getByText('Shared item ready')).toBeTruthy(),
    );

    expect(JSON.parse(importPostCall()![1].body)).toEqual({
      kind: 'IMAGE_URL',
      sourceUrl: SOURCE_URL,
      uploadId: UPLOAD_ID,
    });
  });

  test('retryable upload failure preserves the staged image and exposes Try Again without chooser actions', async () => {
    (NativeModules as unknown as Record<string, unknown>).ShareHandoff = {
      consumeShare: jest.fn(),
      releaseStagedImage: jest.fn(),
    };
    fetchMock
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(500));

    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'image', localImageUri: STAGED_URI }}
        api={api()}
      />,
    );
    expect(await view.findByText('Could not prepare the shared item')).toBeTruthy();
    expect(view.queryByText('Choose Screenshot')).toBeNull();
    expect(view.getByText('Try Again')).toBeTruthy();
    expect(view.queryByText('Upload Image')).toBeNull();

    const release = (
      NativeModules as unknown as {
        ShareHandoff: { releaseStagedImage: jest.Mock };
      }
    ).ShareHandoff.releaseStagedImage;
    expect(release).not.toHaveBeenCalled();
    expect(importPostCall()).toBeUndefined();
  });

  test('NEEDS_IMAGE shows exactly the two recovery actions with no retry offer', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, needsImage()));
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Add a photo to continue')).toBeTruthy();
    const body = view.getByText(
      "We found the product link, but couldn't reliably extract the item image.",
    );
    expect(body).toBeTruthy();
    expect(body.parent?.props.accessibilityLiveRegion).toBe('polite');
    expect(view.getByText('Choose Screenshot')).toBeTruthy();
    expect(view.getByText('Upload Image')).toBeTruthy();
    expect(view.queryByText('Try Again')).toBeNull();
    expect(view.queryByText(/https?:\/\//)).toBeNull();
  });

  test('NEEDS_IMAGE recovery uploads and PUTs the attach endpoint, then shows READY', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, needsImage()))
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(200, ready()));

    const picker = jest.fn().mockResolvedValue({ uri: PHOTO_URI });
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        picker={picker}
        api={api()}
      />,
    );
    fireEvent.press(await view.findByText('Choose Screenshot'));

    expect(await view.findByText('Shared item ready')).toBeTruthy();
    expect(picker).toHaveBeenCalledTimes(1);

    const attachPut = fetchMock.mock.calls.find(
      call => requestUrl(call[0]).endsWith(`/share/imports/${IMPORT_ID}/image`),
    );
    expect(attachPut).toBeDefined();
    expect(attachPut![1].method).toBe('PUT');
    expect(JSON.parse(attachPut![1].body)).toEqual({ uploadId: UPLOAD_ID });
    expect(typeof attachPut![1].headers['Idempotency-Key']).toBe('string');
  });

  test('recovery retries reuse one idempotency key per import', async () => {
    // Backend binds the recovery idempotency key to the first attach: a
    // retry after a transient failure must present the same key (spec:
    // recovery replay with the same key returns the same result).
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, needsImage()))
      // First attach attempt: upload succeeds, attach PUT fails transiently.
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(503, {code: 'DEPENDENCY_UNAVAILABLE'}))
      // Retry: fresh upload, attach succeeds.
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(jsonResponse(200, ready()));

    const picker = jest.fn().mockResolvedValue({ uri: PHOTO_URI });
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        picker={picker}
        api={api()}
      />,
    );
    fireEvent.press(await view.findByText('Choose Screenshot'));
    // The failure notice appears only after the first attach has settled and
    // busy cleared; pressing earlier would be swallowed by the guard.
    await waitFor(() =>
      expect(
        view.getByText('Could not prepare the shared item'),
      ).toBeTruthy(),
    );

    fireEvent.press(view.getByText('Choose Screenshot'));
    expect(await view.findByText('Shared item ready')).toBeTruthy();

    const attachPuts = fetchMock.mock.calls.filter(
      call => requestUrl(call[0]).endsWith(`/share/imports/${IMPORT_ID}/image`),
    );
    expect(attachPuts).toHaveLength(2);
    const [firstKey, secondKey] = attachPuts.map(
      call => call[1].headers['Idempotency-Key'],
    );
    expect(typeof firstKey).toBe('string');
    expect(firstKey.length).toBeGreaterThan(0);
    expect(secondKey).toBe(firstKey);
  });

  test('cancelled picker selection keeps NEEDS_IMAGE and uploads nothing', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, needsImage()));
    const picker = jest.fn().mockResolvedValue(null);
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        picker={picker}
        api={api()}
      />,
    );
    fireEvent.press(await view.findByText('Upload Image'));
    await waitFor(() => expect(picker).toHaveBeenCalledTimes(1));

    expect(view.getByText('Add a photo to continue')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  test('HTTP 5xx exposes retryable Try Again without chooser actions', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(503, null));
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        api={api()}
      />,
    );

    expect(await view.findByText('Could not prepare the shared item')).toBeTruthy();
    expect(view.getByText('This looks temporary. Try again.')).toBeTruthy();
    expect(view.getByText('Try Again')).toBeTruthy();
    expect(view.queryByText('Choose Screenshot')).toBeNull();
    expect(view.queryByText('Upload Image')).toBeNull();
  });

  test('FAILED_RETRYABLE offers retry alongside screenshot and upload recovery for resolver outage; retry re-invokes the resolver', async () => {
    const outcomes: ShareImportResponse[] = [
      {
        importId: IMPORT_ID,
        status: 'FAILED_RETRYABLE',
        reason: 'DEPENDENCY_UNAVAILABLE',
      },
      ready(),
    ];
    let call = 0;
    const resolver = jest.fn(
      async (_p: unknown, _s: AbortSignal): Promise<ShareImportResponse> =>
        outcomes[call++],
    );
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        resolver={resolver as unknown as ShareImportResolver}
      />,
    );
    expect(await view.findByText('Try Again')).toBeTruthy();
    expect(await view.findByText('Choose Screenshot')).toBeTruthy();
    expect(await view.findByText('Upload Image')).toBeTruthy();

    fireEvent.press(view.getByText('Try Again'));
    await waitFor(() =>
      expect(view.getByText('Shared item ready')).toBeTruthy(),
    );
    expect(resolver).toHaveBeenCalledTimes(2);
  });

  test('FAILED_RETRYABLE with RESOLUTION_TIMEOUT offers retry only; screenshot and upload actions are absent', async () => {
    const resolver = jest.fn(
      async (_p: unknown, _s: AbortSignal): Promise<ShareImportResponse> => ({
        importId: IMPORT_ID,
        status: 'FAILED_RETRYABLE',
        reason: 'RESOLUTION_TIMEOUT',
      }),
    );
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        resolver={resolver as unknown as ShareImportResolver}
      />,
    );
    expect(await view.findByText('Try Again')).toBeTruthy();
    expect(view.queryByText('Choose Screenshot')).toBeNull();
    expect(view.queryByText('Upload Image')).toBeNull();
  });

  test('retry without a resolver prop reuses one idempotency key across both POSTs', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse(201, {
          importId: IMPORT_ID,
          status: 'FAILED_RETRYABLE',
          reason: 'DEPENDENCY_UNAVAILABLE',
        }),
      )
      .mockResolvedValueOnce(jsonResponse(200, ready()));

    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    fireEvent.press(await view.findByText('Try Again'));
    await waitFor(() =>
      expect(view.getByText('Shared item ready')).toBeTruthy(),
    );

    const importPosts = fetchMock.mock.calls.filter(
      call =>
        requestUrl(call[0]).endsWith('/share/imports') &&
        call[1]?.method === 'POST',
    );
    expect(importPosts).toHaveLength(2);
    const [firstKey, secondKey] = importPosts.map(
      call => call[1].headers['Idempotency-Key'],
    );
    expect(typeof firstKey).toBe('string');
    expect(firstKey.length).toBeGreaterThan(0);
    // Story 9: one share creates at most one import, so the retry must
    // replay under the same key rather than mint a second resolver.
    expect(secondKey).toBe(firstKey);
  });
  test('image payload transient import rejection releases staged media and exposes Try Again without chooser actions', async () => {
    (NativeModules as unknown as Record<string, unknown>).ShareHandoff = {
      consumeShare: jest.fn(),
      releaseStagedImage: jest.fn(),
    };
    fetchMock
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(
        jsonResponse(503, {
          code: 'DEPENDENCY_UNAVAILABLE',
        }),
      )

    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'image', localImageUri: STAGED_URI }}
        api={api()}
      />,
    );
    expect(await view.findByText('Could not prepare the shared item')).toBeTruthy();
    expect(view.getByText('Try Again')).toBeTruthy();
    expect(view.queryByText('Choose Screenshot')).toBeNull();
    expect(view.queryByText('Upload Image')).toBeNull();

    const importPosts = fetchMock.mock.calls.filter(
      call =>
        requestUrl(call[0]).endsWith('/share/imports') &&
        call[1]?.method === 'POST',
    );
    expect(importPosts).toHaveLength(1);
    expect(JSON.parse(importPosts[0][1].body)).toEqual({
      kind: 'IMAGE',
      uploadId: UPLOAD_ID,
    });

    const [firstKey] = importPosts.map(call => call[1].headers['Idempotency-Key']);
    expect(typeof firstKey).toBe('string');
    expect(firstKey.length).toBeGreaterThan(0);

    // Exactly one upload ticket initiated across initial attempt and retry
    const ticketPosts = fetchMock.mock.calls.filter(
      call =>
        requestUrl(call[0]) === `${BASE_URL}/uploads` && call[1]?.method === 'POST',
    );
    expect(ticketPosts).toHaveLength(1);

    const release = (
      NativeModules as unknown as {
        ShareHandoff: { releaseStagedImage: jest.Mock };
      }
    ).ShareHandoff.releaseStagedImage;
    expect(release).toHaveBeenCalledTimes(1);
    expect(release).toHaveBeenCalledWith(STAGED_URI);
  });

  test('recovery attach returning 202 RESOLVING does not show READY', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(201, needsImage()))
      .mockImplementationOnce(() => fileResponse(PHOTO_BYTES, 'image/png'))
      .mockResolvedValueOnce(ticketResponse())
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(emptyResponse(200))
      .mockResolvedValueOnce(
        jsonResponse(202, { importId: IMPORT_ID, status: 'RESOLVING' }),
      );

    const picker = jest.fn().mockResolvedValue({ uri: PHOTO_URI });
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        picker={picker}
        api={api()}
      />,
    );
    fireEvent.press(await view.findByText('Choose Screenshot'));

    expect(await view.findByText('Preparing shared item')).toBeTruthy();
    expect(view.queryByText('Shared item ready')).toBeNull();
  });

  test('FAILED_TERMINAL renders neutral copy with no retry or screenshot affordances', async () => {
    const resolver = jest.fn(
      async (_p: unknown, _s: AbortSignal): Promise<ShareImportResponse> => ({
        importId: IMPORT_ID,
        status: 'FAILED_TERMINAL',
        reason: 'INVALID_SOURCE',
      }),
    );
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        resolver={resolver as unknown as ShareImportResolver}
      />,
    );
    expect(
      await view.findByText('Could not prepare the shared item'),
    ).toBeTruthy();
    expect(view.queryByText('Try Again')).toBeNull();
    expect(view.queryByText('Choose Screenshot')).toBeNull();
  });

  test('unmount aborts the in-flight resolution via the signal', async () => {
    let observed: AbortSignal | null = null;
    const resolver = (_payload: unknown, signal: AbortSignal) => {
      observed = signal;
      return new Promise<ShareImportResponse>(() => {});
    };
    const view = await render(
      <ShareImportScreen
        payload={{ kind: 'url', url: SOURCE_URL }}
        resolver={resolver}
      />,
    );
    expect(observed).not.toBeNull();
    expect((observed as AbortSignal | null)!.aborted).toBe(false);
    await view.unmount();
    expect((observed as AbortSignal | null)!.aborted).toBe(true);
  });

  test('reaches the import POST through the real client and settles quietly after unmount', async () => {
    let resolvePost!: (r: Response) => void;
    fetchMock.mockImplementationOnce(
      () =>
        new Promise<Response>(r => {
          resolvePost = r;
        }),
    );
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    await flush();
    expect(importPostCall()).toBeDefined();

    await view.unmount();
    await act(async () => {
      resolvePost(await jsonResponse(201, ready()));
    });
    await flush();
  });
});
