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
