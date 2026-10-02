/**
 * Push notification device registration (C20, mobile half).
 *
 * The server owns the lifecycle: registration is an idempotent upsert keyed on
 * the device token, removal is owner-bound and always answers 204, and
 * preferences default to enabled when nothing is stored. This module is the
 * client for those mounted routes — it never invents a token, never invents a
 * preference, and never reports a state the server did not return.
 *
 * TOKEN SOURCE IS INJECTED. This app ships no push library, so nothing here
 * can obtain a real FCM/APNs token; the token arrives as a value from a native
 * seam (the same shape as photoSource's injectable picker). Physical delivery
 * and token acquisition are therefore EXTERNALLY-DEPENDENT — only the client
 * lifecycle is proven here.
 */
import { apiRequest, requireOk } from '../my-model/http';
import type { AvatarApiConfig } from '../my-model/types';
import { productionApiConfig } from '../../config/apiConfig';

export const DEVICE_PLATFORMS = ['ANDROID', 'IOS'] as const;
export type DevicePlatform = (typeof DEVICE_PLATFORMS)[number];

export type DeviceRegistration = {
  id: string;
  deviceToken: string;
  platform: DevicePlatform;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string;
};

export type NotificationPreference = {
  pushEnabled: boolean;
  updatedAt: string | null;
};

export type NotificationRegistrationApi = {
  register(deviceToken: string, platform: DevicePlatform): Promise<DeviceRegistration>;
  list(): Promise<DeviceRegistration[]>;
  remove(deviceToken: string): Promise<void>;
  preferences(): Promise<NotificationPreference>;
  updatePreferences(pushEnabled: boolean): Promise<NotificationPreference>;
};

function isPlatform(value: unknown): value is DevicePlatform {
  return (
    typeof value === 'string' &&
    (DEVICE_PLATFORMS as readonly string[]).includes(value)
  );
}

function requiredString(value: unknown): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error('Invalid device registration response');
  }
  return value;
}

function parseDevice(value: unknown): DeviceRegistration {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid device registration response');
  }
  const row = value as Record<string, unknown>;
  if (!isPlatform(row.platform)) {
    throw new Error('Invalid device registration response');
  }
  return {
    id: requiredString(row.id),
    deviceToken: requiredString(row.deviceToken),
    platform: row.platform,
    createdAt: requiredString(row.createdAt),
    updatedAt: requiredString(row.updatedAt),
    lastSeenAt: requiredString(row.lastSeenAt),
  };
}

function parsePreference(value: unknown): NotificationPreference {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid notification preference response');
  }
  const row = value as Record<string, unknown>;
  if (typeof row.pushEnabled !== 'boolean') {
    throw new Error('Invalid notification preference response');
  }
  if (row.updatedAt !== null && typeof row.updatedAt !== 'string') {
    throw new Error('Invalid notification preference response');
  }
  return {
    pushEnabled: row.pushEnabled,
    updatedAt: (row.updatedAt as string | null) ?? null,
  };
}

export function createNotificationRegistrationApi(
  config: AvatarApiConfig,
): NotificationRegistrationApi {
  return {
    async register(deviceToken, platform) {
      if (deviceToken.trim().length === 0) {
        throw new Error('Device token is required');
      }
      return parseDevice(
        await requireOk(
          await apiRequest(config, '/me/devices', {
            method: 'POST',
            body: JSON.stringify({ deviceToken, platform }),
          }),
        ),
      );
    },

    async list() {
      const payload = await requireOk(
        await apiRequest(config, '/me/devices', { method: 'GET' }),
      );
      if (!Array.isArray(payload)) {
        throw new Error('Invalid device registration response');
      }
      return payload.map(parseDevice);
    },

    async remove(deviceToken) {
      if (deviceToken.trim().length === 0) {
        throw new Error('Device token is required');
      }
      // The route answers 204 with no body; requireOk handles that.
      await requireOk(
        await apiRequest(config, '/me/devices', {
          method: 'DELETE',
          body: JSON.stringify({ deviceToken }),
        }),
      );
    },

    async preferences() {
      return parsePreference(
        await requireOk(
          await apiRequest(config, '/me/notification-preferences', {
            method: 'GET',
          }),
        ),
      );
    },

    async updatePreferences(pushEnabled) {
      return parsePreference(
        await requireOk(
          await apiRequest(config, '/me/notification-preferences', {
            method: 'PUT',
            body: JSON.stringify({ pushEnabled }),
          }),
        ),
      );
    },
  };
}

export const notificationRegistrationApi = createNotificationRegistrationApi(
  productionApiConfig,
);

export type RegistrationOutcome =
  | { outcome: 'registered'; registration: DeviceRegistration }
  | { outcome: 'failed' };

export type RemovalOutcome = 'removed' | 'failed';

/**
 * Registers a token and reports honestly. The server upserts, so calling this
 * again with the same token is a safe repeat rather than a duplicate device —
 * which is why a failed registration is reported as `failed` and never
 * silently treated as success.
 */
export async function registerDevice(
  api: Pick<NotificationRegistrationApi, 'register'>,
  deviceToken: string,
  platform: DevicePlatform,
): Promise<RegistrationOutcome> {
  try {
    return { outcome: 'registered', registration: await api.register(deviceToken, platform) };
  } catch {
    return { outcome: 'failed' };
  }
}

/**
 * Removes a token. The route is idempotent and always 204, so a repeat removal
 * is `removed` too; only a transport/auth failure is `failed`.
 */
export async function removeDevice(
  api: Pick<NotificationRegistrationApi, 'remove'>,
  deviceToken: string,
): Promise<RemovalOutcome> {
  try {
    await api.remove(deviceToken);
    return 'removed';
  } catch {
    return 'failed';
  }
}
