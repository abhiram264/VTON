import { useEffect, useState } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { looksApi, type LookDetail } from '../features/looks/looksApi';

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
  const [notice, setNotice] = useState<{ lookId: string; text: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [imageUnavailable, setImageUnavailable] = useState(false);
  const [loading, setLoading] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleted, setDeleted] = useState(false);

  useEffect(() => {
    setLook(null);
    setNotice(null);
    setImageUnavailable(false);
    setConfirmingDelete(false);
    setDeleted(false);
    setLoading(true);
    let cancelled = false;
    (async () => {
      try {
        const resource = await looksApi.get(lookId);
        if (!cancelled) {
          setLook(resource);
          setLoading(false);
        }
      } catch {
        if (!cancelled) {
          setNotice({ lookId, text: 'Could not open the look.' });
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
      setNotice({
        lookId,
        text: 'Could not delete the look. It is still saved.',
      });
    }
  }

  const visibleLook = look?.id === lookId ? look : null;
  const visibleNotice = notice?.lookId === lookId ? notice.text : null;
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
        <Text accessibilityLiveRegion="polite" style={styles.notice}>
          {visibleNotice}
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
                {SLOT_LABELS[item.slot] ?? item.slot} — {item.vaultItemId}
              </Text>
            ))
          )}
          <Text style={styles.body}>
            Private preview link expires at {visibleLook.image.expiresAt}.
          </Text>
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
