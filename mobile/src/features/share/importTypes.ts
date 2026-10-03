import type {SharePayload} from './types';

export type ShareImportRequest =
  | {kind: 'URL'; sourceUrl: string; uploadId?: never}
  | {kind: 'IMAGE'; sourceUrl?: never; uploadId: string}
  | {kind: 'IMAGE_URL'; sourceUrl: string; uploadId: string};

export type ShareImportResponse =
  | {importId: string; status: 'RESOLVING'}
  | {importId: string; status: 'READY'}
  | {
      importId: string;
      status: 'NEEDS_IMAGE';
      reason: 'URL_RESOLUTION_FAILED';
      recoveryActions: ['CHOOSE_SCREENSHOT', 'UPLOAD_IMAGE'];
    }
  | {
      importId: string;
      status: 'FAILED_RETRYABLE';
      reason: 'DEPENDENCY_UNAVAILABLE' | 'RESOLUTION_TIMEOUT';
    }
  | {importId: string; status: 'FAILED_TERMINAL'; reason: 'INVALID_SOURCE'};

export type AttachShareImageRequest = {uploadId: string};

export type ShareImportProblemCode =
  | 'IDEMPOTENCY_KEY_REQUIRED'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'IMPORT_NOT_FOUND'
  | 'IMPORT_STATE_CONFLICT'
  | 'UPLOAD_NOT_CLAIMABLE'
  | 'INVALID_SHARE_IMPORT'
  | 'DEPENDENCY_UNAVAILABLE'
  | 'RESOLUTION_TIMEOUT';

export type ShareImportResolver = (
  payload: SharePayload,
  signal: AbortSignal,
) => Promise<ShareImportResponse>;

/**
 * The persisted Vault item a confirmation produced. Identity is server-derived
 * from (owner, import) — `POST /share/imports/{importId}/confirm` is
 * idempotent, so repeating a confirmation (including after a lost response)
 * returns this same `id` instead of creating a second item.
 */
export type ShareImportConfirmedItem = {
  id: string;
  sourceReferenceId: string;
  createdAt?: string;
};

/**
 * Owner-scoped preview of a resolved import (`GET /share/imports/{id}/preview`).
 * `previewUrl` is a short-lived signed read, not a permanent identifier: it is
 * re-issued by calling the route again, and the app must never persist it as
 * the item's identity.
 */
export type ShareImportPreview = {
  importId: string;
  previewUrl: string;
  previewExpiresAt: string;
  sourceTitle?: string;
  sourceSiteName?: string;
};

export type ShareImportPreviewLoader = (
  importId: string,
  signal: AbortSignal,
) => Promise<ShareImportPreview>;

/**
 * Optional owner corrections applied at confirmation. All fields are optional;
 * omitting the object confirms with the resolved metadata only. The server is
 * the authority on name length and category membership, and answers
 * `INVALID_SHARE_IMPORT` for a malformed value rather than silently dropping it.
 */
export type ShareImportConfirmCorrections = {
  name?: string;
  category?: string;
  collectionId?: string;
};

export type ShareImportConfirmer = (
  importId: string,
  signal: AbortSignal,
  corrections?: ShareImportConfirmCorrections,
) => Promise<ShareImportConfirmedItem>;
