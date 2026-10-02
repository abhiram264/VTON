/**
 * Restart recovery and cancellation for generations.
 *
 * RECOVERY SOURCE OF TRUTH: the server's owner-scoped list. The mobile app
 * ships no durable key/value store (package.json has no storage dependency),
 * so a process-local variable cannot survive a restart, and the only
 * account-scoped persistence this app actually has is the server. Recovery
 * therefore reads `GET /generations` rather than an in-memory
 * "last generation" variable; activeGeneration.ts stays a same-process fast
 * path only.
 *
 * HONESTY RULE: "no active job" and "could not ask the server" are different
 * facts and must not collapse into one. A failed lookup is reported as
 * `unavailable`, never as "nothing in flight", because the latter would let a
 * still-running (and already charged) job look abandoned.
 */
import type { GenerationApi, GenerationResource } from './types';
import { isTerminalGeneration } from './generationApi';

export type GenerationRecovery =
  | { kind: 'active'; generation: GenerationResource }
  | { kind: 'none' }
  | { kind: 'unavailable' };

/**
 * The most recent non-terminal generation for the signed-in owner, or `none`.
 *
 * The server returns newest first, so the first non-terminal entry is the
 * newest in-flight job. A failed or malformed lookup is `unavailable`, not
 * `none`.
 */
export async function recoverActiveGeneration(
  api: Pick<GenerationApi, 'list'>,
): Promise<GenerationRecovery> {
  let jobs: GenerationResource[];
  try {
    jobs = await api.list();
  } catch {
    return { kind: 'unavailable' };
  }
  if (!Array.isArray(jobs)) {
    return { kind: 'unavailable' };
  }
  const active = jobs.find(
    job => job !== null && typeof job === 'object' && !isTerminalGeneration(job),
  );
  return active ? { kind: 'active', generation: active } : { kind: 'none' };
}

export type CancellationOutcome =
  | { outcome: 'cancelled'; generation: GenerationResource }
  | { outcome: 'cancel-requested'; generation: GenerationResource }
  | { outcome: 'not-cancellable' }
  | { outcome: 'failed'; code?: string };

export const CANCELLED_BY_USER = 'CANCELLED_BY_USER';
export const GENERATION_NOT_CANCELLABLE = 'GENERATION_NOT_CANCELLABLE';

function problemCode(error: unknown): string | null {
  if (error !== null && typeof error === 'object' && 'code' in error) {
    const code = error.code;
    return typeof code === 'string' ? code : null;
  }
  return null;
}

/**
 * Cancellation reports what the server actually reached, so "the user asked"
 * is never presented as "the provider stopped".
 *
 * - `cancelled` — the server reports the terminal CANCELLED_BY_USER state.
 * - `cancel-requested` — the server accepted the request while the job is
 *   still non-terminal; the provider has not physically stopped yet.
 * - `not-cancellable` — the job was already terminal (or never active).
 * - `failed` — the request itself failed; nothing is claimed about the job.
 *
 * While the returned promise is pending the caller is in the
 * "cancel requested" state, which is exactly what the UI should say.
 */
export async function requestCancellation(
  api: Pick<GenerationApi, 'cancel'>,
  generationId: string,
): Promise<CancellationOutcome> {
  let generation: GenerationResource;
  try {
    generation = await api.cancel(generationId);
  } catch (error) {
    const code = problemCode(error);
    if (code === GENERATION_NOT_CANCELLABLE) {
      return { outcome: 'not-cancellable' };
    }
    return code === null ? { outcome: 'failed' } : { outcome: 'failed', code };
  }
  if (
    isTerminalGeneration(generation) &&
    generation.failureCode === CANCELLED_BY_USER
  ) {
    return { outcome: 'cancelled', generation };
  }
  return { outcome: 'cancel-requested', generation };
}
