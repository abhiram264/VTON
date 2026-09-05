import type {
  GenerationApi,
  GenerationResource,
  ProductPicker,
  ProductUploader,
} from './types';
import { createIdempotencyKey, pollGeneration } from './generationApi';
import { ProductRejectedError } from './productUpload';
import { setActiveGeneration } from './activeGeneration';
import { ApiProblemError } from '../my-model/http';
import type { LocalPhoto } from '../my-model/types';

export type CreateLookOutcome =
  | { outcome: 'cancelled' }
  | { outcome: 'ready'; lookId: string }
  | {
      outcome: 'failed';
      reason: 'rejected' | 'error';
      code?: string;
      networkLevel?: boolean;
    };

export function isNetworkError(error: unknown): boolean {
  if (!error || typeof error !== 'object') {
    return false;
  }
  const err = error as { name?: string; message?: string };
  const isTypeErr = err.name === 'TypeError' || error instanceof TypeError;
  if (!isTypeErr) {
    return false;
  }
  const msg = (err.message ?? '').toLowerCase();
  return (
    msg.includes('network') ||
    msg.includes('fetch') ||
    msg.includes('load failed')
  );
}

// One manual product, one My Model, one asynchronous generation. Picker
// cancellation happens first and performs no network call at all.
export async function createLook(input: {
  picker: ProductPicker;
  upload: ProductUploader;
  api: GenerationApi;
  onUpdate?: (generation: GenerationResource) => void;
  delay?: (ms: number) => Promise<void>;
  hint?: { current: boolean };
  photo?: LocalPhoto;
}): Promise<CreateLookOutcome> {
  const photo = input.photo ?? (await input.picker());
  if (!photo) {
    return { outcome: 'cancelled' };
  }
  try {
    const productUploadId = await input.upload(photo);
    const generation = await input.api.create(
      productUploadId,
      createIdempotencyKey(),
    );
    setActiveGeneration(generation.id);
    const final = await pollGeneration({
      get: input.api.get,
      id: generation.id,
      onUpdate: input.onUpdate,
      delay: input.delay,
      hint: input.hint,
    });
    if (final.status !== 'COMPLETED' || final.lookId == null) {
      return {
        outcome: 'failed',
        reason: 'error',
        code: final.failureCode,
      };
    }
    const look = await input.api.getLook(final.lookId);
    return { outcome: 'ready', lookId: look.id };
  } catch (error) {
    if (error instanceof ProductRejectedError) {
      return { outcome: 'failed', reason: 'rejected', code: error.code };
    }
    if (error instanceof ApiProblemError) {
      return { outcome: 'failed', reason: 'error', code: error.code };
    }
    if (isNetworkError(error)) {
      return { outcome: 'failed', reason: 'error', networkLevel: true };
    }
    return { outcome: 'failed', reason: 'error' };
  }
}
