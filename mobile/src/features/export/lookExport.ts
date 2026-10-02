import {NativeModules, Share} from 'react-native';

/**
 * User-directed local export of a rendered Look (C16).
 *
 * The user explicitly asks to export a Look they can already see. The result
 * is downloaded into a bounded private staging file and handed to the OS share
 * sheet — the shared artifact is the actual image, never the short-lived signed
 * read URL it came from (which would expire in the recipient's hands).
 *
 * Deliberately NOT a share-link feature: this module never calls
 * `POST /looks/{id}/share-link` and never mints a bearer URL. Export is a local
 * copy the user chooses to send, so its boundary is the OS share sheet.
 *
 * Native seam: React Native core cannot write a private file, and its Android
 * `Share` implementation is text-only (it never attaches `EXTRA_STREAM`), so
 * the download, the image share and save-to-photos all live behind the
 * `LookExportStager` bridge. When no bridge is registered (tests, platforms
 * without the native writer) export reports `SHARE_UNAVAILABLE` rather than
 * fabricating a share; iOS can still fall back to React Native's `Share`, which
 * does accept a file URL.
 */

/** Native bridge names probed, most specific first. */
const STAGER_MODULE_NAMES = ['ImageExport', 'LookExportStager'] as const;

/** Native staging seam. Enforces `maxBytes` natively; never returns a URL. */
export interface LookExportStager {
  /** Downloads `url` into a bounded private temp file; resolves a local uri. */
  stageFromUrl(url: string, maxBytes: number): Promise<string>;
  /** Attaches the staged image to the OS share sheet. Optional on iOS. */
  shareStaged?(uri: string): Promise<'shared' | 'dismissed' | 'failed'>;
  /** Copies a staged file into the photo library with minimal permission. */
  saveToPhotos?(uri: string): Promise<'saved' | 'denied' | 'failed'>;
  /** Removes one staged file. Must be safe to call on an absent file. */
  removeStaged(uri: string): Promise<void>;
  /** Sweeps staged files older than `maxAgeMs`; omit if the native side owns it. */
  sweepStaged?(maxAgeMs: number): Promise<void>;
}

export type LookExportAction = 'share' | 'save';

export type LookExportOutcome =
  | {status: 'SHARED'}
  | {status: 'SAVED'}
  | {status: 'CANCELLED'}
  | {status: 'PERMISSION_DENIED'}
  | {status: 'STAGING_FAILED'}
  | {status: 'SHARE_FAILED'}
  | {status: 'SAVE_FAILED'}
  | {status: 'SHARE_UNAVAILABLE'}
  | {status: 'DISCLOSURE_REQUIRED'};

export type LookExportRequest = {
  lookId: string;
  /** Short-lived signed read from GET /looks/{id}; never shared verbatim. */
  imageUrl: string;
  /** The user acknowledged the copy leaves the app's control boundary. */
  disclosureAcknowledged: boolean;
  fileName?: string;
};

export type LookExportDeps = {
  stager: LookExportStager | null;
  /** React Native Share fallback for platforms whose bridge omits shareStaged. */
  rnShare: (options: {url: string; title?: string}) => Promise<{action?: string}>;
  maxBytes: number;
  /** Staged files older than this are swept even after a crash. */
  maxStagingAgeMs: number;
};

const DEFAULT_MAX_BYTES = 25 * 1024 * 1024;
const DEFAULT_MAX_STAGING_AGE_MS = 15 * 60 * 1000;

/** Typed staging failure a native stager raises for a denied permission. */
export function isPermissionDenied(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    error.code === 'EXPORT_PERMISSION_DENIED'
  );
}

/**
 * Picks the registered export bridge out of a module map. Exported so the
 * probing/shape rules are testable without a live React Native runtime.
 */
export function resolveNativeStager(
  modules: Record<string, unknown>,
): LookExportStager | null {
  for (const name of STAGER_MODULE_NAMES) {
    const candidate = modules[name];
    if (typeof candidate !== 'object' || candidate === null) {
      continue;
    }
    // Only the two methods every export needs are required here; shareStaged,
    // saveToPhotos and sweepStaged stay optional so a bridge that implements a
    // narrower surface still works (its absence is reported honestly, never
    // faked).
    if (!('stageFromUrl' in candidate) || !('removeStaged' in candidate)) {
      continue;
    }
    if (
      typeof candidate.stageFromUrl !== 'function' ||
      typeof candidate.removeStaged !== 'function'
    ) {
      continue;
    }
    // The required methods were each checked as functions above; this cast
    // only names the validated bridge shape for the caller.
    const stager = candidate as LookExportStager;
    return stager;
  }
  return null;
}

