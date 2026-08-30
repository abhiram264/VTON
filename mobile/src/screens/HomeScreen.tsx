import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
import type { SharePayload } from '../features/share/types';

type Props = {
  onImport: (payload: SharePayload) => void;
  onOpenLook: (lookId: string) => void;
  onProfile: () => void;
  onQuickTry: () => void;
};

export default function HomeScreen({ onImport, onProfile, onQuickTry }: Props) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function importItem() {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const photo = await choosePhoto();
      if (photo) onImport({ kind: 'image', localImageUri: photo.uri });
    } catch {
      setNotice('Could not select an item. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.page}>
      <View style={styles.headingRow}>
        <Text accessibilityRole="header" style={styles.title}>Home</Text>
        <Action label="Profile and settings" onPress={onProfile} />
      </View>
      <Action label="Import Item" onPress={importItem} busy={busy} />
      {notice ? <Text accessibilityLiveRegion="polite" style={styles.error}>{notice}</Text> : null}
      <Text accessibilityRole="header" style={styles.heading}>Recent shares</Text>
      <Text>No recent shares yet.</Text>
      <Text accessibilityRole="header" style={styles.heading}>Recent Looks</Text>
      <Text>No recent Looks yet.</Text>
      <Action label="Quick Try" onPress={onQuickTry} />
    </View>
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
  page: { flex: 1, padding: 20, gap: 14, backgroundColor: '#f4f4f2' },
  headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' },
  heading: { fontSize: 20, fontWeight: '700', color: '#1a1a1a' },
  button: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 8, backgroundColor: '#6b4f2a' },
  buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' },
  error: { color: '#8a2f2b' },
});
