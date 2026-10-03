// Parity contract for the share-first promise (docs/VTON-PRD.md:31 "the app
// receives either a product URL and/or image blob"): every handoff URI the
// native hosts actually emit must reach the real import route carrying its
// id, and every handoff the native bridge rejects must be an honest refusal
// rather than a silent no-op that looks identical to success.
//
// Native sources of the accepted shape:
//   android .../ShareImportActivity.kt:248  Intent(aifashionvault://share-import/$shareId)
//   ios     .../ShareHandoffDiscovery.swift:22  shareURIPrefix
// Native sources of the accepted reasons (parity target):
//   android .../ShareHandoffInterop.kt:21-24  invalid_share_id,
//     share_not_found, share_expired, invalid_payload
//   ios     .../ShareHandoff.swift:108,:117,:124,:146  the same four codes
import { Linking, NativeModules } from 'react-native';
import { startShareLinkHandling } from '../src/features/share/linkHandler';
import { SHARE_HANDOFF_URI_PATTERN } from '../src/features/share/types';

type UrlListener = (event: { url: string }) => void;

// linkHandler registers exactly one 'url' subscription; capturing it here
// keeps the assertion on the real handler instead of a call-count mock.
let linkHandlerListener: UrlListener | null = null;
const addEventListenerMock = Linking.addEventListener as unknown as jest.Mock;
const getInitialURLMock = Linking.getInitialURL as unknown as jest.Mock;

// Fresh ids per case: linkHandler remembers consumed ids for the process
// lifetime (mobile/src/features/share/linkHandler.ts:17), so reusing one id
// across cases would silently pass through the dedupe path.
let shareIdCounter = 0;
function freshShareId(): string {
  shareIdCounter += 1;
  return `9e000000-7c1d-4b2a-8f3e-${shareIdCounter
    .toString(16)
    .padStart(8, '0')}0000`;
}
const SOURCE_URL = 'https://shop.example.invalid/p/123456';

function handoffUri(shareId: string): string {
  return `aifashionvault://share-import/${shareId}`;
}

function consumeShareMock(): jest.Mock {
  return (
    NativeModules as unknown as { ShareHandoff: { consumeShare: jest.Mock } }
  ).ShareHandoff.consumeShare;
}

function emitLink(url: string): void {
  const listener = linkHandlerListener;
  if (listener === null) {
    throw new Error('linkHandler did not register a url listener');
  }
  listener({ url });
}

// Drains the native consume -> validate -> navigate promise chain.
async function flush(): Promise<void> {
  const tick = new Promise<void>(resolve => setTimeout(resolve, 0));
  await tick;
}

// Observable result of a link: the route the app was asked to open.
type Navigation =
  | { name: 'ShareImport'; params: { payload: { kind: string; url?: string } } }
  | { name: 'Look'; params: { lookId: string } };

