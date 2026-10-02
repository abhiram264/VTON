/**
 * C20 notification-preferences section contract.
 *
 * The behaviours that matter: the section shows only what the server returned,
 * a failed read is never presented as a setting, and a failed write leaves the
 * displayed value at the last confirmed one.
 */
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import NotificationPreferencesSection, {
  NOTIFICATIONS_DELIVERY_UNAVAILABLE_NOTICE,
  NOTIFICATIONS_UNAVAILABLE_NOTICE,
  NOTIFICATIONS_WRITE_FAILED_NOTICE,
} from './NotificationPreferencesSection';
import type { NotificationRegistrationApi } from './notificationRegistration';

type PreferenceApi = Pick<
  NotificationRegistrationApi,
  'preferences' | 'updatePreferences'
>;

function api(overrides: Partial<PreferenceApi> = {}): PreferenceApi {
  return {
    preferences: jest.fn().mockResolvedValue({ pushEnabled: true, updatedAt: null }),
    updatePreferences: jest
      .fn()
      .mockImplementation((pushEnabled: boolean) =>
        Promise.resolve({ pushEnabled, updatedAt: '2026-10-03T12:00:00Z' }),
      ),
    ...overrides,
  };
}

describe('NotificationPreferencesSection', () => {
  test('shows the stored preference and the delivery caveat', async () => {
    await render(
      <NotificationPreferencesSection
        api={api({
          preferences: jest
            .fn()
            .mockResolvedValue({ pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' }),
        })}
      />,
    );

    expect(await screen.findByText('Push notifications are off.')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Turn on push notifications' }),
    ).toBeTruthy();
    // An "on" toggle must never imply delivery works in this build.
    expect(screen.getByText(NOTIFICATIONS_DELIVERY_UNAVAILABLE_NOTICE)).toBeTruthy();
  });

  test('the server default (no stored preference) is shown as on, not invented locally', async () => {
    const preferences = jest
      .fn()
      .mockResolvedValue({ pushEnabled: true, updatedAt: null });

    await render(<NotificationPreferencesSection api={api({ preferences })} />);

    expect(await screen.findByText('Push notifications are on.')).toBeTruthy();
    expect(preferences).toHaveBeenCalledTimes(1);
  });

  test('toggling writes through and shows the value the server returned', async () => {
    const updatePreferences = jest
      .fn()
      .mockResolvedValue({ pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' });

    await render(<NotificationPreferencesSection api={api({ updatePreferences })} />);
    await act(async () => {
      fireEvent.press(
        await screen.findByRole('button', { name: 'Turn off push notifications' }),
      );
    });

    expect(updatePreferences).toHaveBeenCalledWith(false);
    expect(await screen.findByText('Push notifications are off.')).toBeTruthy();
  });

  test('a failed write changes nothing and says so', async () => {
    const updatePreferences = jest
      .fn()
      .mockRejectedValue(new TypeError('Network request failed'));

    await render(<NotificationPreferencesSection api={api({ updatePreferences })} />);
    await act(async () => {
      fireEvent.press(
        await screen.findByRole('button', { name: 'Turn off push notifications' }),
      );
    });

    expect(await screen.findByText(NOTIFICATIONS_WRITE_FAILED_NOTICE)).toBeTruthy();
    // The displayed value is still the last one the server confirmed.
    expect(screen.getByText('Push notifications are on.')).toBeTruthy();
  });

  test('a failed read is presented as unavailable with a retry, not as a setting', async () => {
    const preferences = jest
      .fn()
      .mockRejectedValueOnce(new TypeError('Network request failed'))
      .mockResolvedValueOnce({ pushEnabled: false, updatedAt: '2026-10-03T12:00:00Z' });

    await render(<NotificationPreferencesSection api={api({ preferences })} />);

    expect(await screen.findByText(NOTIFICATIONS_UNAVAILABLE_NOTICE)).toBeTruthy();
    expect(screen.queryByText('Push notifications are on.')).toBeNull();
    expect(screen.queryByText('Push notifications are off.')).toBeNull();

    await act(async () => {
      fireEvent.press(
        screen.getByRole('button', { name: 'Retry notification settings' }),
      );
    });

    expect(await screen.findByText('Push notifications are off.')).toBeTruthy();
    expect(preferences).toHaveBeenCalledTimes(2);
  });
});
