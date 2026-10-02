import {
  createLookExporter,
  isPermissionDenied,
  resolveNativeStager,
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
  const sharedNatively: string[] = [];
  const stager: LookExportStager = {
    stageFromUrl: async (url, maxBytes) => {
      staged.push({url, maxBytes});
      return STAGED_URI;
    },
    shareStaged: async uri => {
      sharedNatively.push(uri);
      return {action: 'sharedAction'};
    },
    saveToPhotos: async uri => {
      saved.push(uri);
      return 'saved';
    },
    removeStaged: async uri => {
      removed.push(uri);
    },
    sweepStaged: async maxAgeMs => {
      swept.push(maxAgeMs);
    },
    ...overrides,
  };
  return {stager, staged, removed, swept, saved, sharedNatively};
}

function exporterWith(
  stager: LookExportStager | null,
  extra: Partial<Parameters<typeof createLookExporter>[0]> = {},
) {
  return createLookExporter({
    stager,
    acknowledgeExport: async () => undefined,
    ...extra,
  });
}

describe('look export (C16)', () => {
  test('refuses before staging when the outside-boundary disclosure was not acknowledged', async () => {
    const {stager, staged} = makeStager();
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: false},
      'share',
    );

    expect(outcome).toEqual({status: 'DISCLOSURE_REQUIRED'});
    expect(staged).toHaveLength(0);
  });

  test('reports SHARE_UNAVAILABLE when no native stager is registered', async () => {
    const rnShare = jest.fn();
    const exporter = exporterWith(null, {rnShare});

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARE_UNAVAILABLE'});
    expect(rnShare).not.toHaveBeenCalled();
  });

  test('shares the staged local image through the native bridge, not the signed URL', async () => {
    const {stager, staged, removed, sharedNatively} = makeStager();
    const exporter = exporterWith(stager, {maxBytes: 4096});

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARED'});
    expect(staged).toEqual([{url: SIGNED_URL, maxBytes: 4096}]);
    // The image is shared from the private local file, never the expiring URL.
    expect(sharedNatively).toEqual([STAGED_URI]);
    expect(STAGED_URI).not.toBe(SIGNED_URL);
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps a native share dismissal to a harmless cancel and still cleans staging', async () => {
    const {stager, removed} = makeStager({
      shareStaged: async () => ({action: 'dismissedAction'}),
    });
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'CANCELLED'});
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps a native share rejection to SHARE_FAILED and still cleans staging', async () => {
    const {stager, removed} = makeStager({
      shareStaged: async () => {
        throw Object.assign(new Error('no app'), {code: 'E_EXPORT_NO_APP'});
      },
    });
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARE_FAILED'});
    expect(removed).toEqual([STAGED_URI]);
  });

  test('falls back to React Native Share when the bridge omits shareStaged (iOS)', async () => {
    const {stager, removed} = makeStager();
    delete stager.shareStaged;
    const offered: {url: string; title?: string}[] = [];
    const exporter = exporterWith(stager, {
      rnShare: async options => {
        offered.push(options);
        return {action: 'sharedAction'};
      },
    });

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARED'});
    expect(offered).toEqual([{url: STAGED_URI}]);
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps an iOS dismissedAction from the fallback to a cancel', async () => {
    const {stager} = makeStager();
    delete stager.shareStaged;
    const exporter = exporterWith(stager, {
      rnShare: async () => ({action: 'dismissedAction'}),
    });

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'CANCELLED'});
  });

  test('maps a denied staging permission distinctly and never shares', async () => {
    const denied = Object.assign(new Error('denied'), {
      code: 'EXPORT_PERMISSION_DENIED',
    });
    const {stager, sharedNatively} = makeStager({
      stageFromUrl: async () => {
        throw denied;
      },
    });
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'PERMISSION_DENIED'});
    expect(isPermissionDenied(denied)).toBe(true);
    expect(sharedNatively).toHaveLength(0);
  });

  test('reports a bounded-download failure as STAGING_FAILED without sharing', async () => {
    const {stager, sharedNatively} = makeStager({
      stageFromUrl: async () => {
        throw new Error('over limit');
      },
    });
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'STAGING_FAILED'});
    expect(sharedNatively).toHaveLength(0);
  });

  test('reports a share-sheet failure and still removes the staged file', async () => {
    const {stager, removed} = makeStager();
    delete stager.shareStaged;
    const exporter = exporterWith(stager, {
      rnShare: async () => {
        throw new Error('no handler');
      },
    });

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARE_FAILED'});
    expect(removed).toEqual([STAGED_URI]);
  });

  test('saves to photos on the save action and cleans staging afterwards', async () => {
    const {stager, saved, removed, sharedNatively} = makeStager();
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    expect(outcome).toEqual({status: 'SAVED'});
    expect(saved).toEqual([STAGED_URI]);
    // Save is not a share: the share sheet is never opened.
    expect(sharedNatively).toHaveLength(0);
    expect(removed).toEqual([STAGED_URI]);
  });

  test('maps a denied photo-library permission distinctly', async () => {
    const {stager} = makeStager({saveToPhotos: async () => 'denied'});
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    expect(outcome).toEqual({status: 'PERMISSION_DENIED'});
  });

  test('reports SAVE_FAILED rather than faking a save when the bridge cannot save', async () => {
    const {stager} = makeStager();
    delete stager.saveToPhotos;
    const exporter = exporterWith(stager);

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    expect(outcome).toEqual({status: 'SAVE_FAILED'});
  });

  test('requests the app image cap by default so the bridge never rejects a size our own bound implied was fine', async () => {
    const {stager, staged} = makeStager();
    const exporter = exporterWith(stager);

    await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    // ShareLimits.MAX_SHARE_BYTES == 20 * 1024 * 1024.
    expect(staged).toEqual([{url: SIGNED_URL, maxBytes: 20 * 1024 * 1024}]);
  });

  test('sweeps staging older than the configured age so a crash does not leak private copies', async () => {
    const {stager, swept} = makeStager();
    const exporter = exporterWith(stager, {maxStagingAgeMs: 60_000});

    await exporter.cleanupExpiredStaging();

    expect(swept).toEqual([60_000]);
  });

  test('cleanup is a no-op when no native stager exists', async () => {
    const exporter = exporterWith(null);
    await expect(exporter.cleanupExpiredStaging()).resolves.toBeUndefined();
  });

  test('cleanup is a no-op when the bridge owns staging itself (no sweepStaged)', async () => {
    const {stager} = makeStager();
    delete stager.sweepStaged;
    const exporter = exporterWith(stager);
    await expect(exporter.cleanupExpiredStaging()).resolves.toBeUndefined();
  });
});

