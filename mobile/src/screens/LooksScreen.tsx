import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { looksApi } from '../features/looks/looksApi';
import { generationApi, pollGeneration } from '../features/generation/generationApi';
import { recoverActiveGeneration } from '../features/generation/generationLifecycle';
import type { GenerationResource, LookResource } from '../features/generation/types';
import { ApiProblemError } from '../features/my-model/http';
import { lookLabel } from './HomeScreen';

type Props = { onModify: (lookId: string) => void; onOpenLook: (lookId: string) => void; onProfile: () => void; pollDelay?: (ms: number) => Promise<void> };

// Two Looks created on the same day would otherwise share a name, which a
// screen reader cannot tell apart; disambiguate duplicates in order. The raw
// Look id is deliberately never rendered (C23: no debug-like identifiers).
function labeledLooks(looks: LookResource[]): { look: LookResource; label: string }[] {
  const seen = new Map<string, number>();
  return looks.map(look => {
    const base = lookLabel(look.createdAt);
    const count = (seen.get(base) ?? 0) + 1;
    seen.set(base, count);
    return { look, label: count === 1 ? base : `${base} (${count})` };
  });
}

export default function LooksScreen({ onModify, onOpenLook, onProfile, pollDelay }: Props) {
  const [looks, setLooks] = useState<LookResource[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  // C23: why the list failed. An ended session is not a transient failure and
  // must not be described with generic "keep using navigation" advice.
  const [loadFailure, setLoadFailure] = useState<'unauthorized' | 'retryable' | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [recentGeneration, setRecentGeneration] = useState<{ sourceLookId: string; generation: GenerationResource } | null>(null);
  const [recovered, setRecovered] = useState<GenerationResource | null>(null);
  const [brokenImages, setBrokenImages] = useState<ReadonlySet<string>>(() => new Set());
  const [refreshingPreview, setRefreshingPreview] = useState<string | null>(null);
  const selectedRef = useRef<string | null>(null);
  const acceptedGenerations = useRef(new Map<string, string>());
  // A regeneration accepted by the server is charged whether or not this
  // process survives to poll it. `acceptedGenerations` only lives for the
  // process, so on every focus the server's owner-scoped list is consulted for
  // an in-flight job; if one exists it is RESUMED instead of re-POSTed.
  const recoveredRef = useRef<GenerationResource | null>(null);
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
    recoveredRef.current = null;
    setRecovered(null);
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
    }, (error: unknown) => {
      if (!active) return;
      setLooks([]);
      setStatus('failed');
      setLoadFailure(
        error instanceof ApiProblemError && error.code === 'UNAUTHORIZED'
          ? 'unauthorized'
          : 'retryable',
      );
    });
    // Recovery is best-effort and independent of the Look list: an `unavailable`
    // answer (the lookup failed) is NOT "nothing in flight", so it leaves the
    // ordinary regenerate path alone rather than inventing an empty state.
    void recoverActiveGeneration({ list: generationApi.list }).then(recovery => {
      if (!active || recovery.kind !== 'active') return;
      recoveredRef.current = recovery.generation;
      if (mounted.current) setRecovered(recovery.generation);
    });
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

  // A signed preview expires by design. When one fails to load, re-reading the
  // Look mints a fresh short-lived URL for THAT look only; the rest of the list
  // is untouched and a failure is reported rather than silently re-broken.
  async function refreshPreview(lookId: string) {
    setRefreshingPreview(lookId);
    try {
      const detail = await looksApi.get(lookId);
      if (!mounted.current) return;
      setLooks(current => current.map(look => (look.id === lookId ? { ...look, image: detail.image } : look)));
      setBrokenImages(current => {
        const next = new Set(current);
        next.delete(lookId);
        return next;
      });
    } catch {
      if (mounted.current) setActionError('Could not refresh that preview. Try again later.');
    } finally {
      if (mounted.current) setRefreshingPreview(null);
    }
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
      const inFlight = recoveredRef.current;
      let generationId = acceptedGenerations.current.get(sourceLookId);
      if (!generationId && inFlight) {
        // Already accepted (and charged) by the server before this process
        // could poll it: resume that job instead of starting another.
        generationId = inFlight.id;
        rememberGeneration(sourceLookId, inFlight);
      }
      recoveredRef.current = null;
      if (mounted.current) setRecovered(null);
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

  const disabled = !selected || busy;
  const resumeSelected = selected ? acceptedGenerations.current.has(selected) : false;
  const labeled = labeledLooks(looks);
  return <View style={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Looks</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    {actionError ? <Text accessibilityLiveRegion="polite">{actionError}</Text> : null}
    {recovered ? <Text accessibilityLiveRegion="polite">A regeneration is already in progress. Press Regenerate to resume it without starting another.</Text> : null}
    <Text accessibilityRole="header" style={styles.heading}>Saved Looks</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Looks…</Text> : null}
    {status === 'failed' ? <Text accessibilityLiveRegion="polite">{loadFailure === 'unauthorized' ? 'Your session has expired. Sign in again to see your Looks.' : 'Could not load Looks. You can keep using navigation.'}</Text> : null}
    {status === 'ready' && looks.length === 0 ? <Text>No saved Looks yet.</Text> : null}
    {labeled.map(({ look, label }) => <View key={look.id} style={styles.cell}>{brokenImages.has(look.id) ? <View style={styles.cell}><Text accessibilityLiveRegion="polite" style={styles.imageNotice}>Preview unavailable for {label}.</Text><Action label={`Refresh preview for ${label}`} onPress={() => refreshPreview(look.id)} busy={refreshingPreview === look.id} disabled={refreshingPreview !== null} /></View> : <Image accessible accessibilityRole="image" accessibilityLabel={`${label} preview`} onError={() => setBrokenImages(current => new Set(current).add(look.id))} source={{ uri: look.image.url }} style={styles.thumbnail} />}<Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityHint="Selects this Look. Use Open Look for details." accessibilityState={{ selected: selected === look.id }} onPress={() => selectLook(look.id)} onLongPress={() => onOpenLook(look.id)} style={styles.button}><Text style={styles.buttonLabel}>{label}</Text></Pressable></View>)}
    <Text accessibilityRole="header" style={styles.heading}>Recent generations</Text>
    {recentGeneration ? <View accessibilityLiveRegion="polite"><Text>{`Last regeneration: ${recentGeneration.generation.status}${recentGeneration.generation.failureCode ? ` (${recentGeneration.generation.failureCode})` : ''}`}</Text>{recentGeneration.generation.lookId ? <Action label="Open generated Look" onPress={() => onOpenLook(recentGeneration.generation.lookId!)} /> : null}</View> : <Text>No recent generations yet.</Text>}
    <Action label={resumeSelected ? 'Resume regeneration' : 'Regenerate'} onPress={regenerate} disabled={disabled} busy={busy} />
    <Action label="Modify" onPress={() => selected && onModify(selected)} disabled={disabled} />
    {!selected ? <Text>Select a Look to regenerate or modify it.</Text> : null}
  </View>;
}

function Action({ label, onPress, disabled = false, busy = false }: { label: string; onPress: () => void; disabled?: boolean; busy?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled, busy }} disabled={disabled} onPress={onPress} style={[styles.button, disabled && styles.disabled]}><Text style={styles.buttonLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, button: { minHeight: 44, justifyContent: 'center', borderRadius: 8, paddingHorizontal: 16, backgroundColor: '#6b4f2a' }, buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' }, disabled: { opacity: 0.45 }, cell: { gap: 8 }, thumbnail: { width: '100%', aspectRatio: 2 / 3, borderRadius: 8, backgroundColor: '#ddd8d0' }, imageNotice: { color: '#8a2f2b' } });
