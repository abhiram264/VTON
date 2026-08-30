import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { choosePhoto } from '../features/my-model/photoSource';
import { createLook } from '../features/generation/createLook';
import { generationApi } from '../features/generation/generationApi';
import { productUploader } from '../features/generation/productUpload';
import type {
  CoarseGenerationPhase,
  ProductPicker,
} from '../features/generation/types';

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
};

export default function GenerateScreen({
  picker = choosePhoto,
  onCreateLook,
  onMissingModel,
}: Props = {}) {
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState<CoarseGenerationPhase | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function start() {
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice(null);
    setPhase(null);
    try {
      const result = await createLook({
        picker,
        upload: productUploader,
        api: generationApi,
        onUpdate: generation => setPhase(generation.phase),
      });
      if (result.outcome === 'cancelled') {
        return;
      }
      if (result.outcome === 'ready') {
        onCreateLook?.(result.lookId);
        return;
      }
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
      setNotice('Could not create the look. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.page}>
      <Text style={styles.title}>Create a Look</Text>
      <Text style={styles.body}>
        Choose one product photo (JPEG or PNG). Your current My Model will wear
        it.
      </Text>
      {phase ? <Text style={styles.phase}>{PHASE_LABELS[phase]}</Text> : null}
      {notice ? <Text style={styles.notice}>{notice}</Text> : null}
      <View style={styles.actions}>
        <Pressable
          accessibilityRole="button"
          disabled={busy}
          onPress={start}
          style={[styles.button, styles.primary, busy && styles.disabled]}
        >
          <Text style={styles.primaryLabel}>
            {busy ? 'Working…' : 'Choose product photo'}
          </Text>
        </Pressable>
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
});
