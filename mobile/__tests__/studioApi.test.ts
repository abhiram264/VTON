import { slotForCategory, studioApi } from '../src/features/studio/studioApi';

// The Studio session and multi-item generation contracts are frozen wire seams
// (docs/CONTRACT_INVENTORY.md 9.1 and the mounted StudioController). StudioScreen
// mocks this client, so nothing else would catch a renamed field or a dropped
// header; this file exercises the real client against a fake fetch.
const BASE_URL = 'https://api.example.invalid';
const TOKEN = 'injected-access-token';

jest.mock('../src/config/apiConfig', () => ({
  productionApiConfig: {
    baseUrl: 'https://api.example.invalid',
    getAccessToken: () => 'injected-access-token',
  },
}));

const SESSION_ID = '11111111-1111-1111-1111-111111111111';
const ITEM_A = '22222222-2222-2222-2222-222222222222';
const ITEM_B = '33333333-3333-3333-3333-333333333333';

function jsonResponse(status: number, body: unknown): Promise<Response> {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

const sessionBody = {
  sessionId: SESSION_ID,
  avatarProfileId: null,
  itemIds: [ITEM_A],
  updatedAt: '2026-10-03T00:00:00Z',
};

describe('studioApi wire contract', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as typeof fetch;
  });

  test('an absent active draft is an ordinary empty state, not an error', async () => {
    fetchMock.mockImplementation(input => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/studio/sessions/active`);
      return jsonResponse(404, { code: 'STUDIO_SESSION_NOT_FOUND' });
    });

    await expect(studioApi.activeDraft()).resolves.toBeNull();
  });

  test('commands carry the session updatedAt as the optimistic token and the slot', async () => {
    fetchMock.mockImplementation((input, init) => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/studio/sessions/${SESSION_ID}/commands`);
      expect(init?.method).toBe('POST');
      expect((init?.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`);
      expect(JSON.parse(String(init?.body))).toEqual({
        command: 'addItem',
        vaultItemId: ITEM_B,
        slot: 'LOWER_BODY',
        expectedUpdatedAt: '2026-10-03T00:00:00Z',
      });
      return jsonResponse(200, { ...sessionBody, itemIds: [ITEM_A, ITEM_B], updatedAt: '2026-10-03T00:01:00Z' });
    });

    const state = await studioApi.addItem(SESSION_ID, ITEM_B, 'LOWER_BODY', '2026-10-03T00:00:00Z');
    expect(state.updatedAt).toBe('2026-10-03T00:01:00Z');
    expect(state.itemIds).toEqual([ITEM_A, ITEM_B]);
  });

  test('removeItem names the item and replaceItem names the slot', async () => {
    const bodies: unknown[] = [];
    fetchMock.mockImplementation((_input, init) => {
      bodies.push(JSON.parse(String(init?.body)));
      return jsonResponse(200, sessionBody);
    });

    await studioApi.removeItem(SESSION_ID, ITEM_A, '2026-10-03T00:00:00Z');
    await studioApi.replaceItem(SESSION_ID, ITEM_B, 'UPPER_BODY', '2026-10-03T00:00:00Z');

    expect(bodies).toEqual([
      { command: 'removeItem', itemId: ITEM_A, expectedUpdatedAt: '2026-10-03T00:00:00Z' },
      { command: 'replaceItem', vaultItemId: ITEM_B, slot: 'UPPER_BODY', expectedUpdatedAt: '2026-10-03T00:00:00Z' },
    ]);
  });

  test('generation freezes the ordered owned items on POST /generations/multi with an idempotency key', async () => {
    fetchMock.mockImplementation((input, init) => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/generations/multi`);
      expect(init?.method).toBe('POST');
      const headers = init?.headers as Record<string, string>;
      expect(typeof headers['Idempotency-Key']).toBe('string');
      expect(headers['Idempotency-Key'].length).toBeGreaterThan(0);
      expect(JSON.parse(String(init?.body))).toEqual({
        studioSessionId: SESSION_ID,
        productVaultItemIds: [ITEM_A, ITEM_B],
        mode: 'standard',
      });
      return jsonResponse(202, {
        id: 'gen-1',
        status: 'SUBMITTED',
        phase: 'GENERATING',
        statusVersion: 1,
        createdAt: '2026-10-03T00:00:00Z',
        updatedAt: '2026-10-03T00:00:00Z',
      });
    });

    const generation = await studioApi.generate(SESSION_ID, [ITEM_A, ITEM_B], 'standard');
    expect(generation.id).toBe('gen-1');
  });

  test('cloning a Look reports its unresolved source garments and missing avatar', async () => {
    fetchMock.mockImplementation((input, init) => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/studio/sessions/from-look`);
      expect(JSON.parse(String(init?.body))).toEqual({ lookId: 'look-1' });
      return jsonResponse(201, {
        sessionId: 'cloned-1',
        avatarProfileId: null,
        itemIds: [ITEM_A],
        updatedAt: '2026-10-03T00:00:00Z',
        unresolved: [{ slot: 'LOWER_BODY', vaultItemId: ITEM_B, reason: 'GARMENT_DELETED' }],
        avatarUnavailable: true,
      });
    });

    const draft = await studioApi.createFromLook('look-1');
    expect(draft.unresolved).toEqual([{ vaultItemId: ITEM_B, reason: 'GARMENT_DELETED' }]);
    expect(draft.avatarUnavailable).toBe(true);
    expect(draft.itemIds).toEqual([ITEM_A]);
  });

  test('the natural-language outcome contract is parsed, and an absent outcome reads as APPLIED', async () => {
    fetchMock.mockImplementation((input, init) => {
      expect(requestUrl(input)).toBe(`${BASE_URL}/studio/sessions/${SESSION_ID}/natural-language-command`);
      expect(JSON.parse(String(init?.body))).toEqual({ text: 'add a top' });
      return jsonResponse(200, {
        ...sessionBody,
        outcome: 'CONFIRM_REQUIRED',
        message: 'Which top did you mean?',
        candidates: [
          { vaultItemId: ITEM_A, label: 'Silk top', slot: 'UPPER_BODY' },
          { vaultItemId: ITEM_B, label: 'Linen top', slot: 'UPPER_BODY' },
        ],
      });
    });

    const result = await studioApi.command(SESSION_ID, 'add a top');
    expect(result.outcome).toBe('CONFIRM_REQUIRED');
    expect(result.message).toBe('Which top did you mean?');
    expect(result.candidates).toEqual([
      { vaultItemId: ITEM_A, label: 'Silk top', slot: 'UPPER_BODY' },
      { vaultItemId: ITEM_B, label: 'Linen top', slot: 'UPPER_BODY' },
    ]);
    expect(result.state.sessionId).toBe(SESSION_ID);

    // The pre-C21 route answers with only the session body; that must still read
    // as an applied command rather than an unknown state.
    fetchMock.mockImplementation(() => jsonResponse(200, sessionBody));
    const legacy = await studioApi.command(SESSION_ID, 'add a top');
    expect(legacy.outcome).toBe('APPLIED');
    expect(legacy.candidates).toEqual([]);
    expect(legacy.message).toBeNull();
  });

  test('a malformed Studio body is rejected rather than rendered', async () => {
    fetchMock.mockImplementation(() => jsonResponse(200, { sessionId: SESSION_ID, itemIds: 'nope' }));
    await expect(studioApi.get(SESSION_ID)).rejects.toThrow();
  });
});

describe('slotForCategory', () => {
  test.each([
    ['TOP', 'UPPER_BODY'],
    ['BOTTOM', 'LOWER_BODY'],
    ['ONE_PIECE', 'FULL_BODY'],
    ['ETHNIC_FULL_BODY', 'FULL_BODY'],
    ['FOOTWEAR', 'FEET'],
    ['HEADWEAR', 'HEAD'],
    ['ACCESSORY', 'ACCESSORY'],
    ['UNKNOWN', 'ACCESSORY'],
  ])('maps %s to the backend slot %s', (category, slot) => {
    expect(slotForCategory(category)).toBe(slot);
  });
});
