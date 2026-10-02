import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  collectionsApi,
  type Collection,
} from '../features/collections/collectionsApi';
import CollectionsPicker from '../features/collections/CollectionsPicker';
import {
  vaultApi,
  type VaultItemImage,
  type VaultItemSummary,
} from '../features/vault/vaultApi';
import { ApiProblemError } from '../features/my-model/http';
import type { VaultFilter } from '../navigation/RootNavigator';

const FILTERS: readonly { value: VaultFilter; label: string; category?: string }[] = [
  { value: 'ALL', label: 'All' }, { value: 'TOPS', label: 'Tops', category: 'TOP' },
  { value: 'BOTTOMS', label: 'Bottoms', category: 'BOTTOM' }, { value: 'ONE_PIECES', label: 'One Pieces', category: 'ONE_PIECE' },
  { value: 'ETHNIC', label: 'Ethnic', category: 'ETHNIC_FULL_BODY' }, { value: 'FOOTWEAR', label: 'Footwear', category: 'FOOTWEAR' },
  { value: 'HEADWEAR', label: 'Headwear', category: 'HEADWEAR' }, { value: 'ACCESSORIES', label: 'Accessories', category: 'ACCESSORY' },
  { value: 'COLLECTIONS', label: 'Collections' },
];

// Mirrors ProductCategory. UNKNOWN is a real value the backend returns for an
// unclassified item, so it is offered explicitly instead of being hidden.
const CATEGORIES: readonly string[] = [
  'TOP', 'BOTTOM', 'ONE_PIECE', 'ETHNIC_FULL_BODY', 'SAREE', 'LEHENGA', 'KURTA',
  'SHERWANI', 'FOOTWEAR', 'HEADWEAR', 'BAG', 'JEWELLERY', 'ACCESSORY', 'UNKNOWN',
];

type Props = { onProfile: () => void; onSelect?: (itemId: string) => void; selectionUnavailable?: boolean };

