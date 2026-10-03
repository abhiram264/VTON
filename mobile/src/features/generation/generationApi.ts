import { AppState } from 'react-native';
import type {
  CoarseGenerationPhase,
  GenerationApi,
  GenerationApiConfig,
  GenerationResource,
  GenerationStatus,
  LookResource,
} from './types';
import { apiRequest, requireOk } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';

const GENERATION_STATUSES: readonly GenerationStatus[] = [
  'QUEUED',
  'PREFLIGHT',
  'SUBMITTED',
  'GENERATING',
  'QUALITY_CHECK',
  'RETRYING',
  'COMPLETED',
  'FAILED',
];

const GENERATION_PHASES: readonly CoarseGenerationPhase[] = [
  'QUEUED',
  'PREPARING',
  'GENERATING',
  'CHECKING_QUALITY',
  'READY',
  'FAILED',
];

export function isTerminalGeneration(generation: GenerationResource): boolean {
  return generation.status === 'COMPLETED' || generation.status === 'FAILED';
}

function parseGeneration(value: unknown): GenerationResource {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid generation response');
  }
  const row = value as Record<string, unknown>;
  const status = row.status;
  const phase = row.phase;
  if (
    typeof status !== 'string' ||
    !GENERATION_STATUSES.includes(status as GenerationStatus)
  ) {
    throw new Error('Unknown generation status');
  }
  if (
    typeof phase !== 'string' ||
    !GENERATION_PHASES.includes(phase as CoarseGenerationPhase)
  ) {
    throw new Error('Unknown generation phase');
  }
  return {
    id: String(row.id),
    status: status as GenerationStatus,
    phase: phase as CoarseGenerationPhase,
    statusVersion: Number(row.statusVersion),
    createdAt: String(row.createdAt),
    updatedAt: String(row.updatedAt),
    ...(row.lookId == null ? null : { lookId: String(row.lookId) }),
    ...(row.failureCode == null
      ? null
      : { failureCode: String(row.failureCode) }),
  };
}

function parseLook(value: unknown): LookResource {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid look response');
  }
  const row = value as Record<string, unknown>;
  const image = row.image;
  if (image === null || typeof image !== 'object') {
    throw new Error('Invalid look response');
  }
  const imageRow = image as Record<string, unknown>;
  const url = imageRow.url;
  const expiresAt = imageRow.expiresAt;
  if (typeof url !== 'string' || typeof expiresAt !== 'string') {
    throw new Error('Invalid look response');
  }
  return {
    id: String(row.id),
    generationId: String(row.generationId),
    createdAt: String(row.createdAt),
    image: { url, expiresAt },
  };
}

export function createGenerationApi(
  config: GenerationApiConfig,
): GenerationApi {
  return {
    async create(productUploadId, idempotencyKey) {
      if (idempotencyKey.trim().length === 0) {
        throw new Error('Idempotency key is required');
      }
      const payload = await requireOk(
        await apiRequest(config, '/generations', {
          method: 'POST',
          body: JSON.stringify({ productUploadId }),
          extraHeaders: { 'Idempotency-Key': idempotencyKey },
        }),
      );
      return parseGeneration(payload);
    },

    async get(id) {
      const payload = await requireOk(
        await apiRequest(config, `/generations/${id}`, { method: 'GET' }),
      );
      return parseGeneration(payload);
    },

    async getLook(id) {
      const payload = await requireOk(
        await apiRequest(config, `/looks/${id}`, { method: 'GET' }),
      );
      return parseLook(payload);
    },

    async list() {
      const payload = await requireOk(
        await apiRequest(config, '/generations', { method: 'GET' }),
      );
      if (!Array.isArray(payload)) {
        throw new Error('Invalid generation list response');
      }
      return payload.map(parseGeneration);
    },

    async cancel(id) {
      const payload = await requireOk(
        await apiRequest(config, `/generations/${id}/cancel`, {
          method: 'POST',
        }),
      );
      return parseGeneration(payload);
    },
  };
}

export const generationApi = createGenerationApi(productionApiConfig);