/**
 * Creates a Look exporter over an injected (or registered) native stager.
 * Staging cleanup has two paths: the per-export `finally` removes the file the
 * user just shared, and {@link cleanupExpiredStaging} sweeps files left behind
 * by a crash or an export that never reached the share sheet.
 */
export function createLookExporter(overrides: Partial<LookExportDeps> = {}) {
  const deps: LookExportDeps = {
    stager: resolveNativeStager(NativeModules as unknown as Record<string, unknown>),
    rnShare: options => Share.share(options),
    maxBytes: DEFAULT_MAX_BYTES,
    maxStagingAgeMs: DEFAULT_MAX_STAGING_AGE_MS,
    ...overrides,
  };

  async function offerImage(
    stager: LookExportStager,
    uri: string,
    fileName?: string,
  ): Promise<LookExportOutcome> {
    // Prefer the native image share: React Native's Android Share is
    // text-only and would drop the image. Only platforms whose bridge omits
    // shareStaged (iOS) fall back to React Native's file-URL share.
    if (typeof stager.shareStaged === 'function') {
      const result = await stager.shareStaged(uri);
      if (result === 'shared') return {status: 'SHARED'};
      if (result === 'dismissed') return {status: 'CANCELLED'};
      return {status: 'SHARE_FAILED'};
    }
    const fallback = await deps.rnShare({
      url: uri,
      ...(fileName === undefined ? {} : {title: fileName}),
    });
    // iOS reports a user cancel as dismissedAction; Android resolves with no
    // action. Either way the user chose not to send: harmless.
    return fallback?.action === 'dismissedAction'
      ? {status: 'CANCELLED'}
      : {status: 'SHARED'};
  }

  async function exportLook(
    request: LookExportRequest,
    action: LookExportAction,
  ): Promise<LookExportOutcome> {
    // Item 2: a deliberate outside-boundary acknowledgement is required
    // before any bytes are staged. No acknowledgement, no download.
    if (!request.disclosureAcknowledged) {
      return {status: 'DISCLOSURE_REQUIRED'};
    }
    const stager = deps.stager;
    if (stager === null) {
      return {status: 'SHARE_UNAVAILABLE'};
    }
    let uri: string;
    try {
      uri = await stager.stageFromUrl(request.imageUrl, deps.maxBytes);
    } catch (error) {
      return isPermissionDenied(error)
        ? {status: 'PERMISSION_DENIED'}
        : {status: 'STAGING_FAILED'};
    }
    try {
      if (action === 'save') {
        // Item 4: save-to-photos is a distinct user intent from sending a
        // copy; the native side requests the minimum platform permission and
        // reports denial distinctly. A bridge that cannot save reports
        // failure rather than a fabricated success.
        if (typeof stager.saveToPhotos !== 'function') {
          return {status: 'SAVE_FAILED'};
        }
        const saved = await stager.saveToPhotos(uri);
        if (saved === 'saved') return {status: 'SAVED'};
        if (saved === 'denied') return {status: 'PERMISSION_DENIED'};
        return {status: 'SAVE_FAILED'};
      }
      return await offerImage(stager, uri, request.fileName);
    } catch {
      return action === 'save' ? {status: 'SAVE_FAILED'} : {status: 'SHARE_FAILED'};
    } finally {
      // Item 3: private staging is always cleaned after use. The OS share
      // sheet and the photo library both copy the bytes out before resolving,
      // so an explicit saved copy is untouched by removing our staging file.
      try {
        await stager.removeStaged(uri);
      } catch {
        // Best-effort: the sweep retries, and a leaked private temp file is
        // preferable to failing the user's export result.
      }
    }
  }

  async function cleanupExpiredStaging(): Promise<void> {
    const stager = deps.stager;
    if (stager === null || typeof stager.sweepStaged !== 'function') {
      return;
    }
    try {
      await stager.sweepStaged(deps.maxStagingAgeMs);
    } catch {
      // Best-effort sweep.
    }
  }

  return {exportLook, cleanupExpiredStaging};
}
