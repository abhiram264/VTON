import { useEffect, useRef, useState } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { looksApi, type LookDetail } from '../features/looks/looksApi';
import { createLookExporter, type LookExportAction, type LookExportOutcome } from '../features/export/lookExport';
import { vaultApi } from '../features/vault/vaultApi';
import { ApiProblemError } from '../features/my-model/http';

const SLOT_LABELS: Record<string, string> = {
  HEAD: 'Head',
  UPPER_BODY: 'Top',
  LOWER_BODY: 'Bottom',
  FULL_BODY: 'Full body',
  FEET: 'Footwear',
  BAG: 'Bag',
  JEWELLERY: 'Jewellery',
  ACCESSORY: 'Accessory',
};

const QUALITY_LABELS: Record<string, string> = {
  PASS: 'Quality check passed',
  RETRY: 'Quality check retried',
  FAIL: 'Rejected by the quality check',
};

// Every export outcome gets its own sentence. A status the UI does not
// recognise is never rendered as success, so a new outcome added to the module
// fails visibly instead of silently reporting a share that did not happen.
const EXPORT_OUTCOME_TEXT: Record<LookExportOutcome['status'], string> = {
  SHARED: 'Shared. The copy you sent is outside this app and cannot be deleted from here.',
  SAVED: 'Saved to your photos.',
  CANCELLED: 'Export cancelled. Nothing was sent.',
  PERMISSION_DENIED: 'Export needs photo permission, which was denied. Nothing was sent.',
  STAGING_FAILED: 'Could not prepare the image for export. Nothing was sent.',
  SHARE_FAILED: 'Sharing failed. Nothing was sent.',
  SAVE_FAILED: 'Could not save to your photos.',
  SHARE_UNAVAILABLE: 'Sharing is not available on this device yet.',
  DISCLOSURE_REQUIRED: 'Confirm that the copy leaves the app before exporting.',
};

// A failed load has three different meanings and they must not be collapsed:
// the look is gone (or is not the caller's) and retrying will never help, the
// session is signed out and retrying will never help, or the request itself
// failed and retrying is exactly the right thing to offer.
type LoadFailure = 'unavailable' | 'signed-out' | 'retryable';

const LOAD_FAILURE_TEXT: Record<LoadFailure, string> = {
  unavailable: 'This look is not available. It may have been deleted.',
  'signed-out': 'Sign in again to open this look.',
  retryable: 'Could not open the look. Check your connection and try again.',
};

function classifyLoadFailure(error: unknown): LoadFailure {
  const code = error instanceof ApiProblemError ? error.code : null;
  if (code === 'UNAUTHORIZED_OR_NOT_FOUND' || code === 'HTTP_404') return 'unavailable';
  if (code === 'UNAUTHORIZED' || code === 'HTTP_401') return 'signed-out';
  return 'retryable';
}

// Provenance names are fetched separately from the Look itself, so "we have
// not asked yet", "the answer is in", and "we could not ask" stay distinct.
type VaultNames =
  | { status: 'loading' }
  | { status: 'ready'; names: ReadonlyMap<string, string> }
  | { status: 'unavailable' };

