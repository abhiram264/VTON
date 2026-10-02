import { useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
} from '@firebase/auth';
import { getAuthSurface, type AuthSurface } from '../config/firebase';

export const NOT_CONFIGURED_MESSAGE =
  'Sign in is unavailable in this local build: Firebase is not configured. Set the Firebase config values, then rebuild.';

const GENERIC_CREDENTIAL_MESSAGE = 'Email or password is incorrect.';

// Neutral by design: an account-creation failure must never confirm whether an
// address already has an account. The user is pointed at the two paths that
// work regardless of the answer.
const GENERIC_SIGN_UP_MESSAGE =
  'Could not create the account. Try signing in instead, or reset your password.';

// Password reset is deliberately answer-free: it reports that a request was
// accepted, never whether the address is registered. Firebase returns
// `auth/user-not-found` for unknown addresses, so that code must fall through
// to this same line rather than being surfaced.
export const RESET_ACCEPTED_MESSAGE =
  'If that email is registered, a password reset link is on its way.';

const FAILURE_MESSAGES: Record<string, string> = {
  'auth/invalid-credential': GENERIC_CREDENTIAL_MESSAGE,
  'auth/wrong-password': GENERIC_CREDENTIAL_MESSAGE,
  'auth/user-not-found': GENERIC_CREDENTIAL_MESSAGE,
  'auth/invalid-email': 'Enter a valid email address.',
  'auth/too-many-requests':
    'Too many sign in attempts. Wait a moment and try again.',
  'auth/network-request-failed':
    'Could not reach the sign in service. Check your connection and try again.',
};

// Only codes that disclose nothing about account existence are surfaced for
// sign-up; everything else (including `auth/email-already-in-use`) collapses
// to the generic line.
const SIGN_UP_FAILURE_MESSAGES: Record<string, string> = {
  'auth/invalid-email': 'Enter a valid email address.',
  'auth/weak-password': 'Choose a stronger password (at least 6 characters).',
  'auth/too-many-requests':
    'Too many attempts. Wait a moment and try again.',
  'auth/network-request-failed':
    'Could not reach the sign in service. Check your connection and try again.',
};

const GENERIC_FAILURE_MESSAGE = 'Could not sign in. Try again.';

export function messageForAuthError(error: unknown): string {
  const code = (error as { code?: string } | null)?.code;
  return (code ? FAILURE_MESSAGES[code] : undefined) ?? GENERIC_FAILURE_MESSAGE;
}

export function messageForSignUpError(error: unknown): string {
  const code = (error as { code?: string } | null)?.code;
  return (
    (code ? SIGN_UP_FAILURE_MESSAGES[code] : undefined) ?? GENERIC_SIGN_UP_MESSAGE
  );
}

type Mode = 'signIn' | 'signUp';

type Props = {
  surface?: AuthSurface;
  signIn?: (email: string, password: string) => Promise<void>;
  signUp?: (email: string, password: string) => Promise<void>;
  sendPasswordReset?: (email: string) => Promise<void>;
  onSignedIn?: () => void;
};

