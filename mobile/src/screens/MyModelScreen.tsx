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
  CONSENT_PURPOSES,
  consentApi,
  isGrantStale,
  isPurposeActive,
  purposeState,
} from '../features/consent/consentApi';
import type {
  ConsentPurpose,
  ConsentSnapshot,
} from '../features/consent/consentApi';
import {
  choosePhoto,
  isCameraPermissionDenied,
  takePhoto,
} from '../features/my-model/photoSource';
import type { AvatarResource, LocalPhoto } from '../features/my-model/types';

const CAMERA_NOTICE =
  'Camera access is off. Enable it in Settings, or choose a photo instead.';

const NO_PHOTO_NOTICE =
  'No photo was selected. Photo selection may be unavailable. Try again.';
const STALE_REVISION_CODE = 'AVATAR_STALE_REVISION';
const MAX_REFRESH_DELAY_MS = 2_147_483_647;

// Plain-language names for the two purposes the server enumerates. These are
// the user-facing surface of C06: the purpose literals themselves are wire
// values and are never shown.
const CONSENT_LABELS: Record<ConsentPurpose, { title: string; detail: string }> = {
  body_image_processing: {
    title: 'Body-image processing',
    detail:
      'Creating or replacing My Model processes a photo of your body. Nothing is uploaded until you grant this.',
  },
  third_party_ai_transfer: {
    title: 'Third-party AI transfer',
    detail:
      'Generating a Look sends your My Model photo and the garment image to a third-party AI provider.',
  },
};

const CONSENT_RETENTION =
  'You can withdraw a consent at any time, and withdrawing pauses new processing for it. Deleting My Model or your account removes the stored photo.';

type ConsentLoad =
  | { status: 'loading' }
  | { status: 'ready'; snapshot: ConsentSnapshot }
  | { status: 'unavailable' };

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
  const [consent, setConsent] = useState<ConsentLoad>({ status: 'loading' });
  const [consentAttempt, setConsentAttempt] = useState(0);
  const [consentBusy, setConsentBusy] = useState<ConsentPurpose | null>(null);
  const [consentNotice, setConsentNotice] = useState<string | null>(null);

  // Consent is read from the server, never assumed. A failed or unparseable
  // read is UNAVAILABLE, which blocks new processing rather than being
  // treated as consent.
  useEffect(() => {
    let cancelled = false;
    setConsent({ status: 'loading' });
    void consentApi.get().then(
      snapshot => {
        if (!cancelled) {
          setConsent({ status: 'ready', snapshot });
        }
      },
      () => {
        if (!cancelled) {
          setConsent({ status: 'unavailable' });
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [consentAttempt]);

  const bodyConsentActive =
    consent.status === 'ready' &&
    isPurposeActive(consent.snapshot, 'body_image_processing');

  async function toggleConsent(purpose: ConsentPurpose, next: boolean) {
    if (consentBusy !== null) {
      return;
    }
    setConsentBusy(purpose);
    setConsentNotice(null);
    try {
      const state = next
        ? await consentApi.grant(purpose)
        : await consentApi.withdraw(purpose);
      setConsent(current => {
        if (current.status !== 'ready') {
          return current;
        }
        const known = current.snapshot.purposes.some(
          existing => existing.purpose === purpose,
        );
        return {
          status: 'ready',
          snapshot: {
            policyVersion: current.snapshot.policyVersion,
            purposes: known
              ? current.snapshot.purposes.map(existing =>
                  existing.purpose === purpose ? state : existing,
                )
              : [...current.snapshot.purposes, state],
          },
        };
      });
    } catch {
      // The server is authoritative and nothing changed locally, so the
      // surface must not pretend it did.
      setConsentNotice('Could not update consent. Nothing was changed.');
    } finally {
      setConsentBusy(null);
    }
  }

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
    // Consent gate. Adding or replacing My Model processes a photo of the
    // user's body, so the photo is not uploaded at all while that consent is
    // not an explicit active grant. "Could not ask" is not consent either, so
    // an unreadable consent state blocks just the same and says why.
    if (!bodyConsentActive) {
      setNotice(
        consent.status === 'unavailable'
          ? 'Consent settings could not be loaded, so new body-image processing is paused. Retry the consent settings below.'
          : 'Grant consent for body-image processing before adding or replacing My Model.',
      );
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
        setNotice(NO_PHOTO_NOTICE);
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
      <View style={styles.consentBlock}>
        <Text accessibilityRole="header" style={styles.heading}>
          Consent
        </Text>
        {consent.status === 'loading' ? (
          <Text accessibilityLiveRegion="polite" style={styles.body}>
            Loading consent settings…
          </Text>
        ) : consent.status === 'unavailable' ? (
          <>
            <Text accessibilityLiveRegion="polite" style={styles.notice}>
              Consent settings are unavailable, so new body-image processing is
              paused.
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ disabled: consentBusy !== null }}
              disabled={consentBusy !== null}
              onPress={() => setConsentAttempt(value => value + 1)}
              style={[styles.button, styles.secondary]}
            >
              <Text style={styles.secondaryLabel}>Retry consent settings</Text>
            </Pressable>
          </>
        ) : (
          <>
            <Text style={styles.body}>
              Policy version {consent.snapshot.policyVersion}.
            </Text>
            {CONSENT_PURPOSES.map(purpose => {
              const state = purposeState(consent.snapshot, purpose);
              const active = state?.active === true;
              const stale = isGrantStale(consent.snapshot, purpose);
              const label = CONSENT_LABELS[purpose];
              const grantedLine =
                active && state !== null
                  ? state.version === null
                    ? `Granted. The current policy is ${state.policyVersion}.`
                    : stale
                      ? `Granted under policy ${state.version}. The current policy is ${state.policyVersion}, so review it.`
                      : `Granted under policy ${state.version}.`
                  : 'Not granted. New processing for this purpose is paused.';
              return (
                <View key={purpose} style={styles.copy}>
                  <Text style={styles.heading}>{label.title}</Text>
                  <Text style={styles.body}>{label.detail}</Text>
                  <Text accessibilityLiveRegion="polite" style={styles.body}>
                    {grantedLine}
                  </Text>
                  <Pressable
                    accessibilityRole="button"
                    accessibilityState={{ disabled: consentBusy !== null }}
                    disabled={consentBusy !== null}
                    onPress={() => void toggleConsent(purpose, !active)}
                    style={[
                      styles.button,
                      active ? styles.quiet : styles.secondary,
                      consentBusy !== null && styles.disabled,
                    ]}
                  >
                    <Text
                      style={active ? styles.quietLabel : styles.secondaryLabel}
                    >
                      {active
                        ? `Withdraw ${label.title}`
                        : `Grant ${label.title}`}
                    </Text>
                  </Pressable>
                </View>
              );
            })}
            <Text style={styles.body}>{CONSENT_RETENTION}</Text>
          </>
        )}
        {consentNotice ? (
          <Text accessibilityLiveRegion="polite" style={styles.notice}>
            {consentNotice}
          </Text>
        ) : null}
      </View>
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
  heading: {
    color: ink,
    fontSize: 17,
    fontWeight: '600',
  },
  consentBlock: {
    borderColor: '#d8d3cc',
    borderRadius: 8,
    borderWidth: 1,
    gap: 12,
    padding: 12,
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