export function createIdempotencyKey(): string {
  const crypto = (globalThis as { crypto?: { randomUUID?: () => string } })
    .crypto;
  if (typeof crypto?.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `gen-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 12)}`;
}

// Canonical status is the only truth. Push notifications may set
// `hint.current = true` to skip one wait; a lost, late, duplicated, or
// reordered push never stops polling.
// A poll is always finite. Without a bound the loop's only exits are an
// undefined bound or a caller-supplied signal, so a generation that never
// reaches a terminal status pins the caller's busy lock for the life of the
// process. The default bound applies to every production call site and is
// derived from the SERVER's own budget, not from observed latency. A job is
// allowed ORCHESTRATION_DEADLINE = 30 minutes (GenerationJob.java:61,
// V013:10), and the engine permits MAX_TECHNICAL_RETRIES_PER_JOB = 2 retries,
// i.e. 3 transmissions (GenerationRetryBudget.java:9), each bounded by the
// adapter read timeout of 300s (InferHubConfig.java:32). The client must not
// give up before the server does: a slow-but-successful generation reported as
// a client-side failure is worse than a long wait, and the poll is still
// cancellable through the caller's signal. So the bound is the full server
// deadline plus a minute of margin to observe a job that finishes right at it.
// 930 attempts = 1_860_000ms at the 2000ms interval.
export const POLL_DEADLINE_MS = 1_860_000;
export const POLL_MAX_ATTEMPTS = 930;

/**
 * Whether the app is in the foreground, as an injectable seam.
 *
 * C14 requires foreground polling to pause appropriately. Polling a job every
 * couple of seconds while the app is backgrounded burns the radio and battery
 * for a screen nobody is looking at, so the poll loop parks until the app is
 * active again. The DEADLINE is deliberately NOT extended by the pause: the
 * bound is the server's own orchestration budget, and a client that kept
 * waiting past it would report success after the server had already given up.
 */
export type ForegroundMonitor = {
  isForeground: () => boolean;
  subscribe: (listener: (foreground: boolean) => void) => () => void;
};

export function createAppStateForegroundMonitor(): ForegroundMonitor {
  return {
    // Only a state that says the app is NOT visible parks the poll loop. An
    // unknown state (RN's AppState starts as null before the first native
    // callback, and a headless/test runtime never receives one) must not stall
    // a legitimate poll — "I cannot tell" is not "the user left".
    isForeground: () =>
      AppState.currentState !== 'background' &&
      AppState.currentState !== 'inactive',
    subscribe: listener => {
      const subscription = AppState.addEventListener('change', state =>
        listener(state !== 'background' && state !== 'inactive'),
      );
      return () => subscription.remove();
    },
  };
}

/** Resolves on the next foreground transition; rejects when aborted. */
function waitForForeground(
  monitor: ForegroundMonitor,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    let unsubscribe: (() => void) | null = null;
    const onAbort = () => {
      unsubscribe?.();
      reject(new Error('Aborted'));
    };
    unsubscribe = monitor.subscribe(foreground => {
      if (!foreground) {
        return;
      }
      unsubscribe?.();
      signal?.removeEventListener('abort', onAbort);
      resolve();
    });
    signal?.addEventListener('abort', onAbort, {once: true});
    if (signal?.aborted) {
      onAbort();
    }
  });
}

export async function pollGeneration(options: {
  get: (id: string) => Promise<GenerationResource>;
  id: string;
  intervalMs?: number;
  delay?: (ms: number) => Promise<void>;
  onUpdate?: (generation: GenerationResource) => void;
  hint?: { current: boolean };
  signal?: AbortSignal;
  deadlineMs?: number;
  maxAttempts?: number;
  /** When supplied, polling parks while the app is backgrounded. */
  foreground?: ForegroundMonitor;
}): Promise<GenerationResource> {
  if (options.signal?.aborted) {
    throw new Error('Aborted');
  }
  const delay =
    options.delay ??
    ((ms: number) =>
      new Promise<void>((resolve, reject) => {
        let timer: ReturnType<typeof setTimeout>;
        const onAbort = () => {
          clearTimeout(timer);
          options.signal?.removeEventListener('abort', onAbort);
          reject(new Error('Aborted'));
        };
        timer = setTimeout(() => {
          options.signal?.removeEventListener('abort', onAbort);
          resolve();
        }, ms);
        options.signal?.addEventListener('abort', onAbort, {once: true});
        if (options.signal?.aborted) {
          clearTimeout(timer);
          options.signal.removeEventListener('abort', onAbort);
          reject(new Error('Aborted'));
        }
      }));
  const intervalMs = options.intervalMs ?? 2000;
  const startedAt = Date.now();
  let attempts = 0;
  for (;;) {
    if (options.signal?.aborted) {
      throw new Error('Aborted');
    }
    if (
      (options.deadlineMs ?? POLL_DEADLINE_MS) - (Date.now() - startedAt) <= 0
    ) {
      throw new Error('Generation polling deadline exceeded');
    }
    if (attempts >= (options.maxAttempts ?? POLL_MAX_ATTEMPTS)) {
      throw new Error('Generation polling maximum attempts exceeded');
    }
    if (options.foreground && !options.foreground.isForeground()) {
      // Park until the app is active again. The deadline above is re-checked on
      // the next iteration, so a long background stay cannot extend the bound.
      await waitForForeground(options.foreground, options.signal);
      continue;
    }
    attempts += 1;
    const generation = await options.get(options.id);
    options.onUpdate?.(generation);
    if (isTerminalGeneration(generation)) {
      return generation;
    }
    if (options.signal?.aborted) {
      throw new Error('Aborted');
    }
    if (options.hint?.current === true) {
      options.hint.current = false;
      continue;
    }
    await delay(intervalMs);
  }
}