export default function SignInScreen({
  surface,
  signIn,
  signUp,
  sendPasswordReset,
  onSignedIn,
}: Props = {}) {
  const [mode, setMode] = useState<Mode>('signIn');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const operationLocked = useRef(false);

  const active: AuthSurface | null = surface ?? getAuthSurface();
  const available = active?.status === 'available' ? active : null;
  const submitSignIn = available
    ? (signIn ??
      ((mail: string, secret: string) =>
        signInWithEmailAndPassword(available.auth, mail, secret).then(
          () => undefined,
        )))
    : null;
  const submitSignUp = available
    ? (signUp ??
      ((mail: string, secret: string) =>
        createUserWithEmailAndPassword(available.auth, mail, secret).then(
          () => undefined,
        )))
    : null;
  const requestReset = available
    ? (sendPasswordReset ??
      ((mail: string) =>
        sendPasswordResetEmail(available.auth, mail).then(() => undefined)))
    : null;

  async function submit() {
    if (!submitSignIn || operationLocked.current) return;
    if (email.trim().length === 0 || password.length === 0) {
      setNotice('Enter your email and password.');
      return;
    }
    operationLocked.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await submitSignIn(email.trim(), password);
      onSignedIn?.();
    } catch (error) {
      setNotice(messageForAuthError(error));
    } finally {
      operationLocked.current = false;
      setBusy(false);
    }
  }

  async function createAccount() {
    if (!submitSignUp || operationLocked.current) return;
    if (email.trim().length === 0 || password.length === 0) {
      setNotice('Enter your email and password.');
      return;
    }
    operationLocked.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await submitSignUp(email.trim(), password);
      onSignedIn?.();
    } catch (error) {
      setNotice(messageForSignUpError(error));
    } finally {
      operationLocked.current = false;
      setBusy(false);
    }
  }

  async function requestPasswordReset() {
    if (!requestReset || operationLocked.current) return;
    if (email.trim().length === 0) {
      setNotice('Enter your email address.');
      return;
    }
    operationLocked.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await requestReset(email.trim());
      setNotice(RESET_ACCEPTED_MESSAGE);
    } catch (error) {
      const code = (error as { code?: string } | null)?.code;
      // A malformed address and a transport failure are honest to report; an
      // unknown address is not, and falls through to the accepted line.
      if (code === 'auth/invalid-email') {
        setNotice('Enter a valid email address.');
      } else if (
        code === 'auth/network-request-failed' ||
        code === 'auth/too-many-requests'
      ) {
        setNotice(messageForAuthError(error));
      } else {
        setNotice(RESET_ACCEPTED_MESSAGE);
      }
    } finally {
      operationLocked.current = false;
      setBusy(false);
    }
  }

  function switchMode(next: Mode) {
    if (operationLocked.current) return;
    setMode(next);
    setNotice(null);
  }

  const disabled = !available || busy;
  const signingUp = mode === 'signUp';
  const primaryLabel = signingUp ? 'Create Account' : 'Sign In';

  return (
    <View style={styles.page}>
      <Text accessibilityRole="header" style={styles.title}>
        {primaryLabel}
      </Text>
      {available ? null : (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {NOT_CONFIGURED_MESSAGE}
        </Text>
      )}
      <TextInput
        accessibilityLabel="Email"
        accessibilityState={{ disabled }}
        autoCapitalize="none"
        autoComplete="email"
        editable={!disabled}
        keyboardType="email-address"
        onChangeText={setEmail}
        placeholder="Email"
        style={styles.input}
        value={email}
      />
      <TextInput
        accessibilityLabel="Password"
        accessibilityState={{ disabled }}
        autoCapitalize="none"
        editable={!disabled}
        onChangeText={setPassword}
        placeholder="Password"
        secureTextEntry
        style={styles.input}
        value={password}
      />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={primaryLabel}
        accessibilityState={{ disabled, busy }}
        disabled={disabled}
        onPress={signingUp ? createAccount : submit}
        style={[styles.button, disabled && styles.disabled]}
      >
        <Text style={styles.buttonLabel}>{primaryLabel}</Text>
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Forgot password?"
        accessibilityState={{ disabled, busy }}
        disabled={disabled}
        onPress={requestPasswordReset}
        style={styles.link}
      >
        <Text style={[styles.linkLabel, disabled && styles.disabled]}>
          Forgot password?
        </Text>
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={signingUp ? 'Sign In' : 'Create Account'}
        accessibilityState={{ disabled, busy }}
        disabled={disabled}
        onPress={() => switchMode(signingUp ? 'signIn' : 'signUp')}
        style={styles.link}
      >
        <Text style={[styles.linkLabel, disabled && styles.disabled]}>
          {signingUp ? 'Sign In' : 'Create Account'}
        </Text>
      </Pressable>
      {notice ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' },
  title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' },
  input: {
    minHeight: 44,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#cdc7bd',
    paddingHorizontal: 12,
    color: '#1a1a1a',
    backgroundColor: '#fff',
  },
  button: {
    minHeight: 44,
    justifyContent: 'center',
    borderRadius: 8,
    paddingHorizontal: 16,
    backgroundColor: '#6b4f2a',
  },
  buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' },
  link: { minHeight: 44, justifyContent: 'center' },
  linkLabel: { color: '#6b4f2a', fontWeight: '600', textAlign: 'center' },
  disabled: { opacity: 0.45 },
  notice: { color: '#8a2f2b' },
});
