import type {
  GenerationApi,
  GenerationResource,
  ProductPicker,
  ProductUploader,
} from './types';
import { createIdempotencyKey, pollGeneration } from './generationApi';
import { ProductRejectedError } from './productUpload';
import {
  clearActiveGeneration,
  getActiveGeneration,
  isPollPhaseFailure,
  markPollPhaseFailure,
  setActiveGeneration,
} from './activeGeneration';
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
      retryable?: boolean;
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
// If an active generation exists after a poll-phase failure, retry resumes
// polling that generation instead of re-uploading or minting a new request.
function handleGenerationError(error: unknown): CreateLookOutcome {
  if (error instanceof ProductRejectedError) {
    return { outcome: 'failed', reason: 'rejected', code: error.code };
  }
  if (error instanceof ApiProblemError) {
    const isRetryableOutcome = error.code === 'CREATE_OUTCOME_UNKNOWN';
    return {
      outcome: 'failed',
      reason: 'error',
      code: error.code,
      ...(isRetryableOutcome ? { networkLevel: true } : {}),
    };
  }
  if (
    error &&
    typeof error === 'object' &&
    'code' in error &&
    error.code === 'CREATE_OUTCOME_UNKNOWN'
  ) {
    return {
      outcome: 'failed',
      reason: 'error',
      code: 'CREATE_OUTCOME_UNKNOWN',
      networkLevel: true,
    };
  }
  if (isNetworkError(error)) {
    return { outcome: 'failed', reason: 'error', networkLevel: true };
  }
  return { outcome: 'failed', reason: 'error' };
}
function isCreateOutcomeUnknown(error: unknown): boolean {
  if (error instanceof ApiProblemError) {
    if (error.code === 'CREATE_OUTCOME_UNKNOWN') {
      return true;
    }
    const match = /^HTTP_(\d{3})$/.exec(error.code);
    return match !== null && Number(match[1]) >= 500;
  }
  return isNetworkError(error);
}

async function resolveGeneration(
  api: Pick<GenerationApi, 'get' | 'getLook'>,
  generationId: string,
  onUpdate?: (generation: GenerationResource) => void,
  delay?: (ms: number) => Promise<void>,
  hint?: { current: boolean },
  signal?: AbortSignal,
): Promise<CreateLookOutcome> {
  const final = await pollGeneration({
    get: api.get,
    id: generationId,
    onUpdate,
    delay,
    hint,
    signal,
  });
  if (final.status !== 'COMPLETED' || final.lookId == null) {
    clearActiveGeneration();
    return {
      outcome: 'failed',
      reason: 'error',
      code: final.failureCode,
    };
  }
  clearActiveGeneration();
  const look = await api.getLook(final.lookId);
  return { outcome: 'ready', lookId: look.id };
}

export async function createLook(input: {
  picker: ProductPicker;
  upload: ProductUploader;
  // The create/poll flow only needs these three: recovery (list) and
  // cancellation are separate lifecycle operations, so a caller does not have
  // to implement them to run a generation.
  api: Pick<GenerationApi, 'create' | 'get' | 'getLook'>;
  onUpdate?: (generation: GenerationResource) => void;
  delay?: (ms: number) => Promise<void>;
  hint?: { current: boolean };
  photo?: LocalPhoto;
  signal?: AbortSignal;
}): Promise<CreateLookOutcome> {
  if (input.signal?.aborted) {
    throw new Error('Aborted');
  }
  const activeId = getActiveGeneration();
  if (activeId && isPollPhaseFailure()) {
    try {
      return await resolveGeneration(
        input.api,
        activeId,
        input.onUpdate,
        input.delay,
        input.hint,
        input.signal,
      );
    } catch (error) {
      if (
        (error as { message?: string })?.message === 'Aborted' ||
        input.signal?.aborted
      ) {
        markPollPhaseFailure();
        throw error;
      }
      markPollPhaseFailure();
      return handleGenerationError(error);
    }
  }

  const photo = input.photo ?? (await input.picker());
  if (!photo) {
    return { outcome: 'cancelled' };
  }
  if (input.signal?.aborted) {
    throw new Error('Aborted');
  }
  let createStarted = false;
  try {
    const productUploadId = await input.upload(photo);
    if (input.signal?.aborted) {
      throw new Error('Aborted');
    }
    createStarted = true;
    const generation = await input.api.create(
      productUploadId,
      createIdempotencyKey(),
    );
    setActiveGeneration(generation.id, false);
    return await resolveGeneration(
      input.api,
      generation.id,
      input.onUpdate,
      input.delay,
      input.hint,
      input.signal,
    );
  } catch (error) {
    if (
      (error as { message?: string })?.message === 'Aborted' ||
      input.signal?.aborted
    ) {
      if (getActiveGeneration() != null) {
        markPollPhaseFailure();
      }
      throw error;
    }
    if (createStarted && getActiveGeneration() == null && isCreateOutcomeUnknown(error)) {
      const outcome = handleGenerationError(error);
      return outcome.outcome === 'failed'
        ? {...outcome, retryable: false}
        : outcome;
    }
    if (getActiveGeneration() != null) {
      markPollPhaseFailure();
    }
    return handleGenerationError(error);
  }
}
