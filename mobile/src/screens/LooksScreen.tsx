import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Pressable, Share, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { looksApi } from '../features/looks/looksApi';
import { generationApi, pollGeneration } from '../features/generation/generationApi';
import type { GenerationResource, LookResource } from '../features/generation/types';

type Props = { onModify: (lookId: string) => void; onOpenLook: (lookId: string) => void; onProfile: () => void; pollDelay?: (ms: number) => Promise<void> };

export default function LooksScreen({ onModify, onOpenLook, onProfile, pollDelay }: Props) {
  const [looks, setLooks] = useState<LookResource[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [recentGeneration, setRecentGeneration] = useState<{ sourceLookId: string; generation: GenerationResource } | null>(null);
  const [brokenImages, setBrokenImages] = useState<ReadonlySet<string>>(() => new Set());
  const selectedRef = useRef<string | null>(null);
  const acceptedGenerations = useRef(new Map<string, string>());
  const recentGenerationRef = useRef<{ sourceLookId: string; generation: GenerationResource } | null>(null);
  const operationLocked = useRef(false);
  const selectionVersion = useRef(0);
  const screenActive = useRef(true);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useFocusEffect(useCallback(() => {
    let active = true;
    screenActive.current = true;
    setBusy(operationLocked.current);
    setStatus('loading');
    setLooks([]);
    selectedRef.current = null;
    setSelected(null);
    setActionError(null);
    void looksApi.list().then(value => {
      if (!active) return;
      setLooks(value);
      setStatus('ready');
      setRecentGeneration(recentGenerationRef.current);
      if (selectedRef.current && !value.some(look => look.id === selectedRef.current)) {
        selectedRef.current = null;
        setSelected(null);
        setActionError(null);
      }
    }, () => { if (active) { setLooks([]); setStatus('failed'); } });
    return () => {
      active = false;
      screenActive.current = false;
      selectionVersion.current += 1;
    };
  }, []));

  function selectLook(lookId: string) {
    selectionVersion.current += 1;
    selectedRef.current = lookId;
    setSelected(lookId);
    setActionError(null);
  }

  function rememberGeneration(sourceLookId: string, generation: GenerationResource) {
    const value = { sourceLookId, generation };
    recentGenerationRef.current = value;
    if (mounted.current) setRecentGeneration(value);
  }

  async function regenerate() {
    const sourceLookId = selected;
    if (!sourceLookId || operationLocked.current) return;
    operationLocked.current = true;
    setBusy(true);
    setActionError(null);
    const version = selectionVersion.current;
    const isCurrent = () => screenActive.current && selectionVersion.current === version && selectedRef.current === sourceLookId;
    try {
      let generationId = acceptedGenerations.current.get(sourceLookId);
      if (!generationId) {
        const generation = await looksApi.regenerate(sourceLookId);
        generationId = generation.id;
        acceptedGenerations.current.set(sourceLookId, generationId);
        rememberGeneration(sourceLookId, generation);
      }
      const completed = await pollGeneration({
        get: generationApi.get,
        id: generationId,
        delay: pollDelay,
        onUpdate: generation => rememberGeneration(sourceLookId, generation),
      });
      acceptedGenerations.current.delete(sourceLookId);
      if (completed.status === 'FAILED') {
        if (isCurrent()) setActionError(`Generation failed${completed.failureCode ? ` (${completed.failureCode})` : ''}. You can regenerate again.`);
      } else if (!completed.lookId) {
        if (isCurrent()) setActionError('Generation completed without a Look. You can regenerate again.');
      } else if (isCurrent()) {
        onOpenLook(completed.lookId);
      }
    } catch {
      if (isCurrent()) setActionError(acceptedGenerations.current.has(sourceLookId) ? 'Could not check the accepted generation. Tap Resume regeneration to retry without starting another one.' : 'Could not start regeneration. Check the connection before trying again.');
    } finally {
      operationLocked.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  async function share() {
    const lookId = selected;
    if (!lookId || operationLocked.current) return;
    operationLocked.current = true;
    setBusy(true);
    setActionError(null);
    try {
      await Share.share({ message: await looksApi.share(lookId) });
    } catch {
      if (screenActive.current && selectedRef.current === lookId) setActionError('Could not share this Look.');
    } finally {
      operationLocked.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  const disabled = !selected || busy;
  const resumeSelected = selected ? acceptedGenerations.current.has(selected) : false;
  return <View style={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Looks</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    {actionError ? <Text accessibilityLiveRegion="polite">{actionError}</Text> : null}
    <Text accessibilityRole="header" style={styles.heading}>Saved Looks</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Looks…</Text> : null}
    {status === 'failed' ? <Text accessibilityLiveRegion="polite">Could not load Looks. You can keep using navigation.</Text> : null}
    {status === 'ready' && looks.length === 0 ? <Text>No saved Looks yet.</Text> : null}
    {looks.map(look => <View key={look.id} style={styles.cell}>{brokenImages.has(look.id) ? <Text accessibilityLiveRegion="polite" style={styles.imageNotice}>Preview unavailable for {look.id}.</Text> : <Image accessible accessibilityRole="image" accessibilityLabel={`Look image ${look.id}`} onError={() => setBrokenImages(current => new Set(current).add(look.id))} source={{ uri: look.image.url }} style={styles.thumbnail} />}<Pressable accessibilityRole="button" accessibilityLabel={`Look ${look.id}`} accessibilityHint="Selects this Look. Use Open Look for details." accessibilityState={{ selected: selected === look.id }} onPress={() => selectLook(look.id)} onLongPress={() => onOpenLook(look.id)} style={styles.button}><Text style={styles.buttonLabel}>Look created {look.createdAt}</Text></Pressable><Action label={`Open Look ${look.id}`} onPress={() => onOpenLook(look.id)} /></View>)}
    <Text accessibilityRole="header" style={styles.heading}>Recent generations</Text>
    {recentGeneration ? <View accessibilityLiveRegion="polite"><Text accessibilityLabel={`Generation ${recentGeneration.generation.id}, ${recentGeneration.generation.status}${recentGeneration.generation.failureCode ? `, ${recentGeneration.generation.failureCode}` : ''}`}>Generation {recentGeneration.generation.id}: {recentGeneration.generation.status}{recentGeneration.generation.failureCode ? ` (${recentGeneration.generation.failureCode})` : ''}</Text>{recentGeneration.generation.lookId ? <Action label="Open generated Look" onPress={() => onOpenLook(recentGeneration.generation.lookId!)} /> : null}</View> : <Text>No recent generations yet.</Text>}
    <Action label={resumeSelected ? 'Resume regeneration' : 'Regenerate'} onPress={regenerate} disabled={disabled} busy={busy} />
    <Action label="Modify" onPress={() => selected && onModify(selected)} disabled={disabled} />
    <Action label="Share" onPress={share} disabled={disabled} busy={busy} />
    {!selected ? <Text>Select a Look to regenerate, modify, or share it.</Text> : null}
  </View>;
}

function Action({ label, onPress, disabled = false, busy = false }: { label: string; onPress: () => void; disabled?: boolean; busy?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled, busy }} disabled={disabled} onPress={onPress} style={[styles.button, disabled && styles.disabled]}><Text style={styles.buttonLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, button: { minHeight: 44, justifyContent: 'center', borderRadius: 8, paddingHorizontal: 16, backgroundColor: '#6b4f2a' }, buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' }, disabled: { opacity: 0.45 }, cell: { gap: 8 }, thumbnail: { width: '100%', aspectRatio: 2 / 3, borderRadius: 8, backgroundColor: '#ddd8d0' }, imageNotice: { color: '#8a2f2b' } });
