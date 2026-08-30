import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { collectionApi, type CollectionSummary } from '../features/vault/collectionApi';
import { vaultApi, type VaultItemSummary } from '../features/vault/vaultApi';
import type { VaultFilter } from '../navigation/RootNavigator';

const FILTERS: readonly { value: VaultFilter; label: string; category?: string }[] = [
  { value: 'ALL', label: 'All' }, { value: 'TOPS', label: 'Tops', category: 'TOP' },
  { value: 'BOTTOMS', label: 'Bottoms', category: 'BOTTOM' }, { value: 'ONE_PIECES', label: 'One Pieces', category: 'ONE_PIECE' },
  { value: 'ETHNIC', label: 'Ethnic', category: 'ETHNIC_FULL_BODY' }, { value: 'FOOTWEAR', label: 'Footwear', category: 'FOOTWEAR' },
  { value: 'HEADWEAR', label: 'Headwear', category: 'HEADWEAR' }, { value: 'ACCESSORIES', label: 'Accessories', category: 'ACCESSORIES' },
  { value: 'COLLECTIONS', label: 'Collections' },
];

type Props = { onProfile: () => void; onSelect?: (itemId: string) => void; selectionUnavailable?: boolean };

export default function VaultScreen({ onProfile, onSelect, selectionUnavailable = false }: Props) {
  const [filter, setFilter] = useState<VaultFilter>('ALL');
  const [items, setItems] = useState<VaultItemSummary[]>([]);
  const [collections, setCollections] = useState<CollectionSummary[]>([]);
  const [collectionId, setCollectionId] = useState<string | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [attempt, setAttempt] = useState(0);
  const option = FILTERS.find(value => value.value === filter) ?? FILTERS[0];

  useEffect(() => {
    let active = true;
    setStatus('loading');
    const load = option.value === 'COLLECTIONS'
      ? collectionApi.list().then(value => { setCollections(value); setCollectionId(null); return []; })
      : vaultApi.list(option.category);
    void load.then(value => { if (active) { setItems(value); setStatus('ready'); } }, () => { if (active) { setItems([]); setCollections([]); setStatus('failed'); } });
    return () => { active = false; };
  }, [attempt, option.category, option.value]);
  useEffect(() => {
    if (!collectionId) return;
    let active = true;
    setStatus('loading');
    const memberIds = new Set(collections.find(value => value.id === collectionId)?.vaultItemIds ?? []);
    void vaultApi.list().then(value => { if (active) { setItems(value.filter(item => memberIds.has(item.id))); setStatus('ready'); } }, () => { if (active) { setItems([]); setStatus('failed'); } });
    return () => { active = false; };
  }, [collectionId, collections]);

  return <ScrollView contentContainerStyle={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Vault</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    <View accessibilityRole="radiogroup" style={styles.filters}>{FILTERS.map(value => {
      const selected = filter === value.value;
      return <Pressable key={value.value} accessibilityRole="button" accessibilityLabel={value.label} accessibilityState={{ selected }} onPress={() => setFilter(value.value)} style={[styles.filter, selected && styles.selected]}><Text style={[styles.filterLabel, selected && styles.selectedLabel]}>{selected ? `✓ ${value.label}` : value.label}</Text></Pressable>;
    })}</View>
    {selectionUnavailable ? <Text accessibilityLiveRegion="polite">Studio selection is unavailable without a valid session. Browse the Vault normally.</Text> : null}
    <Text accessibilityRole="header" style={styles.heading}>{option.label}</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Vault items…</Text> : null}
    {status === 'failed' ? <><Text accessibilityLiveRegion="polite">Could not load Vault items.</Text><Action label="Retry Vault" onPress={() => setAttempt(value => value + 1)} /></> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collections.length === 0 ? <Text>No collections yet.</Text> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId ? collections.map(collection => <Action key={collection.id} label={collection.name} onPress={() => setCollectionId(collection.id)} />) : null}
    {status === 'ready' && (option.value !== 'COLLECTIONS' || collectionId) && items.length === 0 ? <Text>No Vault items in {option.label}.</Text> : null}
    {items.map(item => <Action key={item.id} label={item.name} onPress={() => onSelect?.(item.id)} disabled={!onSelect} />)}
  </ScrollView>;
}

function Action({ label, onPress, disabled = false }: { label: string; onPress: () => void; disabled?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={styles.profile}><Text style={styles.profileLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { padding: 20, gap: 14, backgroundColor: '#f4f4f2' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, profile: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12 }, profileLabel: { color: '#563c1d', fontWeight: '600' }, filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, filter: { minHeight: 44, justifyContent: 'center', borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 22, paddingHorizontal: 14 }, selected: { borderWidth: 3, backgroundColor: '#eee2d0' }, filterLabel: { color: '#3f3a33' }, selectedLabel: { fontWeight: '700', color: '#24180b' } });
