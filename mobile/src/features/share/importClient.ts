import {
  ApiProblemError,
  apiRequest,
  rejectDataUri,
  requireOk,
  uploadMedia,
} from '../my-model/http';
import {productContentType} from '../generation/productUpload';
import {releaseStagedImage} from './shareHandoff';
import type {AvatarApiConfig} from '../my-model/types';
import { productionApiConfig } from '../../config/apiConfig';
import type {
  ShareImportConfirmCorrections,
  ShareImportConfirmedItem,
  ShareImportConfirmer,
  ShareImportPreview,
  ShareImportPreviewLoader,
  ShareImportRequest,
  ShareImportResolver,
  ShareImportResponse,
} from './importTypes';
import type {SharePayload} from './types';

export type ShareImportClientDeps = {
  releaseStagedImage?: (localImageUri: string) => Promise<void>;
  pollDelayMs?: number;
};

function toImportRequest(
  payload: SharePayload,
  uploadId: string | null,
): ShareImportRequest {
  switch (payload.kind) {
    case 'url':
      return {kind: 'URL', sourceUrl: payload.url};
    case 'image':
      if (uploadId === null) {
        throw new Error('Upload ID required for image import');
      }
      return {kind: 'IMAGE', uploadId};
    case 'image_url':
      if (uploadId === null) {
        throw new Error('Upload ID required for image import');
      }
      return {
        kind: 'IMAGE_URL',
        sourceUrl: payload.url,
        uploadId,
      };
  }
}

// Bounded backoff: attempts 1..15 at 500ms base spend exactly the 60s
// resolution lease budget before the caller offers a retry.
const POLL_ATTEMPTS = 15;
const POLL_BASE_DELAY_MS = 500;
const STATUS_PATH = '/share/imports';