export default function VaultScreen({ onProfile, onSelect, selectionUnavailable = false }: Props) {
  const [filter, setFilter] = useState<VaultFilter>('ALL');
  const [items, setItems] = useState<VaultItemSummary[]>([]);
  const [collections, setCollections] = useState<Collection[]>([]);
  const [collectionId, setCollectionId] = useState<string | null>(null);
  const [collectionMode, setCollectionMode] = useState<'browse' | 'manage'>('browse');
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [previewFailures, setPreviewFailures] = useState(0);
  const [previewAttempt, setPreviewAttempt] = useState(0);
  // A failure carries its kind so the screen can tell the truth: a revoked or
  // expired session is not something Retry can fix, while a transient server
  // failure is. Both are distinct from an empty Vault.
  const [status, setStatus] = useState<
    'loading' | 'ready' | { kind: 'unauthorized' | 'retryable' }
  >('loading');
  const [attempt, setAttempt] = useState(0);
  const [collectionVersion, setCollectionVersion] = useState(0);
  const [detailId, setDetailId] = useState<string | null>(null);
  const collectionIdRef = useRef<string | null>(null);
  collectionIdRef.current = collectionId;
  const option = FILTERS.find(value => value.value === filter) ?? FILTERS[0];
  const selectedCollection = collectionId ? collections.find(value => value.id === collectionId) : undefined;

  useEffect(() => {
    let active = true;
    setStatus('loading');
    if (option.value === 'COLLECTIONS') {
      const requestedCollectionId = collectionIdRef.current;
      void collectionsApi.list().then(value => {
        if (!active) return;
        setCollections(value);
        if (requestedCollectionId && !value.some(entry => entry.id === requestedCollectionId)) {
          setCollectionId(null);
        }
        setItems([]);
        setCollectionVersion(version => version + 1);
        setStatus('ready');
      }, (error: unknown) => {
        if (!active) return;
        setCollections([]);
        setCollectionId(null);
        setItems([]);
        setStatus({ kind: isUnauthorized(error) ? 'unauthorized' : 'retryable' });
      });
    } else {
      void vaultApi.list(option.category).then(value => {
        if (!active) return;
        setItems(value);
        setStatus('ready');
      }, (error: unknown) => {
        if (!active) return;
        setItems([]);
        setStatus({ kind: isUnauthorized(error) ? 'unauthorized' : 'retryable' });
      });
    }
    return () => { active = false; };
  }, [attempt, option.category, option.value]);

  // Collections belong to the user, not to a device: any success (a create, a
  // rename, a delete, or a membership change) must not leave a stale list on
  // screen, so it bumps collectionVersion, which both list effects depend on.
  const onCollectionsChanged = () => {
    setAttempt(value => value + 1);
    setCollectionVersion(value => value + 1);
  };

  useEffect(() => {
    if (option.value !== 'COLLECTIONS' || !collectionId || !selectedCollection) return;
    let active = true;
    setStatus('loading');
    const memberIds = new Set(
      selectedCollection.items.map(item => item.vaultItemId),
    );
    void vaultApi.list().then(value => {
      if (active) {
        setItems(value.filter(item => memberIds.has(item.id)));
        setStatus('ready');
      }
    }, (error: unknown) => {
      if (active) {
        setItems([]);
        setStatus({ kind: isUnauthorized(error) ? 'unauthorized' : 'retryable' });
      }
    });
    return () => { active = false; };
  }, [collectionId, collectionVersion, option.value, selectedCollection]);

  // Each list row shows the owner-authorized image capability the item route
  // mints. The capability is short-lived, so it is re-minted whenever the list
  // reloads or the user asks for a refresh; a row whose capability cannot be
  // read keeps its name and the list offers one retry, because an unreadable
  // preview is a retryable problem, never a missing garment.
  useEffect(() => {
    if (items.length === 0) {
      setPreviews({});
      setPreviewFailures(0);
      return;
    }
    let active = true;
    void Promise.all(
      items.map(item =>
        vaultApi.image(item.id).then(
          value => [item.id, value.url] as const,
          () => [item.id, null] as const,
        ),
      ),
    ).then(entries => {
      if (!active) return;
      const next: Record<string, string> = {};
      let failures = 0;
      for (const [id, url] of entries) {
        if (url === null) {
          failures += 1;
          continue;
        }
        next[id] = url;
      }
      setPreviews(next);
      setPreviewFailures(failures);
    });
    return () => {
      active = false;
    };
  }, [items, previewAttempt]);

  // A detail view replaces the list rather than overlaying it: no modal
  // dependency is installed, and the list stays mounted behind so returning
  // does not refetch.
  if (detailId !== null) {
    return <ItemDetail
      itemId={detailId}
      onBack={() => setDetailId(null)}
      onChanged={onCollectionsChanged}
      onRemoved={() => { setDetailId(null); setAttempt(value => value + 1); }}
    />;
  }

  return <ScrollView contentContainerStyle={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Vault</Text><Action label="Profile and settings" onPress={onProfile} /></View>
    <View accessibilityRole="radiogroup" style={styles.filters}>{FILTERS.map(value => {
      const selected = filter === value.value;
      return <Pressable key={value.value} accessibilityRole="radio" accessibilityLabel={value.label} accessibilityState={{ checked: selected }} onPress={() => { if (value.value !== filter) { setFilter(value.value); setCollectionId(null); setItems([]); setStatus('loading'); } }} style={[styles.filter, selected && styles.selected]}><Text style={[styles.filterLabel, selected && styles.selectedLabel]}>{selected ? `✓ ${value.label}` : value.label}</Text></Pressable>;
    })}</View>
    {selectionUnavailable ? <Text accessibilityLiveRegion="polite">Studio selection is unavailable because no Studio session was provided. Browse the Vault normally.</Text> : null}
    <Text accessibilityRole="header" accessibilityLiveRegion="polite" style={styles.heading}>{selectedCollection?.name ?? option.label}</Text>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading Vault items…</Text> : null}
    {typeof status === 'object' && status.kind === 'unauthorized' ? <Text accessibilityLiveRegion="polite">Your session has expired. Sign in again to see your Vault.</Text> : null}
    {typeof status === 'object' && status.kind === 'retryable' ? <><Text accessibilityLiveRegion="polite">Could not load Vault items.</Text><Action label="Retry Vault" onPress={() => setAttempt(value => value + 1)} /></> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collections.length === 0 ? <Text accessibilityLiveRegion="polite">No collections yet.</Text> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collections.length > 0 ? <Action label={collectionMode === 'manage' ? 'Done managing collections' : 'Manage collections'} onPress={() => setCollectionMode(mode => (mode === 'manage' ? 'browse' : 'manage'))} /> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collectionMode === 'manage' ? <CollectionsPicker vaultItemId={null} manageOnly onError={() => undefined} onChange={onCollectionsChanged} /> : null}
    {status === 'ready' && option.value === 'COLLECTIONS' && !collectionId && collectionMode === 'browse' ? collections.map(collection => <Action key={collection.id} label={collection.name} onPress={() => { setCollectionId(collection.id); setItems([]); setStatus('loading'); }} />) : null}
    {status === 'ready' && (option.value !== 'COLLECTIONS' || collectionId) && items.length === 0 ? <Text accessibilityLiveRegion="polite">No Vault items in {selectedCollection?.name ?? option.label}.</Text> : null}
    {status === 'ready' && previewFailures > 0 ? <Action label="Refresh previews" onPress={() => setPreviewAttempt(value => value + 1)} /> : null}
    {status === 'ready' ? items.map(item => <View key={item.id} style={styles.row}>
      {previews[item.id] ? <Image
        source={{ uri: previews[item.id] }}
        accessibilityLabel={`Preview of ${item.name}`}
        style={styles.thumbnail}
      /> : null}
      <Action
        label={item.name}
        onPress={() => {
          if (onSelect) {
            onSelect(item.id);
            return;
          }
          setDetailId(item.id);
        }}
      />
    </View>) : null}
  </ScrollView>;
}

