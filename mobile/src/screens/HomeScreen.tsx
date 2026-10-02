import { useCallback, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
import { ApiProblemError } from '../features/my-model/http';
import { looksApi } from '../features/looks/looksApi';
import type { LookResource } from '../features/generation/types';
import { useFocusEffect } from '@react-navigation/native';
import type { SharePayload } from '../features/share/types';

type Props = {
  onImport: (payload: SharePayload) => void;
  onOpenLook: (lookId: string) => void;
  onProfile: () => void;
  onQuickTry: () => void;
};

// Failure is differentiated so the user is told the truth: a revoked/expired
// session is not something "Retry" can fix, while a transient server or
// network failure is. Neither state is presented as an empty list.
type LooksState =
  | { status: 'loading' }
  | { status: 'ready'; looks: LookResource[] }
  | { status: 'error'; kind: 'unauthorized' | 'retryable' };

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

// Product label for a Look. The backend Look id is an opaque UUID and must not
// be shown to the user as the item's name; the creation date is the one
// human-meaningful field the list resource carries. UTC keeps the label stable
// regardless of the device timezone.
export function lookLabel(createdAt: string): string {
  const parsed = new Date(createdAt);
  if (Number.isNaN(parsed.getTime())) {
    return 'Look from earlier';
  }
  return `Look from ${parsed.getUTCDate()} ${MONTHS[parsed.getUTCMonth()]} ${parsed.getUTCFullYear()}`;
}

// Two Looks created on the same day would otherwise share an accessible name,
// which screen readers cannot tell apart; disambiguate duplicates in order.
function labeledLooks(looks: LookResource[]): { look: LookResource; label: string }[] {
  const seen = new Map<string, number>();
  return looks.map(look => {
    const base = lookLabel(look.createdAt);
    const count = (seen.get(base) ?? 0) + 1;
    seen.set(base, count);
    return { look, label: count === 1 ? base : `${base} (${count})` };
  });
}

export default function HomeScreen({ onImport, onOpenLook, onProfile, onQuickTry }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [looksState, setLooksState] = useState<LooksState>({ status: 'loading' });
  // Monotonic request id: a slower earlier load must not overwrite a newer one
  // (focus reload, Retry, or a blur that invalidates the in-flight response).
  const looksRequest = useRef(0);

  // One loader for both entry points (focus and the Retry control), so the two
  // paths cannot drift and neither needs a synthetic re-run dependency.
  const loadLooks = useCallback(async () => {
    const request = ++looksRequest.current;
    setLooksState({ status: 'loading' });
    try {
      const value = await looksApi.list();
      if (looksRequest.current === request) {
        setLooksState({ status: 'ready', looks: value });
      }
    } catch (error) {
      if (looksRequest.current !== request) {
        return;
      }
      const kind =
        error instanceof ApiProblemError && error.code === 'UNAUTHORIZED'
          ? 'unauthorized'
          : 'retryable';
      setLooksState({ status: 'error', kind });
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadLooks();
      // Blur invalidates whatever this focus started.
      return () => {
        looksRequest.current += 1;
      };
    }, [loadLooks]),
  );

  async function importItem() {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const photo = await choosePhoto();
      if (photo) onImport({ kind: 'image', localImageUri: photo.uri });
      else setNotice('No photo was selected. Photo selection may be unavailable. Try again.');
    } catch {
      setNotice('Could not select an item. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <ScrollView style={styles.page} contentContainerStyle={styles.content}>
      <View style={styles.headingRow}>
        <Text accessibilityRole="header" style={styles.title}>Home</Text>
        <Action label="Profile and settings" onPress={onProfile} />
      </View>
      <Action label="Import Item" onPress={importItem} busy={busy} />
      {notice ? <Text accessibilityLiveRegion="polite" style={styles.error}>{notice}</Text> : null}
      <Text accessibilityRole="header" style={styles.heading}>Recent shares</Text>
      <Text>No recent shares yet.</Text>
      <Text accessibilityRole="header" style={styles.heading}>Recent Looks</Text>
      {looksState.status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading recent Looks…</Text> : null}
      {looksState.status === 'error' && looksState.kind === 'unauthorized' ? (
        <Text accessibilityLiveRegion="polite">Your session has expired. Sign in again to see your Looks.</Text>
      ) : null}
      {looksState.status === 'error' && looksState.kind === 'retryable' ? (
        <>
          <Text accessibilityLiveRegion="polite">Could not load recent Looks.</Text>
          <Action label="Retry loading Looks" onPress={() => void loadLooks()} />
        </>
      ) : null}
      {looksState.status === 'ready' && looksState.looks.length === 0 ? <Text>No recent Looks yet.</Text> : null}
      {looksState.status === 'ready'
        ? labeledLooks(looksState.looks).map(({ look, label }) => (
            <Action key={look.id} label={label} onPress={() => onOpenLook(look.id)} />
          ))
        : null}
      <Action label="Quick Try" onPress={onQuickTry} />
    </ScrollView>
  );
}

function Action({ label, onPress, busy = false }: { label: string; onPress: () => void; busy?: boolean }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ busy }} disabled={busy} onPress={onPress} style={styles.button}>
      <Text style={styles.buttonLabel}>{busy ? 'Selecting…' : label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#f4f4f2' },
  content: { padding: 20, gap: 14 },
  headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' },
  heading: { fontSize: 20, fontWeight: '700', color: '#1a1a1a' },
  button: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 8, backgroundColor: '#6b4f2a' },
  buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' },
  error: { color: '#8a2f2b' },
});
