import { useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { signInWithEmailAndPassword } from '@firebase/auth';
import { getAuthSurface, type AuthSurface } from '../config/firebase';

export const NOT_CONFIGURED_MESSAGE =
  'Sign in is unavailable in this local build: Firebase is not configured. Set the Firebase config values, then rebuild.';

const GENERIC_CREDENTIAL_MESSAGE = 'Email or password is incorrect.';

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

const GENERIC_FAILURE_MESSAGE = 'Could not sign in. Try again.';

export function messageForAuthError(error: unknown): string {
  const code = (error as { code?: string } | null)?.code;
  return (code ? FAILURE_MESSAGES[code] : undefined) ?? GENERIC_FAILURE_MESSAGE;
}

type Props = {
  surface?: AuthSurface;
  signIn?: (email: string, password: string) => Promise<void>;
  onSignedIn?: () => void;
};

export default function SignInScreen({
  surface,
  signIn,
  onSignedIn,
}: Props = {}) {
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

  const disabled = !submitSignIn || busy;

  return (
    <View style={styles.page}>
      <Text accessibilityRole="header" style={styles.title}>
        Sign In
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
        accessibilityLabel="Sign In"
        accessibilityState={{ disabled, busy }}
        disabled={disabled}
        onPress={submit}
        style={[styles.button, disabled && styles.disabled]}
      >
        <Text style={styles.buttonLabel}>Sign In</Text>
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
  disabled: { opacity: 0.45 },
  notice: { color: '#8a2f2b' },
});