function ItemDetail({ itemId, onBack, onChanged, onRemoved }: {
  itemId: string;
  onBack: () => void;
  onChanged: () => void;
  onRemoved: () => void;
}) {
  const [item, setItem] = useState<VaultItemSummary | null>(null);
  const [status, setStatus] = useState<
    'loading' | 'ready' | 'gone' | { kind: 'unauthorized' | 'retryable' }
  >('loading');
  const [image, setImage] = useState<VaultItemImage | null>(null);
  const [imageStatus, setImageStatus] = useState<'loading' | 'ready' | 'unavailable'>('loading');
  const [name, setName] = useState('');
  const [category, setCategory] = useState('');
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'failed' | 'in-use'>('idle');
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteStatus, setDeleteStatus] = useState<'idle' | 'deleting' | 'failed' | 'in-use'>('idle');

  const load = useCallback(() => {
    let active = true;
    setStatus('loading');
    void vaultApi.get(itemId).then(value => {
      if (!active) return;
      setItem(value);
      setName(value.name);
      setCategory(value.category);
      setStatus('ready');
    }, (error: unknown) => {
      if (!active) return;
      // 404 is the opaque "not found or not yours" answer, so an item the
      // owner cannot see is reported as gone rather than as a retryable error.
      setStatus(
        error instanceof ApiProblemError && error.code === 'VAULT_ITEM_NOT_FOUND'
          ? 'gone'
          : { kind: isUnauthorized(error) ? 'unauthorized' : 'retryable' },
      );
    });
    return () => { active = false; };
  }, [itemId]);

  const loadImage = useCallback(() => {
    let active = true;
    setImageStatus('loading');
    void vaultApi.image(itemId).then(value => {
      if (active) {
        setImage(value);
        setImageStatus('ready');
      }
    }, (error: unknown) => {
      if (!active) return;
      // The image route answers 404 only when the item is gone or not ours, so
      // that is reported as gone. Anything else (503 VAULT_MEDIA_UNAVAILABLE,
      // network) is a retryable preview problem, never a deletion.
      if (error instanceof ApiProblemError && error.code === 'VAULT_ITEM_NOT_FOUND') {
        setStatus('gone');
        return;
      }
      setImageStatus('unavailable');
    });
    return () => { active = false; };
  }, [itemId]);

  useEffect(() => load(), [load]);
  useEffect(() => loadImage(), [loadImage]);

  const save = () => {
    setSaveStatus('saving');
    void vaultApi.update(itemId, { name: name.trim(), category }).then(value => {
      setItem(value);
      setName(value.name);
      setCategory(value.category);
      setSaveStatus('saved');
      onChanged();
    }, (error: unknown) => {
      setSaveStatus(error instanceof ApiProblemError && error.code === 'VAULT_ITEM_IN_USE' ? 'in-use' : 'failed');
    });
  };

  const remove = () => {
    setDeleteStatus('deleting');
    void vaultApi.remove(itemId).then(() => {
      onRemoved();
    }, (error: unknown) => {
      if (error instanceof ApiProblemError && error.code === 'VAULT_ITEM_IN_USE') {
        setDeleteStatus('in-use');
        setConfirmingDelete(false);
        return;
      }
      if (error instanceof ApiProblemError && error.code === 'VAULT_ITEM_NOT_FOUND') {
        onRemoved();
        return;
      }
      setDeleteStatus('failed');
    });
  };

  return <ScrollView contentContainerStyle={styles.page}>
    <View style={styles.headingRow}><Text accessibilityRole="header" style={styles.title}>Vault item</Text><Action label="Back" onPress={onBack} /></View>
    {status === 'loading' ? <Text accessibilityLiveRegion="polite">Loading item…</Text> : null}
    {status === 'gone' ? <Text accessibilityLiveRegion="polite">This item is no longer available.</Text> : null}
    {typeof status === 'object' && status.kind === 'unauthorized' ? <Text accessibilityLiveRegion="polite">Your session has expired. Sign in again to see this item.</Text> : null}
    {typeof status === 'object' && status.kind === 'retryable' ? <><Text accessibilityLiveRegion="polite">Could not load this item.</Text><Action label="Retry item" onPress={load} /></> : null}
    {status === 'ready' && item ? <>
      <CollectionsPicker vaultItemId={itemId} onError={() => undefined} onChange={onChanged} />
      {imageStatus === 'ready' && image ? <Text accessibilityLiveRegion="polite">Preview available until {image.expiresAt}.</Text> : null}
      {imageStatus === 'unavailable' ? <><Text accessibilityLiveRegion="polite">Preview temporarily unavailable.</Text><Action label="Retry preview" onPress={loadImage} /></> : null}
      {imageStatus === 'loading' ? <Text accessibilityLiveRegion="polite">Loading preview…</Text> : null}

      <Text style={styles.label}>Name</Text>
      <TextInput accessibilityLabel="Item name" value={name} onChangeText={setName} style={styles.input} />

      <Text style={styles.label}>Category</Text>
      <View accessibilityRole="radiogroup" style={styles.filters}>{CATEGORIES.map(value => {
        const selected = category === value;
        return <Pressable key={value} accessibilityRole="radio" accessibilityLabel={value} accessibilityState={{ checked: selected }} onPress={() => setCategory(value)} style={[styles.filter, selected && styles.selected]}><Text style={[styles.filterLabel, selected && styles.selectedLabel]}>{selected ? `✓ ${value}` : value}</Text></Pressable>;
      })}</View>

      {item.brand ? <Text>Brand: {item.brand}</Text> : null}
      {item.color ? <Text>Colour: {item.color}</Text> : null}
      {item.category === 'UNKNOWN' ? <Text accessibilityLiveRegion="polite">This item is unclassified. Choose a category above and save.</Text> : null}

      <Action label="Save changes" onPress={save} disabled={saveStatus === 'saving' || name.trim().length === 0} />
      {saveStatus === 'saved' ? <Text accessibilityLiveRegion="polite">Changes saved.</Text> : null}
      {saveStatus === 'failed' ? <Text accessibilityLiveRegion="polite">Could not save changes. Your edits are still here.</Text> : null}
      {saveStatus === 'in-use' ? <Text accessibilityLiveRegion="polite">This item is currently used by a draft or Look, so its details cannot be changed yet.</Text> : null}

      <Text accessibilityLiveRegion="polite">Removing this item takes it out of your Vault and out of any collection it belongs to. The collection itself and any Look already generated from it stay.</Text>
      {confirmingDelete
        ? <><Text accessibilityLiveRegion="polite">Delete this Vault item?</Text><Action label="Confirm delete" onPress={remove} disabled={deleteStatus === 'deleting'} /><Action label="Cancel delete" onPress={() => setConfirmingDelete(false)} /></>
        : <Action label="Delete item" onPress={() => { setDeleteStatus('idle'); setConfirmingDelete(true); }} />}
      {deleteStatus === 'failed' ? <Text accessibilityLiveRegion="polite">Could not delete this item. Try again.</Text> : null}
      {deleteStatus === 'in-use' ? <Text accessibilityLiveRegion="polite">Delete blocked: this item is still used by an active draft or Look.</Text> : null}
    </> : null}
  </ScrollView>;
}

