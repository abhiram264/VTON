import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { isFeatureEnabled } from '../config/featureFlags';
import { studioApi, type StudioState } from '../features/studio/studioApi';
import { vaultApi } from '../features/vault/vaultApi';
type Props = {
  studioSessionId?: string;
  sourceLookId?: string;
  selectedVaultItemId?: string;
  onAddFromVault: (studioSessionId: string) => void;
  onAvatar: () => void;
  onGenerate: () => void;
  onProfile: () => void;
};

export default function StudioScreen({ studioSessionId, sourceLookId, selectedVaultItemId, onAddFromVault, onAvatar, onGenerate, onProfile }: Props) {
  const trimmedSessionId = studioSessionId?.trim() ?? '';
  const [command, setCommand] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [sessionStatus, setSessionStatus] = useState<'checking' | 'ready' | 'unavailable'>(trimmedSessionId ? 'checking' : 'unavailable');
  const [studioState, setStudioState] = useState<StudioState | null>(null);
  const commandLock = useRef(false);
  const commandRequest = useRef(0);
  const [itemCategories, setItemCategories] = useState<Record<string, string>>({});
  const activeSession = sessionStatus === 'ready' && studioState?.sessionId === trimmedSessionId ? studioState : null;
  const sessionChecking = Boolean(trimmedSessionId) && !activeSession && sessionStatus !== 'unavailable';
  const sessionUnavailable = !trimmedSessionId || sessionStatus === 'unavailable';
  const activeSessionId = activeSession?.sessionId ?? '';

  useEffect(() => {
    if (!activeSessionId) {
      setItemCategories({});
      return;
    }
    let mounted = true;
    vaultApi.list().then(items => {
      if (mounted) setItemCategories(Object.fromEntries(items.map(item => [item.id, item.category])));
    }).catch(() => {
      if (mounted) setItemCategories({});
    });
    return () => { mounted = false; };
  }, [activeSessionId]);
  const otherItemIds = activeSession
    ? activeSession.itemIds.filter(itemId => !SLOT_CATEGORIES.includes(itemCategories[itemId] ?? 'OTHER'))
    : [];
  const slotRows = activeSession
    ? SLOTS.map(slot => ({ ...slot, itemIds: activeSession.itemIds.filter(itemId => (itemCategories[itemId] ?? 'OTHER') === slot.category) }))
    : [];
  const trimmedItemId = selectedVaultItemId?.trim() ?? '';
  const pendingItemId = trimmedItemId && activeSession && !activeSession.itemIds.includes(trimmedItemId) ? trimmedItemId : '';

  useEffect(() => {
    if (!pendingItemId || !activeSessionId) return;
    let mounted = true;
    studioApi.addItem(activeSessionId, pendingItemId).then(state => {
      if (!mounted) return;
      if (state.sessionId !== activeSessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      setSessionStatus('ready');
    }).catch(() => {
      if (mounted) setNotice('Could not add the selected Vault item to this Studio session.');
    });
    return () => { mounted = false; };
  }, [pendingItemId, activeSessionId]);

  useEffect(() => {
    let mounted = true;
    commandRequest.current += 1;
    commandLock.current = false;
    setBusy(false);
    setCommand('');
    setNotice(null);
    setStudioState(null);

    if (!trimmedSessionId) {
      setSessionStatus('unavailable');
      return () => { mounted = false; };
    }

    setSessionStatus('checking');
    studioApi.get(trimmedSessionId).then(state => {
      if (!mounted) return;
      if (state.sessionId !== trimmedSessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      setSessionStatus('ready');
    }).catch(() => {
      if (!mounted) return;
      setStudioState(null);
      setSessionStatus('unavailable');
    });
    return () => { mounted = false; };
  }, [trimmedSessionId]);

  async function applyCommand() {
    const submittedCommand = command;
    const text = submittedCommand.trim();
    if (!activeSession || !text || commandLock.current) return;
    const request = ++commandRequest.current;
    commandLock.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const state = await studioApi.command(activeSession.sessionId, text);
      if (commandRequest.current !== request) return;
      if (state.sessionId !== activeSession.sessionId) throw new Error('Studio session mismatch');
      setStudioState(state);
      setCommand(current => current === submittedCommand ? '' : current);
    } catch {
      if (commandRequest.current === request) setNotice('Could not apply command. Use manual controls below.');
    } finally {
      if (commandRequest.current === request) {
        commandLock.current = false;
        setBusy(false);
      }
    }
  }

  return (
    <View style={styles.page}>
      <View style={styles.headingRow}>
        <Text accessibilityRole="header" style={styles.title}>Studio</Text>
        <Action label="Profile and settings" onPress={onProfile} />
      </View>
      <Action label="Avatar" onPress={onAvatar} />
      <Text accessibilityRole="header" style={styles.heading}>Active outfit</Text>
      <Text>{activeSession ? activeSession.itemIds.length > 0 ? `Studio session loaded with ${activeSession.itemIds.length} outfit item(s).` : 'Authorized Studio session has no outfit items yet.' : sessionChecking ? 'Checking Studio session.' : 'No authorized Studio session is available.'}</Text>
      {sourceLookId ? <Text>Selected Look was not loaded into this Studio session.</Text> : null}
      <Text accessibilityRole="header" style={styles.heading}>Slots</Text>
      {slotRows.map(row => (
        <View key={row.category} style={styles.slotRow}>
          <Text style={styles.slotLabel}>{row.label}</Text>
          <Text testID={`slot-${row.category}`}>{row.itemIds.length > 0 ? row.itemIds.join(', ') : 'Empty'}</Text>
        </View>
      ))}
      {otherItemIds.length > 0 ? <View style={styles.slotRow}><Text style={styles.slotLabel}>Other</Text><Text testID="slot-OTHER">{otherItemIds.join(', ')}</Text></View> : null}
      {selectedVaultItemId ? <Text>Selected Vault item has not been added to this Studio session.</Text> : null}
      <Action label="Add from Vault" onPress={() => activeSession && onAddFromVault(activeSession.sessionId)} disabled={!activeSession} />
      {sessionChecking ? <Text accessibilityLiveRegion="polite">Checking Studio session.</Text> : null}
      {sessionUnavailable ? <Text accessibilityLiveRegion="polite">Studio session unavailable. Start or load an authorized session.</Text> : null}
{isFeatureEnabled('NATURAL_LANGUAGE_STUDIO') ? (
  <>
    <TextInput accessibilityLabel="Natural-language command" accessibilityState={{ disabled: !activeSession || busy }} editable={Boolean(activeSession) && !busy} multiline onChangeText={setCommand} placeholder="Describe an outfit change" style={styles.input} value={command} />
    <Action label="Apply command" onPress={applyCommand} disabled={!activeSession || command.trim().length === 0 || busy} />
    {notice ? <Text accessibilityLiveRegion="polite">{notice}</Text> : null}
    {!activeSession ? <Text>Commands require a verified Studio session.</Text> : null}
  </>
) : null}
      <Action label="Generate" onPress={onGenerate} disabled={!activeSession || activeSession.itemIds.length === 0} />
      <Text>Generation is unavailable until an immutable Studio outfit can be handed to the generation flow.</Text>
    </View>
  );
}

function Action({ label, onPress, disabled = false }: { label: string; onPress: () => void; disabled?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={[styles.button, disabled && styles.disabled]}><Text style={styles.buttonLabel}>{label}</Text></Pressable>;
}
const SLOTS: readonly { label: string; category: string }[] = [
  { label: 'Top', category: 'TOP' },
  { label: 'Bottom', category: 'BOTTOM' },
  { label: 'Footwear', category: 'FOOTWEAR' },
];
const SLOT_CATEGORIES = SLOTS.map(slot => slot.category);

const styles = StyleSheet.create({
  page: { flex: 1, padding: 20, gap: 12, backgroundColor: '#f4f4f2' },
  headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' },
  heading: { fontSize: 18, fontWeight: '700' },
  button: { minHeight: 44, justifyContent: 'center', borderRadius: 8, paddingHorizontal: 16, backgroundColor: '#6b4f2a' },
  buttonLabel: { color: '#fff', fontWeight: '600', textAlign: 'center' },
  disabled: { opacity: 0.45 },
  input: { minHeight: 88, borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 8, padding: 12, textAlignVertical: 'top' },
  slotRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  slotLabel: { fontWeight: '600', color: '#1a1a1a' },
});