describe('native bridge resolution (C16)', () => {
  const complete = {
    stageFromUrl: async () => STAGED_URI,
    saveToPhotos: async () => 'saved',
    removeStaged: async () => undefined,
    sweepStaged: async () => undefined,
  };

  test('prefers the ImageExport bridge name', () => {
    const stager = resolveNativeStager({ImageExport: complete});
    expect(stager).not.toBeNull();
  });

  test('accepts the LookExportStager bridge name as a fallback', () => {
    const stager = resolveNativeStager({LookExportStager: complete});
    expect(stager).not.toBeNull();
  });

  test('ignores a bridge missing a required method', () => {
    const incomplete = {
      stageFromUrl: async () => STAGED_URI,
      saveToPhotos: async () => 'saved',
    };
    expect(resolveNativeStager({ImageExport: incomplete})).toBeNull();
  });

  test('accepts a bridge implementing only the two required methods', () => {
    const minimal = {
      stageFromUrl: async () => STAGED_URI,
      removeStaged: async () => undefined,
    };
    expect(resolveNativeStager({ImageExport: minimal})).not.toBeNull();
  });

  test('ignores non-object module entries and returns null when nothing matches', () => {
    expect(resolveNativeStager({ImageExport: 'nope'})).toBeNull();
    expect(resolveNativeStager({})).toBeNull();
  });
});

describe('look export acknowledgement (C22)', () => {
  test('acknowledges the Look after a successful share', async () => {
    const {stager} = makeStager();
    const ack = jest.fn(async () => undefined);
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-42', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'SHARED'});
    expect(ack).toHaveBeenCalledWith('look-42');
  });

  test('acknowledges the Look after a successful save-to-photos', async () => {
    const {stager} = makeStager();
    const ack = jest.fn(async () => undefined);
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-7', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    expect(outcome).toEqual({status: 'SAVED'});
    expect(ack).toHaveBeenCalledWith('look-7');
  });

  test('does not acknowledge a cancelled share', async () => {
    const {stager} = makeStager({
      shareStaged: async () => ({action: 'dismissedAction'}),
    });
    const ack = jest.fn(async () => undefined);
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'CANCELLED'});
    expect(ack).not.toHaveBeenCalled();
  });

  test('does not acknowledge a denied permission', async () => {
    const {stager} = makeStager({
      saveToPhotos: async () => 'denied',
    });
    const ack = jest.fn(async () => undefined);
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    expect(outcome).toEqual({status: 'PERMISSION_DENIED'});
    expect(ack).not.toHaveBeenCalled();
  });

  test('does not acknowledge a staging failure', async () => {
    const {stager} = makeStager({
      stageFromUrl: async () => {
        throw new Error('staging failed');
      },
    });
    const ack = jest.fn(async () => undefined);
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-1', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    expect(outcome).toEqual({status: 'STAGING_FAILED'});
    expect(ack).not.toHaveBeenCalled();
  });

  test('a failing acknowledgement never changes the export outcome', async () => {
    const {stager} = makeStager();
    const ack = jest.fn(async () => {
      throw new Error('ack endpoint unavailable');
    });
    const exporter = exporterWith(stager, {acknowledgeExport: ack});

    const outcome = await exporter.exportLook(
      {lookId: 'look-9', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'share',
    );

    // The export succeeded; telemetry failure is invisible to the user.
    expect(outcome).toEqual({status: 'SHARED'});
    expect(ack).toHaveBeenCalledWith('look-9');
  });

  test('an acknowledgement that throws synchronously still reports the export as successful', async () => {
    const {stager} = makeStager();
    const ack = jest.fn(() => {
      throw new Error('ack client threw before returning a promise');
    });
    const exporter = exporterWith(stager, {acknowledgeExport: ack as never});

    const outcome = await exporter.exportLook(
      {lookId: 'look-10', imageUrl: SIGNED_URL, disclosureAcknowledged: true},
      'save',
    );

    // The save already happened; a broken ack must not claim it failed.
    expect(outcome).toEqual({status: 'SAVED'});
  });
});