// A 401/403 is the session, not the network: retrying cannot fix it.
function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiProblemError && (error.code === 'UNAUTHORIZED' || error.code === 'HTTP_401' || error.code === 'HTTP_403');
}

function Action({ label, onPress, disabled = false }: { label: string; onPress: () => void; disabled?: boolean }) { return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={styles.profile}><Text style={styles.profileLabel}>{label}</Text></Pressable>; }
const styles = StyleSheet.create({ page: { padding: 20, gap: 14, backgroundColor: '#f4f4f2' }, row: { flexDirection: 'row', alignItems: 'center', gap: 10 }, thumbnail: { width: 56, height: 56, borderRadius: 8, backgroundColor: '#e6e1d8' }, headingRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }, title: { fontSize: 28, fontWeight: '700', color: '#1a1a1a' }, heading: { fontSize: 20, fontWeight: '700' }, item: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12, color: '#563c1d', fontWeight: '600' }, profile: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12 }, profileLabel: { color: '#563c1d', fontWeight: '600' }, label: { fontWeight: '700', color: '#24180b' }, input: { minHeight: 44, borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 8, paddingHorizontal: 12, backgroundColor: '#ffffff', color: '#1a1a1a' }, filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, filter: { minHeight: 44, justifyContent: 'center', borderWidth: 1, borderColor: '#6b4f2a', borderRadius: 22, paddingHorizontal: 14 }, selected: { borderWidth: 3, backgroundColor: '#eee2d0' }, filterLabel: { color: '#3f3a33' }, selectedLabel: { fontWeight: '700', color: '#24180b' } });
