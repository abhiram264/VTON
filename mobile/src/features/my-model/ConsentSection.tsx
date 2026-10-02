import { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { ApiProblemError } from './http';
import {
  CONSENT_POLICY_VERSION,
  consentApi,
  consentState,
  type ConsentApi,
  type ConsentPurpose,
  type ConsentRecord,
  type ConsentState,
} from './consentApi';

// The disclosed purposes, in the words the user reads. Each maps 1:1 to a server
// ConsentPurpose; nothing here invents a purpose the backend cannot enforce.
const PURPOSE_COPY: Record<
  ConsentPurpose,
  { title: string; body: string }
> = {
  body_image_processing: {
    title: 'Process my body photo',
    body: 'Use the photo I provided as my My Model to build outfits.',
  },
  third_party_ai_transfer: {
    title: 'Send my photo to the AI provider',
    body: 'Your photo is transferred to a third-party AI provider to generate the Look.',
  },
  retention: {
    title: 'Keep my photo and results',
    body: 'Retain the photo and generated Looks so I can revisit them.',
  },
};

const STATE_COPY: Record<ConsentState, string> = {
  granted: 'Granted',
  withdrawn: 'Withdrawn',
  not_granted: 'Not granted',
};

type LoadState = 'loading' | 'ready' | 'failed';

export default function ConsentSection({
  api = consentApi,
}: { api?: ConsentApi } = {}) {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [records, setRecords] = useState<ConsentRecord[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<ConsentPurpose | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoadState('loading');
    setNotice(null);
    api
      .list()
      .then(value => {
        if (!cancelled) {
          setRecords(value);
          setLoadState('ready');
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadState('failed');
          setNotice('Consent settings could not be loaded.');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api, attempt]);

  const grant = useCallback(
    async (purpose: ConsentPurpose) => {
      setBusy(purpose);
      setNotice(null);
      try {
        await api.grant(purpose, CONSENT_POLICY_VERSION);
        // The server is authoritative for consent state: re-read it rather than
        // guessing what the write did to the history.
        setRecords(await api.list());
      } catch {
        setNotice('Consent could not be recorded. Try again.');
      } finally {
        setBusy(null);
      }
    },
    [api],
  );

  const withdraw = useCallback(
    async (purpose: ConsentPurpose) => {
      setBusy(purpose);
      setNotice(null);
      try {
        await api.withdraw(purpose);
        setRecords(await api.list());
      } catch (error) {
        // A refused withdrawal is a real answer, not a transient error: say so
        // rather than leaving the user believing consent was revoked.
        setNotice(
          error instanceof ApiProblemError && error.code === 'CONSENT_NOT_GRANTED'
            ? 'That consent was not active, so nothing was withdrawn.'
            : 'Consent could not be withdrawn. Try again.',
        );
      } finally {
        setBusy(null);
      }
    },
    [api],
  );

  const confirmWithdraw = useCallback(
    (purpose: ConsentPurpose) => {
      Alert.alert(
        'Withdraw consent?',
        'New Looks that need this will be blocked until you grant it again. Existing items are not deleted.',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Withdraw',
            style: 'destructive',
            onPress: () => {
              void withdraw(purpose);
            },
          },
        ],
        { cancelable: true },
      );
    },
    [withdraw],
  );

  const canAct = loadState === 'ready' && busy === null;

  return (
    <View style={styles.section}>
      <Text accessibilityRole="header" style={styles.heading}>
        Privacy choices
      </Text>
      <Text style={styles.body}>
        These choices control what we may do with your My Model photo. Policy
        version {CONSENT_POLICY_VERSION}.
      </Text>
      {loadState === 'loading' ? (
        <Text accessibilityLiveRegion="polite">Loading consent settings…</Text>
      ) : null}
      {loadState === 'failed' ? (
        <View style={styles.stack}>
          <Text accessibilityLiveRegion="polite" style={styles.notice}>
            {notice}
          </Text>
          <Pressable
            accessibilityRole="button"
            onPress={() => setAttempt(value => value + 1)}
            style={[styles.button, styles.secondary]}
          >
            <Text style={styles.secondaryLabel}>Retry consent settings</Text>
          </Pressable>
        </View>
      ) : null}
      {loadState === 'ready' && notice ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
      {loadState === 'ready'
        ? (Object.keys(PURPOSE_COPY) as ConsentPurpose[]).map(purpose => {
            const state = consentState(records, purpose);
            const copy = PURPOSE_COPY[purpose];
            const active = state === 'granted';
            return (
              <View key={purpose} style={styles.row}>
                <Text style={styles.rowTitle}>{copy.title}</Text>
                <Text style={styles.body}>{copy.body}</Text>
                <Text style={styles.state}>
                  {STATE_COPY[state]}
                  {active ? '' : ' — new Looks needing this are blocked'}
                </Text>
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{ disabled: !canAct }}
                  disabled={!canAct}
                  onPress={() =>
                    active ? confirmWithdraw(purpose) : void grant(purpose)
                  }
                  style={[
                    styles.button,
                    active ? styles.quiet : styles.primary,
                    !canAct && styles.disabled,
                  ]}
                >
                  <Text
                    style={active ? styles.quietLabel : styles.primaryLabel}
                  >
                    {active ? `Withdraw: ${copy.title}` : `Grant: ${copy.title}`}
                  </Text>
                </Pressable>
              </View>
            );
          })
        : null}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  section: {
    gap: 12,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#ffffff',
  },
  stack: {
    gap: 12,
  },
  heading: {
    fontSize: 18,
    fontWeight: '600',
    color: ink,
  },
  row: {
    gap: 6,
    paddingVertical: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#d9d4cc',
  },
  rowTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: ink,
  },
  state: {
    fontSize: 14,
    fontWeight: '600',
    color: accent,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
    color: muted,
  },
  notice: {
    fontSize: 14,
    lineHeight: 20,
    color: '#8a2b2b',
  },
  button: {
    minHeight: 44,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 16,
    borderRadius: 10,
    backgroundColor: paper,
  },
  primary: {
    backgroundColor: accent,
  },
  secondary: {
    borderWidth: 1,
    borderColor: accent,
  },
  quiet: {
    backgroundColor: paper,
  },
  primaryLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: '#ffffff',
  },
  secondaryLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: accent,
  },
  quietLabel: {
    fontSize: 15,
    fontWeight: '600',
    color: muted,
  },
  disabled: {
    opacity: 0.5,
  },
});
