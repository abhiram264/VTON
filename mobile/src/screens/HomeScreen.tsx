import { useCallback, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
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

export default function HomeScreen({ onImport, onOpenLook, onProfile, onQuickTry }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [looks, setLooks] = useState<LookResource[]>([]);
  const [looksStatus, setLooksStatus] = useState<'loading' | 'ready' | 'failed'>('loading');

  useFocusEffect(useCallback(() => {
    let active = true;
    setLooksStatus('loading');
    setLooks([]);
    void looksApi.list().then(value => { if (active) { setLooks(value); setLooksStatus('ready'); } }, () => { if (active) { setLooks([]); setLooksStatus('failed'); } });
    return () => { active = false; };
  }, []));
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
      {looksStatus === 'loading' ? <Text accessibilityLiveRegion="polite">Loading recent Looks…</Text> : null}
      {looksStatus === 'failed' ? <Text accessibilityLiveRegion="polite">Could not load recent Looks.</Text> : null}
      {looksStatus === 'ready' && looks.length === 0 ? <Text>No recent Looks yet.</Text> : null}
      {looks.map(look => <Action key={look.id} label={`Recent Look ${look.id}`} onPress={() => onOpenLook(look.id)} />)}
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
