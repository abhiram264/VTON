import {
  apiRequest,
  rejectDataUri,
  requireOk,
  uploadMedia,
} from '../my-model/http';
import {productContentType} from '../generation/productUpload';
import {releaseStagedImage} from './shareHandoff';
import type {AvatarApiConfig} from '../my-model/types';
import type {
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
      return {kind: 'IMAGE', uploadId: uploadId ?? ''};
    case 'image_url':
      return {
        kind: 'IMAGE_URL',
        sourceUrl: payload.url,
        uploadId: uploadId ?? '',
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
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer);
        reject(new Error('Aborted'));
      },
      {once: true},
    );
    if (signal.aborted) {
      clearTimeout(timer);
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
    throw new Error('Unsupported staged image type');
  }
  return {body, contentType, contentLength: body.byteLength};
}

export function createShareImportResolver(
  config: AvatarApiConfig,
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
    if (signal.aborted) {
      releaseOnce();
      throw new Error('Aborted');
    }

    let uploadId = retainedUploadId;
    if (isImageBearing && uploadId === null) {
      stagedUri = payload.localImageUri;
      // Cancellation or any pre-completion failure must still release the
      // native staged file exactly once per resolver instance.
      signal.addEventListener('abort', releaseOnce, {once: true});
      try {
        const media = await readStagedImage(payload.localImageUri, signal);
        uploadId = await uploadMedia(config, media);
      } catch (error) {
        releaseOnce();
        throw error;
      }
      retainedUploadId = uploadId;
      // Verified server-side ownership transfer: release the native staged
      // file exactly once, even if resolution later fails or needs recovery.
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
