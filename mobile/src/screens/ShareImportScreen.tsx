import {useCallback, useEffect, useRef, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, TextInput, View, Image} from 'react-native';
import {ApiProblemError} from '../features/my-model/http';
import {
  choosePhoto,
  isCameraPermissionDenied,
} from '../features/my-model/photoSource';
import {createShareImportConfirmer, createShareImportPreviewLoader, createShareImportResolver} from '../features/share/importClient';
import type {
  ShareImportConfirmCorrections,
  ShareImportConfirmer,
  ShareImportPreview,
  ShareImportPreviewLoader,
  ShareImportResolver,
  ShareImportResponse,
} from '../features/share/importTypes';
import CollectionsPicker from '../features/collections/CollectionsPicker';
import {createCollectionsApi} from '../features/collections/collectionsApi';
import type {CollectionsApi} from '../features/collections/collectionsApi';
import {apiConfig, type ShareImportApiConfig} from './share-import/apiConfig';
import {attachRecoveryImage} from './share-import/recoveryClient';
import type {SharePayload} from '../features/share/types';
import type {LocalPhoto} from '../features/my-model/types';
import {
  ACTION_CHOOSE_SCREENSHOT,
  ACTION_UPLOAD_IMAGE,
  EXTRACTION_FAILURE_BODY,
} from '../features/generation/failureContract';

// Screen flow states are the frozen response statuses plus the transient
// lease-exhausted view (bounded backoff spent inside the client; after the
// 60s lease we offer retry, not screenshot, unless the server said
// NEEDS_IMAGE).
export type ShareImportFlowState =
  | {view: 'RESOLVING'}
  | {view: 'READY'; importId: string}
  | {view: 'SAVED'; itemId: string}
  | {view: 'NEEDS_IMAGE'; importId: string}
  | {view: 'FAILED_RETRYABLE'; reason?: 'DEPENDENCY_UNAVAILABLE' | 'RESOLUTION_TIMEOUT'; importId?: string}
  | {view: 'FAILED_TERMINAL'; message?: string}
  | {view: 'LEASE_EXPIRED'};

function terminalMessage(code: string): string {
  switch (code) {
    // C23: an ended session is not a broken share. Telling the user to re-share
    // would send them in a circle, because the same expired credential would
    // fail the new share too; the actionable fact is that they must sign in.
    case 'UNAUTHORIZED':
      return 'Your session has expired. Sign in again to continue this share.';
    case 'IMPORT_NOT_FOUND':
      return 'This shared import is no longer available. Share the item again from its app.';
    case 'IMPORT_STATE_CONFLICT':
      return 'This shared import has already changed. Share the item again from its app.';
    case 'UPLOAD_NOT_CLAIMABLE':
      return 'That image could not be attached. Choose another image.';
    default:
      return 'This share could not be continued. Start a new share from its app.';
  }
}

function toErrorFlowState(error: unknown): ShareImportFlowState {
  if (error instanceof ApiProblemError) {
    if (
      error.code === 'DEPENDENCY_UNAVAILABLE' ||
      error.code === 'RESOLUTION_TIMEOUT'
    ) {
      return {view: 'FAILED_RETRYABLE', reason: error.code};
    }
    if (/^HTTP_(?:408|425|429|5\d\d)$/.test(error.code)) {
      return {view: 'FAILED_RETRYABLE'};
    }
    return {view: 'FAILED_TERMINAL', message: terminalMessage(error.code)};
  }
  return {view: 'FAILED_RETRYABLE'};
}

type Props = {
  payload: SharePayload;
  resolver?: ShareImportResolver;
  confirmer?: ShareImportConfirmer;
  previewLoader?: ShareImportPreviewLoader;
  picker?: () => Promise<LocalPhoto | null>;
  api?: ShareImportApiConfig;
};

