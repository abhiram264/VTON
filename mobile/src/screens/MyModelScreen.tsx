import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
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
const STALE_REVISION_CODE = 'AVATAR_STALE_REVISION';
const MAX_REFRESH_DELAY_MS = 2_147_483_647;

type LoadState = 'loading' | 'ready' | 'failed';

export default function MyModelScreen({
  onCreateLook,
}: { onCreateLook?: () => void } = {}) {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [pickerBusy, setPickerBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [avatar, setAvatar] = useState<AvatarResource | null>(null);
  const [imageFailed, setImageFailed] = useState(false);
  const [refreshNeeded, setRefreshNeeded] = useState(false);
  const pickerLock = useRef(false);
  const mutationLock = useRef(false);
  const deletePromptOpen = useRef(false);

  useEffect(() => {
    let cancelled = false;
    setLoadState('loading');
    setNotice(null);
    (async () => {
      try {
        const avatars = await avatarApi.list();
        if (!cancelled) {
          setAvatar(avatars[0] ?? null);
          setImageFailed(false);
          setRefreshNeeded(false);
          setLoadState('ready');
        }
      } catch {
        if (!cancelled) {
          setLoadState('failed');
          setNotice('My Model is unavailable because it could not be loaded.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  useEffect(() => {
    if (!avatar) {
      return;
    }
    const expiresAt = Date.parse(avatar.displayExpiresAt);
    if (Number.isNaN(expiresAt) || expiresAt <= Date.now()) {
      setRefreshNeeded(true);
      setNotice('My Model preview needs refreshing.');
      return;
    }

    setRefreshNeeded(false);
    let cancelled = false;
    const refreshDelay = Math.min(
      expiresAt - Date.now(),
      MAX_REFRESH_DELAY_MS,
    );
    const timer = setTimeout(() => {
      if (mutationLock.current) {
        setRefreshNeeded(true);
        return;
      }
      setLoadState('loading');
      avatarApi
        .list()
        .then(avatars => {
          if (!cancelled) {
            setAvatar(avatars[0] ?? null);
            setImageFailed(false);
            setRefreshNeeded(false);
            setNotice(null);
            setLoadState('ready');
          }
        })
        .catch(() => {
          if (!cancelled) {
            setLoadState('failed');
            setRefreshNeeded(true);
            setNotice('My Model preview expired and could not be refreshed.');
          }
        });
    }, refreshDelay);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [avatar]);

  async function reconcileStaleRevision(
    error: unknown,
    emptyNotice: string,
  ): Promise<boolean> {
    if (
      !(error instanceof ApiProblemError) ||
      error.code !== STALE_REVISION_CODE
    ) {
      return false;
    }
    try {
      const avatars = await avatarApi.list();
      const current = avatars[0] ?? null;
      setAvatar(current);
      setImageFailed(false);
      setRefreshNeeded(false);
      setLoadState('ready');
      setNotice(
        current
          ? 'My Model changed elsewhere. Review it and try again.'
          : emptyNotice,
      );
    } catch {
      setLoadState('failed');
      setRefreshNeeded(true);
      setNotice('My Model changed elsewhere. Refresh before trying again.');
    }
    return true;
  }

  async function submit(photo: LocalPhoto) {
    if (mutationLock.current) {
      return;
    }
    mutationLock.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const next = avatar
        ? await avatarApi.replace(avatar.id, avatar.revision, photo)
        : await avatarApi.create(photo);
      setAvatar(next);
      setImageFailed(false);
      setRefreshNeeded(false);
      setLoadState('ready');
      setPicking(false);
    } catch (error) {
      if (
        await reconcileStaleRevision(
          error,
          'My Model changed elsewhere. Add a new photo to continue.',
        )
      ) {
        return;
      }
      if (
        error instanceof ApiProblemError &&
        AVATAR_CLEAR_PHOTO_CODES.has(error.code)
      ) {
        setNotice(AVATAR_FAILURE_BODY);
      } else {
        setNotice('Could not save My Model. Try another photo.');
      }
    } finally {
      mutationLock.current = false;
      setBusy(false);
    }
  }

  async function onPick(source: () => Promise<LocalPhoto | null>) {
    if (
      pickerLock.current ||
      mutationLock.current ||
      busy ||
      loadState === 'loading'
    ) {
      return;
    }
    pickerLock.current = true;
    setPickerBusy(true);
    setNotice(null);
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
    } finally {
      pickerLock.current = false;
      setPickerBusy(false);
    }
  }

  function onDelete() {
    if (
      !avatar ||
      mutationLock.current ||
      pickerLock.current ||
      busy ||
      loadState === 'loading' ||
      deletePromptOpen.current
    ) {
      return;
    }
    deletePromptOpen.current = true;
    Alert.alert(
      'Delete My Model?',
      'This permanently deletes your private My Model photo.',
      [
        {
          text: 'Cancel',
          style: 'cancel',
          onPress: () => {
            deletePromptOpen.current = false;
          },
        },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            deletePromptOpen.current = false;
            void deleteAvatar();
          },
        },
      ],
      {
        cancelable: true,
        onDismiss: () => {
          deletePromptOpen.current = false;
        },
      },
    );
  }

  async function deleteAvatar() {
    if (!avatar || mutationLock.current) {
      return;
    }
    mutationLock.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await avatarApi.remove(avatar.id, avatar.revision);
      setAvatar(null);
      setImageFailed(false);
      setRefreshNeeded(false);
      setLoadState('ready');
      setPicking(false);
    } catch (error) {
      if (
        await reconcileStaleRevision(
          error,
          'My Model was already deleted. Add a new photo to continue.',
        )
      ) {
        return;
      }
      setNotice('Could not delete My Model.');
    } finally {
      mutationLock.current = false;
      setBusy(false);
    }
  }

  function retryLoad() {
    if (busy || pickerLock.current || mutationLock.current) {
      return;
    }
    setAttempt(value => value + 1);
  }

  const ready = avatar !== null;
  const canAct = !busy && !pickerBusy && loadState !== 'loading';
  const previewDimensions = {
    aspectRatio:
      avatar && avatar.width > 0 && avatar.height > 0
        ? avatar.width / avatar.height
        : 2 / 3,
  };

  return (
    <ScrollView
      style={styles.page}
      contentContainerStyle={styles.content}
      contentInsetAdjustmentBehavior="automatic"
    >
      <Text accessibilityRole="header" style={styles.title}>
        My Model
      </Text>
      {loadState === 'loading' && !ready ? (
        <Text accessibilityLiveRegion="polite">Loading My Model…</Text>
      ) : loadState === 'failed' && !ready ? (
        <View style={styles.stack}>
          <Text accessibilityLiveRegion="polite" style={styles.notice}>
            {notice}
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ disabled: !canAct }}
            disabled={!canAct}
            onPress={retryLoad}
            style={[styles.button, styles.primary]}
          >
            <Text style={styles.primaryLabel}>Retry My Model</Text>
          </Pressable>
        </View>
      ) : ready ? (
        <View style={styles.stack}>
          {imageFailed ? (
            <View
              accessible
              accessibilityRole="image"
              accessibilityLabel="My Model preview unavailable"
              style={[
                styles.preview,
                styles.previewUnavailable,
                previewDimensions,
              ]}
            >
              <Text style={styles.body}>Preview unavailable.</Text>
            </View>
          ) : (
            <Image
              accessible
              accessibilityRole="image"
              accessibilityLabel="My Model preview"
              onError={() => {
                setImageFailed(true);
                setNotice('My Model preview could not be loaded.');
              }}
              source={{ uri: avatar.displayUrl }}
              style={[styles.preview, previewDimensions]}
            />
          )}
          {loadState === 'loading' ? (
            <Text accessibilityLiveRegion="polite">Refreshing My Model…</Text>
          ) : null}
          {notice ? (
            <Text accessibilityLiveRegion="polite" style={styles.notice}>
              {notice}
            </Text>
          ) : null}
          {imageFailed || refreshNeeded || loadState === 'failed' ? (
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: !canAct }}
              disabled={!canAct}
              onPress={retryLoad}
              style={[styles.button, styles.secondary]}
            >
              <Text style={styles.secondaryLabel}>Retry My Model</Text>
            </Pressable>
          ) : null}
          <View style={styles.actions}>
            {picking ? (
              <>
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{ disabled: !canAct }}
                  disabled={!canAct}
                  onPress={() => onPick(takePhoto)}
                  style={[
                    styles.button,
                    styles.primary,
                    !canAct && styles.disabled,
                  ]}
                >
                  <Text style={styles.primaryLabel}>Take photo</Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{ disabled: !canAct }}
                  disabled={!canAct}
                  onPress={() => onPick(choosePhoto)}
                  style={[
                    styles.button,
                    styles.secondary,
                    !canAct && styles.disabled,
                  ]}
                >
                  <Text style={styles.secondaryLabel}>Choose photo</Text>
                </Pressable>
              </>
            ) : (
              <Pressable
                accessibilityRole="button"
                accessibilityState={{ disabled: !canAct }}
                disabled={!canAct}
                onPress={() => {
                  setNotice(null);
                  setPicking(true);
                }}
                style={[
                  styles.button,
                  styles.primary,
                  !canAct && styles.disabled,
                ]}
              >
                <Text style={styles.primaryLabel}>Replace</Text>
              </Pressable>
            )}
            {onCreateLook ? (
              <Pressable
                accessibilityRole="button"
                accessibilityState={{ disabled: !canAct }}
                disabled={!canAct}
                onPress={onCreateLook}
                style={[
                  styles.button,
                  styles.primary,
                  !canAct && styles.disabled,
                ]}
              >
                <Text style={styles.primaryLabel}>Create a Look</Text>
              </Pressable>
            ) : null}
            <Pressable
              accessibilityRole="button"
              accessibilityHint="Requires confirmation"
              accessibilityState={{ disabled: !canAct }}
              disabled={!canAct}
              onPress={onDelete}
              style={[
                styles.button,
                styles.quiet,
                !canAct && styles.disabled,
              ]}
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
              accessibilityState={{ disabled: !canAct }}
              disabled={!canAct}
              onPress={() => onPick(takePhoto)}
              style={[
                styles.button,
                styles.primary,
                !canAct && styles.disabled,
              ]}
            >
              <Text style={styles.primaryLabel}>Take photo</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: !canAct }}
              disabled={!canAct}
              onPress={() => onPick(choosePhoto)}
              style={[
                styles.button,
                styles.secondary,
                !canAct && styles.disabled,
              ]}
            >
              <Text style={styles.secondaryLabel}>Choose photo</Text>
            </Pressable>
          </View>
        </View>
      )}
    </ScrollView>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';
const accent = '#6b4f2a';

const styles = StyleSheet.create({
  page: {
    backgroundColor: paper,
  },
  content: {
    gap: 16,
    padding: 16,
  },
  title: {
    color: ink,
    fontSize: 20,
    fontWeight: '600',
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
  previewUnavailable: {
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
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
