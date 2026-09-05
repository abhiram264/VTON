import { act, render, screen } from '@testing-library/react-native';
import App from '../App';
import { Linking, NativeModules, Platform } from 'react-native';
import * as featureFlags from '../src/config/featureFlags';
import { generationApi } from '../src/features/generation/generationApi';
import type { SharePayload } from '../src/features/share/types';

// Mock Platform.OS as android in this file only per frozen decisions.
Platform.OS = 'android';

// Lowercase canonical UUIDs per the frozen look deep-link seam. The look and
// share handlers remember ids for the process lifetime (spec dedupe), so
// every test draws fresh ids via freshLookId() and freshShareId().
let lookIdCounter = 0;
function freshLookId(): string {
  lookIdCounter += 1;
  const hex = lookIdCounter.toString(16).padStart(8, '0');
  return `8c000000-2b3c-4d5e-af01-${hex}00bf`;
}
function lookUri(lookId: string) {
  return `fashionvault://looks/${lookId}`;
}

let shareIdCounter = 0;
function freshShareId(): string {
  shareIdCounter += 1;
  const hex = shareIdCounter.toString(16).padStart(8, '0');
  return `9d000000-3c4d-5e6f-b012-${hex}00cf`;
}
function shareUri(shareId: string) {
  return `aifashionvault://share-import/${shareId}`;
}

type UrlListener = (event: { url: string }) => void;

const linkHandlerListeners: UrlListener[] = [];
const addEventListenerMock = Linking.addEventListener as unknown as jest.Mock;
const getInitialURLMock = Linking.getInitialURL as unknown as jest.Mock;

function emitLink(url: string) {
  for (const listener of [...linkHandlerListeners]) listener({ url });
}

function consumeShareMock(): jest.Mock {
  return (
    NativeModules as unknown as {
      ShareHandoff: { consumeShare: jest.Mock };
    }
  ).ShareHandoff.consumeShare;
}

// Drains every pending promise chain (native consume -> navigate)
async function flush() {
  const flushPromise = new Promise<void>(resolve => {
    setTimeout(resolve, 0);
  });
  await act(async () => {
    await flushPromise;
  });
}

function spyGetLook(): jest.Mock {
  return jest
    .spyOn(generationApi, 'getLook')
    .mockReturnValue(
      new Promise<never>(() => undefined),
    ) as unknown as jest.Mock;
}

