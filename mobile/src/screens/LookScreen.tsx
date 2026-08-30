import { useEffect, useState } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { generationApi } from '../features/generation/generationApi';
import type { LookResource } from '../features/generation/types';

// The read capability is minted on demand and short-lived; it is never
// persisted as identity.
export default function LookScreen({ lookId }: { lookId: string }) {
  const [look, setLook] = useState<LookResource | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const resource = await generationApi.getLook(lookId);
        if (!cancelled) {
          setLook(resource);
        }
      } catch {
        if (!cancelled) {
          setNotice('Could not open the look.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [lookId]);

  return (
    <View style={styles.page}>
      <Text style={styles.title}>Look</Text>
      {notice ? <Text style={styles.notice}>{notice}</Text> : null}
      {look ? (
        <View style={styles.stack}>
          <Image
            accessible
            accessibilityRole="image"
            accessibilityLabel="Look preview"
            source={{ uri: look.image.url }}
            style={styles.preview}
          />
          <Text style={styles.body}>
            Private preview link expires at {look.image.expiresAt}.
          </Text>
        </View>
      ) : null}
    </View>
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
});
