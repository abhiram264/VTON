/**
 * C06 consent client contract.
 *
 * The behaviours defended here are the ones that decide whether a user is told
 * the truth about consent: a malformed or unreadable response must never be
 * read as "granted", and a grant must not be invented client-side.
 */
import {
  CONSENT_PURPOSES,
  createConsentApi,
  isGrantStale,
  isPurposeActive,
  purposeState,
} from './consentApi';
import type { ConsentSnapshot, ConsentState } from './consentApi';

const BASE_URL = 'https://api.test';
const POLICY = '2026-10-03';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function state(
  overrides: Partial<ConsentState> = {},
): ConsentState {
  return {
    purpose: 'body_image_processing',
    active: false,
    version: null,
    grantedAt: null,
    policyVersion: POLICY,
    ...overrides,
  };
}

function snapshot(): ConsentSnapshot {
  return {
    policyVersion: POLICY,
    purposes: [
      state({ purpose: 'body_image_processing' }),
      state({ purpose: 'third_party_ai_transfer' }),
    ],
  };
}

// Deliberately malformed wire payloads: the parser must reject these, so the
// fixture is not typed as a valid ConsentState.
function rawState(overrides: Record<string, unknown>): Record<string, unknown> {
  return { ...state(), ...overrides };
}

describe('consent api', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  const config = { baseUrl: BASE_URL, getAccessToken: () => 'token' };
  const api = () => createConsentApi(config);

  test('get reads both purposes and the server policy version', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        policyVersion: POLICY,
        purposes: [
          state({
            purpose: 'body_image_processing',
            active: true,
            version: POLICY,
            grantedAt: '2026-10-03T12:00:00Z',
          }),
          state({ purpose: 'third_party_ai_transfer' }),
        ],
      }),
    );

    const result = await api().get();

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/consents`);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('GET');
    expect(result.policyVersion).toBe(POLICY);
    expect(result.purposes).toHaveLength(2);
    expect(isPurposeActive(result, 'body_image_processing')).toBe(true);
    expect(isPurposeActive(result, 'third_party_ai_transfer')).toBe(false);
  });

  test('grant posts to the purpose route with no request body and parses the single state', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        200,
        state({
          active: true,
          version: POLICY,
          grantedAt: '2026-10-03T12:00:00Z',
        }),
      ),
    );

    const granted = await api().grant('body_image_processing');

    expect(fetchMock.mock.calls[0][0]).toBe(
      `${BASE_URL}/consents/body_image_processing`,
    );
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    // The server chooses the policy version; the client must not send one.
    expect(init.body).toBeUndefined();
    expect(granted.active).toBe(true);
    expect(granted.version).toBe(POLICY);
  });

  test('withdraw deletes the purpose route and parses the inactive state', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, state()));

    const withdrawn = await api().withdraw('third_party_ai_transfer');

    expect(fetchMock.mock.calls[0][0]).toBe(
      `${BASE_URL}/consents/third_party_ai_transfer`,
    );
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('DELETE');
    expect(withdrawn.active).toBe(false);
    expect(withdrawn.version).toBeNull();
  });

  test('an unconfigured consent store surfaces as CONSENT_UNAVAILABLE rather than a grant', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(503, { code: 'CONSENT_UNAVAILABLE' }),
    );

    await expect(api().get()).rejects.toMatchObject({
      name: 'ApiProblemError',
      code: 'CONSENT_UNAVAILABLE',
    });
  });

  test.each([
    ['a non-object payload', []],
    ['a missing policy version', { purposes: [] }],
    ['a missing purposes list', { policyVersion: POLICY }],
    [
      'an unknown purpose literal',
      { policyVersion: POLICY, purposes: [rawState({ purpose: 'marketing' })] },
    ],
    [
      'a non-boolean active flag',
      { policyVersion: POLICY, purposes: [rawState({ active: 'true' })] },
    ],
    [
      'a missing active flag',
      {
        policyVersion: POLICY,
        purposes: [
          { purpose: 'body_image_processing', policyVersion: POLICY },
        ],
      },
    ],
  ])('get rejects %s instead of reporting consent', async (_label, payload) => {
    fetchMock.mockResolvedValue(jsonResponse(200, payload));

    await expect(api().get()).rejects.toThrow('Invalid consent response');
  });

  test('isPurposeActive is false for an absent purpose and true only for an explicit active grant', () => {
    const onlyBody = {
      policyVersion: POLICY,
      purposes: [state({ active: true, version: POLICY })],
    };

    expect(isPurposeActive(onlyBody, 'body_image_processing')).toBe(true);
    // Absent means unknown, never granted.
    expect(isPurposeActive(onlyBody, 'third_party_ai_transfer')).toBe(false);
    expect(purposeState(onlyBody, 'third_party_ai_transfer')).toBeNull();
    expect(isPurposeActive(snapshot(), 'body_image_processing')).toBe(false);
  });

  test('isGrantStale flags an active grant recorded under a superseded policy', () => {
    const stale = {
      policyVersion: POLICY,
      purposes: [
        state({ active: true, version: '2026-01-01', grantedAt: '2026-01-01T00:00:00Z' }),
      ],
    };
    const current = {
      policyVersion: POLICY,
      purposes: [state({ active: true, version: POLICY })],
    };

    expect(isGrantStale(stale, 'body_image_processing')).toBe(true);
    expect(isGrantStale(current, 'body_image_processing')).toBe(false);
    expect(isGrantStale(snapshot(), 'body_image_processing')).toBe(false);
  });

  test('the frozen purpose literals are the wire names', () => {
    expect([...CONSENT_PURPOSES]).toEqual([
      'body_image_processing',
      'third_party_ai_transfer',
    ]);
  });

  test('an unauthenticated call surfaces the auth problem code', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 401,
      headers: { get: () => null },
      json: () => Promise.reject(new SyntaxError('empty')),
      text: () => Promise.resolve(''),
    } as unknown as Response);

    await expect(api().get()).rejects.toMatchObject({
      name: 'ApiProblemError',
      code: 'UNAUTHORIZED',
    });
  });
});