// Mirrors ProductCategory (backend/normalization/ProductCategory). UNKNOWN is a
// real server value for an unclassified item, so it is offered rather than
// hidden; the server remains the authority and rejects an unknown name.
const CATEGORY_OPTIONS: readonly string[] = [
  'TOP', 'BOTTOM', 'ONE_PIECE', 'ETHNIC_FULL_BODY', 'SAREE', 'LEHENGA', 'KURTA',
  'SHERWANI', 'FOOTWEAR', 'HEADWEAR', 'BAG', 'JEWELLERY', 'ACCESSORY', 'UNKNOWN',
];

// Retry after the bounded in-client backoff spends the 60s lease: the same
// stable idempotency key replays the import instead of creating a new one.
function retryAfterLease(
  resolve: ShareImportResolver,
  payload: SharePayload,
  signal: AbortSignal,
): Promise<ShareImportFlowState> {
  return resolve(payload, signal).then(toFlowState, error => {
    if (signal.aborted) {
      throw error;
    }
    return toErrorFlowState(error);
  });
}

function toFlowState(response: ShareImportResponse): ShareImportFlowState {
  switch (response.status) {
    case 'READY':
      return {view: 'READY', importId: response.importId};
    case 'NEEDS_IMAGE':
      return {view: 'NEEDS_IMAGE', importId: response.importId};
    case 'FAILED_RETRYABLE':
      return {view: 'FAILED_RETRYABLE', reason: response.reason, importId: response.importId};
    case 'FAILED_TERMINAL':
      return {view: 'FAILED_TERMINAL'};
    case 'RESOLVING':
      // The client's bounded polling already spent the lease budget.
      return {view: 'LEASE_EXPIRED'};
  }
}

function sameSharePayload(left: SharePayload, right: SharePayload): boolean {
  if (left.kind !== right.kind) return false;
  if (left.kind === 'url' && right.kind === 'url') {
    return left.url === right.url && left.sourcePackage === right.sourcePackage;
  }
  if (left.kind === 'image' && right.kind === 'image') {
    return left.localImageUri === right.localImageUri && left.sourcePackage === right.sourcePackage;
  }
  return left.url === right.url && left.localImageUri === right.localImageUri && left.sourcePackage === right.sourcePackage;
}