function createIdempotencyKey(): string {
  const crypto = (globalThis as {crypto?: {randomUUID?: () => string}})
    .crypto;
  if (typeof crypto?.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `gen-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(new Error('Aborted'));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, {once: true});
    if (signal.aborted) {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
      reject(new Error('Aborted'));
    }
  });
}

function isShareImportResponse(value: unknown): value is ShareImportResponse {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  if (typeof record.importId !== 'string') {
    return false;
  }
  switch (record.status) {
    case 'RESOLVING':
    case 'READY':
      return true;
    case 'NEEDS_IMAGE':
      return (
        record.reason === 'URL_RESOLUTION_FAILED' &&
        Array.isArray(record.recoveryActions) &&
        record.recoveryActions.length === 2 &&
        record.recoveryActions[0] === 'CHOOSE_SCREENSHOT' &&
        record.recoveryActions[1] === 'UPLOAD_IMAGE'
      );
    case 'FAILED_RETRYABLE':
      return (
        record.reason === 'DEPENDENCY_UNAVAILABLE' ||
        record.reason === 'RESOLUTION_TIMEOUT'
      );
    case 'FAILED_TERMINAL':
      return record.reason === 'INVALID_SOURCE';
    default:
      return false;
  }
}

function isTerminalUploadError(error: unknown): boolean {
  if (error instanceof ApiProblemError) {
    const match = /^HTTP_(\d{3})$/.exec(error.code);
    if (match) {
      const status = Number(match[1]);
      return status >= 400 && status < 500 && status !== 408 && status !== 429;
    }
    return error.code === 'UNSUPPORTED_STAGED_IMAGE_TYPE';
  }
  if (error instanceof Error) {
    const message = error.message.toLowerCase();
    return (
      message.includes('unsupported staged image type') ||
      message.includes('data uri')
    );
  }
  return false;
}

async function readStagedImage(
  localImageUri: string,
  signal: AbortSignal,
): Promise<{body: ArrayBuffer; contentType: string; contentLength: number}> {
  rejectDataUri(localImageUri);
  const response = await fetch(localImageUri, {signal});
  if (!response.ok) {
    throw new Error('Failed to read staged image');
  }
  const body = await response.arrayBuffer();
  const contentType = productContentType(
    localImageUri,
    response.headers.get('content-type'),
  );
  if (contentType === null) {
    throw new ApiProblemError('UNSUPPORTED_STAGED_IMAGE_TYPE');
  }
  return {body, contentType, contentLength: body.byteLength};
}

export function createShareImportResolver(
  config: AvatarApiConfig = productionApiConfig,
  deps: ShareImportClientDeps = {},
): ShareImportResolver {
  const release =
    deps.releaseStagedImage ??
    ((localImageUri: string) => releaseStagedImage(localImageUri));
  const pollDelayMs = deps.pollDelayMs ?? POLL_BASE_DELAY_MS;
  const idempotencyKey = createIdempotencyKey();
  // Resolver-scoped state: the resolver is created once per mounted payload,
  // so these live across invocations. The native staged file is gone after
  // the first release, and a retry must replay the original uploadId rather
  // than mint a new one (its fingerprint differs → 409 IDEMPOTENCY_KEY_REUSED).
  let retainedUploadId: string | null = null;
  let released = false;
  let stagedUri: string | null = null;
  const releaseOnce = () => {
    if (!released && stagedUri !== null) {
      released = true;
      void release(stagedUri);
    }
  };

  return async function resolve(
    payload: SharePayload,
    signal: AbortSignal,
  ): Promise<ShareImportResponse> {
    const isImageBearing = payload.kind !== 'url';
    if (isImageBearing && stagedUri === null) {
      stagedUri = payload.localImageUri;
    }
    if (signal.aborted) {
      releaseOnce();
      throw new Error('Aborted');
    }

    let uploadId = retainedUploadId;
    if (isImageBearing && uploadId === null) {
      // Releasing on abort guarantees the staged file never leaks if the user
      // cancels, unmounts, or navigates away.
      signal.addEventListener('abort', releaseOnce, {once: true});
      try {
        const media = await readStagedImage(payload.localImageUri, signal);
        uploadId = await uploadMedia(config, media);
      } catch (error) {
        // Release on abort or terminal failure; preserve staged media on
        // recoverable failures so UI retry can succeed without losing source.
        if (signal.aborted || isTerminalUploadError(error)) {
          releaseOnce();
        }
        throw error;
      }
      retainedUploadId = uploadId;
      // Verified server-side ownership transfer: release the native staged
      // file exactly once upon upload completion.
      releaseOnce();
      if (signal.aborted) {
        throw new Error('Aborted');
      }
    }
    const request = toImportRequest(payload, uploadId);

    if (signal.aborted) {
      throw new Error('Aborted');
    }
    const firstResponse = (await requireOk(
      await apiRequest(config, STATUS_PATH, {
        method: 'POST',
        body: JSON.stringify(request),
        extraHeaders: {'Idempotency-Key': idempotencyKey},
      }),
    )) as unknown;

    let latest: ShareImportResponse;
    if (isShareImportResponse(firstResponse)) {
      latest = firstResponse;
    } else {
      throw new Error('Invalid import response');
    }

    let attempts = 0;
    while (latest.status === 'RESOLVING' && attempts < POLL_ATTEMPTS) {
      attempts += 1;
      await sleep(pollDelayMs * attempts, signal);
      if (signal.aborted) {
        throw new Error('Aborted');
      }
      const pollResponse = (await requireOk(
        await apiRequest(config, `${STATUS_PATH}/${latest.importId}`, {
          method: 'GET',
        }),
      )) as unknown;
      if (isShareImportResponse(pollResponse)) {
        latest = pollResponse;
      } else {
        throw new Error('Invalid import response');
      }
    }
    return latest;
  };
}

function isConfirmedItem(value: unknown): value is ShareImportConfirmedItem {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.id === 'string' &&
    record.id.length > 0 &&
    typeof record.sourceReferenceId === 'string' &&
    record.sourceReferenceId.length > 0
  );
}

/**
 * Persists a resolved share import into the owner's Vault through the mounted
 * `POST /share/imports/{importId}/confirm` route. This is the only save path:
 * replaying resolution (`POST /share/imports`) can never persist an item, so
 * the caller must never report "saved" from a resolution response.
 *
 * The route is idempotent server-side (saved-item identity is derived from
 * owner + import), so a repeat — including a retry after a lost response —
 * converges on the same item rather than creating a duplicate.
 */
export function createShareImportConfirmer(
  config: AvatarApiConfig = productionApiConfig,
): ShareImportConfirmer {
  return async function confirm(
    importId: string,
    signal: AbortSignal,
    corrections?: ShareImportConfirmCorrections,
  ): Promise<ShareImportConfirmedItem> {
    if (signal.aborted) {
      throw new Error('Aborted');
    }
    // Only send the keys the owner actually corrected: an absent body and an
    // empty body both mean "confirm with the resolved metadata".
    const body: Record<string, string> = {};
    if (corrections?.name !== undefined) {
      body.name = corrections.name;
    }
    if (corrections?.category !== undefined) {
      body.category = corrections.category;
    }
    if (corrections?.collectionId !== undefined) {
      body.collectionId = corrections.collectionId;
    }
    const payload = (await requireOk(
      await apiRequest(
        config,
        `${STATUS_PATH}/${encodeURIComponent(importId)}/confirm`,
        Object.keys(body).length === 0
          ? {method: 'POST'}
          : {method: 'POST', body: JSON.stringify(body)},
      ),
    )) as unknown;
    if (!isConfirmedItem(payload)) {
      throw new Error('Invalid confirm response');
    }
    if (signal.aborted) {
      throw new Error('Aborted');
    }
    return {
      id: payload.id,
      sourceReferenceId: payload.sourceReferenceId,
      createdAt:
        typeof payload.createdAt === 'string' ? payload.createdAt : undefined,
    };
  };
}

function isPreview(value: unknown): value is ShareImportPreview {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.importId === 'string' &&
    typeof record.previewUrl === 'string' &&
    record.previewUrl.length > 0 &&
    typeof record.previewExpiresAt === 'string' &&
    !Number.isNaN(Date.parse(record.previewExpiresAt))
  );
}

/**
 * Owner-scoped preview of a resolved import. `previewUrl` is a short-lived
 * signed read (re-issue by calling again), never the item's identity.
 *
 * The caller treats a preview failure as "no preview", not as a failed import:
 * this route is presentation-only and must never block saving an item the
 * server has already resolved.
 */
export function createShareImportPreviewLoader(
  config: AvatarApiConfig = productionApiConfig,
): ShareImportPreviewLoader {
  return async function loadPreview(
    importId: string,
    signal: AbortSignal,
  ): Promise<ShareImportPreview> {
    if (signal.aborted) {
      throw new Error('Aborted');
    }
    const payload = (await requireOk(
      await apiRequest(
        config,
        `${STATUS_PATH}/${encodeURIComponent(importId)}/preview`,
        {method: 'GET'},
      ),
    )) as unknown;
    if (!isPreview(payload)) {
      throw new Error('Invalid preview response');
    }
    if (signal.aborted) {
      throw new Error('Aborted');
    }
    return {
      importId: payload.importId,
      previewUrl: payload.previewUrl,
      previewExpiresAt: payload.previewExpiresAt,
      sourceTitle:
        typeof payload.sourceTitle === 'string' ? payload.sourceTitle : undefined,
      sourceSiteName:
        typeof payload.sourceSiteName === 'string'
          ? payload.sourceSiteName
          : undefined,
    };
  };
}
