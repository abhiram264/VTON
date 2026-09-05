import { useEffect, useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import {
  AVATAR_CLEAR_PHOTO_CODES,
  AVATAR_FAILURE_BODY,
} from '../features/generation/failureContract';
import { ApiProblemError } from '../features/my-model/http';
import { avatarApi } from '../features/my-model/avatarApi';
import {
  choosePhoto,
  isCameraPermissionDenied,
  takePhoto,
} from '../features/my-model/photoSource';
import type { AvatarResource, LocalPhoto } from '../features/my-model/types';

const CAMERA_NOTICE =
  'Camera access is off. Enable it in Settings, or choose a photo instead.';

export default function MyModelScreen({
  onCreateLook,
}: { onCreateLook?: () => void } = {}) {
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [avatar, setAvatar] = useState<AvatarResource | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const avatars = await avatarApi.list();
        if (!cancelled) {
          setAvatar(avatars[0] ?? null);
        }
      } catch {
        if (!cancelled) {
          setNotice('Could not load My Model.');
          setAvatar(null);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function submit(photo: LocalPhoto) {
    setBusy(true);
    setNotice(null);
    try {
      const next = avatar
        ? await avatarApi.replace(avatar.id, avatar.revision, photo)
        : await avatarApi.create(photo);
      setAvatar(next);
      setPicking(false);
    } catch (error) {
      if (
        error instanceof ApiProblemError &&
        AVATAR_CLEAR_PHOTO_CODES.has(error.code)
      ) {
        setNotice(AVATAR_FAILURE_BODY);
      } else {
        setNotice('Could not save My Model. Try another photo.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function onPick(source: () => Promise<LocalPhoto | null>) {
    if (busy) {
      return;
    }
    try {
      const photo = await source();
      if (!photo) {
        return;
      }
      await submit(photo);
    } catch (error) {
      if (isCameraPermissionDenied(error)) {
        setNotice(CAMERA_NOTICE);
        return;
      }
      setNotice('Could not open the photo source.');
    }
  }

  async function onDelete() {
    if (!avatar || busy) {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      await avatarApi.remove(avatar.id, avatar.revision);
      setAvatar(null);
      setPicking(false);
    } catch {
      setNotice('Could not delete My Model.');
    } finally {
      setBusy(false);
    }
  }

  const ready = avatar !== null;

  return (
    <View style={styles.page}>
      <Text style={styles.title}>My Model</Text>
      {loading ? null : ready ? (
        <View style={styles.stack}>
          <Image
            accessible
            accessibilityRole="image"
            accessibilityLabel="My Model preview"
            source={{ uri: avatar.displayUrl }}
            style={[
              styles.preview,
              {
                aspectRatio:
                  avatar.width > 0 && avatar.height > 0
                    ? avatar.width / avatar.height
                    : 2 / 3,
              },
            ]}
          />
          {notice ? (
            <Text accessibilityLiveRegion="polite" style={styles.notice}>
              {notice}
            </Text>
          ) : null}
          <View style={styles.actions}>
            {picking ? (
              <>
                <Pressable
                  accessibilityRole="button"
                  disabled={busy}
                  onPress={() => onPick(takePhoto)}
                  style={[
                    styles.button,
                    styles.primary,
                    busy && styles.disabled,
                  ]}
                >
                  <Text style={styles.primaryLabel}>Take photo</Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  disabled={busy}
                  onPress={() => onPick(choosePhoto)}
                  style={[
                    styles.button,
                    styles.secondary,
                    busy && styles.disabled,
                  ]}
                >
                  <Text style={styles.secondaryLabel}>Choose photo</Text>
                </Pressable>
              </>
            ) : (
              <Pressable
                accessibilityRole="button"
                disabled={busy}
                onPress={() => setPicking(true)}
                style={[styles.button, styles.primary, busy && styles.disabled]}
              >
                <Text style={styles.primaryLabel}>Replace</Text>
              </Pressable>
            )}
            {onCreateLook ? (
              <Pressable
                accessibilityRole="button"
                disabled={busy}
                onPress={onCreateLook}
                style={[styles.button, styles.primary, busy && styles.disabled]}
              >
                <Text style={styles.primaryLabel}>Create a Look</Text>
              </Pressable>
            ) : null}
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={onDelete}
              style={[styles.button, styles.quiet, busy && styles.disabled]}
            >
              <Text style={styles.quietLabel}>Delete</Text>
            </Pressable>
          </View>
        </View>
      ) : (
        <View style={styles.stack}>
          <View style={styles.copy}>
            <Text style={styles.body}>
              Use a clear, well-lit, full-body photo. Face and whole body should
              be visible. This is guidance, not a machine-verified check.
            </Text>
            <Text style={styles.body}>
              This photo is stored privately as your My Model until you replace
              or delete it.
            </Text>
            {notice ? (
              <Text accessibilityLiveRegion="polite" style={styles.notice}>
                {notice}
              </Text>
            ) : null}
          </View>
          <View style={styles.actions}>
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={() => onPick(takePhoto)}
              style={[styles.button, styles.primary, busy && styles.disabled]}
            >
              <Text style={styles.primaryLabel}>Take photo</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              disabled={busy}
              onPress={() => onPick(choosePhoto)}
              style={[styles.button, styles.secondary, busy && styles.disabled]}
            >
              <Text style={styles.secondaryLabel}>Choose photo</Text>
            </Pressable>
          </View>
        </View>
      )}
    </View>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  page: {
    backgroundColor: paper,
    padding: 16,
  },
  title: {
    color: ink,
    fontSize: 20,
    fontWeight: '600',
    marginBottom: 16,
  },
  stack: {
    gap: 16,
  },
  copy: {
    gap: 8,
  },
  actions: {
    gap: 8,
  },
  body: {
    color: muted,
    fontSize: 16,
    lineHeight: 24,
  },
  notice: {
    color: ink,
    fontSize: 16,
    lineHeight: 24,
  },
  preview: {
    backgroundColor: '#e6e2dc',
    width: '100%',
  },
  button: {
    alignItems: 'center',
    borderRadius: 8,
    minHeight: 48,
    justifyContent: 'center',
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
  quiet: {
    backgroundColor: paper,
  },
  quietLabel: {
    color: muted,
    fontSize: 16,
    fontWeight: '500',
  },
  disabled: {
    opacity: 0.5,
  },
});
