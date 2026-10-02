import { useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { AuthSession, AuthSurface } from '../config/firebase';
import { signOutAccount, type SignOutOutcome } from '../features/auth/accountSession';

export const SIGNED_OUT_NOTICE = 'You have been signed out.';
export const SIGN_OUT_FAILED_NOTICE =
  'Could not sign out. Check your connection and try again.';
export const SIGN_OUT_UNAVAILABLE_NOTICE =
  'Sign out is unavailable because no account service is configured in this build.';

type Props = {
  session?: AuthSession;
  surface?: AuthSurface;
  signOut?: () => Promise<SignOutOutcome>;
  onBack?: () => void;
  onOpenMyModel?: () => void;
  onSignedOut?: () => void;
  /**
   * Additional account sections rendered after the sign-out block (e.g. the
   * account-deletion card). Kept as a slot so this file stays the single
   * writer of the settings surface.
   */
  children?: ReactNode;
};

function accountLine(session: AuthSession | undefined): string {
  switch (session?.status) {
    case 'signed-in':
      // The raw Firebase UID is a debug identifier, not a product state, so it
      // is deliberately not rendered (C23).
      return 'Signed in.';
    case 'signed-out':
      return 'Not signed in.';
    case 'unavailable':
      return 'Account service is unavailable in this build.';
    default:
      return 'Checking account…';
  }
}

/**
 * The account settings surface (C03).
 *
 * Before this screen the only route named "Profile and settings" rendered My
 * Model, so the app had no sign-out control at all. Sign-out is delegated to
 * `signOutAccount` (features/auth/accountSession), which performs the SDK
 * sign-out and then drops the account-bound local pointers, so a second
 * account never inherits the first account's state.
 *
 * The three outcomes are reported distinctly and none of them is optimistic:
 * a failed SDK call is never reported as signed out, and an unavailable auth
 * surface (no Firebase configuration) is reported as such rather than
 * pretending a credential was dropped.
 */
export default function SettingsScreen({
  session,
  surface,
  signOut,
  onBack,
  onOpenMyModel,
  onSignedOut,
  children,
}: Props = {}) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function submit() {
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const outcome = await (signOut ?? (() => signOutAccount({ surface })))();
      if (outcome === 'unavailable') {
        setNotice(SIGN_OUT_UNAVAILABLE_NOTICE);
        return;
      }
      setNotice(SIGNED_OUT_NOTICE);
      onSignedOut?.();
    } catch {
      setNotice(SIGN_OUT_FAILED_NOTICE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.page}>
      {onBack ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back"
          onPress={onBack}
          style={styles.link}
        >
          <Text style={styles.linkLabel}>Back</Text>
        </Pressable>
      ) : null}
      <Text accessibilityRole="header" style={styles.title}>
        Settings
      </Text>
      <Text style={styles.body}>{accountLine(session)}</Text>
      {notice ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
      {session?.status === 'signed-out' ? null : (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          accessibilityState={{ busy, disabled: busy }}
          disabled={busy}
          onPress={submit}
          style={[styles.button, styles.primary, busy && styles.disabled]}
        >
          <Text style={styles.primaryLabel}>
            {busy ? 'Signing out…' : 'Sign out'}
          </Text>
        </Pressable>
      )}
      {onOpenMyModel ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="My Model"
          onPress={onOpenMyModel}
          style={styles.button}
        >
          <Text style={styles.secondaryLabel}>My Model</Text>
        </Pressable>
      ) : null}
      {children}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: paper, padding: 24, gap: 16 },
  title: { color: ink, fontSize: 28, fontWeight: '700' },
  body: { color: muted, fontSize: 15, lineHeight: 22 },
  notice: { color: muted, fontSize: 14, lineHeight: 20 },
  link: { minHeight: 44, justifyContent: 'center' },
  linkLabel: { color: accent, fontWeight: '600' },
  button: {
    minHeight: 44,
    justifyContent: 'center',
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  primary: { backgroundColor: accent },
  disabled: { opacity: 0.5 },
  primaryLabel: { color: '#ffffff', fontSize: 16, fontWeight: '600' },
  secondaryLabel: { color: ink, fontSize: 16, fontWeight: '600' },
});
