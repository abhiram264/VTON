import { apiRequest, requireOk } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';
import { createIdempotencyKey } from '../generation/generationApi';
import type {
  CoarseGenerationPhase,
  GenerationResource,
  GenerationStatus,
} from '../generation/types';

// Wire contract of the mounted Studio session surface
// (platform/web/StudioController, owner-scoped):
//   POST /studio/sessions                                  -> StudioState
//   GET  /studio/sessions/active                           -> StudioState | 404 STUDIO_SESSION_NOT_FOUND
//   GET  /studio/sessions/{id}                             -> StudioState | 404
//   POST /studio/sessions/{id}/commands                    -> StudioState | 409 STUDIO_STALE_REVISION
// Optimistic concurrency uses the session's `updatedAt` instant as the token:
// there is no revision column (V006). A stale token is rejected 409 rather than
// silently winning, so a stale device can never overwrite a newer draft.
export type StudioState = {
  sessionId: string;
  avatarProfileId?: string;
  /** Optimistic-concurrency token; echoed back as `expectedUpdatedAt`. */
  updatedAt?: string;
  itemIds: string[];
};

// Vault category -> Studio slot. The backend owns slot conflict rules
// (FULL_BODY conflicts with UPPER_BODY/LOWER_BODY); the client only needs the
// same vocabulary to name the slot it is mutating.
export type StudioSlot =
  | 'HEAD'
  | 'UPPER_BODY'
  | 'LOWER_BODY'
  | 'FULL_BODY'
  | 'FEET'
  | 'BAG'
  | 'JEWELLERY'
  | 'ACCESSORY';

const CATEGORY_SLOTS: Record<string, StudioSlot> = {
  TOP: 'UPPER_BODY',
  BOTTOM: 'LOWER_BODY',
  ONE_PIECE: 'FULL_BODY',
  ETHNIC_FULL_BODY: 'FULL_BODY',
  SAREE: 'FULL_BODY',
  LEHENGA: 'FULL_BODY',
  KURTA: 'FULL_BODY',
  SHERWANI: 'FULL_BODY',
  FOOTWEAR: 'FEET',
  HEADWEAR: 'HEAD',
  BAG: 'BAG',
  JEWELLERY: 'JEWELLERY',
  ACCESSORY: 'ACCESSORY',
};

export function slotForCategory(category: string | undefined): StudioSlot {
  return CATEGORY_SLOTS[category ?? ''] ?? 'ACCESSORY';
}

function parseState(value: unknown): StudioState {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Studio response');
  const row = value as Record<string, unknown>;
  if (typeof row.sessionId !== 'string' || !Array.isArray(row.itemIds) || !row.itemIds.every(id => typeof id === 'string')) throw new Error('Invalid Studio response');
  return {
    sessionId: row.sessionId,
    itemIds: row.itemIds as string[],
    ...(typeof row.avatarProfileId === 'string' ? { avatarProfileId: row.avatarProfileId } : {}),
    ...(typeof row.updatedAt === 'string' ? { updatedAt: row.updatedAt } : {}),
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
  if (value === null || typeof value !== 'object') throw new Error('Invalid generation response');
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
    ...(row.failureCode == null ? null : { failureCode: String(row.failureCode) }),
  };
}

const config = productionApiConfig;

/** A Studio draft cloned from a Look, plus the explicit gaps the clone reports. */
export type StudioDraft = StudioState & {
  unresolved: { vaultItemId: string; reason: string }[];
  avatarUnavailable: boolean;
};

/**
 * The C21 natural-language outcome. Only APPLIED means the draft changed;
 * CONFIRM_REQUIRED, EXPLAINED and REFUSED all leave it exactly as it was.
 */
export type StudioCommandOutcome = 'APPLIED' | 'CONFIRM_REQUIRED' | 'EXPLAINED' | 'REFUSED';

export type StudioCommandResult = {
  state: StudioState;
  outcome: StudioCommandOutcome;
  message: string | null;
  /** Owned garments offered for an ambiguous command; empty unless CONFIRM_REQUIRED. */
  candidates: { vaultItemId: string; label: string; slot: StudioSlot | null }[];
};

function isStudioSlot(value: unknown): value is StudioSlot {
  return value === 'HEAD' || value === 'UPPER_BODY' || value === 'LOWER_BODY' || value === 'FULL_BODY'
    || value === 'FEET' || value === 'BAG' || value === 'JEWELLERY' || value === 'ACCESSORY';
}

function commandBody(command: string, expectedUpdatedAt: string | undefined, extra: Record<string, unknown>): string {
  return JSON.stringify({ command, ...extra, ...(expectedUpdatedAt ? { expectedUpdatedAt } : {}) });
}

