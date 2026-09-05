import { saveToVaultFallback } from './vaultFallback';
import type { LocalPhoto } from '../my-model/types';
import type { ShareImportResolver, ShareImportResponse } from '../share/importTypes';

describe('saveToVaultFallback', () => {
  const validPhoto: LocalPhoto = { uri: 'file:///path/to/item.jpg' };

  it('returns unsupported when photo is missing or invalid', async () => {
    const importer = jest.fn();
    expect(
      await saveToVaultFallback({ photo: null as unknown as LocalPhoto, importer }),
    ).toEqual({ outcome: 'not-saved', reason: 'unsupported' });

    expect(
      await saveToVaultFallback({ photo: { uri: '' }, importer }),
    ).toEqual({ outcome: 'not-saved', reason: 'unsupported' });

    expect(
      await saveToVaultFallback({ photo: { uri: 'data:image/png;base64,abc' }, importer }),
    ).toEqual({ outcome: 'not-saved', reason: 'unsupported' });

    expect(importer).not.toHaveBeenCalled();
  });

  it('returns unsupported when importer is not a function', async () => {
    expect(
      await saveToVaultFallback({
        photo: validPhoto,
        importer: null as unknown as ShareImportResolver,
      }),
    ).toEqual({ outcome: 'not-saved', reason: 'unsupported' });
  });

  it('returns not-saved when signal is already aborted', async () => {
    const importer = jest.fn();
    const controller = new AbortController();
    controller.abort();

    expect(
      await saveToVaultFallback({
        photo: validPhoto,
        importer,
        signal: controller.signal,
      }),
    ).toEqual({ outcome: 'not-saved', reason: 'import-failed' });

    expect(importer).not.toHaveBeenCalled();
  });

  it('calls importer with kind image and localImageUri exactly once', async () => {
    const importer = jest.fn().mockResolvedValue({
      status: 'READY',
      importId: 'vault-item-123',
    } as ShareImportResponse);

    const result = await saveToVaultFallback({
      photo: validPhoto,
      importer,
    });

    expect(importer).toHaveBeenCalledTimes(1);
    expect(importer).toHaveBeenCalledWith(
      { kind: 'image', localImageUri: validPhoto.uri },
      expect.any(Object),
    );
    expect(result).toEqual({ outcome: 'saved', vaultItemId: 'vault-item-123' });
  });

  it('rejects READY with blank importId', async () => {
    const importer = jest.fn().mockResolvedValue({
      status: 'READY',
      importId: '   ',
    } as ShareImportResponse);

    const result = await saveToVaultFallback({
      photo: validPhoto,
      importer,
    });

    expect(result).toEqual({ outcome: 'not-saved', reason: 'import-failed' });
  });

  it('returns not-saved without throwing when importer returns non-READY statuses', async () => {
    const nonReadyResponses: ShareImportResponse[] = [
      { importId: 'imp-1', status: 'RESOLVING' },
      {
        importId: 'imp-2',
        status: 'NEEDS_IMAGE',
        reason: 'URL_RESOLUTION_FAILED',
        recoveryActions: ['CHOOSE_SCREENSHOT', 'UPLOAD_IMAGE'],
      },
      {
        importId: 'imp-3',
        status: 'FAILED_RETRYABLE',
        reason: 'DEPENDENCY_UNAVAILABLE',
      },
      {
        importId: 'imp-4',
        status: 'FAILED_TERMINAL',
        reason: 'INVALID_SOURCE',
      },
    ];

    for (const response of nonReadyResponses) {
      const importer = jest.fn().mockResolvedValue(response);
      const result = await saveToVaultFallback({
        photo: validPhoto,
        importer,
      });
      expect(result).toEqual({ outcome: 'not-saved', reason: 'import-failed' });
    }
  });

  it('returns not-saved without hanging or throwing when importer throws', async () => {
    const importer = jest.fn().mockRejectedValue(new Error('Network offline'));
    const result = await saveToVaultFallback({
      photo: validPhoto,
      importer,
    });
    expect(result).toEqual({ outcome: 'not-saved', reason: 'import-failed' });
  });
});
