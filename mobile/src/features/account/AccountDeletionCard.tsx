import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { ApiProblemError } from '../my-model/http';
import {
  deleteAccount as defaultDeleteAccount,
  signOutCurrentSession as defaultSignOut,
} from './accountApi';

export type AccountDeletionDeps = {
  deleteAccount?: () => Promise<void>;
  signOut?: () => Promise<void>;
};

type Phase = 'idle' | 'confirming' | 'deleting' | 'accepted' | 'failed';

const REQUESTED_BODY =
  'Your account deletion has been requested. Deletion runs in the background; anything already exported or saved outside the app is not affected.';

const WITHDRAW_BODY =
  'This permanently deletes your account, its My Model photos, Vault garments and Looks. It cannot be undone.';

/**
 * Truthful failure copy: an unrecognised failure must never read as if the
 * deletion happened, so every branch states that nothing was deleted.
 */
function failureMessage(error: unknown): string {
  if (error instanceof ApiProblemError) {
    if (error.code === 'UNAUTHORIZED') {
      return 'Your session has expired, so the deletion was not requested. Sign in again and retry.';
    }
    if (/^HTTP_5\d\d$/.test(error.code)) {
      return 'The server could not accept the deletion request. Nothing was deleted. Try again.';
    }
  }
  return 'We could not request the deletion. Nothing was deleted. Try again.';
}

export default function AccountDeletionCard({
  deps,
}: {
  deps?: AccountDeletionDeps;
} = {}) {
  const remove = deps?.deleteAccount ?? defaultDeleteAccount;
  const signOut = deps?.signOut ?? defaultSignOut;
  const [phase, setPhase] = useState<Phase>('idle');
  const [message, setMessage] = useState<string | null>(null);
  const [signOutFailed, setSignOutFailed] = useState(false);

  const requestDeletion = async () => {
    if (phase === 'deleting') {
      return;
    }
    setPhase('deleting');
    setMessage(null);
    try {
      await remove();
    } catch (error) {
      setPhase('failed');
      setMessage(failureMessage(error));
      return;
    }
    // Only a server-accepted deletion ends the local session. A local
    // sign-out failure is reported, but it never turns an accepted deletion
    // into a failed one.
    let localSignOutFailed = false;
    try {
      await signOut();
    } catch {
      localSignOutFailed = true;
    }
    setSignOutFailed(localSignOutFailed);
    setPhase('accepted');
  };

  if (phase === 'accepted') {
    return (
      <View style={styles.card} testID="account-deletion-requested">
        <Text accessibilityRole="header" style={styles.heading}>
          Account deletion
        </Text>
        <Text style={styles.body}>{REQUESTED_BODY}</Text>
        {signOutFailed ? (
          <Text style={styles.notice}>
            We could not sign you out on this device. Close and reopen the app.
          </Text>
        ) : null}
      </View>
    );
  }

  return (
    <View style={styles.card}>
      <Text accessibilityRole="header" style={styles.heading}>
        Delete account
      </Text>
      <Text style={styles.body}>{WITHDRAW_BODY}</Text>
      {phase === 'idle' ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Delete account"
          onPress={() => {
            setMessage(null);
            setPhase('confirming');
          }}
          style={styles.destructive}
        >
          <Text style={styles.destructiveLabel}>Delete account</Text>
        </Pressable>
      ) : null}
      {phase === 'confirming' ? (
        <View style={styles.row}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Cancel deletion"
            onPress={() => setPhase('idle')}
            style={styles.secondary}
          >
            <Text style={styles.secondaryLabel}>Cancel</Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Confirm delete account"
            onPress={requestDeletion}
            style={styles.destructive}
          >
            <Text style={styles.destructiveLabel}>Delete my account</Text>
          </Pressable>
        </View>
      ) : null}
      {phase === 'deleting' ? (
        <Text style={styles.body}>Requesting deletion…</Text>
      ) : null}
      {phase === 'failed' ? (
        <View style={styles.stack}>
          <Text accessibilityRole="alert" style={styles.notice}>
            {message}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Delete account"
            onPress={() => setPhase('confirming')}
            style={styles.destructive}
          >
            <Text style={styles.destructiveLabel}>Try again</Text>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';

const styles = StyleSheet.create({
  card: {
    backgroundColor: paper,
    padding: 16,
    gap: 12,
  },
  heading: {
    color: ink,
    fontSize: 18,
    fontWeight: '700',
  },
  body: {
    color: muted,
    fontSize: 14,
    lineHeight: 20,
  },
  notice: {
    color: '#8a2f2b',
    fontSize: 14,
    lineHeight: 20,
  },
  stack: {
    gap: 12,
  },
  row: {
    flexDirection: 'row',
    gap: 12,
  },
  secondary: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: ink,
  },
  secondaryLabel: {
    color: ink,
    fontSize: 14,
    fontWeight: '600',
  },
  destructive: {
    minHeight: 44,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#8a2f2b',
  },
  destructiveLabel: {
    color: '#8a2f2b',
    fontSize: 14,
    fontWeight: '600',
  },
});
