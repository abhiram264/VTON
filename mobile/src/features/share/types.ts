// Frozen seam: TS mirror of the native SharePayload discriminated union.
// Do not widen, rename, or reorder — the native sides are byte-for-byte
// contractually identical (see .scratch/android-share-target-spec.md and
// .scratch/ios-share-extension-spec.md).
export type SharePayload =
  | { kind: 'url'; url: string; localImageUri?: never; sourcePackage?: string }
  | {
      kind: 'image';
      url?: never;
      localImageUri: string;
      sourcePackage?: string;
    }
  | {
      kind: 'image_url';
      url: string;
      localImageUri: string;
      sourcePackage?: string;
    };

// Frozen seam: typed error codes surfaced by the native consumeShare bridge.
export type ConsumeShareErrorCode =
  | 'invalid_share_id'
  | 'share_not_found'
  | 'share_expired'
  | 'invalid_payload';

// Frozen seam: result of consuming a staged share, verbatim from the spec.
export type ConsumeShareResult =
  | { ok: true; payload: SharePayload }
  | { ok: false; code: ConsumeShareErrorCode };

// Frozen seam: native module contract under the share-handoff namespace.
// Recorded seam amendment: releaseStagedImage is added beyond the spec's
// "one asynchronous method" handoff seam because the spec's own
// cleanup-ownership rule transfers staged-file deletion to the Share Import
// route on cancellation, which requires a native release call.
export interface ShareHandoffNativeModule {
  consumeShare(shareId: string): Promise<ConsumeShareResult>;
  releaseStagedImage(localImageUri: string): Promise<void>;
}

// JS-side name of the native module (confirmed by the native unit).
export const SHARE_HANDOFF_MODULE_NAME = 'ShareHandoff';

// Only lowercase canonical UUIDs are accepted in a handoff URI. Query
// strings, extra segments, uppercase hex, and other hosts are rejected.
export const SHARE_HANDOFF_URI_PATTERN =
  /^aifashionvault:\/\/share-import\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;

export function extractShareId(uri: string): string | null {
  const match = SHARE_HANDOFF_URI_PATTERN.exec(uri);
  return match ? match[1] : null;
}

// Runtime validation mirrors the frozen union: each kind demands its own
// fields, forbids the others, allows nothing beyond the union, and rejects
// null where the type says optional (absent, never null).
export function validateSharePayload(value: unknown): SharePayload | null {
  if (typeof value !== 'object' || value === null) return null;
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record);
  const permitted = ['kind', 'url', 'localImageUri', 'sourcePackage'];
  for (const key of keys) {
    if (!permitted.includes(key)) return null;
  }
  if (typeof record.kind !== 'string') return null;
  const sourcePackageOk =
    record.sourcePackage === undefined ||
    typeof record.sourcePackage === 'string';
  if (!sourcePackageOk) return null;
  switch (record.kind) {
    case 'url':
      if (typeof record.url !== 'string') return null;
      if ('localImageUri' in record) return null;
      return { kind: 'url', url: record.url, ...includeSource(record) };
    case 'image':
      if (typeof record.localImageUri !== 'string') return null;
      if ('url' in record) return null;
      return {
        kind: 'image',
        localImageUri: record.localImageUri,
        ...includeSource(record),
      };
    case 'image_url':
      if (typeof record.url !== 'string') return null;
      if (typeof record.localImageUri !== 'string') return null;
      return {
        kind: 'image_url',
        url: record.url,
        localImageUri: record.localImageUri,
        ...includeSource(record),
      };
    default:
      return null;
  }
}

function includeSource(
  record: Record<string, unknown>,
): { sourcePackage: string } | Record<string, never> {
  if (typeof record.sourcePackage === 'string') {
    return { sourcePackage: record.sourcePackage };
  }
  return {};
}
