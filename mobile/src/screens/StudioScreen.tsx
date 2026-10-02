import { useEffect, useRef, useState } from 'react';
import { Image, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { isFeatureEnabled } from '../config/featureFlags';
import { studioApi, slotForCategory, type StudioSlot, type StudioState } from '../features/studio/studioApi';
import { vaultApi } from '../features/vault/vaultApi';
import { avatarApi } from '../features/my-model/avatarApi';
import { generationApi, pollGeneration } from '../features/generation/generationApi';
import type { CoarseGenerationPhase } from '../features/generation/types';

type Props = {
  studioSessionId?: string;
  sourceLookId?: string;
  selectedVaultItemId?: string;
  onAddFromVault: (studioSessionId: string) => void;
  onAvatar: () => void;
  onOpenLook: (lookId: string) => void;
  onProfile: () => void;
  // A route param that has been acted on must not survive in the navigator, or
  // re-entering this tab re-applies a stale selection (the garment comes back
  // after the user removed it; the same Look is cloned into a second draft).
  // The screen cannot clear its own route params, so it reports consumption.
  onConsumed?: () => void;
};

type ItemInfo = { name: string; slot: StudioSlot; imageUrl: string | null };

const SLOTS: readonly { label: string; slot: StudioSlot }[] = [
  { label: 'Top', slot: 'UPPER_BODY' },
  { label: 'Bottom', slot: 'LOWER_BODY' },
  { label: 'Footwear', slot: 'FEET' },
];

const PHASE_LABELS: Record<CoarseGenerationPhase, string> = {
  QUEUED: 'Queued',
  PREPARING: 'Preparing',
  GENERATING: 'Generating',
  CHECKING_QUALITY: 'Checking quality',
  READY: 'Ready',
  FAILED: 'Failed',
};

export default function StudioScreen({ studioSessionId, sourceLookId, selectedVaultItemId, onAddFromVault, onAvatar, onOpenLook, onProfile, onConsumed }: Props) {
  const trimmedSessionId = studioSessionId?.trim() ?? '';
  const trimmedSourceLookId = sourceLookId?.trim() ?? '';
  const [command, setCommand] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [sessionStatus, setSessionStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [studioState, setStudioState] = useState<StudioState | null>(null);
  const [itemInfo, setItemInfo] = useState<Record<string, ItemInfo>>({});
  const [itemsLoaded, setItemsLoaded] = useState(false);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);
  const [phase, setPhase] = useState<CoarseGenerationPhase | null>(null);
  const [unresolved, setUnresolved] = useState<{ vaultItemId: string; reason: string }[]>([]);
  const commandLock = useRef(false);
  const imageRetried = useRef<Set<string>>(new Set());
  // Held in a ref so the load/selection effects depend on the route params, not
  // on a fresh callback identity each render.
  const consumed = useRef(onConsumed);
  consumed.current = onConsumed;
  // The selection effect re-runs as the draft changes; a given param value is
  // acted on (and reported consumed) exactly once. A cleared param resets the
  // memo so a genuine later re-selection still applies.
  const consumedItemId = useRef<string | null>(null);
  const commandRequest = useRef(0);
  const operationRef = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      operationRef.current += 1;
    };
  }, []);

  const activeSession = sessionStatus === 'ready' ? studioState : null;
  const activeSessionId = activeSession?.sessionId ?? '';
  // The draft's item set as a stable primitive so the Vault/garment-image effect
  // re-runs when items change but not on every render.
  const itemsKey = activeSession ? activeSession.itemIds.join(',') : '';
  // The three outfit slots are always rendered so the screen shows its real
  // surface even before a draft loads; their controls are disabled until the
  // owned draft is ready. A missing session must not erase the UI.
  const slotRows = SLOTS.map(slot => ({ ...slot, itemIds: activeSession ? activeSession.itemIds.filter(itemId => itemInfo[itemId]?.slot === slot.slot) : [] }));
  const otherItemIds = activeSession
    ? activeSession.itemIds.filter(itemId => !SLOTS.some(slot => slot.slot === itemInfo[itemId]?.slot))
    : [];

  // Load the owned draft: an explicit session id when supplied; otherwise clone
  // the source Look's provenance into a fresh draft when Modify-from-Look sent
  // one; otherwise the owner's active draft, creating one when there is none.
  // The screen must never dead-end on "no session".
  useEffect(() => {
    let current = true;
    setStudioState(null);
    setSessionStatus('loading');
    setNotice(null);
    setPhase(null);
    setUnresolved([]);
    const load = async (): Promise<StudioState> => {
      if (trimmedSessionId) {
        return studioApi.get(trimmedSessionId);
      }
      if (trimmedSourceLookId) {
        const draft = await studioApi.createFromLook(trimmedSourceLookId);
        setUnresolved(draft.unresolved);
        if (draft.avatarUnavailable) {
          setNotice('This draft has no My Model. Add one before generating.');
        }
        return draft;
      }
      const active = await studioApi.activeDraft();
      return active ?? (await studioApi.create());
    };
    load().then(state => {
      if (!current) return;
      if (trimmedSessionId && state.sessionId !== trimmedSessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      setSessionStatus('ready');
      if (trimmedSourceLookId) consumed.current?.();
    }).catch(() => {
      if (!current) return;
      setStudioState(null);
      setSessionStatus('failed');
      if (trimmedSourceLookId) {
        setNotice('Could not load that Look into a Studio draft. It may no longer be available.');
        // The param is spent either way: leaving it would re-clone on every
        // re-entry instead of showing this draft's own failure state.
        consumed.current?.();
      }
    });
    return () => { current = false; };
  }, [trimmedSessionId, trimmedSourceLookId]);

  useEffect(() => {
    if (!activeSessionId) {
      setItemInfo({});
      setItemsLoaded(false);
      return;
    }
    let current = true;
    setItemsLoaded(false);
    imageRetried.current.clear();
    const draftItemIds = activeSession?.itemIds ?? [];
    vaultApi.list().then(items => {
      if (!current) return;
      setItemInfo(Object.fromEntries(items.map(item => [item.id, { name: item.name, slot: slotForCategory(item.category), imageUrl: null }])));
      setItemsLoaded(true);
      // Garment images are owner-authorized signed capabilities served per item
      // (GET /vault/items/{id}/image), not list fields. Fetch them for the items
      // in this draft only; an item without a capability renders by name.
      for (const itemId of draftItemIds) {
        void loadItemImage(itemId);
      }
    }).catch(() => {
      if (!current) return;
      setItemInfo({});
      setItemsLoaded(true);
    });
    return () => { current = false; };
  }, [activeSessionId, itemsKey]);

  useEffect(() => {
    const avatarProfileId = studioState?.avatarProfileId;
    if (!avatarProfileId || sessionStatus !== 'ready') {
      setAvatarUrl(null);
      return;
    }
    let current = true;
    avatarApi.list().then(avatars => {
      if (!current) return;
      const match = avatars.find(avatar => avatar.id === avatarProfileId) ?? avatars[0];
      setAvatarUrl(match ? match.displayUrl : null);
    }).catch(() => {
      if (current) setAvatarUrl(null);
    });
    return () => { current = false; };
  }, [sessionStatus, studioState?.avatarProfileId]);

  // A Vault selection returns here as `selectedVaultItemId`. Filling an empty
  // slot adds the garment; selecting into an occupied slot replaces it. Both go
  // through the same owned, revisioned Studio commands as the manual controls.
  useEffect(() => {
    const itemId = selectedVaultItemId?.trim() ?? '';
    if (!itemId) {
      consumedItemId.current = null;
      return;
    }
    if (consumedItemId.current === itemId) return;
    // Wait for the Vault categories before deciding add-vs-replace: an empty
    // slot map would classify an occupied slot as empty and add a second item
    // instead of replacing the existing one.
    if (!activeSession || !itemsLoaded) return;
    // Already in the outfit: it was applied by an earlier pass and must not be
    // re-applied when the user later removes it.
    if (activeSession.itemIds.includes(itemId)) {
      consumedItemId.current = itemId;
      consumed.current?.();
      return;
    }
    let current = true;
    const sessionId = activeSession.sessionId;
    const expectedUpdatedAt = activeSession.updatedAt;
    const info = itemInfo[itemId];
    const replacing = Boolean(info && activeSession.itemIds.some(id => id !== itemId && itemInfo[id]?.slot === info.slot));
    const request = replacing
      ? studioApi.replaceItem(sessionId, itemId, info.slot, expectedUpdatedAt)
      : studioApi.addItem(sessionId, itemId, info?.slot ?? 'ACCESSORY', expectedUpdatedAt);
    request.then(state => {
      if (!current) return;
      if (state.sessionId !== sessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      consumedItemId.current = itemId;
      consumed.current?.();
    }).catch(() => {
      if (current) setNotice(replacing ? 'Could not replace that slot item.' : 'Could not add the selected Vault item to this Studio session.');
    });
    return () => { current = false; };
  }, [selectedVaultItemId, activeSession, itemInfo, itemsLoaded]);

  async function loadItemImage(itemId: string) {
    try {
      const image = await vaultApi.image(itemId);
      if (!mounted.current) return;
      setItemInfo(previous => {
        const existing = previous[itemId];
        return existing ? { ...previous, [itemId]: { ...existing, imageUrl: image.url } } : previous;
      });
    } catch {
      // Keep the name-only row; the item is still usable in the outfit.
    }
  }

  // A signed read capability expires. Refresh it once; if the refresh also
  // fails, drop the capability so the row renders the garment name instead of a
  // permanently broken preview.
  async function handleImageError(itemId: string) {
    if (imageRetried.current.has(itemId)) {
      setItemInfo(previous => {
        const existing = previous[itemId];
        return existing ? { ...previous, [itemId]: { ...existing, imageUrl: null } } : previous;
      });
      return;
    }
    imageRetried.current.add(itemId);
    await loadItemImage(itemId);
  }

  async function removeItem(itemId: string) {
    if (!activeSession || busy) return;
    const sessionId = activeSession.sessionId;
    setBusy(true);
    setNotice(null);
    try {
      const state = await studioApi.removeItem(sessionId, itemId, activeSession.updatedAt);
      if (!mounted.current) return;
      if (state.sessionId !== sessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
    } catch {
      if (mounted.current) setNotice('Could not remove that item from the Studio outfit.');
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function applyCommand() {
    const submittedCommand = command;
    const text = submittedCommand.trim();
    if (!activeSession || !text || commandLock.current) return;
    const sessionId = activeSession.sessionId;
    const request = ++commandRequest.current;
    commandLock.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const state = await studioApi.command(sessionId, text);
      if (commandRequest.current !== request) return;
      if (state.sessionId !== sessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      setCommand(current => current === submittedCommand ? '' : current);
    } catch {
      if (commandRequest.current === request) setNotice('Could not apply command. Use manual controls below.');
    } finally {
      if (commandRequest.current === request) {
        commandLock.current = false;
        if (mounted.current) setBusy(false);
      }
    }
  }

  // Studio generation freezes the owned session's exact item list and order
  // (POST /generations/multi), never the manual photo upload path.
  // Idempotency-Key makes a lost response reuse the same job instead of
  // spending a second generation.
  async function generate() {
    if (!activeSession || busy || activeSession.itemIds.length === 0) return;
    const sessionId = activeSession.sessionId;
    const itemIds = activeSession.itemIds;
    const operation = ++operationRef.current;
    const isCurrent = () => mounted.current && operationRef.current === operation;
    setBusy(true);
    setNotice(null);
    setPhase(null);
    try {
      const accepted = await studioApi.generate(sessionId, itemIds, 'standard');
      if (!isCurrent()) return;
      setPhase(accepted.phase);
      const completed = await pollGeneration({
        get: generationApi.get,
        id: accepted.id,
        onUpdate: generation => { if (isCurrent()) setPhase(generation.phase); },
      });
      if (!isCurrent()) return;
      if (completed.status === 'FAILED') {
        setNotice(`Generation failed${completed.failureCode ? ` (${completed.failureCode})` : ''}. Try again.`);
      } else if (!completed.lookId) {
        setNotice('Generation completed without a Look. Try again.');
      } else {
        onOpenLook(completed.lookId);
      }
    } catch {
      if (isCurrent()) setNotice('Could not generate from this Studio outfit. Check the connection and try again.');
    } finally {
      if (isCurrent()) {
        setBusy(false);
        setPhase(null);
      }
    }
  }

  return (
    <View style={styles.page}>
      <View style={styles.headingRow}>
        <Text accessibilityRole="header" style={styles.title}>Studio</Text>
        <Action label="Profile and settings" onPress={onProfile} />
      </View>
      <View style={styles.avatarRow}>
        {avatarUrl ? <Image accessibilityLabel="My Model" source={{ uri: avatarUrl }} style={styles.avatar} testID="studio-avatar" /> : <View accessibilityLabel="My Model" style={[styles.avatar, styles.avatarEmpty]} testID="studio-avatar-empty" />}
        <View style={styles.avatarText}>
          <Text style={styles.heading}>My Model</Text>
          <Text>{avatarUrl ? 'Your My Model is set for this outfit.' : 'No My Model is set for this outfit yet.'}</Text>
        </View>
        <Action label="Avatar" onPress={onAvatar} />
      </View>
      <Text accessibilityRole="header" style={styles.heading}>Active outfit</Text>
      {sessionStatus === 'loading' ? <Text accessibilityLiveRegion="polite">Loading your Studio draft.</Text> : null}
      {sessionStatus === 'failed' ? <Text accessibilityLiveRegion="polite">Could not load your Studio draft. Check the connection and try again.</Text> : null}
      {activeSession ? <Text testID="studio-summary">{activeSession.itemIds.length > 0 ? `Studio outfit has ${activeSession.itemIds.length} item(s).` : 'Your Studio outfit is empty. Add garments from your Vault.'}</Text> : null}
      <Text accessibilityRole="header" style={styles.heading}>Slots</Text>
      {slotRows.map(row => (
        <View key={row.slot} testID={`slot-${row.slot}`} style={styles.slotRow}>
          <Text style={styles.slotLabel}>{row.label}</Text>
          <View style={styles.slotItems}>
            {row.itemIds.length === 0 ? <Text>Empty</Text> : row.itemIds.map(itemId => (
              <View key={itemId} style={styles.itemRow}>
                {itemInfo[itemId]?.imageUrl ? <Image accessibilityLabel={itemInfo[itemId]?.name ?? 'Garment'} onError={() => void handleImageError(itemId)} source={{ uri: itemInfo[itemId].imageUrl as string }} style={styles.itemImage} testID={`item-image-${itemId}`} /> : null}
                <Text testID={`item-${itemId}`}>{itemInfo[itemId]?.name ?? 'Vault garment'}</Text>
                <Action label={`Remove ${itemInfo[itemId]?.name ?? 'garment'}`} onPress={() => removeItem(itemId)} disabled={busy} />
              </View>
            ))}
          </View>
          <Action label={`${row.itemIds.length > 0 ? 'Replace' : 'Add'} ${row.label}`} onPress={() => activeSession && onAddFromVault(activeSession.sessionId)} disabled={!activeSession || busy} />
        </View>
      ))}
      {otherItemIds.length > 0 ? <View style={styles.slotRow}><Text style={styles.slotLabel}>Other</Text><Text testID="slot-OTHER">{otherItemIds.map(itemId => itemInfo[itemId]?.name ?? 'Vault garment').join(', ')}</Text></View> : null}
      {selectedVaultItemId ? <Text accessibilityLiveRegion="polite">Adding the selected Vault item…</Text> : null}
      {unresolved.length > 0 ? <Text accessibilityLiveRegion="polite" testID="studio-unresolved">{`${unresolved.length} source garment(s) from that Look are no longer available and were not added.`}</Text> : null}
      {isFeatureEnabled('NATURAL_LANGUAGE_STUDIO') ? (
        <>
          <TextInput accessibilityLabel="Natural-language command" accessibilityState={{ disabled: !activeSession || busy }} editable={Boolean(activeSession) && !busy} multiline onChangeText={setCommand} placeholder="Describe an outfit change" style={styles.input} value={command} />
          <Action label="Apply command" onPress={applyCommand} disabled={!activeSession || command.trim().length === 0 || busy} />
        </>
      ) : null}
      {notice ? <Text accessibilityLiveRegion="polite" testID="studio-notice">{notice}</Text> : null}
      {phase ? <Text accessibilityLiveRegion="polite">{`Generation ${PHASE_LABELS[phase]}.`}</Text> : null}
      <Action label="Generate" onPress={generate} disabled={!activeSession || activeSession.itemIds.length === 0 || busy} />
    </View>
  );
}

function Action({ label, onPress, disabled = false }: { label: string; onPress: () => void; disabled?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={[styles.button, disabled && styles.disabled]}><Text style={styles.buttonLabel}>{label}</Text></Pressable>;
}

const styles = StyleSheet.create({
  page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' },
  headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' },
  heading: { fontSize: 18, fontWeight: '700' },
  button: { minHeight: 44, justifyContent: 'center', borderRadius: 8, paddingHorizontal: 16, backgroundColor: '#6b4f2a' },
  buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' },
  disabled: { opacity: 0.45 },
  input: { minHeight: 88, borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 8, padding: 12, textAlignVertical: 'top' },
  avatarRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  avatar: { width: 64, height: 64, borderRadius: 8, backgroundColor: '#ddd8d0' },
  avatarEmpty: { borderWidth: 1, borderColor: '#b9b2a7' },
  avatarText: { flex: 1, gap: 2 },
  slotRow: { gap: 8, paddingVertical: 4 },
  slotLabel: { fontWeight: '600', color: '#1a1a1a' },
  slotItems: { gap: 8 },
  itemRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  itemImage: { width: 44, height: 44, borderRadius: 6, backgroundColor: '#ddd8d0' },
});
