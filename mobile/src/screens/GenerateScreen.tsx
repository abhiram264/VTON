import { useEffect, useRef, useState } from 'react';
import { AppState, Pressable, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
import { createLook, isNetworkError } from '../features/generation/createLook';
import type { CreateLookOutcome } from '../features/generation/createLook';
import { generationApi, pollGeneration } from '../features/generation/generationApi';
import {
  CANCELLED_BY_USER,
  recoverActiveGeneration,
  requestCancellation,
} from '../features/generation/generationLifecycle';
import {
  clearActiveGeneration,
  getActiveGeneration,
} from '../features/generation/activeGeneration';
import { productUploader } from '../features/generation/productUpload';
import type {
  CoarseGenerationPhase,
  GenerationApi,
  GenerationResource,
  ProductPicker,
} from '../features/generation/types';
import {
  ACTION_CHOOSE_ANOTHER_PHOTO,
  ACTION_TRY_AGAIN,
  OUTAGE_BODY,
  OUTAGE_TITLE,
  QUALITY_FAILURE_BODY,
  isProviderOutage,
} from '../features/generation/failureContract';
import type { LocalPhoto } from '../features/my-model/types';
import { vaultApi } from '../features/vault/vaultApi';
import { saveToVaultFallback } from '../features/vault/vaultFallback';
import type { ShareImportResolver } from '../features/share/importTypes';
import type { SharePayload } from '../features/share/types';

const PHASE_LABELS: Record<CoarseGenerationPhase, string> = {
  QUEUED: 'Queued',
  PREPARING: 'Preparing',
  GENERATING: 'Generating',
  CHECKING_QUALITY: 'Checking quality',
  READY: 'Ready',
  FAILED: 'Failed',
};

const REJECTION_NOTICES: Record<string, string> = {
  UNSUPPORTED_IMAGE_TYPE: 'Only JPEG or PNG product images are supported.',
  IMAGE_EMPTY: 'That image is empty.',
  IMAGE_TOO_LARGE: 'That image is too large. Use an image up to 30 MB.',
};

type Props = {
  picker?: ProductPicker;
  onCreateLook?: (lookId: string) => void;
  onMissingModel?: () => void;
  vault?: typeof vaultApi;
  api?: GenerationApi;
  importer?: ShareImportResolver;
};

export default function GenerateScreen({
  picker = choosePhoto,
  onCreateLook,
  onMissingModel,
  vault = vaultApi,
  api = generationApi,
  importer,
}: Props = {}) {
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState<CoarseGenerationPhase | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [retainedPhoto, setRetainedPhoto] = useState<LocalPhoto | null>(null);
  const [isQualityFailure, setIsQualityFailure] = useState(false);
  const [outageState, setOutageState] = useState<
    'pending' | 'saved' | 'save_failed' | null
  >(null);
  const mountedRef = useRef(true);
  const operationRef = useRef(0);
  const busyRef = useRef(false);
  const operationAbortRef = useRef<AbortController | null>(null);
  const [retryBlocked, setRetryBlocked] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const recoveryStarted = useRef(false);
  const recoveringRef = useRef(false);
  const [cancelState, setCancelState] = useState<
    'idle' | 'requested' | 'failed'
  >('idle');
  const [inFlightId, setInFlightId] = useState<string | null>(null);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      operationRef.current += 1;
      operationAbortRef.current?.abort();
      operationAbortRef.current = null;
    };
  }, []);

  // Polls an already-accepted generation to its terminal state. Shared by
  // restart recovery and the cancel flow so both surfaces agree on what the
  // server reports.
  async function resumeAccepted(
    generationId: string,
  ): Promise<GenerationResource | null> {
    try {
      return await pollGeneration({
        get: api.get,
        id: generationId,
        onUpdate: generation => {
          if (mountedRef.current) {
            setPhase(generation.phase);
          }
        },
      });
    } catch {
      return null;
    }
  }

  // Restart recovery. The screen owns no durable store, so the only
  // account-scoped source of truth for an in-flight job is the server: we ask
  // for the owner's recent generations and, when one is still running, resume
  // polling THAT job instead of letting the user mint a second (chargeable)
  // one. `none` renders nothing; `unavailable` deliberately does NOT claim
  // "nothing in flight" and simply leaves the normal flow usable.
  async function reconcileWithServer(): Promise<void> {
    const recovery = await recoverActiveGeneration({ list: () => api.list() });
    if (recovery.kind !== 'active' || !mountedRef.current) {
      return;
    }
    recoveringRef.current = true;
    setRecovering(true);
    try {
      const completed = await resumeAccepted(recovery.generation.id);
      if (!mountedRef.current) {
        return;
      }
      setPhase(null);
      if (completed === null) {
        setNotice(
          'A previous generation is still being checked. Open this screen again to check it.',
        );
        return;
      }
      if (completed.status === 'COMPLETED' && completed.lookId != null) {
        onCreateLook?.(completed.lookId);
        return;
      }
      if (completed.failureCode === CANCELLED_BY_USER) {
        setNotice('Generation cancelled.');
        return;
      }
      setNotice(
        'A previous generation did not finish. Choose a product photo to try again.',
      );
    } finally {
      recoveringRef.current = false;
      if (mountedRef.current) {
        setRecovering(false);
      }
    }
  }

  useEffect(() => {
    if (recoveryStarted.current) {
      return;
    }
    recoveryStarted.current = true;
    void reconcileWithServer();
    // Mount-only: recovery is a start-up reconciliation, not a reaction to
    // changing props.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Refresh on resume. A backgrounded app can miss a completion, so the server
  // is re-read when the screen becomes active again — but only while this
  // screen is idle, so an in-progress flow is never disturbed.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state !== 'active' || busyRef.current || recoveringRef.current) {
        return;
      }
      void reconcileWithServer();
    });
    return () => subscription.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The user asked to cancel: the button switches to the truthful
  // "cancel requested" state, the request is sent, and the answer decides what
  // the user is told. Nothing here claims the provider physically stopped.
  async function cancel() {
    const generationId = getActiveGeneration();
    if (!generationId || cancelState === 'requested') {
      return;
    }
    setCancelState('requested');
    const result = await requestCancellation(api, generationId);
    if (!mountedRef.current) {
      return;
    }
    if (result.outcome === 'cancelled') {
      clearActiveGeneration();
      setCancelState('idle');
      setPhase(null);
      setNotice('Generation cancelled.');
      return;
    }
    if (result.outcome === 'cancel-requested') {
      // The server accepted the request; the job is still running. Keep
      // showing the honest request state rather than a completion claim.
      return;
    }
    clearActiveGeneration();
    setCancelState('idle');
    setPhase(null);
    setNotice('Cancellation could not be confirmed. Check this screen again.');
  }

  async function start(retryPhoto?: LocalPhoto, saveOnly = false) {
    if (busyRef.current) {
      return;
    }
    const operation = ++operationRef.current;
    const isCurrent = () =>
      mountedRef.current && operationRef.current === operation;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    setPhase(null);
    setIsQualityFailure(false);
    setOutageState(null);
    setRetryBlocked(false);
    setCancelState('idle');
    setInFlightId(null);
    const controller = new AbortController();
    operationAbortRef.current = controller;
    try {
      let pickedPhoto: LocalPhoto | null = retryPhoto ?? null;
      const savePhoto = async (photo: LocalPhoto) => {
        const effectiveImporter: ShareImportResolver =
          importer ??
          (async (payload: SharePayload) => {
            if (payload.kind === 'image') {
              const item = await vault.createFromPhoto({
                uri: payload.localImageUri,
              });
              return { status: 'READY', importId: item.id };
            }
            return {
              status: 'FAILED_TERMINAL',
              importId: '',
              reason: 'INVALID_SOURCE',
            };
          });
        const outcome = await saveToVaultFallback({
          photo,
          importer: effectiveImporter,
        });
        if (isCurrent()) {
          setOutageState(outcome.outcome === 'saved' ? 'saved' : 'save_failed');
        }
      };
      if (saveOnly) {
        const photo = retryPhoto ?? retainedPhoto;
        if (!photo) {
          setOutageState('save_failed');
          return;
        }
        await savePhoto(photo);
        return;
      }
      let result: CreateLookOutcome;
      try {
        result = await createLook({
          picker: retryPhoto
            ? async () => retryPhoto
            : async () => {
                const photo = await picker();
                if (photo) {
                  pickedPhoto = photo;
                }
                return photo;
              },
          upload: productUploader,
          api,
          photo: retryPhoto,
          signal: controller.signal,
          onUpdate: generation => {
            if (isCurrent()) {
              setPhase(generation.phase);
              setInFlightId(generation.id);
            }
          },
        });
      } catch (error) {
        if (!isCurrent()) return;
        if (!pickedPhoto && retryPhoto === undefined) {
          setNotice('Could not select an item. Try again.');
          return;
        }
        result = {
          outcome: 'failed',
          reason: 'error',
          ...(isNetworkError(error) ? { networkLevel: true } : {}),
        };
      }
      if (!isCurrent()) return;
      if (pickedPhoto) {
        setRetainedPhoto(pickedPhoto);
      }
      if (result.outcome === 'cancelled') {
        return;
      }
      if (result.outcome === 'ready') {
        onCreateLook?.(result.lookId);
        return;
      }
      setPhase(null);
      if (result.code === 'MY_MODEL_MISSING') {
        setNotice('Create your My Model first.');
        onMissingModel?.();
        return;
      }
      if (result.reason === 'rejected') {
        setNotice(
          REJECTION_NOTICES[result.code ?? ''] ?? 'That image cannot be used.',
        );
        return;
      }
      if (result.code === 'QUALITY_REJECTED') {
        setIsQualityFailure(true);
        return;
      }
      if (result.retryable === false) {
        setRetryBlocked(true);
        setNotice('We could not confirm whether this Look was created.');
        return;
      }
      const isOutage = isProviderOutage(
        result.code,
        result.reason === 'error' && Boolean(result.networkLevel),
      );
      if (isOutage) {
        setOutageState('pending');
        const photoToSave = pickedPhoto ?? retainedPhoto;
        if (photoToSave) {
          await savePhoto(photoToSave);
        } else {
          setOutageState('save_failed');
        }
        return;
      }
      setNotice('Could not create the look. Try again.');
    } finally {
      if (isCurrent()) {
        busyRef.current = false;
        setBusy(false);
        setInFlightId(null);
      }
    }
  }

  return (
    <View style={styles.page}>
      <Text accessibilityRole="header" style={styles.title}>Create a Look</Text>
      <Text style={styles.body}>
        Choose one product photo (JPEG or PNG). Your current My Model will wear
        it.
      </Text>
      {cancelState === 'requested' ? (
        <Text accessibilityLiveRegion="polite" style={styles.phase}>
          Cancellation requested. The provider may still be finishing.
        </Text>
      ) : null}
      {phase ? (
        <Text
          accessibilityLiveRegion="polite"
          style={styles.phase}
        >
          {PHASE_LABELS[phase]}
        </Text>
      ) : null}
      {outageState ? (
        <View style={styles.outageBlock}>
          <Text accessibilityLiveRegion="polite" style={styles.outageTitle}>
            {OUTAGE_TITLE}
          </Text>
          {outageState === 'saved' ? (
            <Text accessibilityLiveRegion="polite" style={styles.outageBody}>
              {OUTAGE_BODY}
            </Text>
          ) : outageState === 'save_failed' ? (
            <Text accessibilityLiveRegion="polite" style={styles.outageBody}>
              We could not save this item to the Vault. Try again.
            </Text>
          ) : null}
        </View>
      ) : isQualityFailure ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {QUALITY_FAILURE_BODY}
        </Text>
      ) : notice ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
      <View style={styles.actions}>
        {isQualityFailure ? (
          <>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: busy, busy }}
              disabled={busy}
              onPress={() => start(retainedPhoto ?? undefined)}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>{ACTION_TRY_AGAIN}</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: busy, busy }}
              disabled={busy}
              onPress={() => start()}
              style={[styles.button, styles.secondary, busy && styles.disabled]}
            >
              <Text style={styles.secondaryLabel}>
                {ACTION_CHOOSE_ANOTHER_PHOTO}
              </Text>
            </Pressable>
          </>
        ) : outageState === 'save_failed' ? (
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: busy, busy }}
            disabled={busy}
            onPress={() => start(retainedPhoto ?? undefined, true)}
          >
            <Text style={styles.primaryLabel}>{ACTION_TRY_AGAIN}</Text>
          </Pressable>
        ) : (
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: busy || retryBlocked || recovering, busy: busy || recovering }}
            disabled={busy || retryBlocked || recovering}
            onPress={() => start()}
            style={[styles.button, styles.primary, (busy || retryBlocked || recovering) && styles.disabled]}
          >
            <Text style={styles.primaryLabel}>
              {recovering ? 'Checking a previous generation…' : busy ? 'Working…' : 'Choose product photo'}
            </Text>
          </Pressable>
        )}
        {busy && inFlightId !== null && cancelState !== 'failed' ? (
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: cancelState === 'requested' }}
            disabled={cancelState === 'requested'}
            onPress={() => void cancel()}
            style={[
              styles.button,
              styles.secondary,
              cancelState === 'requested' && styles.disabled,
            ]}
          >
            <Text style={styles.secondaryLabel}>
              {cancelState === 'requested' ? 'Cancelling…' : 'Cancel generation'}
            </Text>
          </Pressable>
        ) : null}
      </View>
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
    gap: 16,
  },
  title: {
    color: ink,
    fontSize: 28,
    fontWeight: '700',
  },
  body: {
    color: muted,
    fontSize: 15,
    lineHeight: 22,
  },
  phase: {
    color: accent,
    fontSize: 16,
    fontWeight: '600',
  },
  notice: {
    color: '#8a2f2b',
    fontSize: 14,
    lineHeight: 20,
  },
  outageBlock: {
    gap: 8,
  },
  outageTitle: {
    color: ink,
    fontSize: 15,
    fontWeight: '600',
    lineHeight: 22,
  },
  outageBody: {
    color: muted,
    fontSize: 14,
    lineHeight: 20,
  },
  actions: {
    gap: 12,
  },
  button: {
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  primary: {
    backgroundColor: accent,
  },
  disabled: {
    opacity: 0.5,
  },
  primaryLabel: {
    color: '#ffffff',
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
});
