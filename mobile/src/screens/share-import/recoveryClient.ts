import {apiRequest, rejectDataUri, requireOk, uploadMedia} from '../../features/my-model/http';
import {productContentType} from '../../features/generation/productUpload';
import type {AvatarApiConfig} from '../../features/my-model/types';
import type {LocalPhoto} from '../../features/my-model/types';
import type {ShareImportResponse} from '../../features/share/importTypes';

const STATUS_PATH = '/share/imports';

// Mirrors the frozen response validation in importClient.ts (not exported
// there) and binds the response to the import whose image was attached.
function isShareImportResponse(
  value: unknown,
  expectedImportId: string,
): value is ShareImportResponse {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  if (
    typeof record.importId !== 'string' ||
    record.importId.trim().length === 0 ||
    record.importId !== expectedImportId
  ) {
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

// Reuses the established photo read (GENERIC-purpose upload boundary) and
// attaches the verified upload to the existing import: recovery never
// creates a second import. Returns the parsed response so the caller can
// distinguish READY (200) from a still-resolving accept (202 RESOLVING).
// No URI or bytes are logged (ADR-015).
export async function attachRecoveryImage(
  config: AvatarApiConfig,
  importId: string,
  photo: LocalPhoto,
  signal: AbortSignal,
): Promise<ShareImportResponse> {
  rejectDataUri(photo.uri);
  const response = await fetch(photo.uri, {signal});
  if (!response.ok) {
    throw new Error('Could not read the chosen image');
  }
  const body = await response.arrayBuffer();
  const contentType = productContentType(
    photo.uri,
    response.headers.get('content-type'),
  );
  if (contentType === null) {
    throw new Error('Only JPEG or PNG images are supported');
  }
  const uploadId = await uploadMedia(config, {
    body,
    contentType,
    contentLength: body.byteLength,
  });
  if (signal.aborted) {
    throw new Error('Aborted');
  }
  const payload = await requireOk(
    await apiRequest(config, `${STATUS_PATH}/${encodeURIComponent(importId)}/image`, {
      method: 'PUT',
      body: JSON.stringify({uploadId} satisfies {uploadId: string}),
      extraHeaders: {'Idempotency-Key': recoveryIdempotencyKey(importId)},
    }),
  );
  if (!isShareImportResponse(payload, importId)) {
    throw new Error('Invalid import response');
  }
  return payload;
}

// One recovery idempotency key per import for the process lifetime: genuine
// retries/replays must present the same key so the backend can return the
// stored result instead of minting a second recovery (spec: replay binding).
const recoveryKeys = new Map<string, string>();

function recoveryIdempotencyKey(importId: string): string {
  const existing = recoveryKeys.get(importId);
  if (existing !== undefined) {
    return existing;
  }
  const created = createIdempotencyKey();
  recoveryKeys.set(importId, created);
  return created;
}

function createIdempotencyKey(): string {
  const crypto = (globalThis as {crypto?: {randomUUID?: () => string}})
    .crypto;
  if (typeof crypto?.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `rec-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 12)}`;
}
