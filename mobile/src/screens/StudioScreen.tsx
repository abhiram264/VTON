import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { isFeatureEnabled } from '../config/featureFlags';
import { studioApi } from '../features/studio/studioApi';

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
  const [command, setCommand] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const hasSession = Boolean(studioSessionId);
  const hasCompatibleItem = Boolean(selectedVaultItemId || sourceLookId);
  const canGenerate = hasSession && hasCompatibleItem;
  async function applyCommand() {
    const text = command.trim();
    if (!studioSessionId || !text || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      await studioApi.command(studioSessionId, text);
      setCommand('');
    } catch {
      setNotice('Could not apply command. Use manual controls below.');
    } finally {
      setBusy(false);
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
      <Text>{sourceLookId ? 'Loading outfit from selected Look.' : 'No active outfit.'}</Text>
      <Text accessibilityRole="header" style={styles.heading}>Slots</Text>
      <Text>{selectedVaultItemId ? 'Selected Vault item is ready to confirm.' : 'No items in outfit slots.'}</Text>
      <Action label="Add from Vault" onPress={() => studioSessionId && onAddFromVault(studioSessionId)} disabled={!hasSession} />
      {!hasSession ? <Text>Start or load a Studio session to add from Vault.</Text> : null}
{isFeatureEnabled('NATURAL_LANGUAGE_STUDIO') ? (
  <>
    <TextInput accessibilityLabel="Natural-language command" editable={hasSession} multiline onChangeText={setCommand} placeholder="Describe an outfit change" style={styles.input} value={command} />
    <Action label="Apply command" onPress={applyCommand} disabled={!hasSession || command.trim().length === 0 || busy} />
    {notice ? <Text accessibilityLiveRegion="polite">{notice}</Text> : null}
    {!hasSession ? <Text>Commands require an authorized Studio session.</Text> : null}
  </>
) : null}
      <Action label="Generate" onPress={onGenerate} disabled={!canGenerate} />
      {!canGenerate ? <Text>Add an avatar session and a compatible item before generating.</Text> : null}
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
});
