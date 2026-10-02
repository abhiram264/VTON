import { apiRequest, requireOk } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';
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

// Provenance carried by GET /looks/{id} (LookResource on the server): the exact
// source garments that produced the render, the provider/model, and the quality
// outcome. Declared here rather than in features/generation so the C15 detail
// surface can show provenance without widening the generation client's frozen
// look shape (pinned by __tests__/look.test.ts).
export type LookProvenanceItem = { vaultItemId: string; slot: string };

export type LookDetail = LookResource & {
  items: LookProvenanceItem[];
  provider: string | null;
  providerModel: string | null;
  qualityStatus: string | null;
};

function parseLookDetail(value: unknown): LookDetail {
  const base = parseLook(value);
  const row = value as Record<string, unknown>;
  const rawItems = row.items;
  if (rawItems !== undefined && !Array.isArray(rawItems)) {
    throw new Error('Invalid Look response');
  }
  const items: LookProvenanceItem[] = [];
  for (const entry of (rawItems ?? []) as unknown[]) {
    if (entry === null || typeof entry !== 'object') {
      throw new Error('Invalid Look response');
    }
    const item = entry as Record<string, unknown>;
    if (typeof item.vaultItemId !== 'string' || typeof item.slot !== 'string') {
      throw new Error('Invalid Look response');
    }
    items.push({ vaultItemId: item.vaultItemId, slot: item.slot });
  }
  return {
    ...base,
    items,
    provider: typeof row.provider === 'string' ? row.provider : null,
    providerModel: typeof row.providerModel === 'string' ? row.providerModel : null,
    qualityStatus: typeof row.qualityStatus === 'string' ? row.qualityStatus : null,
  };
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
const defaultConfig: AvatarApiConfig = productionApiConfig;

// No share method, deliberately. POST /looks/{id}/share-link mints a signed, time-limited
// URL that hands a rendered Look to anyone holding it. The backend endpoint still exists
// (LooksController:113) but the app must not be able to call it: an exfiltration capability
// with no call site is still a capability. t121 removed the UI call site; this removes the
// client. A future share feature must be an explicit owner decision with its own consent
// surface, not a re-added one-liner.
export function createLooksApi(config: AvatarApiConfig = defaultConfig) {
  return {
    async list(): Promise<LookResource[]> {
      const value = await requireOk(
        await apiRequest(config, '/looks', { method: 'GET' }),
      );
      if (!Array.isArray(value)) throw new Error('Invalid Looks response');
      return value.map(parseLook);
    },
    // Owner-scoped detail read. A Look the caller does not own answers 404
    // UNAUTHORIZED_OR_NOT_FOUND, which surfaces as an ApiProblemError carrying
    // no image, id, or metadata — a foreign deep link discloses nothing.
    async get(lookId: string): Promise<LookDetail> {
      const value = await requireOk(
        await apiRequest(config, `/looks/${encodeURIComponent(lookId)}`, {
          method: 'GET',
        }),
      );
      return parseLookDetail(value);
    },
    // Mounted route: DELETE /looks/{id} logically deletes the Look and removes
    // only the render object. Vault garments are untouched by construction.
    async remove(lookId: string): Promise<void> {
      await requireOk(
        await apiRequest(config, `/looks/${encodeURIComponent(lookId)}`, {
          method: 'DELETE',
        }),
      );
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
  };
}


export const looksApi = createLooksApi(defaultConfig);
