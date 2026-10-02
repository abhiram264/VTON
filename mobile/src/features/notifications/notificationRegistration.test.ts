/**
 * C20 device-registration client contract.
 *
 * The behaviours that matter: the client never invents a device state, a
 * failed call is never reported as success, and a missing stored preference is
 * the server's own default (enabled) rather than something the client made up.
 */
import {
  createNotificationRegistrationApi,
  registerDevice,
  removeDevice,
} from './notificationRegistration';
import type { NotificationRegistrationApi } from './notificationRegistration';

const BASE_URL = 'https://api.test';
const TOKEN = 'fcm-token-abc123';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response;
}

function noContentResponse(): Response {
  return {
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: () => Promise.reject(new SyntaxError('no body')),
    text: () => Promise.resolve(''),
  } as unknown as Response;
}

function device(overrides: Record<string, unknown> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    deviceToken: TOKEN,
    platform: 'ANDROID',
    createdAt: '2026-10-03T12:00:00Z',
    updatedAt: '2026-10-03T12:00:00Z',
    lastSeenAt: '2026-10-03T12:00:00Z',
    ...overrides,
  };
}

describe('notification registration api', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  const config = { baseUrl: BASE_URL, getAccessToken: () => 'token' };
  const api = () => createNotificationRegistrationApi(config);

  test('register posts the token and platform to /me/devices and parses the registration', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, device()));

    const registration = await api().register(TOKEN, 'ANDROID');

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/me/devices`);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({
      deviceToken: TOKEN,
      platform: 'ANDROID',
    });
    expect(registration.deviceToken).toBe(TOKEN);
    expect(registration.platform).toBe('ANDROID');
  });

  test('list reads the owner-scoped device collection', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [device(), device({ id: 'other', platform: 'IOS' })]));

    const devices = await api().list();

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/me/devices`);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('GET');
    expect(devices).toHaveLength(2);
  });

  test('remove deletes the token and accepts the empty 204 body', async () => {
    fetchMock.mockResolvedValue(noContentResponse());

    await expect(api().remove(TOKEN)).resolves.toBeUndefined();

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/me/devices`);
    expect(init.method).toBe('DELETE');
    expect(JSON.parse(init.body as string)).toEqual({ deviceToken: TOKEN });
  });

  test('preferences reads the stored value, and the server default when nothing is stored', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' }),
    );
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { pushEnabled: true, updatedAt: null }));

    const stored = await api().preferences();
    const defaulted = await api().preferences();

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/me/notification-preferences`);
    expect(stored).toEqual({ pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' });
    // null updatedAt is the server's "no stored preference, default enabled".
    expect(defaulted).toEqual({ pushEnabled: true, updatedAt: null });
  });

  test('updatePreferences PUTs exactly the boolean', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' }));

    const updated = await api().updatePreferences(false);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/me/notification-preferences`);
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ pushEnabled: false });
    expect(updated.pushEnabled).toBe(false);
  });

  test.each([
    ['a non-object payload', 42],
    ['an unknown platform', device({ platform: 'WINDOWS' })],
    ['a missing token', device({ deviceToken: null })],
    ['a missing id', device({ id: '' })],
  ])('register rejects %s rather than reporting a device', async (_label, payload) => {
    fetchMock.mockResolvedValue(jsonResponse(200, payload));

    await expect(api().register(TOKEN, 'ANDROID')).rejects.toThrow(
      'Invalid device registration response',
    );
  });

  test('preferences rejects a malformed payload instead of assuming a setting', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { pushEnabled: 'yes' }));

    await expect(api().preferences()).rejects.toThrow(
      'Invalid notification preference response',
    );
  });

  test('a failed read is not turned into a device list', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, { code: 'UNAVAILABLE' }));

    await expect(api().list()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
  });
});

describe('registration outcomes', () => {
  test('registerDevice reports failed instead of a fabricated registration', async () => {
    const register = jest.fn().mockRejectedValue(new TypeError('Network request failed'));
    const api: Pick<NotificationRegistrationApi, 'register'> = { register };

    expect(await registerDevice(api, TOKEN, 'IOS')).toEqual({ outcome: 'failed' });
  });

  test('registerDevice reports the server registration on success', async () => {
    const registration = {
      id: 'id-1',
      deviceToken: TOKEN,
      platform: 'IOS' as const,
      createdAt: '2026-10-03T12:00:00Z',
      updatedAt: '2026-10-03T12:00:00Z',
      lastSeenAt: '2026-10-03T12:00:00Z',
    };
    const api: Pick<NotificationRegistrationApi, 'register'> = {
      register: jest.fn().mockResolvedValue(registration),
    };

    expect(await registerDevice(api, TOKEN, 'IOS')).toEqual({
      outcome: 'registered',
      registration,
    });
  });

  test('removeDevice distinguishes a removed token from a failed request', async () => {
    const ok: Pick<NotificationRegistrationApi, 'remove'> = {
      remove: jest.fn().mockResolvedValue(undefined),
    };
    const bad: Pick<NotificationRegistrationApi, 'remove'> = {
      remove: jest.fn().mockRejectedValue(new TypeError('Network request failed')),
    };

    expect(await removeDevice(ok, TOKEN)).toBe('removed');
    expect(await removeDevice(bad, TOKEN)).toBe('failed');
  });

  test('a blank token is refused before any request is made', async () => {
    const fetchMock = jest.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(
      createNotificationRegistrationApi({
        baseUrl: BASE_URL,
        getAccessToken: () => 'token',
      }).register('   ', 'ANDROID'),
    ).rejects.toThrow('Device token is required');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
