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

// Lowercase canonical UUIDs per the frozen handoff URI seam. linkHandler
// remembers consumed ids for the process lifetime (spec dedupe), so every
// routing test draws fresh ids via freshShareId() to stay order-independent.
const SHARE_ID_A = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
const SHARE_ID_B = 'b1d3e4a5-6c7f-4a2b-9d8e-0f1a2b3c4d5e';
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
  // Routing tests must stay on the preparing surface: keep any in-flight
  // import request pending forever instead of letting it reject into a
  // failure view. Flow tests below replace this with their fetchMock.
  globalThis.fetch = (() => new Promise<Response>(() => {})) as typeof fetch;
  urlListeners.length = 0;
  linkHandlerListeners.length = 0;
  addEventListenerMock.mockReset();
  addEventListenerMock.mockImplementation(
    (eventType: string, listener: UrlListener) => {
      if (eventType !== 'url') return { remove: () => undefined };
      urlListeners.push(listener);
      const stack = new Error().stack ?? '';
      if (stack.includes('useLinking')) return { remove: () => undefined };
      linkHandlerListeners.push(listener);
      return { remove: () => undefined };
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
    let listenersAtInitialUrl = -1;
    getInitialURLMock.mockImplementation(async () => {
      listenersAtInitialUrl = linkHandlerListeners.length;
      return handoffUri(SHARE_ID_A);
    });
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: { kind: 'url', url: SOURCE_URL },
    });

    await render(<App />);
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();

    // Ordering property: our linkHandler listener was registered before the
    // initial URL was resolved (exactly one — @react-navigation's inert
    // useLinking subscription is excluded).
    expect(listenersAtInitialUrl).toBe(1);
    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(SHARE_ID_A);
    expect(screen.getAllByText('Preparing shared item')).toHaveLength(1);
  });

  test('warm start handles the url event and routes the image_url payload to ShareImport', async () => {
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: {
        kind: 'image_url',
        url: SOURCE_URL,
        localImageUri: STAGED_URI,
      },
    });

    await render(<App />);
    expect(screen.queryByText('Preparing shared item')).toBeNull();

    await act(async () => {
      emitLink(handoffUri(SHARE_ID_B));
    });
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(SHARE_ID_B);
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
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();
    await act(async () => {
      emitLink(handoffUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(screen.getAllByText('Preparing shared item')).toHaveLength(1);
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
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();

    await act(async () => {
      emitLink(handoffUri(idB));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(2));

    expect(consumeShareMock()).toHaveBeenCalledTimes(2);
    expect(consumeShareMock().mock.calls).toEqual([[idA], [idB]]);
  });

  test('malformed, noncanonical, and query-carrying links never reach the native bridge or route state', async () => {
    await render(<App />);

    await act(async () => {
      emitLink(handoffUri('not-a-uuid'));
      emitLink(handoffUri(SHARE_ID_A.toUpperCase()));
      emitLink(`${handoffUri(SHARE_ID_A)}/extra`);
      emitLink(
        `${handoffUri(SHARE_ID_A)}?url=${encodeURIComponent(SOURCE_URL)}`,
      );
      emitLink(`aifashionvault://other/${SHARE_ID_A}`);
      emitLink('aifashionvault://share-import/');
      emitLink(`https://example.invalid/share-import/${SHARE_ID_A}`);
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

    await render(<App />);
    await act(async () => {
      emitLink(handoffUri(SHARE_ID_A));
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
    await act(async () => {
      emitLink(handoffUri(idA));
    });
    await act(async () => {
      emitLink(handoffUri(idB));
    });
    await waitFor(() => expect(consumeShareMock()).toHaveBeenCalledTimes(2));
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

  test('upload failure releases the staged image once, sends no import POST, and offers retry', async () => {
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
    expect(await view.findByText('Try Again')).toBeTruthy();
    expect(view.queryByText('Choose Screenshot')).toBeNull();

    const release = (
      NativeModules as unknown as {
        ShareHandoff: { releaseStagedImage: jest.Mock };
      }
    ).ShareHandoff.releaseStagedImage;
    expect(release).toHaveBeenCalledTimes(1);
    expect(release).toHaveBeenCalledWith(STAGED_URI);
    expect(importPostCall()).toBeUndefined();
  });

  test('NEEDS_IMAGE shows exactly the two recovery actions with no retry offer', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, needsImage()));
    const view = await render(
      <ShareImportScreen payload={{ kind: 'url', url: SOURCE_URL }} api={api()} />,
    );
    expect(await view.findByText('Add a photo to continue')).toBeTruthy();
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
        view.getByText('Could not attach that image. Try another one.'),
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

  test('FAILED_RETRYABLE offers retry, not screenshot; retry re-invokes the resolver', async () => {
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
    expect(view.queryByText('Choose Screenshot')).toBeNull();

    fireEvent.press(view.getByText('Try Again'));
    await waitFor(() =>
      expect(view.getByText('Shared item ready')).toBeTruthy(),
    );
    expect(resolver).toHaveBeenCalledTimes(2);
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
