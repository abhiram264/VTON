import {useCallback, useEffect, useRef, useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {
  choosePhoto,
  isCameraPermissionDenied,
} from '../features/my-model/photoSource';
import {createShareImportResolver} from '../features/share/importClient';
import type {
  ShareImportResolver,
  ShareImportResponse,
} from '../features/share/importTypes';
import {apiConfig, type ShareImportApiConfig} from './share-import/apiConfig';
import {attachRecoveryImage} from './share-import/recoveryClient';
import type {SharePayload} from '../features/share/types';
import type {LocalPhoto} from '../features/my-model/types';

// Screen flow states are the frozen response statuses plus the transient
// lease-exhausted view (bounded backoff spent inside the client; after the
// 60s lease we offer retry, not screenshot, unless the server said
// NEEDS_IMAGE).
export type ShareImportFlowState =
  | {view: 'RESOLVING'}
  | {view: 'READY'}
  | {view: 'NEEDS_IMAGE'; importId: string}
  | {view: 'FAILED_RETRYABLE'}
  | {view: 'FAILED_TERMINAL'}
  | {view: 'LEASE_EXPIRED'};

type Props = {
  payload: SharePayload;
  resolver?: ShareImportResolver;
  picker?: () => Promise<LocalPhoto | null>;
  api?: ShareImportApiConfig;
};

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
    return {view: 'LEASE_EXPIRED'};
  });
}

function toFlowState(response: ShareImportResponse): ShareImportFlowState {
  switch (response.status) {
    case 'READY':
      return {view: 'READY'};
    case 'NEEDS_IMAGE':
      return {view: 'NEEDS_IMAGE', importId: response.importId};
    case 'FAILED_RETRYABLE':
      return {view: 'FAILED_RETRYABLE'};
    case 'FAILED_TERMINAL':
      return {view: 'FAILED_TERMINAL'};
    case 'RESOLVING':
      // The client's bounded polling already spent the lease budget.
      return {view: 'LEASE_EXPIRED'};
  }
}

export default function ShareImportScreen({
  payload,
  resolver,
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
    resolverRef.current.payload !== payload
  ) {
    resolverRef.current = {
      payload,
      resolve: resolver ?? createShareImportResolver(api),
    };
  }
  const activeResolver = resolverRef.current.resolve;

  // The client resolver owns staged-image release discipline (exactly once
  // after verified upload completion, or once on cancel before it). The
  // screen only cancels via the signal on unmount/navigation.
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    abortRef.current = controller;
    const active = activeResolver;
    let cancelled = false;
    active(payloadRef.current, controller.signal).then(
      result => {
        if (!cancelled) {
          setState(toFlowState(result));
        }
      },
      () => {
        // Client rejections are upload/network failures: retryable, never
        // screenshot (spec D-infrastructure). Abort means unmount.
        if (!cancelled && !controller.signal.aborted) {
          setState({view: 'FAILED_RETRYABLE'});
        }
      },
    );
    return () => {
      cancelled = true;
      controller.abort();
    };
    // activeResolver identity changes only with payload: the idempotency
    // key stays stable for the lifetime of the route (spec D-lifecycle).
  }, [payload, activeResolver]);

  const retry = useCallback(async () => {
    if (busy) {
      return;
    }
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
      setState(next);
    } catch {
      if (!controller.signal.aborted) {
        setState({view: 'LEASE_EXPIRED'});
      }
    } finally {
      setBusy(false);
    }
  }, [busy, activeResolver]);

  const recover = useCallback(
    async (source: 'CHOOSE_SCREENSHOT' | 'UPLOAD_IMAGE') => {
      if (busy || state.view !== 'NEEDS_IMAGE') {
        return;
      }
      let photo: LocalPhoto | null = null;
      try {
        photo = await picker();
      } catch (error) {
        setNotice(
          isCameraPermissionDenied(error)
            ? 'Photo access is off. Enable it in Settings, then try again.'
            : 'Could not open the photo picker.',
        );
        return;
      }
      if (!photo) {
        return;
      }
      setBusy(true);
      setNotice(null);
      const controller = new AbortController();
      abortRef.current = controller;
      try {
        const result = await attachRecoveryImage(
          api,
          state.importId,
          photo,
          controller.signal,
        );
        if (!controller.signal.aborted) {
          // 202 RESOLVING is an accepted-but-unresolved attach, not READY:
          // show the neutral resolving view until state is observed again.
          setState(
            result.status === 'RESOLVING'
              ? {view: 'RESOLVING'}
              : toFlowState(result),
          );
        }
      } catch {
        if (!controller.signal.aborted) {
          setNotice('Could not attach that image. Try another one.');
        }
      } finally {
        setBusy(false);
      }
    },
    [busy, picker, api, state],
  );

  // Neutral copy only: no shared URL, URI, or payload content is ever
  // rendered or logged (ADR-015).
  return (
    <View style={styles.page}>
      {state.view === 'RESOLVING' ? (
        <>
          <Text style={styles.title}>Preparing shared item</Text>
          <Text style={styles.body}>This usually takes a moment.</Text>
        </>
      ) : null}
      {state.view === 'READY' ? (
        <Text style={styles.title}>Shared item ready</Text>
      ) : null}
      {state.view === 'NEEDS_IMAGE' ? (
        <>
          <Text style={styles.title}>Add a photo to continue</Text>
          <Text style={styles.body}>
            We could not read an image from this share. Choose a screenshot or
            another image of the item.
          </Text>
          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={() => recover('CHOOSE_SCREENSHOT')}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>Choose Screenshot</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={() => recover('UPLOAD_IMAGE')}
              style={[styles.button, styles.secondary, busy && styles.disabled]}
            >
              <Text style={styles.secondaryLabel}>Upload Image</Text>
            </Pressable>
          </View>
        </>
      ) : null}
      {state.view === 'FAILED_RETRYABLE' || state.view === 'LEASE_EXPIRED' ? (
        <>
          <Text style={styles.title}>Could not prepare the shared item</Text>
          <Text style={styles.body}>This looks temporary. Try again.</Text>
          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={retry}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>Try Again</Text>
            </Pressable>
          </View>
        </>
      ) : null}
      {state.view === 'FAILED_TERMINAL' ? (
        <>
          <Text style={styles.title}>Could not prepare the shared item</Text>
          <Text style={styles.body}>
            This share is not supported. Share the item again from its app.
          </Text>
        </>
      ) : null}
      {notice ? <Text style={styles.notice}>{notice}</Text> : null}
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