// The read capability is minted on demand and short-lived; it is never
// persisted as identity. Provenance (source garments, provider, outcome) comes
// from the same owner-scoped GET /looks/{id} that mints it.
export default function LookScreen({
  lookId,
  onDeleted,
}: {
  lookId: string;
  onDeleted?: () => void;
}) {
  const [look, setLook] = useState<LookDetail | null>(null);
  const [notice, setNotice] = useState<{ lookId: string; failure: LoadFailure } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [imageUnavailable, setImageUnavailable] = useState(false);
  const [loading, setLoading] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleted, setDeleted] = useState(false);
  const [deleteFailed, setDeleteFailed] = useState(false);
  const [pendingExport, setPendingExport] = useState<LookExportAction | null>(null);
  const [exportNotice, setExportNotice] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  // The Look's frozen provenance carries ids only, and a raw id is not a
  // garment a person can recognise. Names come from the owner-scoped Vault
  // list. Three distinct facts are kept apart: still asking, answered (an id
  // absent from the answer was deleted or unshared), and could-not-ask — the
  // last must never be shown as "the garment is gone".
  const [vaultNames, setVaultNames] = useState<VaultNames>({ status: 'loading' });

  // One exporter per mount: the native bridge is resolved once, and the sweep
  // below runs against the same instance the buttons use.
  const exporter = useRef(createLookExporter()).current;

  useEffect(() => {
    // Private staging from a crashed or abandoned export is swept on mount;
    // the per-export path already removes the file it just shared.
    void exporter.cleanupExpiredStaging();
  }, [exporter]);

  useEffect(() => {
    setLook(null);
    setNotice(null);
    setImageUnavailable(false);
    setConfirmingDelete(false);
    setDeleted(false);
    setDeleteFailed(false);
    setPendingExport(null);
    setExportNotice(null);
    setVaultNames({ status: 'loading' });
    setLoading(true);
    let cancelled = false;
    (async () => {
      try {
        const resource = await looksApi.get(lookId);
        if (cancelled) return;
        setLook(resource);
        setLoading(false);
        if (resource.items.length === 0) {
          setVaultNames({ status: 'ready', names: new Map() });
          return;
        }
        try {
          const owned = await vaultApi.list();
          if (cancelled) return;
          setVaultNames({ status: 'ready', names: new Map(owned.map(item => [item.id, item.name])) });
        } catch {
          // The look is still readable; only the garment names are missing, so
          // the list says so instead of claiming the garments are gone.
          if (!cancelled) setVaultNames({ status: 'unavailable' });
        }
      } catch (error) {
        if (!cancelled) {
          setNotice({ lookId, failure: classifyLoadFailure(error) });
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [lookId, attempt]);

  async function deleteLook() {
    setDeleting(true);
    try {
      await looksApi.remove(lookId);
      setDeleting(false);
      setConfirmingDelete(false);
      setDeleted(true);
      setLook(null);
      onDeleted?.();
    } catch {
      setDeleting(false);
      setConfirmingDelete(false);
      setDeleteFailed(true);
    }
  }

  async function runExport(action: LookExportAction, imageUrl: string) {
    if (exporting) return;
    setExporting(true);
    setExportNotice(null);
    // The acknowledgement is the user's press on the disclosure confirmation,
    // never a constant: this call is only reachable from that control.
    const outcome = await exporter.exportLook(
      {
        lookId,
        imageUrl,
        disclosureAcknowledged: true,
        fileName: `look-${lookId}.jpg`,
      },
      action,
    );
    setPendingExport(null);
    setExporting(false);
    setExportNotice(EXPORT_OUTCOME_TEXT[outcome.status] ?? 'Export did not complete.');
  }

  const visibleLook = look?.id === lookId ? look : null;
  const visibleNotice = notice?.lookId === lookId ? notice.failure : null;
  const quality =
    visibleLook && visibleLook.qualityStatus !== null
      ? QUALITY_LABELS[visibleLook.qualityStatus] ?? visibleLook.qualityStatus
      : null;
  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Text accessibilityRole="header" style={styles.title}>Look</Text>
      {loading ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          Loading preview…
        </Text>
      ) : visibleNotice ? (
        <View style={styles.stack}>
          <Text accessibilityLiveRegion="polite" style={styles.notice}>
            {LOAD_FAILURE_TEXT[visibleNotice]}
          </Text>
          {visibleNotice === 'retryable' ? (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Retry opening the look"
              onPress={() => setAttempt((value) => value + 1)}
              style={styles.retry}
            >
              <Text style={styles.retryLabel}>Retry</Text>
            </Pressable>
          ) : null}
        </View>
      ) : deleteFailed ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          Could not delete the look. It is still saved.
        </Text>
      ) : deleted ? (
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          Look deleted. Your saved garments are unchanged.
        </Text>
      ) : null}
      {visibleLook ? (
        <View style={styles.stack}>
          {!imageUnavailable ? (
            <Image
              accessible
              accessibilityRole="image"
              accessibilityLabel="Look preview"
              onError={() => setImageUnavailable(true)}
              source={{ uri: visibleLook.image.url }}
              style={styles.preview}
            />
          ) : (
            <View style={styles.stack}>
              <Text accessibilityLiveRegion="polite" style={styles.notice}>
                Signed preview unavailable.
              </Text>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Retry preview"
                onPress={() => setAttempt((value) => value + 1)}
                style={styles.retry}
              >
                <Text style={styles.retryLabel}>Retry preview</Text>
              </Pressable>
            </View>
          )}
          <Text style={styles.body}>
            Created {visibleLook.createdAt}
          </Text>
          {quality ? (
            <Text style={styles.body}>Outcome: {quality}</Text>
          ) : null}
          {visibleLook.provider ? (
            <Text style={styles.body}>
              Generated with {visibleLook.provider}
              {visibleLook.providerModel ? ` (${visibleLook.providerModel})` : ''}
            </Text>
          ) : null}
          <Text accessibilityRole="header" style={styles.heading}>
            Made from
          </Text>
          {visibleLook.items.length === 0 ? (
            <Text style={styles.body}>
              No source garments are recorded for this look.
            </Text>
          ) : (
            visibleLook.items.map(item => (
              <Text key={`${item.slot}:${item.vaultItemId}`} style={styles.body}>
                {SLOT_LABELS[item.slot] ?? item.slot} —{' '}
                {vaultNames.status === 'loading'
                  ? 'checking your Vault…'
                  : vaultNames.status === 'unavailable'
                    ? 'name unavailable right now'
                    : vaultNames.names.get(item.vaultItemId) ??
                      'no longer in your Vault — use Modify to build a new look without it'}
              </Text>
            ))
          )}
          <Text style={styles.body}>
            Private preview link expires at {visibleLook.image.expiresAt}.
          </Text>
          {exportNotice ? (
            <Text accessibilityLiveRegion="polite" style={styles.body}>
              {exportNotice}
            </Text>
          ) : null}
          {pendingExport ? (
            <View style={styles.stack}>
              <Text accessibilityLiveRegion="polite" style={styles.body}>
                Exporting makes a copy of this look outside the app. It will not be covered by
                deleting the look here, and you will not be able to remove it from this app.
              </Text>
              <View style={styles.row}>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={
                    pendingExport === 'save' ? 'Confirm save to photos' : 'Confirm export copy'
                  }
                  accessibilityState={{ disabled: exporting, busy: exporting }}
                  disabled={exporting}
                  onPress={() => runExport(pendingExport, visibleLook.image.url)}
                  style={[styles.retry, exporting && styles.disabled]}
                >
                  <Text style={styles.retryLabel}>
                    {exporting
                      ? 'Exporting…'
                      : pendingExport === 'save'
                        ? 'Save to photos'
                        : 'Share a copy'}
                  </Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Cancel export"
                  accessibilityState={{ disabled: exporting }}
                  disabled={exporting}
                  onPress={() => setPendingExport(null)}
                  style={[styles.secondary, exporting && styles.disabled]}
                >
                  <Text style={styles.secondaryLabel}>Cancel</Text>
                </Pressable>
              </View>
            </View>
          ) : (
            <View style={styles.row}>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Share look"
                accessibilityState={{ disabled: exporting, busy: exporting }}
                disabled={exporting}
                onPress={() => setPendingExport('share')}
                style={[styles.retry, exporting && styles.disabled]}
              >
                <Text style={styles.retryLabel}>Share</Text>
              </Pressable>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Save look to photos"
                accessibilityState={{ disabled: exporting, busy: exporting }}
                disabled={exporting}
                onPress={() => setPendingExport('save')}
                style={[styles.secondary, exporting && styles.disabled]}
              >
                <Text style={styles.secondaryLabel}>Save to photos</Text>
              </Pressable>
            </View>
          )}
          {confirmingDelete ? (
            <View style={styles.stack}>
              <Text accessibilityLiveRegion="polite" style={styles.body}>
                Delete this look? The rendered image is removed; the garments it used stay in
                your Vault.
              </Text>
              <View style={styles.row}>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Confirm delete look"
                  accessibilityState={{ disabled: deleting, busy: deleting }}
                  disabled={deleting}
                  onPress={deleteLook}
                  style={[styles.danger, deleting && styles.disabled]}
                >
                  <Text style={styles.retryLabel}>
                    {deleting ? 'Deleting…' : 'Delete look'}
                  </Text>
                </Pressable>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Cancel delete look"
                  accessibilityState={{ disabled: deleting }}
                  disabled={deleting}
                  onPress={() => setConfirmingDelete(false)}
                  style={[styles.secondary, deleting && styles.disabled]}
                >
                  <Text style={styles.secondaryLabel}>Keep look</Text>
                </Pressable>
              </View>
            </View>
          ) : (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Delete look"
              onPress={() => setConfirmingDelete(true)}
              style={styles.secondary}
            >
              <Text style={styles.secondaryLabel}>Delete look</Text>
            </Pressable>
          )}
        </View>
      ) : null}
    </ScrollView>
  );
}

const ink = '#1a1a1a';
const paper = '#f4f4f2';
const muted = '#3f3a33';

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
  heading: {
    color: ink,
    fontSize: 18,
    fontWeight: '700',
  },
  stack: {
    gap: 12,
  },
  row: {
    flexDirection: 'row',
    gap: 12,
  },
  preview: {
    width: '100%',
    aspectRatio: 2 / 3,
    borderRadius: 10,
    backgroundColor: '#ddd8d0',
  },
  body: {
    color: muted,
    fontSize: 14,
    lineHeight: 20,
  },
  notice: {
    color: '#8a2f2b',
    fontSize: 14,
    lineHeight: 20,
  },
  retry: {
    minHeight: 44,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 8,
    backgroundColor: ink,
  },
  retryLabel: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  secondary: {
    minHeight: 44,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: ink,
  },
  secondaryLabel: {
    color: ink,
    fontSize: 14,
    fontWeight: '600',
  },
  danger: {
    minHeight: 44,
    justifyContent: 'center',
    paddingHorizontal: 16,
    borderRadius: 8,
    backgroundColor: '#8a2f2b',
  },
  disabled: {
    opacity: 0.45,
  },
});
