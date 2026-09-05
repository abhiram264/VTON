import type { LocalPhoto } from '../my-model/types';
import type { ShareImportResolver } from '../share/importTypes';

export type VaultSaveOutcome =
  | { outcome: 'saved'; vaultItemId: string }
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
      return { outcome: 'saved', vaultItemId: response.importId.trim() };
    }
    return { outcome: 'not-saved', reason: 'import-failed' };
  } catch {
    return { outcome: 'not-saved', reason: 'import-failed' };
  }
}
