import { apiRequest, requireOk } from '../my-model/http';
import { createIdempotencyKey } from '../generation/generationApi';
import type { AvatarApiConfig } from '../my-model/types';
import type {
  CoarseGenerationPhase,
  GenerationResource,
  GenerationStatus,
  LookResource,
} from '../generation/types';

function parseLook(value: unknown): LookResource {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Look response');
  const row = value as Record<string, unknown>;
  const image = row.image as Record<string, unknown> | undefined;
  if (typeof row.id !== 'string' || typeof row.generationId !== 'string' || typeof row.createdAt !== 'string' || !image || typeof image.url !== 'string' || typeof image.expiresAt !== 'string') throw new Error('Invalid Look response');
  return { id: row.id, generationId: row.generationId, createdAt: row.createdAt, image: { url: image.url, expiresAt: image.expiresAt } };
}

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

function parseGeneration(value: unknown): GenerationResource {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid generation response');
  }
  const row = value as Record<string, unknown>;
  const status = row.status;
  const phase = row.phase;
  if (
    typeof row.id !== 'string' ||
    typeof status !== 'string' ||
    !GENERATION_STATUSES.includes(status as GenerationStatus) ||
    typeof phase !== 'string' ||
    !GENERATION_PHASES.includes(phase as CoarseGenerationPhase)
  ) {
    throw new Error('Invalid generation response');
  }
  return {
    id: row.id,
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
const defaultConfig: AvatarApiConfig = { baseUrl: '', getAccessToken: () => null };

export function createLooksApi(config: AvatarApiConfig = defaultConfig) {
  return {
    async list(): Promise<LookResource[]> {
      const value = await requireOk(
        await apiRequest(config, '/looks', { method: 'GET' }),
      );
      if (!Array.isArray(value)) throw new Error('Invalid Looks response');
      return value.map(parseLook);
    },
    async regenerate(lookId: string): Promise<GenerationResource> {
      const payload = await requireOk(
        await apiRequest(
          config,
          `/looks/${encodeURIComponent(lookId)}/regenerate`,
          {
            method: 'POST',
            extraHeaders: { 'Idempotency-Key': createIdempotencyKey() },
          },
        ),
      );
      return parseGeneration(payload);
    },
    async share(lookId: string): Promise<string> {
      const value = await requireOk(
        await apiRequest(
          config,
          `/looks/${encodeURIComponent(lookId)}/share-link`,
          { method: 'POST' },
        ),
      );
      if (
        value === null ||
        typeof value !== 'object' ||
        typeof (value as Record<string, unknown>).url !== 'string'
      ) {
        throw new Error('Invalid share response');
      }
      return (value as { url: string }).url;
    },
  };
}


export const looksApi = createLooksApi(defaultConfig);
