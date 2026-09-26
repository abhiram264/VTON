import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { collectionApi, type CollectionSummary } from '../features/vault/collectionApi';
import { vaultApi, type VaultItemSummary } from '../features/vault/vaultApi';
import type { VaultFilter } from '../navigation/RootNavigator';

const FILTERS: readonly { value: VaultFilter; label: string; category?: string }[] = [
  { value: 'ALL', label: 'All' }, { value: 'TOPS', label: 'Tops', category: 'TOP' },
  { value: 'BOTTOMS', label: 'Bottoms', category: 'BOTTOM' }, { value: 'ONE_PIECES', label: 'One Pieces', category: 'ONE_PIECE' },
  { value: 'ETHNIC', label: 'Ethnic', category: 'ETHNIC_FULL_BODY' }, { value: 'FOOTWEAR', label: 'Footwear', category: 'FOOTWEAR' },
  { value: 'HEADWEAR', label: 'Headwear', category: 'HEADWEAR' }, { value: 'ACCESSORIES', label: 'Accessories', category: 'ACCESSORY' },
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
  const [collectionVersion, setCollectionVersion] = useState(0);
  const collectionIdRef = useRef<string | null>(null);
  collectionIdRef.current = collectionId;
  const option = FILTERS.find(value => value.value === filter) ?? FILTERS[0];
  const selectedCollection = collectionId ? collections.find(value => value.id === collectionId) : undefined;

  useEffect(() => {
    let active = true;
    setStatus('loading');
    if (option.value === 'COLLECTIONS') {
      const requestedCollectionId = collectionIdRef.current;
      void collectionApi.list().then(value => {
        if (!active) return;
        setCollections(value);
        if (requestedCollectionId && !value.some(entry => entry.id === requestedCollectionId)) {
          setCollectionId(null);
        }
        setItems([]);
        setCollectionVersion(version => version + 1);
        setStatus('ready');
      }, () => {
        if (!active) return;
        setCollections([]);
        setCollectionId(null);
        setItems([]);
        setStatus('failed');
      });
    } else {
      void vaultApi.list(option.category).then(value => {
        if (!active) return;
        setItems(value);
        setStatus('ready');
      }, () => {
        if (!active) return;
        setItems([]);
        setStatus('failed');
      });
    }
    return () => { active = false; };
  }, [attempt, option.category, option.value]);

  useEffect(() => {
    if (option.value !== 'COLLECTIONS' || !collectionId || !selectedCollection) return;
    let active = true;
    setStatus('loading');
    const memberIds = new Set(selectedCollection.vaultItemIds);
    void vaultApi.list().then(value => {
      if (active) {
        setItems(value.filter(item => memberIds.has(item.id)));
        setStatus('ready');
      }
    }, () => {
      if (active) {
        setItems([]);
        setStatus('failed');
      }
    });
    return () => { active = false; };
  }, [collectionId, collectionVersion, option.value, selectedCollection]);

  return <ScrollView contentContainerStyle={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Vault</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    <View accessibilityRole="radiogroup" style={styles.filters}>{FILTERS.map(value => {
      const selected = filter === value.value;
      return <Pressable key={value.value} accessibilityRole="radio" accessibilityLabel={value.label} accessibilityState={{ checked: selected }} onPress={() => { if (value.value !== filter) { setFilter(value.value); setCollectionId(null); setItems([]); setStatus('loading'); } }} style={[styles.filter, selected && styles.selected]}><Text style={[styles.filterLabel, selected && styles.selectedLabel]}>{selected ? `✓ ${value.label}` : value.label}</Text></Pressable>;
    })}</View>
    {selectionUnavailable ? <Text accessibilityLiveRegion="polite">Studio selection is unavailable because no Studio session was provided. Browse the Vault normally.</Text> : null}
    <Text accessibilityRole="header" accessibilityLiveRegion="polite" style={styles.heading}>{selectedCollection?.name ?? option.label}</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Vault items…</Text> : null}
    {status === 'failed' ? <><Text accessibilityLiveRegion="polite">Could not load Vault items.</Text><Action label="Retry Vault" onPress={() => setAttempt(value => value + 1)} /></> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collections.length === 0 ? <Text accessibilityLiveRegion="polite">No collections yet.</Text> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId ? collections.map(collection => <Action key={collection.id} label={collection.name} onPress={() => { setCollectionId(collection.id); setItems([]); setStatus('loading'); }} />) : null}
    {status === 'ready' && (option.value !== 'COLLECTIONS' || collectionId) && items.length === 0 ? <Text accessibilityLiveRegion="polite">No Vault items in {selectedCollection?.name ?? option.label}.</Text> : null}
    {status === 'ready' ? items.map(item => <Action key={item.id} label={item.name} onPress={() => onSelect?.(item.id)} disabled={!onSelect} />) : null}
  </ScrollView>;
}

function Action({ label, onPress, disabled = false }: { label: string; onPress: () => void; disabled?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={styles.profile}><Text style={styles.profileLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { padding: 20, gap: 14, backgroundColor: '#f4f4f2' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, item: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12, color: '#563c1d', fontWeight: '600' }, profile: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12 }, profileLabel: { color: '#563c1d', fontWeight: '600' }, filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, filter: { minHeight: 44, justifyContent: 'center', borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 22, paddingHorizontal: 14 }, selected: { borderWidth: 3, backgroundColor: '#eee2d0' }, filterLabel: { color: '#3f3a33' }, selectedLabel: { fontWeight: '700', color: '#24180b' } });
