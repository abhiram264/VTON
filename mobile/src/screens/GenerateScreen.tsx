import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
import { createLook, isNetworkError } from '../features/generation/createLook';
import type { CreateLookOutcome } from '../features/generation/createLook';
import { generationApi } from '../features/generation/generationApi';
import { productUploader } from '../features/generation/productUpload';
import type {
  CoarseGenerationPhase,
  GenerationApi,
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
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      operationRef.current += 1;
      operationAbortRef.current?.abort();
      operationAbortRef.current = null;
    };
  }, []);

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
            if (isCurrent()) setPhase(generation.phase);
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
            accessibilityState={{ disabled: busy || retryBlocked, busy }}
            disabled={busy || retryBlocked}
            onPress={() => start()}
            style={[styles.button, styles.primary, (busy || retryBlocked) && styles.disabled]}
          >
            <Text style={styles.primaryLabel}>
              {busy ? 'Working…' : 'Choose product photo'}
            </Text>
          </Pressable>
        )}
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
