/**
 * Consent client for the My Model surface (C06).
 *
 * The server owns the policy version and the record: the client never chooses
 * a version, never invents a grant, and treats anything it cannot parse as
 * UNAVAILABLE rather than as consent. "Could not ask" and "not granted" are
 * different facts and are kept different here, exactly as they are for
 * generation recovery.
 *
 * Wire contract (frozen by the ConsentsController seam):
 *   GET    /consents                     -> { policyVersion, purposes: [...] }
 *   POST   /consents/{purpose}           -> one purpose object (server picks version)
 *   DELETE /consents/{purpose}           -> one purpose object, idempotent
 * Purpose literals are the snake_case wire names, and both supported purposes
 * are always enumerated, so "absent" never has to be told apart from
 * "not granted".
 */
import { apiRequest, requireOk } from '../my-model/http';
import type { AvatarApiConfig } from '../my-model/types';
import { productionApiConfig } from '../../config/apiConfig';

export const CONSENT_PURPOSES = [
  'body_image_processing',
  'third_party_ai_transfer',
] as const;

export type ConsentPurpose = (typeof CONSENT_PURPOSES)[number];

export type ConsentState = {
  purpose: ConsentPurpose;
  active: boolean;
  /** Policy version the ACTIVE grant was recorded under; null when inactive. */
  version: string | null;
  /** ISO-8601 UTC of the active grant; null when inactive. */
  grantedAt: string | null;
  /** Policy version the server would record for a NEW grant. */
  policyVersion: string;
};

export type ConsentSnapshot = {
  policyVersion: string;
  purposes: ConsentState[];
};

export type ConsentApi = {
  get(): Promise<ConsentSnapshot>;
  grant(purpose: ConsentPurpose): Promise<ConsentState>;
  withdraw(purpose: ConsentPurpose): Promise<ConsentState>;
};

function isPurpose(value: unknown): value is ConsentPurpose {
  return (
    typeof value === 'string' &&
    (CONSENT_PURPOSES as readonly string[]).includes(value)
  );
}

function nullableString(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === 'string') {
    return value;
  }
  throw new Error('Invalid consent response');
}

function parseState(value: unknown): ConsentState {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid consent response');
  }
  const row = value as Record<string, unknown>;
  if (!isPurpose(row.purpose) || typeof row.active !== 'boolean') {
    throw new Error('Invalid consent response');
  }
  if (typeof row.policyVersion !== 'string' || row.policyVersion.length === 0) {
    throw new Error('Invalid consent response');
  }
  return {
    purpose: row.purpose,
    active: row.active,
    version: nullableString(row.version),
    grantedAt: nullableString(row.grantedAt),
    policyVersion: row.policyVersion,
  };
}

function parseSnapshot(value: unknown): ConsentSnapshot {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid consent response');
  }
  const row = value as Record<string, unknown>;
  if (typeof row.policyVersion !== 'string' || row.policyVersion.length === 0) {
    throw new Error('Invalid consent response');
  }
  if (!Array.isArray(row.purposes)) {
    throw new Error('Invalid consent response');
  }
  return {
    policyVersion: row.policyVersion,
    purposes: row.purposes.map(parseState),
  };
}

export function createConsentApi(config: AvatarApiConfig): ConsentApi {
  return {
    async get() {
      return parseSnapshot(
        await requireOk(
          await apiRequest(config, '/consents', { method: 'GET' }),
        ),
      );
    },

    async grant(purpose) {
      return parseState(
        await requireOk(
          await apiRequest(config, `/consents/${purpose}`, { method: 'POST' }),
        ),
      );
    },

    async withdraw(purpose) {
      return parseState(
        await requireOk(
          await apiRequest(config, `/consents/${purpose}`, { method: 'DELETE' }),
        ),
      );
    },
  };
}

export const consentApi = createConsentApi(productionApiConfig);

/**
 * The recorded state for one purpose, or null when the server did not
 * enumerate it. Null means "unknown", never "granted".
 */
export function purposeState(
  snapshot: ConsentSnapshot,
  purpose: ConsentPurpose,
): ConsentState | null {
  return snapshot.purposes.find(state => state.purpose === purpose) ?? null;
}

/**
 * True only for an explicit, currently-active grant. A missing entry, an
 * inactive grant, or an unreadable response all read as NOT active.
 */
export function isPurposeActive(
  snapshot: ConsentSnapshot,
  purpose: ConsentPurpose,
): boolean {
  return purposeState(snapshot, purpose)?.active === true;
}

/**
 * True when the active grant was recorded under a policy version that is no
 * longer current, so the surface can say the consent needs reviewing instead
 * of implying it is up to date.
 */
export function isGrantStale(
  snapshot: ConsentSnapshot,
  purpose: ConsentPurpose,
): boolean {
  const state = purposeState(snapshot, purpose);
  return (
    state !== null &&
    state.active &&
    state.version !== null &&
    state.version !== state.policyVersion
  );
}
