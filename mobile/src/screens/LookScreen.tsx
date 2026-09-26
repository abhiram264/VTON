import { useEffect, useState } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { generationApi } from '../features/generation/generationApi';
import type { LookResource } from '../features/generation/types';

// The read capability is minted on demand and short-lived; it is never
// persisted as identity.
export default function LookScreen({ lookId }: { lookId: string }) {
  const [look, setLook] = useState<LookResource | null>(null);
  const [notice, setNotice] = useState<{ lookId: string; text: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [imageUnavailable, setImageUnavailable] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLook(null);
    setNotice(null);
    setImageUnavailable(false);
    setLoading(true);
    let cancelled = false;
    (async () => {
      try {
        const resource = await generationApi.getLook(lookId);
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
  const visibleLook = look?.id === lookId ? look : null;
  const visibleNotice = notice?.lookId === lookId ? notice.text : null;
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
                onPress={() => setAttempt((value) => value + 1)}
                style={styles.retry}
              >
                <Text style={styles.retryLabel}>Retry preview</Text>
              </Pressable>
            </View>
          )}
          <Text style={styles.body}>
            Private preview link expires at {visibleLook.image.expiresAt}.
          </Text>
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
  stack: {
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
});