export default function ShareImportScreen({
  payload,
  resolver,
  confirmer,
  previewLoader,
  picker = choosePhoto,
  api = apiConfig,
}: Props) {
  const [state, setState] = useState<ShareImportFlowState>({
    view: 'RESOLVING',
  });
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const payloadRef = useRef(payload);
  payloadRef.current = payload;

  // One resolver per mounted payload, minted once: the idempotency key it
  // generates must stay stable across the initial resolve AND every retry,
  // so 'Try Again' replays the same import instead of creating a second one
  // (story 9: one share creates at most one import).
  const resolverRef = useRef<{
    payload: SharePayload;
    resolve: ShareImportResolver;
  } | null>(null);
  if (
    resolver !== undefined ||
    resolverRef.current === null ||
    !sameSharePayload(resolverRef.current.payload, payload)
  ) {
    resolverRef.current = {
      payload,
      resolve: resolver ?? createShareImportResolver(api),
    };
  }
  const activeResolver = resolverRef.current.resolve;

  // The confirmer and preview loader are stateless (server-side identity is
  // derived from owner + import), but their identity must stay stable: a fresh
  // closure per render would make the preview effect re-run on every render,
  // aborting the in-flight load and consuming the response it was waiting for.
  const clientsRef = useRef<{
    api: ShareImportApiConfig;
    confirmer?: ShareImportConfirmer;
    previewLoader?: ShareImportPreviewLoader;
    resolved: {
      confirmer: ShareImportConfirmer;
      previewLoader: ShareImportPreviewLoader;
      collections: CollectionsApi;
    };
  } | null>(null);
  if (
    clientsRef.current === null ||
    clientsRef.current.api !== api ||
    clientsRef.current.confirmer !== confirmer ||
    clientsRef.current.previewLoader !== previewLoader
  ) {
    clientsRef.current = {
      api,
      confirmer,
      previewLoader,
      resolved: {
        confirmer: confirmer ?? createShareImportConfirmer(api),
        previewLoader: previewLoader ?? createShareImportPreviewLoader(api),
        // The picker must read through the SAME config as the rest of this
        // screen: its own module-level default would resolve a different API
        // base URL and silently fail to load.
        collections: createCollectionsApi(api),
      },
    };
  }
  const activeConfirmer = clientsRef.current.resolved.confirmer;
  const activePreviewLoader = clientsRef.current.resolved.previewLoader;
  const activeCollectionsApi = clientsRef.current.resolved.collections;
  const [preview, setPreview] = useState<ShareImportPreview | null>(null);
  const [name, setName] = useState('');
  const [category, setCategory] = useState<string | null>(null);
  // The confirm contract accepts ONE collectionId, so this surface is
  // single-select: the picker is told about the current choice and any new
  // selection replaces it. Multi-select here would silently drop the extras.
  const [selectedCollectionId, setSelectedCollectionId] = useState<string | null>(
    null,
  );
  // The collections picker fetches on mount, so it is revealed on demand: a
  // READY screen that only needs "Save to Vault" issues no collection request.
  const [showCollections, setShowCollections] = useState(false);

  // The client resolver owns staged-image release discipline (exactly once
  // after verified upload completion, or once on cancel before it). The
  // screen only cancels via the signal on unmount/navigation.
  const abortRef = useRef<AbortController | null>(null);
  const operationRef = useRef(0);
  const busyRef = useRef(false);

  useEffect(() => {
    const operation = ++operationRef.current;
    busyRef.current = true;
    setBusy(false);
    setNotice(null);
    setState({view: 'RESOLVING'});
    const controller = new AbortController();
    abortRef.current = controller;
    const active = activeResolver;
    let cancelled = false;
    active(payloadRef.current, controller.signal).then(
      result => {
        if (!cancelled && operation === operationRef.current) {
          setState(toFlowState(result));
        }
      },
      error => {
        if (
          !cancelled &&
          !controller.signal.aborted &&
          operation === operationRef.current
        ) {
          setState(toErrorFlowState(error));
        }
      },
    ).finally(() => {
      if (!cancelled && operation === operationRef.current) {
        busyRef.current = false;
        setBusy(false);
      }
    });
    return () => {
      cancelled = true;
      if (abortRef.current === controller) {
        abortRef.current.abort();
        abortRef.current = null;
      }
    };
    // activeResolver identity changes only with payload: the idempotency
    // key stays stable for the lifetime of the route (spec D-lifecycle).
  }, [payload, activeResolver]);

  const retry = useCallback(async () => {
    if (busyRef.current) {
      return;
    }
    const operation = ++operationRef.current;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    setState({view: 'RESOLVING'});
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const next = await retryAfterLease(
        activeResolver,
        payloadRef.current,
        controller.signal,
      );
      if (!controller.signal.aborted && operation === operationRef.current) {
        setState(next);
      }
    } catch (error) {
      if (!controller.signal.aborted && operation === operationRef.current) {
        setState(toErrorFlowState(error));
      }
    } finally {
      if (!controller.signal.aborted && operation === operationRef.current) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  }, [activeResolver]);

  // Persist the resolved import. Only the confirm response may present the
  // saved state; a failed confirm never claims the item was kept.
  const confirmItem = useCallback(
    async (importId: string) => {
      if (busyRef.current) {
        return;
      }
      const operation = ++operationRef.current;
      busyRef.current = true;
      setBusy(true);
      setNotice(null);
      const controller = new AbortController();
      abortRef.current = controller;
      const corrections: ShareImportConfirmCorrections = {};
      const trimmedName = name.trim();
      if (trimmedName.length > 0) {
        corrections.name = trimmedName;
      }
      if (category !== null) {
        corrections.category = category;
      }
      if (selectedCollectionId !== null) {
        corrections.collectionId = selectedCollectionId;
      }
      try {
        const item = await activeConfirmer(
          importId,
          controller.signal,
          Object.keys(corrections).length === 0 ? undefined : corrections,
        );
        if (!controller.signal.aborted && operation === operationRef.current) {
          setState({view: 'SAVED', itemId: item.id});
        }
      } catch (error) {
        if (controller.signal.aborted || operation !== operationRef.current) {
          return;
        }
        const next = toErrorFlowState(error);
        if (next.view === 'FAILED_TERMINAL') {
          setState(next);
        } else {
          // Transient failure: the import is still resolvable, so keep the
          // READY affordance instead of restarting resolution.
          setState({view: 'READY', importId});
          setNotice('Could not save this item to your Vault. Try again.');
        }
      } finally {
        if (!controller.signal.aborted && operation === operationRef.current) {
          busyRef.current = false;
          setBusy(false);
        }
      }
    },
    [activeConfirmer, name, category, selectedCollectionId],
  );

  // Preview is presentation-only: the route may be absent (unmounted) or the
  // capability expired, and neither is a reason to block saving a resolved
  // import. A failed load simply leaves the preview slot empty.
  const previewImportId = state.view === 'READY' ? state.importId : null;
  useEffect(() => {
    if (previewImportId === null) {
      return;
    }
    let active = true;
    const controller = new AbortController();
    activePreviewLoader(previewImportId, controller.signal).then(
      value => {
        if (active) {
          setPreview(value);
        }
      },
      () => {
        if (active) {
          setPreview(null);
        }
      },
    );
    return () => {
      active = false;
      controller.abort();
    };
  }, [previewImportId, activePreviewLoader]);

  const recover = useCallback(
    async (_source: 'CHOOSE_SCREENSHOT' | 'UPLOAD_IMAGE') => {
      const importId =
        state.view === 'NEEDS_IMAGE'
          ? state.importId
          : state.view === 'FAILED_RETRYABLE' && state.reason === 'DEPENDENCY_UNAVAILABLE'
          ? state.importId
          : undefined;
      if (busyRef.current || !importId) {
        return;
      }
      const operation = ++operationRef.current;
      busyRef.current = true;
      setBusy(true);
      setNotice(null);
      const controller = new AbortController();
      abortRef.current = controller;
      const isCurrent = () =>
        !controller.signal.aborted && operation === operationRef.current;
      try {
        let photo: LocalPhoto | null;
        try {
          photo = await picker();
        } catch (error) {
          if (isCurrent()) {
            setNotice(
              isCameraPermissionDenied(error)
                ? 'Photo access is off. Enable it in Settings, then try again.'
                : 'Could not open the photo picker.',
            );
          }
          return;
        }
        if (!isCurrent() || !photo) {
          return;
        }
        try {
          const result = await attachRecoveryImage(
            api,
            importId,
            photo,
            controller.signal,
          );
          if (isCurrent()) {
            // 202 RESOLVING is an accepted-but-unresolved attach, not READY:
            // show the neutral resolving view until state is observed again.
            setState(
              result.status === 'RESOLVING'
                ? {view: 'RESOLVING'}
                : toFlowState(result),
            );
          }
        } catch (error) {
          if (isCurrent()) {
            if (error instanceof ApiProblemError) {
              if (error.code === 'UPLOAD_NOT_CLAIMABLE') {
                setState({view: 'FAILED_RETRYABLE', reason: 'DEPENDENCY_UNAVAILABLE', importId});
                setNotice('Could not attach that image. Try another one.');
              } else {
                const next = toErrorFlowState(error);
                setState(next.view === 'FAILED_RETRYABLE' ? { ...next, importId } : next);
                if (next.view === 'FAILED_RETRYABLE') {
                  setNotice('Could not attach that image. Try another one.');
                }
              }
            } else {
              setNotice('Could not attach that image. Try another one.');
            }
          }
        }
      } finally {
        if (isCurrent()) {
          busyRef.current = false;
          setBusy(false);
        }
      }
    },
    [picker, api, state],
  );


  // Neutral copy only: no shared URL, URI, or payload content is ever
  // rendered or logged (ADR-015).
  return (
    <View style={styles.page}>
      {state.view === 'RESOLVING' ? (
        <View accessible accessibilityLiveRegion="polite">
          <Text accessibilityRole="header" style={styles.title}>Preparing shared item</Text>
          <Text style={styles.body}>This usually takes a moment.</Text>
        </View>
      ) : null}
      {state.view === 'READY' ? (
        <ScrollView contentContainerStyle={styles.readyContent}>
          <View accessible accessibilityLiveRegion="polite">
            <Text accessibilityRole="header" style={styles.title}>Shared item ready</Text>
            <Text style={styles.body}>
              Check this item, correct anything that looks wrong, then save it to
              your Vault.
            </Text>
          </View>
          {preview !== null ? (
            <>
              <Image
                accessible
                accessibilityRole="image"
                accessibilityLabel="Shared item preview"
                source={{uri: preview.previewUrl}}
                style={styles.preview}
              />
              {preview.sourceTitle !== undefined ? (
                <Text style={styles.body}>{preview.sourceTitle}</Text>
              ) : null}
              {preview.sourceSiteName !== undefined ? (
                <Text style={styles.body}>{preview.sourceSiteName}</Text>
              ) : null}
            </>
          ) : null}
          <TextInput
            accessibilityLabel="Item name"
            accessibilityState={{disabled: busy}}
            editable={!busy}
            onChangeText={setName}
            placeholder="Name (optional)"
            style={styles.input}
            value={name}
          />
          <Text style={styles.body}>Category</Text>
          <View accessibilityRole="radiogroup" style={styles.categoryRow}>
            {CATEGORY_OPTIONS.map(option => {
              const chosen = category === option;
              return (
                <Pressable
                  key={option}
                  accessibilityRole="radio"
                  accessibilityLabel={option}
                  accessibilityState={{checked: chosen, disabled: busy}}
                  disabled={busy}
                  onPress={() => setCategory(chosen ? null : option)}
                  style={[styles.category, chosen && styles.categorySelected]}
                >
                  <Text
                    style={[
                      styles.categoryLabel,
                      chosen && styles.categoryLabelSelected,
                    ]}
                  >
                    {option}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{expanded: showCollections, disabled: busy}}
            disabled={busy}
            onPress={() => setShowCollections(value => !value)}
            style={[styles.button, styles.secondary, busy && styles.disabled]}
          >
            <Text style={styles.secondaryLabel}>
              {showCollections ? 'Hide collections' : 'Add to a collection'}
            </Text>
          </Pressable>
          {showCollections ? (
            <CollectionsPicker
              api={activeCollectionsApi}
              onChange={ids => setSelectedCollectionId(ids[ids.length - 1] ?? null)}
              selectedIds={
                selectedCollectionId === null ? [] : [selectedCollectionId]
              }
              vaultItemId={null}
            />
          ) : null}
          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{disabled: busy, busy}}
              disabled={busy}
              onPress={() => confirmItem(state.importId)}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>Save to Vault</Text>
            </Pressable>
          </View>
        </ScrollView>
      ) : null}
      {state.view === 'SAVED' ? (
        <View accessible accessibilityLiveRegion="polite">
          <Text accessibilityRole="header" style={styles.title}>Saved to your Vault</Text>
          <Text style={styles.body}>
            This item is now in your Vault.
          </Text>
        </View>
      ) : null}
      {state.view === 'NEEDS_IMAGE' ? (
        <>
          <View accessible accessibilityLiveRegion="polite">
            <Text accessibilityRole="header" style={styles.title}>Add a photo to continue</Text>
            <Text accessibilityLiveRegion="polite" style={styles.body}>{EXTRACTION_FAILURE_BODY}</Text>
          </View>
          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{disabled: busy, busy}}
              disabled={busy}
              onPress={() => recover('CHOOSE_SCREENSHOT')}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>{ACTION_CHOOSE_SCREENSHOT}</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{disabled: busy, busy}}
              disabled={busy}
              onPress={() => recover('UPLOAD_IMAGE')}
              style={[styles.button, styles.secondary, busy && styles.disabled]}
            >
              <Text style={styles.secondaryLabel}>{ACTION_UPLOAD_IMAGE}</Text>
            </Pressable>
          </View>
        </>
      ) : null}
      {state.view === 'FAILED_RETRYABLE' || state.view === 'LEASE_EXPIRED' ? (
        <>
          <View accessible accessibilityLiveRegion="polite">
            <Text accessibilityRole="header" style={styles.title}>Could not prepare the shared item</Text>
            <Text style={styles.body}>This looks temporary. Try again.</Text>
          </View>
            <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{disabled: busy, busy}}
              disabled={busy}
              onPress={() => retry()}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>Try Again</Text>
            </Pressable>
            {state.view === 'FAILED_RETRYABLE' &&
            state.importId && state.reason === 'DEPENDENCY_UNAVAILABLE' ? (
              <>
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{disabled: busy, busy}}
                  disabled={busy}
                  onPress={() => recover('CHOOSE_SCREENSHOT')}
                  style={[styles.button, styles.primary, busy && styles.disabled]}
                >
                  <Text style={styles.primaryLabel}>{ACTION_CHOOSE_SCREENSHOT}</Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{disabled: busy, busy}}
                  disabled={busy}
                  onPress={() => recover('UPLOAD_IMAGE')}
                  style={[styles.button, styles.secondary, busy && styles.disabled]}
                >
                  <Text style={styles.secondaryLabel}>{ACTION_UPLOAD_IMAGE}</Text>
                </Pressable>
              </>
            ) : null}
          </View>
        </>
      ) : null}
      {state.view === 'FAILED_TERMINAL' ? (
        <View accessible accessibilityLiveRegion="assertive">
          <Text accessibilityRole="header" style={styles.title}>Could not prepare the shared item</Text>
          <Text style={styles.body}>
            {state.message ?? 'This share is not supported. Share the item again from its app.'}
          </Text>
        </View>
      ) : null}
      {notice ? <Text accessibilityLiveRegion="assertive" style={styles.notice}>{notice}</Text> : null}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: paper,
    padding: 24,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  title: {
    color: ink,
    fontSize: 20,
    fontWeight: '600',
    textAlign: 'center',
  },
  body: {
    color: muted,
    fontSize: 15,
    lineHeight: 22,
    textAlign: 'center',
  },
  readyContent: {
    gap: 10,
    alignSelf: 'stretch',
    paddingVertical: 8,
  },
  preview: {
    width: '100%',
    aspectRatio: 2 / 3,
    borderRadius: 8,
    backgroundColor: '#ddd8d0',
  },
  input: {
    borderColor: ink,
    borderWidth: 1,
    borderRadius: 8,
    minHeight: 48,
    paddingHorizontal: 12,
    color: ink,
  },
  categoryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  category: {
    borderColor: ink,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  categorySelected: {
    backgroundColor: accent,
    borderColor: accent,
  },
  categoryLabel: {
    color: ink,
    fontSize: 13,
  },
  categoryLabelSelected: {
    color: paper,
    fontWeight: '600',
  },
  notice: {
    color: '#8a2f2b',
    fontSize: 14,
    lineHeight: 20,
    textAlign: 'center',
  },
  actions: {
    gap: 8,
    alignSelf: 'stretch',
  },
  button: {
    borderRadius: 8,
    minHeight: 48,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  primary: {
    backgroundColor: accent,
  },
  primaryLabel: {
    color: paper,
    fontSize: 16,
    fontWeight: '600',
  },
  secondary: {
    backgroundColor: paper,
    borderColor: ink,
    borderWidth: 1,
  },
  secondaryLabel: {
    color: ink,
    fontSize: 16,
    fontWeight: '600',
  },
  disabled: {
    opacity: 0.5,
  },
});
