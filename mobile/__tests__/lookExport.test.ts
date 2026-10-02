import {
  createLookExporter,
  isPermissionDenied,
} from '../src/features/export/lookExport';
import type {LookExportStager} from '../src/features/export/lookExport';

const SIGNED_URL = 'https://objects.example.invalid/look-1?X-Amz-Signature=abc';
const STAGED_URI = 'file:///data/user/0/com.aifashionvault.app/cache/look-export-1.jpg';

type StageRecord = {url: string; maxBytes: number};

function makeStager(overrides: Partial<LookExportStager> = {}) {
  const staged: StageRecord[] = [];
  const removed: string[] = [];
  const swept: number[] = [];
  const saved: string[] = [];
  const stager: LookExportStager = {
    stageFromUrl: async (url, maxBytes) => {
      staged.push({url, maxBytes});
      return STAGED_URI;
    },
    removeStaged: async uri => {
      removed.push(uri);
    },
    sweepStaged: async maxAgeMs => {
      swept.push(maxAgeMs);
    },
    saveToPhotos: async uri => {
      saved.push(uri);
      return 'saved';
    },
    ...overrides,
  };
  return {stager, staged, removed, swept, saved};
}

describe('look export (C16)', () => {
  test('refuses before staging when the outside-boundary disclosure was not acknowledged', async () => {
    const {stager, staged} = makeStager();
    const share = jest.fn();
    const exporter = createLookExporter({stager, share});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: false,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'DISCLOSURE_REQUIRED'});
    // No bytes staged and nothing offered to the OS share sheet.
    expect(staged).toHaveLength(0);
    expect(share).not.toHaveBeenCalled();
  });

  test('reports SHARE_UNAVAILABLE when no native stager is registered', async () => {
    const share = jest.fn();
    const exporter = createLookExporter({stager: null, share});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'SHARE_UNAVAILABLE'});
    expect(share).not.toHaveBeenCalled();
  });

  test('shares the staged local image, not the expiring signed read URL', async () => {
    const {stager, staged, removed} = makeStager();
    const shared: {url: string; title?: string}[] = [];
    const exporter = createLookExporter({
      stager,
      share: async options => {
        shared.push(options);
        return {action: 'sharedAction'};
      },
      maxBytes: 4096,
    });

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'SHARED'});
    expect(staged).toEqual([{url: SIGNED_URL, maxBytes: 4096}]);
    expect(shared).toHaveLength(1);
    expect(shared[0].url).toBe(STAGED_URI);
    expect(shared[0].url).not.toBe(SIGNED_URL);
    expect(shared[0].url.startsWith('file://')).toBe(true);
    // Private staging is cleaned after use.
    expect(removed).toEqual([STAGED_URI]);
  });

  test('treats a dismissed share sheet as a harmless cancel and still cleans staging', async () => {
    const {stager, removed} = makeStager();
    const exporter = createLookExporter({
      stager,
      share: async () => ({action: 'dismissedAction'}),
    });

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'CANCELLED'});
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps a denied permission distinctly and never opens the share sheet', async () => {
    const denied = Object.assign(new Error('denied'), {
      code: 'EXPORT_PERMISSION_DENIED',
    });
    const {stager} = makeStager({
      stageFromUrl: async () => {
        throw denied;
      },
    });
    const share = jest.fn();
    const exporter = createLookExporter({stager, share});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'PERMISSION_DENIED'});
    expect(isPermissionDenied(denied)).toBe(true);
    expect(share).not.toHaveBeenCalled();
  });

  test('reports a bounded-download failure as STAGING_FAILED without sharing', async () => {
    const {stager} = makeStager({
      stageFromUrl: async () => {
        throw new Error('over limit');
      },
    });
    const share = jest.fn();
    const exporter = createLookExporter({stager, share});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'STAGING_FAILED'});
    expect(share).not.toHaveBeenCalled();
  });

  test('reports a share-sheet failure and still removes the staged file', async () => {
    const {stager, removed} = makeStager();
    const exporter = createLookExporter({
      stager,
      share: async () => {
        throw new Error('no handler');
      },
    });

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'share',
    );

    expect(outcome).toEqual({status: 'SHARE_FAILED'});
    expect(removed).toEqual([STAGED_URI]);
  });

  test('saves to photos on the save action and cleans staging afterwards', async () => {
    const {stager, saved, removed} = makeStager();
    const share = jest.fn();
    const exporter = createLookExporter({stager, share});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'save',
    );

    expect(outcome).toEqual({status: 'SAVED'});
    expect(saved).toEqual([STAGED_URI]);
    // Save is not a share: the OS share sheet is never opened.
    expect(share).not.toHaveBeenCalled();
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps a denied photo-library permission distinctly', async () => {
    const {stager} = makeStager({saveToPhotos: async () => 'denied'});
    const exporter = createLookExporter({stager});

    const outcome = await exporter.exportLook(
      {
        lookId: 'look-1',
        imageUrl: SIGNED_URL,
        disclosureAcknowledged: true,
      },
      'save',
    );

    expect(outcome).toEqual({status: 'PERMISSION_DENIED'});
  });

  test('sweeps staging older than the configured age so a crash does not leak private copies', async () => {
    const {stager, swept} = makeStager();
    const exporter = createLookExporter({stager, maxStagingAgeMs: 60_000});

    await exporter.cleanupExpiredStaging();

    expect(swept).toEqual([60_000]);
  });

  test('cleanup is a no-op when no native stager exists', async () => {
    const exporter = createLookExporter({stager: null});
    await expect(exporter.cleanupExpiredStaging()).resolves.toBeUndefined();
  });
});
