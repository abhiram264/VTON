package com.aifashionvault.platform.objectstore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifashionvault.generation.vton.GenerationOutputStore;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ObjectStoreGenerationOutputStoreTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UUID JOB_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID ATTEMPT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
	private static final ImageMediaType MEDIA_TYPE = ImageMediaType.JPEG;
	private static final String SOURCE_VERSION = "source-v1";
	private static final long MAX_BYTES = 16L;

	private final GenerationMediaStore media = mock(GenerationMediaStore.class);
	private final ObjectStore objects = mock(ObjectStore.class);
	private final ObjectStoreGenerationOutputStore store = new ObjectStoreGenerationOutputStore(
		media,
		objects,
		USER_ID,
		JOB_ID,
		ATTEMPT_ID,
		MEDIA_TYPE);

	@Test
	void writePrivateTempUsesProviderMediaSourceOnly() {
		assertThat(GenerationOutputStore.class.getMethods())
			.filteredOn(method -> method.getName().equals("writePrivateTemp"))
			.hasSize(1)
			.allSatisfy(method -> assertThat(method.getParameterTypes())
				.containsExactly(ProviderMediaSource.class)
				.doesNotContain(byte[].class));
		assertThat(ObjectStoreGenerationOutputStore.class.getDeclaredMethods())
			.filteredOn(method -> method.getName().equals("writePrivateTemp"))
			.singleElement()
			.extracting(Method::getParameterTypes)
			.isEqualTo(new Class<?>[] {ProviderMediaSource.class});
	}

	@Test
	void writePrivateTempPutsBoundedStreamAtGenerationTempKey() throws Exception {
		byte[] payload = {1, 2, 3, 4};
		ProviderMediaSource source = source(payload, OptionalLong.of(payload.length));

		PrivateMediaRef ref = store.writePrivateTemp(source);

		StorageKey dest = StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);
		assertThat(ref.storageKey()).isEqualTo(dest);
		assertThat(ref.sizeBytes()).hasValue(payload.length);
		ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
		verify(media).putBounded(eq(dest), stream.capture(), eq(MAX_BYTES), eq(source.mediaType()));
		assertThat(stream.getValue()).isSameAs(source.stream());
		verifyNoInteractions(objects);
	}

	@Test
	void writePrivateTempReturnsEmptySizeWhenSourceLengthUnknown() throws Exception {
		ProviderMediaSource source = source(new byte[] {9, 8}, OptionalLong.empty());

		PrivateMediaRef ref = store.writePrivateTemp(source);

		assertThat(ref.storageKey()).isEqualTo(StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE));
		assertThat(ref.sizeBytes()).isEmpty();
	}

	@Test
	void repeatedWriteUsesIdempotentDestAndPutsOnce() throws Exception {
		StorageKey dest = StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);

		PrivateMediaRef first = store.writePrivateTemp(source(new byte[] {1}, OptionalLong.of(1)));
		PrivateMediaRef second = store.writePrivateTemp(source(new byte[] {2}, OptionalLong.of(1)));

		assertThat(first.storageKey()).isEqualTo(dest);
		assertThat(second.storageKey()).isEqualTo(dest);
		verify(media, times(1)).putBounded(eq(dest), any(InputStream.class), eq(MAX_BYTES), eq("image/jpeg"));
	}

	@Test
	void transientUnavailabilityGetsExactlyOneLocalRetryOnSameKey() throws Exception {
		StorageKey dest = StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);
		FailingMediaStore failing = new FailingMediaStore(
			new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable"),
			null);
		ObjectStoreGenerationOutputStore recovering = new ObjectStoreGenerationOutputStore(
			failing, objects, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);
		byte[] payload = {1, 2, 3};

		PrivateMediaRef ref = recovering.writePrivateTemp(source(payload, OptionalLong.of(payload.length)));

		assertThat(ref.storageKey()).isEqualTo(dest);
		assertThat(failing.putAttempts).isEqualTo(2);
		assertThat(failing.lastDest).isEqualTo(dest);
		assertThat(failing.lastPayload).isEqualTo(payload);
		assertThat(failing.lastPayload).isNotSameAs(payload);
	}

	@Test
	void secondTransientFailureIsStorageUnavailableWithFirstSuppressed() {
		ObjectStoreExceptions.ObjectStoreUnavailableException firstFailure =
			new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		ObjectStoreExceptions.ObjectStoreUnavailableException secondFailure =
			new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		FailingMediaStore failing = new FailingMediaStore(firstFailure, secondFailure);
		ObjectStoreGenerationOutputStore recovering = new ObjectStoreGenerationOutputStore(
			failing, objects, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);

		assertThatThrownBy(() -> recovering.writePrivateTemp(source(new byte[] {1, 2}, OptionalLong.of(2))))
			.isInstanceOf(GenerationOutputStore.StorageUnavailableException.class)
			.hasCause(secondFailure);
		assertThat(secondFailure.getSuppressed()).containsExactly(firstFailure);
		assertThat(failing.putAttempts).isEqualTo(2);
		assertThat(failing.claimAttempts).isZero();
	}

	@Test
	void confirmedMissingSourceIsImmediatelyStorageUnavailable() {
		FailingMediaStore failing = new FailingMediaStore(
			new ObjectStoreExceptions.StagedObjectMissingException("staged object is missing"),
			null);
		ObjectStoreGenerationOutputStore recovering = new ObjectStoreGenerationOutputStore(
			failing, objects, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);

		assertThatThrownBy(() -> recovering.writePrivateTemp(source(new byte[] {1, 2}, OptionalLong.of(2))))
			.isInstanceOf(GenerationOutputStore.StorageUnavailableException.class)
			.hasCauseInstanceOf(ObjectStoreExceptions.StagedObjectMissingException.class);
		assertThat(failing.putAttempts).isEqualTo(1);
	}

	@Test
	void nonRepositionableSourceCannotServeLocalRetryAndIsStorageUnavailable() {
		ObjectStoreExceptions.ObjectStoreUnavailableException failure =
			new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		FailingMediaStore failing = new FailingMediaStore(failure, failure);
		ObjectStoreGenerationOutputStore recovering = new ObjectStoreGenerationOutputStore(
			failing, objects, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);
		InputStream oneShot = new ByteArrayInputStream(new byte[] {1, 2}) {
			@Override
			public boolean markSupported() {
				return false;
			}
		};

		assertThatThrownBy(() -> recovering.writePrivateTemp(
			ProviderMediaSource.of(oneShot, OptionalLong.of(2), MAX_BYTES, "image/jpeg")))
			.isInstanceOf(GenerationOutputStore.StorageUnavailableException.class)
			.hasCause(failure);
		assertThat(failing.putAttempts).isEqualTo(1);
	}

	@Test
	void discardDeletesTempObject() {
		store.discard();

		verify(objects).delete(new ObjectRef(
			StorageDomain.TEMP,
			StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE).value()));
		verifyNoInteractions(media);
	}

	@Test
	void promoteClaimsTempToLookWithAttemptIdMarker() {
		StorageKey tempKey = StorageKeys.generationTempKey(USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE);
		StorageKey lookKey = StorageKeys.persistentKey(USER_ID, StorageDomain.LOOK, ATTEMPT_ID, MEDIA_TYPE);
		GenerationClaimCommand command = new GenerationClaimCommand(
			USER_ID,
			tempKey,
			lookKey,
			SOURCE_VERSION,
			ATTEMPT_ID.toString());
		when(media.claim(command))
			.thenReturn(new ClaimResult.Copied())
			.thenReturn(new ClaimResult.Replay());

		assertThat(store.promote(SOURCE_VERSION)).isInstanceOf(ClaimResult.Copied.class);
		assertThat(store.promote(SOURCE_VERSION)).isInstanceOf(ClaimResult.Replay.class);

		verify(media, times(2)).claim(command);
		verifyNoInteractions(objects);
	}

	@Test
	void constructorRejectsMissingIdentity() {
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			null, objects, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			media, null, USER_ID, JOB_ID, ATTEMPT_ID, MEDIA_TYPE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			media, objects, null, JOB_ID, ATTEMPT_ID, MEDIA_TYPE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			media, objects, USER_ID, null, ATTEMPT_ID, MEDIA_TYPE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			media, objects, USER_ID, JOB_ID, null, MEDIA_TYPE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ObjectStoreGenerationOutputStore(
			media, objects, USER_ID, JOB_ID, ATTEMPT_ID, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static ProviderMediaSource source(byte[] payload, OptionalLong contentLength) {
		return ProviderMediaSource.of(
			new ByteArrayInputStream(payload),
			contentLength,
			MAX_BYTES,
			"image/jpeg");
	}

	private static final class FailingMediaStore implements GenerationMediaStore {

		private final RuntimeException firstFailure;
		private final RuntimeException secondFailure;
		private int putAttempts;
		private int claimAttempts;
		private StorageKey lastDest;
		private byte[] lastPayload;

		private FailingMediaStore(RuntimeException firstFailure, RuntimeException secondFailure) {
			this.firstFailure = firstFailure;
			this.secondFailure = secondFailure;
		}

		@Override
		public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) {
			putAttempts++;
			try {
				lastDest = dest;
				lastPayload = source.readAllBytes();
			}
			catch (java.io.IOException ex) {
				throw new AssertionError("fake media store failed to read the candidate payload", ex);
			}
			if (putAttempts == 1 && firstFailure != null) {
				throw firstFailure;
			}
			if (putAttempts >= 2 && secondFailure != null) {
				throw secondFailure;
			}
		}

		@Override
		public ClaimResult claim(GenerationClaimCommand command) {
			claimAttempts++;
			throw new AssertionError("claim must not be part of storage recovery");
		}

		@Override public void preflightProductInput(ProductInputClaimCommand command) {
			throw new AssertionError("product input is unrelated to output storage");
		}

		@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) {
			throw new AssertionError("product input is unrelated to output storage");
		}

		@Override public void releaseProductInput(ProductInputClaimCommand command) {
			throw new AssertionError("product input is unrelated to output storage");
		}
	}
}