// NOTE: shareIdCounter is deliberately NOT reset here. linkHandler remembers
// every consumed id for the process lifetime
// (mobile/src/features/share/linkHandler.ts:17), so ids must stay unique
// across the whole file, not merely within one test — a per-test reset would
// let the dedupe path, not the handler, decide the later cases.
beforeEach(() => {
  linkHandlerListener = null;
  addEventListenerMock.mockReset();
  addEventListenerMock.mockImplementation(
    (eventType: string, listener: UrlListener) => {
      if (eventType === 'url') linkHandlerListener = listener;
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

describe('share handoff URI accepted by the native hosts', () => {
  test('the exact URI the Android and iOS hosts emit matches the accepted pattern', () => {
    const emitted = handoffUri(freshShareId());
    expect(SHARE_HANDOFF_URI_PATTERN.test(emitted)).toBe(true);
  });

  test('an accepted handoff opens the real import route carrying the shared id', async () => {
    const shareId = freshShareId();
    consumeShareMock().mockResolvedValue({ ok: true, payload: { kind: 'url', url: SOURCE_URL } });

    const navigations: Navigation[] = [];
    const stop = startShareLinkHandling(
      (name, params) => navigations.push({ name, params }),
      (name, params) => navigations.push({ name, params }),
    );
    try {
      expect(linkHandlerListener).not.toBeNull();
      emitLink(handoffUri(shareId));
      await flush();

      // Observable outcome: the ShareImport route was opened with the payload
      // the bridge returned for this id. Not a call count.
      expect(navigations).toEqual([
        { name: 'ShareImport', params: { payload: { kind: 'url', url: SOURCE_URL } } },
      ]);
      // The id actually consumed is the one carried by the emitted URI.
      expect(consumeShareMock().mock.calls[0][0]).toBe(shareId);
    } finally {
      stop();
    }
  });

  test('an image handoff also opens the real import route', async () => {
    const shareId = freshShareId();
    const stagedUri = 'file:///share-staged/image-1.png';
    consumeShareMock().mockResolvedValue({
      ok: true,
      payload: { kind: 'image', localImageUri: stagedUri },
    });

    const navigations: Navigation[] = [];
    const stop = startShareLinkHandling(
      (name, params) => navigations.push({ name, params }),
      (name, params) => navigations.push({ name, params }),
    );
    try {
      emitLink(handoffUri(shareId));
      await flush();

      expect(navigations).toEqual([
        {
          name: 'ShareImport',
          params: { payload: { kind: 'image', localImageUri: stagedUri } },
        },
      ]);
    } finally {
      stop();
    }
  });
});

describe('handoff the native bridge rejects', () => {
  // Parity with ShareHandoffInterop.kt:21-24 / ShareHandoff.swift:108,:117,
  // :124,:146. Each code is a consumed, final refusal: the id must not
  // re-consume, and the app must not enter the import route.
  const REJECTIONS = [
    'invalid_share_id',
    'share_not_found',
    'share_expired',
    'invalid_payload',
  ] as const;

  test.each(REJECTIONS)(
    '%s produces an honest refusal, never the import route',
    async code => {
      const shareId = freshShareId();
      consumeShareMock().mockResolvedValue({ ok: false, code });

      const navigations: Navigation[] = [];
      const stop = startShareLinkHandling(
        (name, params) => navigations.push({ name, params }),
        (name, params) => navigations.push({ name, params }),
      );
      try {
        emitLink(handoffUri(shareId));
        await flush();

        // Honest rejection: the bridge was asked about this exact id, and
        // nothing was navigated. Distinct from a dropped URL, where the
        // bridge is never consulted at all.
        expect(consumeShareMock().mock.calls).toEqual([[shareId]]);
        expect(navigations).toEqual([]);
      } finally {
        stop();
      }
    },
  );

  test('a rejected id is final: a replay does not re-consume or navigate', async () => {
    const shareId = freshShareId();
    consumeShareMock().mockResolvedValue({ ok: false, code: 'share_expired' });

    const navigations: Navigation[] = [];
    const stop = startShareLinkHandling(
      (name, params) => navigations.push({ name, params }),
      (name, params) => navigations.push({ name, params }),
    );
    try {
      emitLink(handoffUri(shareId));
      await flush();
      emitLink(handoffUri(shareId));
      await flush();

      expect(consumeShareMock().mock.calls).toEqual([[shareId]]);
      expect(navigations).toEqual([]);
    } finally {
      stop();
    }
  });

  test('a thrown bridge failure stays silent but is still a refusal', async () => {
    const shareId = freshShareId();
    consumeShareMock().mockRejectedValue(new Error('bridge exploded'));

    const navigations: Navigation[] = [];
    const stop = startShareLinkHandling(
      (name, params) => navigations.push({ name, params }),
      (name, params) => navigations.push({ name, params }),
    );
    try {
      emitLink(handoffUri(shareId));
      await flush();

      expect(navigations).toEqual([]);
    } finally {
      stop();
    }
  });
});

describe('URI outside the accepted native shape', () => {
  test('a malformed link is dropped without consulting the bridge or routing', async () => {
    const shareId = freshShareId();
    const navigations: Navigation[] = [];
    const stop = startShareLinkHandling(
      (name, params) => navigations.push({ name, params }),
      (name, params) => navigations.push({ name, params }),
    );
    try {
      emitLink(handoffUri('not-a-uuid'));
      emitLink(handoffUri(shareId.toUpperCase()));
      emitLink(`${handoffUri(shareId)}?src=sharesheet`);
      emitLink(`aifashionvault://other/${shareId}`);
      await flush();

      expect(consumeShareMock()).not.toHaveBeenCalled();
      expect(navigations).toEqual([]);
    } finally {
      stop();
    }
  });
});
