import { useEffect, useRef, useState } from 'react';
import { Pressable, Share, StyleSheet, Text, View } from 'react-native';
import { looksApi } from '../features/looks/looksApi';
import { generationApi, pollGeneration } from '../features/generation/generationApi';
import type { LookResource } from '../features/generation/types';

type Props = { onModify: (lookId: string) => void; onOpenLook: (lookId: string) => void; onProfile: () => void; pollDelay?: (ms: number) => Promise<void> };

export default function LooksScreen({ onModify, onOpenLook, onProfile, pollDelay }: Props) {
  const [looks, setLooks] = useState<LookResource[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [busy, setBusy] = useState(false);
  const regenerated = useRef(new Set<string>());
  useEffect(() => { let active = true; void looksApi.list().then(value => { if (active) { setLooks(value); setStatus('ready'); } }, () => { if (active) setStatus('failed'); }); return () => { active = false; }; }, []);

  async function regenerate() {
    if (!selected || busy || regenerated.current.has(selected)) return;
    setBusy(true);
    try {
      const generation = await looksApi.regenerate(selected);
      regenerated.current.add(selected);
      const completed = await pollGeneration({ get: generationApi.get, id: generation.id, delay: pollDelay });
      if (completed.lookId) onOpenLook(completed.lookId);
    } finally { setBusy(false); }
  }
  async function share() { if (!selected || busy) return; setBusy(true); try { await Share.share({ message: await looksApi.share(selected) }); } finally { setBusy(false); } }

  const disabled = !selected || busy;
  return <View style={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Looks</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    <Text accessibilityRole="header" style={styles.heading}>Saved Looks</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Looks…</Text> : null}
    {status === 'failed' ? <Text accessibilityLiveRegion="polite">Could not load Looks. You can keep using navigation.</Text> : null}
    {status === 'ready' && looks.length === 0 ? <Text>No saved Looks yet.</Text> : null}
    {looks.map(look => <Pressable key={look.id} accessibilityRole="button" accessibilityLabel={`Look ${look.id}`} accessibilityState={{ selected: selected === look.id }} onPress={() => setSelected(look.id)} onLongPress={() => onOpenLook(look.id)} style={styles.button}><Text style={styles.buttonLabel}>Look created {look.createdAt}</Text></Pressable>)}
    <Text accessibilityRole="header" style={styles.heading}>Recent generations</Text><Text>No recent generations yet.</Text>
    <Action label="Regenerate" onPress={regenerate} disabled={disabled} busy={busy} />
    <Action label="Modify" onPress={() => selected && onModify(selected)} disabled={disabled} />
    <Action label="Share" onPress={share} disabled={disabled} busy={busy} />
    {!selected ? <Text>Select a Look to regenerate, modify, or share it.</Text> : null}
  </View>;
}

function Action({ label, onPress, disabled = false, busy = false }: { label: string; onPress: () => void; disabled?: boolean; busy?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled, busy }} disabled={disabled} onPress={onPress} style={[styles.button, disabled && styles.disabled]}><Text style={styles.buttonLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, button: { minHeight: 44, justifyContent: 'center', borderRadius: 8, paddingHorizontal: 16, backgroundColor: '#6b4f2a' }, buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' }, disabled: { opacity: 0.45 } });
