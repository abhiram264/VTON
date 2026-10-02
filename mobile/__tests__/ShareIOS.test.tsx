import { act, render, screen } from '@testing-library/react-native';
import App from '../App';
import { Linking, NativeModules } from 'react-native';
import * as featureFlags from '../src/config/featureFlags';
import { looksApi } from '../src/features/looks/looksApi';

// Lowercase canonical UUIDs per the frozen look deep-link seam. The look and
// share handlers remember ids for the process lifetime (spec dedupe), so
// every test draws fresh ids via freshLookId() to stay order-independent.
let lookIdCounter = 0;
function freshLookId(): string {
  lookIdCounter += 1;
  const hex = lookIdCounter.toString(16).padStart(8, '0');
  return `7c000000-1a2b-4c3d-9e8f-${hex}00af`;
}
function lookUri(lookId: string) {
  return `fashionvault://looks/${lookId}`;
}

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

function consumeShareMock(): jest.Mock {
  return (
    NativeModules as unknown as {
      ShareHandoff: { consumeShare: jest.Mock };
    }
  ).ShareHandoff.consumeShare;
}

// Drains every pending promise chain (native consume -> navigate) so
async function flush() {
  // Executor form required: the project's TS lib target predates
  // Promise.withResolvers and tsconfig.json is outside this unit's paths.
  const flushPromise = new Promise<void>(resolve => {
    setTimeout(resolve, 0);
  });
  await act(async () => {
    await flushPromise;
  });
}
beforeEach(() => {
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

describe('iOS share-import flag gate', () => {
  test('with SHARE_IMPORT_IOS off, a warm url event never consumes and never navigates', async () => {
    const flagSpy = jest
      .spyOn(featureFlags, 'isFeatureEnabled')
      .mockReturnValue(false);

    await render(<App />);
    expect(screen.queryByText('Preparing shared item')).toBeNull();

    await act(async () => {
      emitLink(
        'aifashionvault://share-import/3f2504e0-4f89-11d3-9a0c-0305e82c3301',
      );
    });
    await flush();

    expect(flagSpy).toHaveBeenCalledWith('SHARE_IMPORT_IOS');
    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.queryByText('Preparing shared item')).toBeNull();
    expect(screen.getByText('Home')).toBeTruthy();

    flagSpy.mockRestore();
  });

  test('with SHARE_IMPORT_IOS off, a cold-start initial URL never consumes and never navigates', async () => {
    const flagSpy = jest
      .spyOn(featureFlags, 'isFeatureEnabled')
      .mockReturnValue(false);
    getInitialURLMock.mockResolvedValue(
      'aifashionvault://share-import/3f2504e0-4f89-11d3-9a0c-0305e82c3301',
    );

    await render(<App />);
    await flush();

    expect(flagSpy).toHaveBeenCalledWith('SHARE_IMPORT_IOS');
    expect(consumeShareMock()).not.toHaveBeenCalled();
    expect(screen.getByText('Home')).toBeTruthy();

    flagSpy.mockRestore();
  });
});

describe('iOS look deep link handling', () => {
  // Navigation to the Look route mounts LookScreen, which fetches exactly
  // once per mount — the getLook call count is the navigation count.
  function spyGetLook(): jest.Mock {
    // Never-settling promise: the fetch result is not under test, and no
    // post-act state update can leak an act() warning.
    return jest
      .spyOn(looksApi, 'get')
      .mockReturnValue(
        new Promise<never>(() => undefined),
      ) as unknown as jest.Mock;
  }

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

    // Concurrent duplicate delivery: both events synchronously, before any
    // dedupe bookkeeping could settle.
    await act(async () => {
      emitLink(lookUri(lookId));
      emitLink(lookUri(lookId));
    });
    expect(await screen.findByText('Look')).toBeTruthy();

    // Replay after the first navigation is final.
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
      // Wrong scheme (the share handoff scheme), right path shape.
      emitLink(`aifashionvault://looks/${validLookId}`);
      // Wrong host on the look scheme.
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
    expect(screen.getByText('Home')).toBeTruthy();
    expect(getLook).not.toHaveBeenCalled();
    expect(consumeShareMock()).not.toHaveBeenCalled();

    getLook.mockRestore();
  });

  test('a cold-start initial look URL is handled the same as a warm event', async () => {
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

describe('iOS handling privacy', () => {
  test('look and share handling emit no console output at all', async () => {
    const logSpy = jest.spyOn(console, 'log');
    const infoSpy = jest.spyOn(console, 'info');
    const warnSpy = jest.spyOn(console, 'warn');
    const errorSpy = jest.spyOn(console, 'error');

    // Bridge failure: consumeShare rejects. Typed failure and success with a
    // kind-invalid payload are covered for navigation elsewhere; here every
    // path must stay silent.
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
      '3f2504e0-4f89-11d3-9a0c-0305e82c3301',
      'b1d3e4a5-6c7f-4a2b-9d8e-0f1a2b3c4d5e',
      '9e000000-7c1d-4b2a-8f3e-000100000000',
    ];
    await act(async () => {
      for (const id of shareIds) {
        emitLink(`aifashionvault://share-import/${id}`);
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
