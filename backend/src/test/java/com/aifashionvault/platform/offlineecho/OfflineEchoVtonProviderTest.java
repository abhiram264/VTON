package com.aifashionvault.platform.offlineecho;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.StorageKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the offline echo backstop through the real provider seam: a full
 * submit -> getStatus -> openOutput round trip returns the submitted avatar bytes
 * unchanged, with the object store as the only collaborator (no HTTP client exists, so
 * the path is structurally incapable of an external or paid call).
 */
class OfflineEchoVtonProviderTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String AVATAR_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/avatar";
    private static final String PRODUCT_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/product";
    private static final String VERSION = "\"v1\"";

    @Test
    void goldenPathEchoesTheAvatarBytesUnchanged() throws Exception {
        ObjectStore store = storeWith(PNG, "image/png");
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);

        GenerationSubmission submission = provider.submit(request("standard"), new SubmissionKey(UUID.randomUUID()));

        assertThat(submission).isInstanceOf(GenerationSubmission.Accepted.class);
        ProviderJobId jobId = ((GenerationSubmission.Accepted) submission).providerJobId();

        StatusQueryResult status = provider.getStatus(jobId);
        assertThat(status).isInstanceOf(StatusQueryResult.Observed.class);
        GenerationStatus reported = ((StatusQueryResult.Observed) status).status();
        assertThat(reported).isInstanceOf(GenerationStatus.Succeeded.class);
        List<ProviderOutputRef> outputs = ((GenerationStatus.Succeeded) reported).outputs();
        assertThat(outputs).hasSize(1);

        try (ProviderMediaSource source = provider.openOutput(outputs.getFirst())) {
            assertThat(source.mediaType()).isEqualTo("image/png");
            assertThat(source.stream().readAllBytes()).isEqualTo(PNG);
        }

        // Only the two declared inputs were resolved; nothing was written, deleted or signed.
        verify(store, times(2)).head(any(ObjectRef.class));
        verify(store, times(2)).readBounded(any(ObjectRef.class), anyString(), anyInt());
        verify(store, never()).delete(any(ObjectRef.class));
        verify(store, never()).presignGet(any(ObjectRef.class), any());
        verify(store, never()).purgeUserObjects(any());
    }

    @Test
    void echoesAJsonpegInputWithItsOwnMediaType() throws Exception {
        ObjectStore store = storeWith(PNG, "image/jpeg");
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);

        ProviderJobId jobId = accepted(provider, request("hd"));
        GenerationStatus.Succeeded succeeded = succeeded(provider, jobId);

        try (ProviderMediaSource source = provider.openOutput(succeeded.outputs().getFirst())) {
            assertThat(source.mediaType()).isEqualTo("image/jpeg");
            assertThat(source.stream().readAllBytes()).isEqualTo(PNG);
        }
    }

    @Test
    void instructionsAreAcceptedAndTheAvatarIsStillEchoed() throws Exception {
        ObjectStore store = storeWith(PNG, "image/png");
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);

        VtonRequest withInstructions = new VtonRequest(
            new PrivateMediaRef(new StorageKey(AVATAR_KEY), OptionalLong.of(PNG.length)),
            List.of(new VtonProductAsset(new PrivateMediaRef(new StorageKey(PRODUCT_KEY), OptionalLong.of(PNG.length)),
                new ItemCategory("tops"))),
            Optional.of("make it a summer look"),
            new VtonMode("standard"));

        ProviderJobId jobId = accepted(provider, withInstructions);
        GenerationStatus.Succeeded succeeded = succeeded(provider, jobId);

        try (ProviderMediaSource source = provider.openOutput(succeeded.outputs().getFirst())) {
            assertThat(source.stream().readAllBytes()).isEqualTo(PNG);
        }
    }

    @Test
    void missingAvatarIsRejectedAndLeavesNoOutputBehind() {
        ObjectStore store = mock(ObjectStore.class);
        when(store.head(any(ObjectRef.class)))
            .thenThrow(new ObjectStoreExceptions.StagedObjectMissingException("missing object"));
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);

        GenerationSubmission submission = provider.submit(request("standard"), new SubmissionKey(UUID.randomUUID()));

        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().safeCode()).isEqualTo("MEDIA_MISSING");
        // Nothing was cached, so a status poll for any job id is an honest failure.
        StatusQueryResult status = provider.getStatus(new ProviderJobId("offline-echo-absent"));
        assertThat(((StatusQueryResult.Observed) status).status()).isInstanceOf(GenerationStatus.Failed.class);
    }

    @Test
    void unsupportedModeIsRefusedBeforeTheStoreIsTouched() {
        ObjectStore store = mock(ObjectStore.class);
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);

        GenerationSubmission submission = provider.submit(request("ultra"), new SubmissionKey(UUID.randomUUID()));

        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        assertThat(((GenerationSubmission.Rejected) submission).failure().safeCode()).isEqualTo("MODE_UNSUPPORTED");
        verify(store, never()).head(any(ObjectRef.class));
    }

    @Test
    void unknownOutputReferenceIsAClassifiedProtocolFailure() {
        ObjectStore store = storeWith(PNG, "image/png");
        OfflineEchoVtonProvider provider = new OfflineEchoVtonProvider(store);
        provider.submit(request("standard"), new SubmissionKey(UUID.randomUUID()));

        assertThatExceptionOfType(OfflineEchoProviderException.class)
            .isThrownBy(() -> provider.openOutput(new ProviderOutputRef("offline-echo-absent:0")))
            .satisfies(ex -> assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_MISSING"));

        assertThatExceptionOfType(OfflineEchoProviderException.class)
            .isThrownBy(() -> provider.openOutput(new ProviderOutputRef("offline-echo-absent:7")))
            .satisfies(ex -> assertThat(ex.failure().safeCode()).isEqualTo("OUTPUT_REF"));
    }

    private static ProviderJobId accepted(OfflineEchoVtonProvider provider, VtonRequest request) {
        GenerationSubmission submission = provider.submit(request, new SubmissionKey(UUID.randomUUID()));
        assertThat(submission).isInstanceOf(GenerationSubmission.Accepted.class);
        return ((GenerationSubmission.Accepted) submission).providerJobId();
    }

    private static GenerationStatus.Succeeded succeeded(OfflineEchoVtonProvider provider, ProviderJobId jobId) {
        StatusQueryResult status = provider.getStatus(jobId);
        GenerationStatus reported = ((StatusQueryResult.Observed) status).status();
        assertThat(reported).isInstanceOf(GenerationStatus.Succeeded.class);
        return (GenerationStatus.Succeeded) reported;
    }

    private static ObjectStore storeWith(byte[] bytes, String contentType) {
        ObjectStore store = mock(ObjectStore.class);
        when(store.head(any(ObjectRef.class)))
            .thenReturn(new ObjectMetadata(bytes.length, contentType, VERSION));
        when(store.readBounded(any(ObjectRef.class), anyString(), anyInt())).thenReturn(bytes);
        return store;
    }

    private static VtonRequest request(String mode) {
        return new VtonRequest(
            new PrivateMediaRef(new StorageKey(AVATAR_KEY), OptionalLong.of(PNG.length)),
            List.of(new VtonProductAsset(new PrivateMediaRef(new StorageKey(PRODUCT_KEY), OptionalLong.of(PNG.length)),
                new ItemCategory("tops"))),
            Optional.empty(),
            new VtonMode(mode));
    }
}