beforeEach(() => {
  // Keep any in-flight import request pending forever so routing tests stay
  // on the "Preparing shared item" surface instead of rejecting into a failure view.
  globalThis.fetch = (() => new Promise<Response>(() => {})) as typeof fetch;
  linkHandlerListeners.length = 0;
  addEventListenerMock.mockImplementation(
    (eventType: string, listener: UrlListener) => {
      if (eventType !== 'url') return { remove: () => undefined };
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

describe('Android share-import flag gate', () => {
  test('with SHARE_IMPORT_ANDROID off, a warm url event never consumes and never navigates', async () => {
    const flagSpy = jest
      .spyOn(featureFlags, 'isFeatureEnabled')
      .mockImplementation((flag: string) => {
        if (flag === 'SHARE_IMPORT_ANDROID') return false;
        return true;
      });

    const shareId = freshShareId();
    await render(<App />);
    expect(screen.queryByText('Preparing shared item')).toBeNull();

    await act(async () => {
      emitLink(shareUri(shareId));
    });
    await flush();

    expect(flagSpy).toHaveBeenCalledWith('SHARE_IMPORT_ANDROID');
    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();

    flagSpy.mockRestore();
  });

  test('with SHARE_IMPORT_ANDROID off, a cold-start initial URL never consumes and never navigates', async () => {
    const flagSpy = jest
      .spyOn(featureFlags, 'isFeatureEnabled')
      .mockImplementation((flag: string) => {
        if (flag === 'SHARE_IMPORT_ANDROID') return false;
        return true;
      });
    const shareId = freshShareId();
    getInitialURLMock.mockResolvedValue(shareUri(shareId));

    await render(<App />);
    await flush();

    expect(flagSpy).toHaveBeenCalledWith('SHARE_IMPORT_ANDROID');
    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();

    flagSpy.mockRestore();
  });
});

describe('Android share-import flag-on consumption and navigation', () => {
  test('with SHARE_IMPORT_ANDROID on, consumes share and navigates to ShareImport with validated payload', async () => {
    const validPayload: SharePayload = {
      kind: 'url',
      url: 'https://example.com/product/123',
    };
    consumeShareMock().mockResolvedValueOnce({
      ok: true,
      payload: validPayload,
    });

    const shareId = freshShareId();
    await render(<App />);
    expect(screen.queryByText('Preparing shared item')).toBeNull();

    await act(async () => {
      emitLink(shareUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();
  });

  test('cold-start initial URL with SHARE_IMPORT_ANDROID on consumes and navigates identically', async () => {
    const validPayload: SharePayload = {
      kind: 'url',
      url: 'https://example.com/product/456',
    };
    consumeShareMock().mockResolvedValueOnce({
      ok: true,
      payload: validPayload,
    });

    const shareId = freshShareId();
    getInitialURLMock.mockResolvedValue(shareUri(shareId));

    await render(<App />);
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();
  });

  test('concurrent duplicate delivery and later replay of the same share id consumes at most once', async () => {
    const validPayload: SharePayload = {
      kind: 'url',
      url: 'https://example.com/product/789',
    };
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: validPayload,
    });

    const shareId = freshShareId();
    await render(<App />);

    // Concurrent duplicate delivery: both events synchronously
    await act(async () => {
      emitLink(shareUri(shareId));
      emitLink(shareUri(shareId));
    });
    await flush();

    // Replay after first consumption settled
    await act(async () => {
      emitLink(shareUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(1);
    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(await screen.findByText('Preparing shared item')).toBeTruthy();
  });

  test('typed consume failure codes never navigate and mark the id final (no replay)', async () => {
    const failureCodes = [
      'invalid_share_id',
      'share_not_found',
      'share_expired',
      'invalid_payload',
    ] as const;

    for (const code of failureCodes) {
      consumeShareMock().mockResolvedValueOnce({ ok: false, code });
      const shareId = freshShareId();

      await render(<App />);

      await act(async () => {
        emitLink(shareUri(shareId));
      });
      await flush();

      expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
      expect(screen.queryByText('Preparing shared item')).toBeNull();
      expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();

      // Replay must not re-consume because the id is final
      await act(async () => {
        emitLink(shareUri(shareId));
      });
      await flush();
    }

    // Exactly one call per failure code across the loop
    expect(consumeShareMock()).toHaveBeenCalledTimes(failureCodes.length);
  });

  test('bridge rejection (consumeShare rejects) is swallowed silently with no navigation', async () => {
    consumeShareMock().mockRejectedValueOnce(new Error('native bridge failure'));

    const shareId = freshShareId();
    await render(<App />);

    await act(async () => {
      emitLink(shareUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();
  });

  test('payload violating kind-specific shape at bridge boundary never navigates', async () => {
    // kind: 'url' requires url and forbids localImageUri; here missing url
    consumeShareMock().mockResolvedValueOnce({
      ok: true,
      payload: { kind: 'url' },
    });

    const shareId = freshShareId();
    await render(<App />);

    await act(async () => {
      emitLink(shareUri(shareId));
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledWith(shareId);
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();
  });
});

describe('Android look deep link handling', () => {
  test('a valid warm look link navigates to Look exactly once and never touches the share bridge', async () => {
    const getLook = spyGetLook();
    const lookId = freshLookId();

    await render(<App />);
    expect(screen.queryByText('Look')).toBeNull();

    await act(async () => {
      emitLink(lookUri(lookId));
    });
    expect(await screen.findByText('Look')).toBeTruthy();

    expect(getLook).toHaveBeenCalledTimes(1);
    expect(getLook).toHaveBeenCalledWith(lookId);
    expect(consumeShareMock()).not.toHaveBeenCalled();

    getLook.mockRestore();
  });

  test('duplicate and concurrent delivery of the same look id navigates exactly once', async () => {
    const getLook = spyGetLook();
    const lookId = freshLookId();

    await render(<App />);

    await act(async () => {
      emitLink(lookUri(lookId));
      emitLink(lookUri(lookId));
    });
    expect(await screen.findByText('Look')).toBeTruthy();

    await act(async () => {
      emitLink(lookUri(lookId));
    });
    await flush();

    expect(getLook).toHaveBeenCalledTimes(1);
    expect(getLook).toHaveBeenCalledWith(lookId);

    getLook.mockRestore();
  });

  test('invalid look links are silently dropped: no navigation, no throw, no share consume', async () => {
    const getLook = spyGetLook();
    const validLookId = freshLookId();

    await render(<App />);

    await act(async () => {
      // Uppercase hex is noncanonical.
      emitLink(lookUri(validLookId.toUpperCase()));
      // Wrong scheme (share handoff scheme), right path shape.
      emitLink(`aifashionvault://looks/${validLookId}`);
      // Wrong host on look scheme.
      emitLink(`fashionvault://shares/${validLookId}`);
      // Missing id.
      emitLink('fashionvault://looks/');
      // Non-UUID id.
      emitLink('fashionvault://looks/not-a-uuid');
      // Query suffix is rejected.
      emitLink(`${lookUri(validLookId)}?src=push`);
      // Extra segment is rejected.
      emitLink(`${lookUri(validLookId)}/extra`);
    });
    await flush();

    expect(screen.queryByText('Look')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();
    expect(getLook).not.toHaveBeenCalled();
    expect(consumeShareMock()).not.toHaveBeenCalled();

    getLook.mockRestore();
  });

  test('invalid share links are silently dropped: no native bridge call, no navigation', async () => {
    const validShareId = freshShareId();

    await render(<App />);

    await act(async () => {
      // Uppercase hex is noncanonical.
      emitLink(shareUri(validShareId.toUpperCase()));
      // Wrong scheme, right path shape.
      emitLink(`fashionvault://share-import/${validShareId}`);
      // Wrong host on share scheme.
      emitLink(`aifashionvault://shares/${validShareId}`);
      // Missing id.
      emitLink('aifashionvault://share-import/');
      // Non-UUID id.
      emitLink('aifashionvault://share-import/not-a-uuid');
      // Query suffix is rejected.
      emitLink(`${shareUri(validShareId)}?src=browser`);
      // Extra segment is rejected.
      emitLink(`${shareUri(validShareId)}/extra`);
    });
    await flush();

    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByRole('header', { name: 'Home' })).toBeTruthy();
  });

  test('a cold-start initial look URL on Android is handled the same as a warm event', async () => {
    const getLook = spyGetLook();
    const lookId = freshLookId();
    getInitialURLMock.mockResolvedValue(lookUri(lookId));

    await render(<App />);
    expect(await screen.findByText('Look')).toBeTruthy();

    expect(getLook).toHaveBeenCalledTimes(1);
    expect(getLook).toHaveBeenCalledWith(lookId);
    expect(consumeShareMock()).not.toHaveBeenCalled();

    getLook.mockRestore();
  });
});

describe('Android handling privacy (ADR-015)', () => {
  test('look and share handling emit no console output on success or failure paths', async () => {
    const logSpy = jest.spyOn(console, 'log');
    const infoSpy = jest.spyOn(console, 'info');
    const warnSpy = jest.spyOn(console, 'warn');
    const errorSpy = jest.spyOn(console, 'error');

    consumeShareMock()
      .mockRejectedValueOnce(new Error('bridge failure'))
      .mockResolvedValueOnce({ ok: false, code: 'share_expired' })
      .mockResolvedValueOnce({ ok: true, payload: { kind: 'url' } });

    const lookId = freshLookId();
    await render(<App />);

    await act(async () => {
      emitLink(lookUri(lookId));
      emitLink('fashionvault://looks/not-a-uuid');
    });

    const shareIds = [
      freshShareId(),
      freshShareId(),
      freshShareId(),
    ];
    await act(async () => {
      for (const id of shareIds) {
        emitLink(shareUri(id));
      }
    });
    await flush();

    expect(consumeShareMock()).toHaveBeenCalledTimes(3);
    const emitted = [logSpy, infoSpy, warnSpy, errorSpy].flatMap(
      spy => spy.mock.calls,
    );
    expect(emitted).toHaveLength(0);

    logSpy.mockRestore();
    infoSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });
});
