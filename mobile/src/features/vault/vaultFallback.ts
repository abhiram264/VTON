import type { LocalPhoto } from '../my-model/types';
import type { ShareImportResolver } from '../share/importTypes';

/**
 * Outcome of the degraded-save path.
 *
 * A READY import is NOT proof of a saved Vault item. ShareImportResponse
 * carries an importId, which lives in the share-import id space; a Vault item
 * id is only minted by POST /share/imports/{importId}/confirm. This path does
 * not call confirm, so it deliberately carries no item id: reporting one would
 * assert a durable state change the backend has not made.
 */
export type VaultSaveOutcome =
  | { outcome: 'saved' }
  | { outcome: 'not-saved'; reason: 'import-failed' | 'unsupported' };

export async function saveToVaultFallback(input: {
  photo: LocalPhoto;
  importer: ShareImportResolver;
  signal?: AbortSignal;
}): Promise<VaultSaveOutcome> {
  if (
    !input ||
    !input.photo ||
    typeof input.photo.uri !== 'string' ||
    input.photo.uri.trim().length === 0 ||
    input.photo.uri.startsWith('data:') ||
    typeof input.importer !== 'function'
  ) {
    return { outcome: 'not-saved', reason: 'unsupported' };
  }

  if (input.signal?.aborted) {
    return { outcome: 'not-saved', reason: 'import-failed' };
  }

  const controller = input.signal ? undefined : new AbortController();
  const signal = input.signal ?? controller!.signal;

  try {
    const response = await input.importer(
      { kind: 'image', localImageUri: input.photo.uri },
      signal,
    );
    if (
      response &&
      response.status === 'READY' &&
      typeof response.importId === 'string' &&
      response.importId.trim().length > 0
    ) {
      // The import resolved; it is NOT yet a Vault item until confirm runs.
      return { outcome: 'saved' };
    }
    return { outcome: 'not-saved', reason: 'import-failed' };
  } catch {
    return { outcome: 'not-saved', reason: 'import-failed' };
  }
}
