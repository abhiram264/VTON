import { useEffect, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  collectionsApi,
  type Collection,
  type CollectionsApi,
} from './collectionsApi';

/**
 * Reusable collections surface over the mounted /collections controller.
 *
 * Three modes, one component:
 *  - membership mode (`vaultItemId` set): shows whether the given garment is
 *    in each collection and toggles membership through the API.
 *  - selection mode (`vaultItemId` null): reports the chosen collection ids
 *    through `onChange` (used when choosing a collection during import)
 *    without touching membership.
 *  - management mode (`manageOnly`): create/rename/delete only. No membership
 *    checkbox is rendered, because there is no item and no selection to toggle
 *    — a checkbox there would be a control that does nothing.
 *
 * Membership is many-to-many and non-destructive: removing a garment from a
 * collection and deleting a collection both leave the garment in the Vault.
 */
export type CollectionsPickerProps = {
  vaultItemId: string | null;
  api?: CollectionsApi;
  selectedIds?: string[];
  manageOnly?: boolean;
  onChange?: (selected: string[]) => void;
  onError?: (error: unknown) => void;
};

export default function CollectionsPicker({
  vaultItemId,
  api = collectionsApi,
  selectedIds,
  manageOnly = false,
  onChange,
  onError,
}: CollectionsPickerProps) {
  const [collections, setCollections] = useState<Collection[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>(
    'loading',
  );
  const [attempt, setAttempt] = useState(0);
  const [name, setName] = useState('');
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    setStatus('loading');
    void api.list().then(
      value => {
        if (!active) return;
        setCollections(value);
        setStatus('ready');
      },
      error => {
        if (!active) return;
        setStatus('failed');
        onError?.(error);
      },
    );
    return () => {
      active = false;
    };
  }, [api, attempt, onError]);

  const replace = (updated: Collection) => {
    setCollections(current =>
      current.map(entry => (entry.id === updated.id ? updated : entry)),
    );
    setDrafts(current => ({ ...current, [updated.id]: updated.name }));
  };

  const guard = async (work: () => Promise<void>) => {
    setBusy(true);
    try {
      await work();
    } catch (error) {
      onError?.(error);
    } finally {
      setBusy(false);
    }
  };

  const inCollection = (collection: Collection): boolean =>
    vaultItemId !== null && !manageOnly
      ? collection.items.some(item => item.vaultItemId === vaultItemId)
      : (selectedIds ?? []).includes(collection.id);

  const toggle = (collection: Collection) =>
    guard(async () => {
      if (vaultItemId === null) {
        const current = selectedIds ?? [];
        onChange?.(
          current.includes(collection.id)
            ? current.filter(id => id !== collection.id)
            : [...current, collection.id],
        );
        return;
      }
      if (inCollection(collection)) {
        await api.removeItem(collection.id, vaultItemId);
        replace({
          ...collection,
          items: collection.items.filter(
            item => item.vaultItemId !== vaultItemId,
          ),
        });
      } else {
        replace(await api.addItem(collection.id, vaultItemId));
      }
    });

  const create = () =>
    guard(async () => {
      const trimmed = name.trim();
      if (trimmed.length === 0) return;
      const created = await api.create(trimmed);
      setName('');
      setDrafts(current => ({ ...current, [created.id]: created.name }));
      if (manageOnly) {
        setCollections(current => [...current, created]);
        return;
      }
      if (vaultItemId === null) {
        setCollections(current => [...current, created]);
        onChange?.([...(selectedIds ?? []), created.id]);
        return;
      }
      setCollections(current => [...current, created]);
      replace(await api.addItem(created.id, vaultItemId));
    });

  const rename = (collection: Collection) =>
    guard(async () => {
      const draft = (drafts[collection.id] ?? collection.name).trim();
      if (draft.length === 0 || draft === collection.name) return;
      replace(await api.rename(collection.id, draft));
    });

  const removeCollection = (collection: Collection) =>
    guard(async () => {
      await api.remove(collection.id);
      setCollections(current =>
        current.filter(entry => entry.id !== collection.id),
      );
      if (selectedIds?.includes(collection.id)) {
        onChange?.(selectedIds.filter(id => id !== collection.id));
      }
    });

  return (
    <View style={styles.panel}>
      <Text accessibilityRole="header" style={styles.heading}>
        Collections
      </Text>
      {status === 'loading' ? (
        <Text accessibilityLiveRegion="polite">Loading collections…</Text>
      ) : null}
      {status === 'failed' ? (
        <>
          <Text accessibilityLiveRegion="polite">
            Could not load collections.
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Retry collections"
            onPress={() => setAttempt(value => value + 1)}
            style={styles.action}
          >
            <Text style={styles.actionLabel}>Retry collections</Text>
          </Pressable>
        </>
      ) : null}
      {status === 'ready' && collections.length === 0 ? (
        <Text accessibilityLiveRegion="polite">No collections yet.</Text>
      ) : null}
      <ScrollView>
        {collections.map(collection => {
          const selected = inCollection(collection);
          return (
            <View key={collection.id} style={styles.row}>
              {manageOnly ? null : (
                <Pressable
                  accessibilityRole="checkbox"
                  accessibilityLabel={`Membership ${collection.name}`}
                  accessibilityState={{ checked: selected, disabled: busy }}
                  disabled={busy}
                  onPress={() => void toggle(collection)}
                  style={styles.checkbox}
                >
                  <Text style={styles.rowLabel}>{selected ? '✓' : '○'}</Text>
                </Pressable>
              )}
              <TextInput
                accessibilityLabel={`Rename ${collection.name}`}
                value={drafts[collection.id] ?? collection.name}
                editable={!busy}
                onChangeText={text =>
                  setDrafts(current => ({ ...current, [collection.id]: text }))
                }
                onSubmitEditing={() => void rename(collection)}
                style={styles.input}
              />
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`Delete ${collection.name}`}
                accessibilityState={{ disabled: busy }}
                disabled={busy}
                onPress={() => void removeCollection(collection)}
                style={styles.action}
              >
                <Text style={styles.actionLabel}>Delete</Text>
              </Pressable>
            </View>
          );
        })}
      </ScrollView>
      <View style={styles.createRow}>
        <TextInput
          accessibilityLabel="New collection name"
          value={name}
          editable={!busy}
          onChangeText={setName}
          placeholder="New collection"
          style={styles.input}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Create collection"
          accessibilityState={{ disabled: busy }}
          disabled={busy}
          onPress={() => void create()}
          style={styles.action}
        >
          <Text style={styles.actionLabel}>Create</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: { gap: 10 },
  heading: { fontSize: 18, fontWeight: '700' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  checkbox: { minHeight: 44, minWidth: 44, justifyContent: 'center' },
  rowLabel: { color: '#24180b', fontWeight: '600' },
  createRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  input: {
    flex: 1,
    minHeight: 44,
    borderWidth: 1,
    borderColor: '#6b4f2a',
    borderRadius: 8,
    paddingHorizontal: 12,
  },
  action: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 10 },
  actionLabel: { color: '#563c1d', fontWeight: '600' },
});
