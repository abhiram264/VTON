/**
 * Notification preferences section (C20).
 *
 * Rendered inside the settings surface via its `children` slot. The value shown
 * is always the one the server returned: a failed read is presented as
 * unavailable with a retry, never as "enabled" or "disabled", and a failed
 * write leaves the displayed value at the last value the server confirmed.
 *
 * HONESTY NOTE ON DELIVERY: this build ships no push library, so no device
 * token can be obtained and no push can arrive. The section says so rather
 * than letting an "enabled" toggle imply that notifications will actually be
 * delivered. The server-side preference is real and is what the toggle
 * controls; delivery itself is EXTERNALLY-DEPENDENT.
 */
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  notificationRegistrationApi,
  type NotificationPreference,
  type NotificationRegistrationApi,
} from './notificationRegistration';

export const NOTIFICATION_PREFERENCES_TITLE = 'Notifications';
export const NOTIFICATIONS_UNAVAILABLE_NOTICE =
  'Notification settings could not be loaded.';
export const NOTIFICATIONS_WRITE_FAILED_NOTICE =
  'Could not save that change. Nothing was updated.';
export const NOTIFICATIONS_DELIVERY_UNAVAILABLE_NOTICE =
  'This build has no push provider configured, so notifications cannot be delivered to this device yet. Your preference is saved for when it is.';

type State =
  | { status: 'loading' }
  | { status: 'ready'; preference: NotificationPreference }
  | { status: 'unavailable' };

type Props = {
  api?: Pick<
    NotificationRegistrationApi,
    'preferences' | 'updatePreferences'
  >;
};

export default function NotificationPreferencesSection({
  api = notificationRegistrationApi,
}: Props = {}) {
  const [state, setState] = useState<State>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    void api.preferences().then(
      preference => {
        if (!cancelled) {
          setState({ status: 'ready', preference });
        }
      },
      () => {
        if (!cancelled) {
          setState({ status: 'unavailable' });
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [api, attempt]);

  async function toggle(next: boolean) {
    if (busy || state.status !== 'ready') {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const preference = await api.updatePreferences(next);
      setState({ status: 'ready', preference });
    } catch {
      // The server is authoritative: nothing changed locally, so the displayed
      // value stays at the last confirmed one.
      setNotice(NOTIFICATIONS_WRITE_FAILED_NOTICE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.block}>
      <Text accessibilityRole="header" style={styles.heading}>
        {NOTIFICATION_PREFERENCES_TITLE}
      </Text>
      {state.status === 'loading' ? (
        <Text accessibilityLiveRegion="polite" style={styles.body}>
          Loading notification settings…
        </Text>
      ) : state.status === 'unavailable' ? (
        <>
          <Text accessibilityLiveRegion="polite" style={styles.notice}>
            {NOTIFICATIONS_UNAVAILABLE_NOTICE}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: busy }}
            disabled={busy}
            onPress={() => setAttempt(value => value + 1)}
            style={[styles.button, styles.secondary]}
          >
            <Text style={styles.secondaryLabel}>Retry notification settings</Text>
          </Pressable>
        </>
      ) : (
        <>
          <Text accessibilityLiveRegion="polite" style={styles.body}>
            {state.preference.pushEnabled
              ? 'Push notifications are on.'
              : 'Push notifications are off.'}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: busy, busy }}
            disabled={busy}
            onPress={() => void toggle(!state.preference.pushEnabled)}
            style={[
              styles.button,
              state.preference.pushEnabled ? styles.quiet : styles.secondary,
              busy && styles.disabled,
            ]}
          >
            <Text
              style={
                state.preference.pushEnabled
                  ? styles.quietLabel
                  : styles.secondaryLabel
              }
            >
              {state.preference.pushEnabled
                ? 'Turn off push notifications'
                : 'Turn on push notifications'}
            </Text>
          </Pressable>
          <Text style={styles.body}>
            {NOTIFICATIONS_DELIVERY_UNAVAILABLE_NOTICE}
          </Text>
        </>
      )}
      {notice ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  block: {
    borderColor: '#d8d3cc',
    borderRadius: 8,
    borderWidth: 1,
    gap: 12,
    margin: 16,
    padding: 12,
  },
  heading: { color: ink, fontSize: 17, fontWeight: '600' },
  body: { color: muted, fontSize: 15, lineHeight: 22 },
  notice: { color: '#8a2f2b', fontSize: 15, lineHeight: 22 },
  button: {
    alignItems: 'center',
    borderRadius: 8,
    justifyContent: 'center',
    minHeight: 44,
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  secondary: { backgroundColor: paper, borderColor: ink, borderWidth: 1 },
  secondaryLabel: { color: ink, fontSize: 15, fontWeight: '600' },
  quiet: { backgroundColor: paper },
  quietLabel: { color: muted, fontSize: 15, fontWeight: '500' },
  disabled: { opacity: 0.45 },
});
