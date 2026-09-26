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
// process. Same two values GenerateScreen already passes (deadlineMs and
// maxAttempts), now applied by default instead of per call site.
const POLL_DEADLINE_MS = 60_000;
const POLL_MAX_ATTEMPTS = 15;

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
}): Promise<GenerationResource> {
  if (options.signal?.aborted) {
    throw new Error('Aborted');
  }
  const delay =
    options.delay ??
    ((ms: number) =>
      new Promise<void>((resolve, reject) => {
        let timer: number;
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