export const studioApi = {
  async get(sessionId: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}`, { method: 'GET' })));
  },
  // The owner's current draft, or null when none exists. A missing draft is an
  // ordinary empty state (404 STUDIO_SESSION_NOT_FOUND), never an error: the
  // screen creates one.
  async activeDraft(): Promise<StudioState | null> {
    const response = await apiRequest(config, '/studio/sessions/active', { method: 'GET' });
    if (response.status === 404 || response.status === 204) return null;
    return parseState(await requireOk(response));
  },
  async create(): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, '/studio/sessions', { method: 'POST' })));
  },
  // Clone an owner-scoped Look's provenance into a NEW Studio draft
  // (POST /studio/sessions/from-look). Missing source garments are reported in
  // `unresolved` rather than silently dropped; `avatarUnavailable` says the
  // draft carries no avatar. A foreign Look is 404 with no disclosure.
  async createFromLook(lookId: string): Promise<StudioDraft> {
    const payload = await requireOk(await apiRequest(config, '/studio/sessions/from-look', { method: 'POST', body: JSON.stringify({ lookId }) }));
    const state = parseState(payload);
    const row = payload as Record<string, unknown>;
    const unresolved = Array.isArray(row.unresolved)
      ? row.unresolved.flatMap(entry => {
          if (entry === null || typeof entry !== 'object') return [];
          const item = entry as Record<string, unknown>;
          if (typeof item.vaultItemId !== 'string' || typeof item.reason !== 'string') return [];
          return [{ vaultItemId: item.vaultItemId, reason: item.reason }];
        })
      : [];
    return { ...state, unresolved, avatarUnavailable: row.avatarUnavailable === true };
  },
  async addItem(sessionId: string, vaultItemId: string, slot: StudioSlot, expectedUpdatedAt?: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/commands`, { method: 'POST', body: commandBody('addItem', expectedUpdatedAt, { vaultItemId, slot }) })));
  },
  async replaceItem(sessionId: string, vaultItemId: string, slot: StudioSlot, expectedUpdatedAt?: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/commands`, { method: 'POST', body: commandBody('replaceItem', expectedUpdatedAt, { vaultItemId, slot }) })));
  },
  async removeItem(sessionId: string, itemId: string, expectedUpdatedAt?: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/commands`, { method: 'POST', body: commandBody('removeItem', expectedUpdatedAt, { itemId }) })));
  },
  // Natural-language command. The route answers the same session shape plus the
  // C21 outcome contract: `outcome` is APPLIED | CONFIRM_REQUIRED | EXPLAINED |
  // REFUSED, `message` is a prompt/explanation (null on APPLIED) and
  // `candidates` is always an array of owned items for a confirmation. An absent
  // outcome is read as APPLIED so the client also works against the pre-C21
  // route, where every successful command mutated the draft.
  async command(sessionId: string, text: string): Promise<StudioCommandResult> {
    const payload = await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/natural-language-command`, { method: 'POST', body: JSON.stringify({ text }) }));
    const row = payload as Record<string, unknown>;
    const outcome = row.outcome === 'CONFIRM_REQUIRED' || row.outcome === 'EXPLAINED' || row.outcome === 'REFUSED'
      ? row.outcome
      : 'APPLIED';
    const candidates = Array.isArray(row.candidates)
      ? row.candidates.flatMap(entry => {
          if (entry === null || typeof entry !== 'object') return [];
          const candidate = entry as Record<string, unknown>;
          if (typeof candidate.vaultItemId !== 'string' || candidate.vaultItemId.trim() === '') return [];
          return [{
            vaultItemId: candidate.vaultItemId,
            label: typeof candidate.label === 'string' ? candidate.label : 'Garment',
            // The planner reports the slot the command TARGETED, so the
            // confirmation re-send never has to re-derive it.
            slot: isStudioSlot(candidate.slot) ? candidate.slot : null,
          }];
        })
      : [];
    return {
      state: parseState(payload),
      outcome,
      message: typeof row.message === 'string' ? row.message : null,
      candidates,
    };
  },
  // Studio-based multi-item generation: the owned session and its item set are
  // the frozen input, never the manual-photo upload path. Idempotency-Key makes
  // a lost response reuse the same job instead of spending a second generation.
  async generate(sessionId: string, productVaultItemIds: string[], mode: string): Promise<GenerationResource> {
    return parseGeneration(await requireOk(await apiRequest(config, '/generations/multi', {
      method: 'POST',
      body: JSON.stringify({ studioSessionId: sessionId, productVaultItemIds, mode }),
      extraHeaders: { 'Idempotency-Key': createIdempotencyKey() },
    })));
  },
};
