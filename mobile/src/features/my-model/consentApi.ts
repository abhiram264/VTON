import { apiRequest, requireOk } from './http';
import { productionApiConfig } from '../../config/apiConfig';
import type { AvatarApiConfig } from './types';

// The bounded purposes the server accepts. Sending anything else is refused by the
// API (CONSENT_PURPOSE_INVALID), so the client keeps the same closed set rather than
// typing free-form strings.
export const CONSENT_PURPOSES = [
  'body_image_processing',
  'third_party_ai_transfer',
  'retention',
] as const;

export type ConsentPurpose = (typeof CONSENT_PURPOSES)[number];

// The policy version the user is shown and agrees to. Recorded server-side on every
// grant; bump it whenever the disclosed terms change so a stale agreement is never
// silently reused.
export const CONSENT_POLICY_VERSION = '2026-10-01';

export type ConsentRecord = {
  purpose: ConsentPurpose;
  version: string;
  grantedAt: string;
  revokedAt: string | null;
  active: boolean;
};

export type ConsentState = 'granted' | 'withdrawn' | 'not_granted';

export type ConsentApi = {
  list(): Promise<ConsentRecord[]>;
  grant(purpose: ConsentPurpose, version?: string): Promise<ConsentRecord>;
  withdraw(purpose: ConsentPurpose): Promise<void>;
};

function isPurpose(value: unknown): value is ConsentPurpose {
  return (
    typeof value === 'string' &&
    (CONSENT_PURPOSES as readonly string[]).includes(value)
  );
}

function parseRecord(value: unknown): ConsentRecord {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid Consent response');
  }
  const row = value as Record<string, unknown>;
  if (
    !isPurpose(row.purpose) ||
    typeof row.version !== 'string' ||
    row.version === '' ||
    typeof row.grantedAt !== 'string' ||
    Number.isNaN(Date.parse(row.grantedAt)) ||
    (row.revokedAt !== null && typeof row.revokedAt !== 'string') ||
    typeof row.active !== 'boolean'
  ) {
    throw new Error('Invalid Consent response');
  }
  return {
    purpose: row.purpose,
    version: row.version,
    grantedAt: row.grantedAt,
    revokedAt: row.revokedAt === null ? null : row.revokedAt,
    active: row.active,
  };
}

function parseList(value: unknown): ConsentRecord[] {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid Consent response');
  }
  const rows = (value as Record<string, unknown>).consents;
  if (!Array.isArray(rows)) {
    throw new Error('Invalid Consent response');
  }
  return rows.map(parseRecord);
}

// Derives the surface state from the recorded history: an active grant is `granted`,
// a revoked row with no active grant is `withdrawn` (a real user decision that must be
// reported differently from never having agreed), anything else is `not_granted`.
export function consentState(
  records: readonly ConsentRecord[],
  purpose: ConsentPurpose,
): ConsentState {
  const rows = records.filter(row => row.purpose === purpose);
  if (rows.some(row => row.active)) {
    return 'granted';
  }
  return rows.length > 0 ? 'withdrawn' : 'not_granted';
}

export function createConsentApi(
  config: AvatarApiConfig = productionApiConfig,
): ConsentApi {
  return {
    async list(): Promise<ConsentRecord[]> {
      return parseList(
        await requireOk(
          await apiRequest(config, '/me/consents', { method: 'GET' }),
        ),
      );
    },

    async grant(
      purpose: ConsentPurpose,
      version: string = CONSENT_POLICY_VERSION,
    ): Promise<ConsentRecord> {
      return parseRecord(
        await requireOk(
          await apiRequest(config, `/me/consents/${purpose}`, {
            method: 'PUT',
            body: JSON.stringify({ version }),
          }),
        ),
      );
    },

    async withdraw(purpose: ConsentPurpose): Promise<void> {
      await requireOk(
        await apiRequest(config, `/me/consents/${purpose}`, { method: 'DELETE' }),
      );
    },
  };
}

export const consentApi = createConsentApi(productionApiConfig);
