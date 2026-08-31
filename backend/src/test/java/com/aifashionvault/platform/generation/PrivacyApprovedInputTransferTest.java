package com.aifashionvault.platform.generation;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.UnsupportedProviderCapabilityException;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PrivacyApprovedInputTransferTest {

    private static final long MAX_INLINE_BYTES = 1_000_000L;
    private static final Duration MAX_SIGNED_READ_TTL = Duration.ofMinutes(5);
    private static final Set<InputTransferMethod> BOTH = Set.of(
        InputTransferMethod.BOUNDED_INLINE_DATA,
        InputTransferMethod.EPHEMERAL_SIGNED_READ);
    private static final Instant EXPIRES = Instant.parse("2026-08-26T12:00:00Z");
    private static final URI FIRST_URI = URI.create(
        "https://private.example.test/users/u1/avatars/a.jpg?X-Amz-Signature=first-secret");
    private static final URI SECOND_URI = URI.create(
        "https://private.example.test/users/u1/avatars/a.jpg?X-Amz-Signature=second-secret");

    @Test
    void inlinePathReadsBoundedBytesAndNeverPresigns() {
        ObjectStore store = mock(ObjectStore.class);
        ObjectRef object = new ObjectRef(StorageDomain.AVATAR, "users/u1/avatars/a.jpg");
        byte[] payload = new byte[] {1, 2, 3};
        when(store.readBounded(eq(object), any(), eq((int) MAX_INLINE_BYTES))).thenReturn(payload);

        PrivacyApprovedInputTransfer.PreparedInput prepared = transfer(store).prepare(
            media("users/u1/avatars/a.jpg", OptionalLong.of(payload.length)),
            BOTH,
            Duration.ofMinutes(5));

        assertThat(prepared.method()).isEqualTo(InputTransferMethod.BOUNDED_INLINE_DATA);
        assertThat(prepared.inlineBytes()).contains(payload);
        assertThat(prepared.signedRead()).isEmpty();
        verify(store).readBounded(object, null, (int) MAX_INLINE_BYTES);
        verify(store, never()).presignGet(any(), any());
        verify(store, never()).head(any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void unknownSizeHeadsThenInlinesWithoutPresign() {
        ObjectStore store = mock(ObjectStore.class);
        ObjectRef object = new ObjectRef(StorageDomain.TEMP, "temp/u1/job/obj.jpg");
        byte[] payload = new byte[] {9, 8, 7};
        when(store.head(object)).thenReturn(new ObjectMetadata(payload.length, "image/jpeg", "etag-1"));
        when(store.readBounded(object, "etag-1", (int) MAX_INLINE_BYTES)).thenReturn(payload);

        PrivacyApprovedInputTransfer.PreparedInput prepared = transfer(store).prepare(
            media("temp/u1/job/obj.jpg", OptionalLong.empty()),
            BOTH,
            Duration.ofMinutes(1));

        assertThat(prepared.method()).isEqualTo(InputTransferMethod.BOUNDED_INLINE_DATA);
        assertThat(prepared.inlineBytes()).contains(payload);
        assertThat(prepared.signedRead()).isEmpty();
        verify(store).head(object);
        verify(store).readBounded(object, "etag-1", (int) MAX_INLINE_BYTES);
        verify(store, never()).presignGet(any(), any());
    }

    @Test
    void oversizedFallbackMintsOnePresignPerPrepareWithCappedTtl() {
        ObjectStore store = mock(ObjectStore.class);
        ObjectRef object = new ObjectRef(StorageDomain.VAULT, "users/u1/vault/item.png");
        Duration providerTtl = Duration.ofMinutes(2);
        when(store.presignGet(object, providerTtl)).thenReturn(access(object, FIRST_URI));

        PrivacyApprovedInputTransfer.PreparedInput prepared = transfer(store).prepare(
            media("users/u1/vault/item.png", OptionalLong.of(MAX_INLINE_BYTES + 1)),
            BOTH,
            providerTtl);

        assertThat(prepared.method()).isEqualTo(InputTransferMethod.EPHEMERAL_SIGNED_READ);
        assertThat(prepared.inlineBytes()).isEmpty();
        assertThat(prepared.signedRead()).contains(access(object, FIRST_URI));
        verify(store).presignGet(object, providerTtl);
        verify(store, never()).readBounded(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(store, never()).head(any());
        verify(store, times(1)).presignGet(any(), any());
    }

    @Test
    void twoPrepareCallsMintFreshPresigns() {
        ObjectStore store = mock(ObjectStore.class);
        ObjectRef object = new ObjectRef(StorageDomain.LOOK, "users/u1/looks/look.png");
        Duration providerTtl = Duration.ofMinutes(5);
        when(store.presignGet(object, providerTtl))
            .thenReturn(access(object, FIRST_URI), access(object, SECOND_URI));
        PrivacyApprovedInputTransfer transfer = transfer(store);
        PrivateMediaRef media = media("users/u1/looks/look.png", OptionalLong.of(MAX_INLINE_BYTES + 8));

        PrivacyApprovedInputTransfer.PreparedInput first = transfer.prepare(media, BOTH, providerTtl);
        PrivacyApprovedInputTransfer.PreparedInput second = transfer.prepare(media, BOTH, providerTtl);

        assertThat(first.signedRead().orElseThrow().uri()).isEqualTo(FIRST_URI);
        assertThat(second.signedRead().orElseThrow().uri()).isEqualTo(SECOND_URI);
        assertThat(first.signedRead().orElseThrow().uri())
            .isNotEqualTo(second.signedRead().orElseThrow().uri());
        verify(store, times(2)).presignGet(object, providerTtl);
        assertThat(first.toString()).doesNotContain("https://", "first-secret", "X-Amz-Signature");
        assertThat(second.toString()).doesNotContain("https://", "second-secret", "X-Amz-Signature");
    }

    @Test
    void providerTtlAboveFiveMinutesFailsClosedWithoutPresign() {
        ObjectStore store = mock(ObjectStore.class);

        assertThatExceptionOfType(UnsupportedProviderCapabilityException.class)
            .isThrownBy(() -> transfer(store).prepare(
                media("users/u1/avatars/a.jpg", OptionalLong.of(MAX_INLINE_BYTES + 1)),
                BOTH,
                Duration.ofMinutes(6)));

        verify(store, never()).presignGet(any(), any());
        verify(store, never()).readBounded(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(store, never()).head(any());
    }

    @Test
    void generationOwnedProductIsAResolvablePrivateBoundary() {
        ObjectStore store = mock(ObjectStore.class);
        PrivateMediaRef input = media(
            "users/u1/generation-inputs/00000000-0000-0000-0000-000000000001/product",
            OptionalLong.of(MAX_INLINE_BYTES + 1));
        ObjectRef expected = new ObjectRef(StorageDomain.TEMP, input.storageKey().value());
        PresignedAccess access = access(expected, FIRST_URI);
        when(store.presignGet(expected, MAX_SIGNED_READ_TTL)).thenReturn(access);

        PrivacyApprovedInputTransfer.PreparedInput prepared = transfer(store).prepare(input, BOTH, MAX_SIGNED_READ_TTL);

        assertThat(prepared.signedRead()).contains(access);
        assertThat(prepared.toString()).doesNotContain(FIRST_URI.toString(), "X-Amz-Signature");
        verify(store).presignGet(expected, MAX_SIGNED_READ_TTL);
    }

    private static PrivacyApprovedInputTransfer transfer(ObjectStore store) {
        return new PrivacyApprovedInputTransfer(policy(), store);
    }

    private static PrivacyTransferPolicy policy() {
        return new PrivacyTransferPolicy(
            BOTH,
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            MAX_INLINE_BYTES,
            MAX_SIGNED_READ_TTL,
            true);
    }

    private static PrivateMediaRef media(String key, OptionalLong sizeBytes) {
        return new PrivateMediaRef(new StorageKey(key), sizeBytes);
    }

    private static PresignedAccess access(ObjectRef object, URI uri) {
        return new PresignedAccess(object, "GET", uri, Map.of(), EXPIRES);
    }
}
