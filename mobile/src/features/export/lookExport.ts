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
 * Native seam: React Native core cannot write a private file, so downloading
 * into staging is an injected platform seam (`LookExportStager`). When the
 * module is not registered (tests, platforms without the native writer) export
 * reports `SHARE_UNAVAILABLE` instead of fabricating a share.
 */

export const LOOK_EXPORT_STAGER = 'LookExportStager';

/** Native staging seam. Enforces `maxBytes` natively; never returns a URL. */
export interface LookExportStager {
  /** Downloads `url` into a bounded private temp file; resolves a local uri. */
  stageFromUrl(url: string, maxBytes: number): Promise<string>;
  /** Removes one staged file. Must be safe to call on an absent file. */
  removeStaged(uri: string): Promise<void>;
  /** Sweeps staged files older than `maxAgeMs` (crash/expiry recovery). */
  sweepStaged(maxAgeMs: number): Promise<void>;
  /** Copies a staged file into the user's photo library with minimal permission. */
  saveToPhotos(uri: string): Promise<'saved' | 'denied' | 'failed'>;
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
  share: (options: {url: string; title?: string}) => Promise<{action?: string}>;
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

function nativeStager(): LookExportStager | null {
  const candidate = (NativeModules as unknown as Record<string, unknown>)[
    LOOK_EXPORT_STAGER
  ];
  if (typeof candidate !== 'object' || candidate === null) {
    return null;
  }
  if (
    !('stageFromUrl' in candidate) ||
    !('removeStaged' in candidate) ||
    !('sweepStaged' in candidate)
  ) {
    return null;
  }
  if (
    typeof candidate.stageFromUrl !== 'function' ||
    typeof candidate.removeStaged !== 'function' ||
    typeof candidate.sweepStaged !== 'function'
  ) {
    return null;
  }
  // Each method was checked as a function above; this cast only names the
  // validated bridge shape for the caller.
  const stager = candidate as LookExportStager;
  return stager;
}

/**
 * Creates a Look exporter over an injected (or registered) native stager.
 * Staging cleanup has two paths: the per-export `finally` removes the file the
 * user just shared, and {@link cleanupExpiredStaging} sweeps files left behind
 * by a crash or an export that never reached the share sheet.
 */
export function createLookExporter(overrides: Partial<LookExportDeps> = {}) {
  const deps: LookExportDeps = {
    stager: nativeStager(),
    share: options => Share.share(options),
    maxBytes: DEFAULT_MAX_BYTES,
    maxStagingAgeMs: DEFAULT_MAX_STAGING_AGE_MS,
    ...overrides,
  };

  async function exportLook(
    request: LookExportRequest,
    action: LookExportAction,
  ): Promise<LookExportOutcome> {
    // Item 2: a deliberate outside-boundary acknowledgement is required
    // before any bytes are staged. No acknowledgement, no download.
    if (!request.disclosureAcknowledged) {
      return {status: 'DISCLOSURE_REQUIRED'};
    }
    if (deps.stager === null) {
      return {status: 'SHARE_UNAVAILABLE'};
    }
    let uri: string;
    try {
      uri = await deps.stager.stageFromUrl(request.imageUrl, deps.maxBytes);
    } catch (error) {
      return isPermissionDenied(error)
        ? {status: 'PERMISSION_DENIED'}
        : {status: 'STAGING_FAILED'};
    }
    try {
      if (action === 'save') {
        // Item 4: save-to-photos is a distinct user intent from sending a
        // copy; the native side requests the minimum platform permission and
        // reports denial distinctly.
        const saved = await deps.stager.saveToPhotos(uri);
        if (saved === 'saved') return {status: 'SAVED'};
        if (saved === 'denied') return {status: 'PERMISSION_DENIED'};
        return {status: 'SAVE_FAILED'};
      }
      const result = await deps.share({
        url: uri,
        ...(request.fileName === undefined ? {} : {title: request.fileName}),
      });
      // iOS reports a user cancel as dismissedAction; Android resolves with
      // no action. Either way the user chose not to send: harmless.
      return result?.action === 'dismissedAction'
        ? {status: 'CANCELLED'}
        : {status: 'SHARED'};
    } catch {
      return action === 'save' ? {status: 'SAVE_FAILED'} : {status: 'SHARE_FAILED'};
    } finally {
      // Item 3: private staging is always cleaned after use. The OS share
      // sheet and the photo library both copy the bytes out before resolving,
      // so an explicit saved copy is untouched by removing our staging file.
      try {
        await deps.stager.removeStaged(uri);
      } catch {
        // Best-effort: the sweep retries, and a leaked private temp file is
        // preferable to failing the user's export result.
      }
    }
  }

  async function cleanupExpiredStaging(): Promise<void> {
    if (deps.stager === null) {
      return;
    }
    try {
      await deps.stager.sweepStaged(deps.maxStagingAgeMs);
    } catch {
      // Best-effort sweep.
    }
  }

  return {exportLook, cleanupExpiredStaging};
}
