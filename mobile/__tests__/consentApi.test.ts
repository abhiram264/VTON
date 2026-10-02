import {
  CONSENT_POLICY_VERSION,
  consentState,
  createConsentApi,
} from '../src/features/my-model/consentApi';
import { ApiProblemError } from '../src/features/my-model/http';

const BASE_URL = 'https://api.example.invalid';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function emptyResponse(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: () => Promise.resolve({}),
    text: () => Promise.resolve(''),
  } as unknown as Response;
}

function record(overrides: Record<string, unknown> = {}) {
  return {
    purpose: 'third_party_ai_transfer',
    version: '2026-10-01',
    grantedAt: '2026-10-01T10:00:00Z',
    revokedAt: null,
    active: true,
    ...overrides,
  };
}

function api() {
  return createConsentApi({ baseUrl: BASE_URL, getAccessToken: () => 'token' });
}

describe('consentApi', () => {
  const originalFetch = global.fetch;
  let calls: { url: string; method: string; body: unknown }[] = [];

  function stub(fetchImpl: (url: string, init: RequestInit) => Response) {
    global.fetch = ((url: string, init: RequestInit = {}) => {
      calls.push({
        url,
        method: init.method ?? 'GET',
        body: init.body === undefined ? undefined : JSON.parse(String(init.body)),
      });
      return Promise.resolve(fetchImpl(url, init));
    }) as unknown as typeof fetch;
  }

  beforeEach(() => {
    calls = [];
  });

  afterAll(() => {
    global.fetch = originalFetch;
  });

  it('lists consent history with the recorded policy version', async () => {
    stub(() => jsonResponse(200, { consents: [record()] }));

    const records = await api().list();

    expect(calls[0].url).toBe(`${BASE_URL}/me/consents`);
    expect(calls[0].method).toBe('GET');
    expect(records).toEqual([
      {
        purpose: 'third_party_ai_transfer',
        version: '2026-10-01',
        grantedAt: '2026-10-01T10:00:00Z',
        revokedAt: null,
        active: true,
      },
    ]);
  });

  it('rejects a malformed or unrecognised consent payload instead of fabricating a grant', async () => {
    stub(() => jsonResponse(200, { consents: [{ purpose: 'marketing', active: true }] }));
    await expect(api().list()).rejects.toThrow('Invalid Consent response');

    stub(() => jsonResponse(200, {}));
    await expect(api().list()).rejects.toThrow('Invalid Consent response');

    stub(() => jsonResponse(200, { consents: [record({ grantedAt: 'not-a-date' })] }));
    await expect(api().list()).rejects.toThrow('Invalid Consent response');
  });

  it('grants with the current policy version and sends it to the server', async () => {
    stub(() => jsonResponse(200, record()));

    const granted = await api().grant('third_party_ai_transfer');

    expect(calls[0].url).toBe(`${BASE_URL}/me/consents/third_party_ai_transfer`);
    expect(calls[0].method).toBe('PUT');
    expect(calls[0].body).toEqual({ version: CONSENT_POLICY_VERSION });
    expect(granted.active).toBe(true);
  });

  it('surfaces a refused withdrawal instead of pretending it succeeded', async () => {
    stub(() => jsonResponse(404, { code: 'CONSENT_NOT_GRANTED' }));

    await expect(api().withdraw('retention')).rejects.toBeInstanceOf(ApiProblemError);
    stub(() => jsonResponse(404, { code: 'CONSENT_NOT_GRANTED' }));
    await expect(api().withdraw('retention')).rejects.toHaveProperty(
      'code',
      'CONSENT_NOT_GRANTED',
    );
  });

  it('withdraws with a DELETE and accepts the empty 204 body', async () => {
    stub(() => emptyResponse(204));

    await expect(api().withdraw('retention')).resolves.toBeUndefined();
    expect(calls[0].url).toBe(`${BASE_URL}/me/consents/retention`);
    expect(calls[0].method).toBe('DELETE');
  });

  it('distinguishes withdrawn from never granted', () => {
    const revoked = {
      purpose: 'retention' as const,
      version: 'v1',
      grantedAt: '2026-10-01T10:00:00Z',
      revokedAt: '2026-10-02T10:00:00Z',
      active: false,
    };

    expect(consentState([revoked], 'retention')).toBe('withdrawn');
    expect(consentState([], 'retention')).toBe('not_granted');
    expect(consentState([record({ purpose: 'retention' })], 'retention')).toBe('granted');
    expect(consentState([revoked, record({ purpose: 'retention' })], 'retention')).toBe('granted');
  });
});
