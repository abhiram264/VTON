package com.aifashionvault.platform.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.ImageMediaType;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.shared.SourceReferenceId;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.vault.JdbcVaultStore;
import com.aifashionvault.vault.VaultContracts.DeleteResult;
import com.aifashionvault.vault.VaultContracts.Filters;
import com.aifashionvault.vault.VaultContracts.Patch;
import com.aifashionvault.vault.VaultItem;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VaultCreateSpanTest {

	private static final UserId USER = new UserId(UUID.nameUUIDFromBytes("vault-create-owner".getBytes()));
	private static final String CANARY_SIGNED_URL =
		"https://cdn.example.test/media.mp4?X-Amz-Signature=canary-signature-0123";
	private static final String CANARY_UPLOAD_ID =
		UUID.nameUUIDFromBytes("canary-upload-0192c6f0".getBytes()).toString();
	private static final String CANARY_SOURCE_ID =
		UUID.nameUUIDFromBytes("canary-source-0192c6f0".getBytes()).toString();

	@Test
	void successfulCreateEmitsSingleCreatedSpanWithOutcomeOnly() {
		TestObservationRegistry spans = TestObservationRegistry.create();
		VaultService service = new VaultService(stub(), media(null, new ClaimResult.Copied()),
			new ObservationFacade(spans));

		VaultItem item = service.create(USER, request());

		assertThat(item.name()).isEqualTo("Canary Name");
		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.VAULT_CREATE)
			.hasLowCardinalityKeyValue("outcome", "CREATED")
			.hasBeenStopped();
		spans.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertThat(contexts.get(0).getLowCardinalityKeyValues())
				.containsExactly(KeyValue.of("outcome", "CREATED"));
			assertThat(contexts.get(0).getName() + contexts.get(0).getAllKeyValues())
				.doesNotContain("canary-signature", CANARY_UPLOAD_ID, CANARY_SOURCE_ID, "base64");
		});
	}

	@Test
	void replayedExistingItemEmitsCreatedSpan() {
		TestObservationRegistry spans = TestObservationRegistry.create();
		VaultService service = new VaultService(stubReturningItem("Replayed"), media(null, new ClaimResult.Copied()),
			new ObservationFacade(spans));

		service.create(USER, request());

		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.VAULT_CREATE)
			.hasLowCardinalityKeyValue("outcome", "CREATED")
			.hasBeenStopped();
	}

	@Test
	void claimReplayRefetchEmitsCreatedSpan() {
		TestObservationRegistry spans = TestObservationRegistry.create();
		VaultService service = new VaultService(stubRefetchOnClaim("Claim Replay"),
			media(null, new ClaimResult.Replay()), new ObservationFacade(spans));

		service.create(USER, request());

		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.VAULT_CREATE)
			.hasLowCardinalityKeyValue("outcome", "CREATED")
			.hasBeenStopped();
	}

	@ParameterizedTest
	@MethodSource("failures")
	void everyThrownFailureEmitsSingleRejectedSpanWithMarkedError(
			VaultService service, Class<? extends Throwable> expected) {
		TestObservationRegistry spans = TestObservationRegistry.create();
		VaultService instrumented = withSpans(service, spans);

		assertThatThrownBy(() -> instrumented.create(USER, request())).isInstanceOf(expected);

		spans.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.VAULT_CREATE)
			.hasLowCardinalityKeyValue("outcome", "REJECTED")
			.hasBeenStopped();
		spans.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertThat(contexts.get(0).getError()).isNotNull();
			assertThat(contexts.get(0).getName() + contexts.get(0).getAllKeyValues())
				.doesNotContain("canary-signature", CANARY_UPLOAD_ID, CANARY_SOURCE_ID, "base64");
		});
	}

	@Test
	void absentFacadeKeepsBehaviorAndNeverEmits() {
		VaultItem created = new VaultService(stub(), media(null, new ClaimResult.Copied())).create(USER, request());
		VaultItem nullFacade = new VaultService(stub(), media(null, new ClaimResult.Copied()), (ObservationFacade) null)
			.create(USER, request());
		assertThat(created.name()).isEqualTo("Canary Name");
		assertThat(nullFacade.name()).isEqualTo("Canary Name");

		VaultService rejected = new VaultService(stub(),
			media(new ObjectStoreExceptions.StagedObjectMissingException("missing"), new ClaimResult.Copied()), (ObservationFacade) null);
		assertThatThrownBy(() -> rejected.create(USER, request())).isInstanceOf(VaultService.VaultProblem.class);
	}

	@Test
	void onStartHandlerFailureDoesNotEscapeAndCreateStillSucceeds() {
		VaultService service = new VaultService(stub(), media(null, new ClaimResult.Copied()),
			new ObservationFacade(registryWithFailingHandler(FailingStage.ON_START)));

		VaultItem item = service.create(USER, request());

		assertThat(item.name()).isEqualTo("Canary Name");
	}

	@Test
	void onErrorHandlerFailureDoesNotEscapeAndOriginalFailureStillPropagates() {
		IllegalStateException insertFailure = new IllegalStateException("canary insert failure");
		JdbcVaultStore store = stub();
		when(store.insert(any(VaultItem.class))).thenThrow(insertFailure);
		VaultService service = new VaultService(store, media(null, new ClaimResult.Copied()),
			new ObservationFacade(registryWithFailingHandler(FailingStage.ON_ERROR)));

		assertThatThrownBy(() -> service.create(USER, request())).isSameAs(insertFailure);
	}

	@Test
	void onStopHandlerFailureDoesNotEscapeOnCreatedOrRejectedOutcomes() {
		VaultService created = new VaultService(stub(), media(null, new ClaimResult.Copied()),
			new ObservationFacade(registryWithFailingHandler(FailingStage.ON_STOP)));
		assertThat(created.create(USER, request()).name()).isEqualTo("Canary Name");

		VaultService rejected = new VaultService(stub(),
			media(new ObjectStoreExceptions.StagedObjectMissingException("missing"), new ClaimResult.Copied()),
			new ObservationFacade(registryWithFailingHandler(FailingStage.ON_STOP)));
		assertThatThrownBy(() -> rejected.create(USER, request())).isInstanceOf(VaultService.VaultProblem.class);
	}

	private static Stream<Arguments> failures() {
		return Stream.of(
			Arguments.of(new VaultService(stub(), media(new ObjectStoreExceptions.StagedObjectMissingException("missing"),
				new ClaimResult.Copied())), VaultService.VaultProblem.class),
			Arguments.of(new VaultService(stub(), media(new ObjectStoreExceptions.ObjectStoreUnavailableException("unavailable"),
				new ClaimResult.Copied())), VaultService.VaultProblem.class),
			Arguments.of(new VaultService(stubRefetchOnClaim("Existing"), media(null, new ClaimResult.Conflict())),
				VaultService.VaultProblem.class),
			Arguments.of(new VaultService(stub(), media(null, new ClaimResult.Replay())),
				VaultService.VaultProblem.class),
			Arguments.of(new VaultService(stub(), media(null, new ClaimResult.Conflict())),
				VaultService.VaultProblem.class),
			Arguments.of(new VaultService(insertFailingStub(), media(null, new ClaimResult.Copied())),
				IllegalStateException.class));
	}

	private static VaultService withSpans(VaultService plain, TestObservationRegistry spans) {
		return new VaultService(plain.store, plain.media, new ObservationFacade(spans));
	}

	private static VaultItemCreateRequest request() {
		return new VaultItemCreateRequest(CANARY_UPLOAD_ID, CANARY_SOURCE_ID, "Canary Name", "Acme",
			ProductCategory.TOP, null, "Black", null, null);
	}

	/**
	 * The final JdbcVaultStore cannot be subclassed, so the test store is a
	 * Mockito inline mock: a real instance of the final class whose JDBC
	 * surface is never touched (DataSource never resolved by unstubbed paths).
	 */
	private static JdbcVaultStore stub() {
		return stub(null, null);
	}

	private static JdbcVaultStore stubReturningItem(String name) {
		return stub(item(name), null);
	}

	private static JdbcVaultStore stubRefetchOnClaim(String name) {
		return stub(null, item(name));
	}

	private static JdbcVaultStore stub(VaultItem immediate, VaultItem onRefetch) {
		JdbcVaultStore store = mock(JdbcVaultStore.class);
		when(store.sourceExists(any(SourceReferenceId.class))).thenReturn(true);
		when(store.findByStorageKeyForUser(any(StorageKey.class), any(UserId.class)))
			.thenReturn(Optional.ofNullable(immediate))
			.thenReturn(Optional.ofNullable(onRefetch));
		when(store.insert(any(VaultItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(store.findByIdForUser(any(VaultItemId.class), any(UserId.class))).thenReturn(Optional.empty());
		when(store.listForUser(any(UserId.class), any(Filters.class))).thenReturn(List.of());
		when(store.patchForUser(any(VaultItemId.class), any(UserId.class), any(Patch.class))).thenReturn(Optional.empty());
		when(store.lockForDelete(any(VaultItemId.class), any(UserId.class))).thenReturn(Optional.empty());
		when(store.isInUse(any(VaultItemId.class))).thenReturn(false);
		when(store.deleteForUser(any(VaultItemId.class), any(UserId.class))).thenReturn(DeleteResult.NOT_FOUND);
		return store;
	}

	private static JdbcVaultStore insertFailingStub() {
		JdbcVaultStore store = stub();
		when(store.insert(any(VaultItem.class))).thenThrow(new IllegalStateException("canary insert failure"));
		return store;
	}

	private static ObservationRegistry registryWithFailingHandler(FailingStage stage) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				if (stage == FailingStage.ON_START) {
					throw new IllegalStateException("canary onStart handler failure");
				}
			}

			@Override
			public void onEvent(Observation.Event event, Observation.Context context) {
				if (stage == FailingStage.ON_ERROR) {
					throw new IllegalStateException("canary onError handler failure");
				}
			}

			@Override
			public void onStop(Observation.Context context) {
				if (stage == FailingStage.ON_STOP) {
					throw new IllegalStateException("canary onStop handler failure");
				}
			}
		});
		return registry;
	}

	private enum FailingStage {
		ON_START,
		ON_ERROR,
		ON_STOP
	}

	private static VaultItem item(String name) {
		return new VaultItem(new VaultItemId(UUID.nameUUIDFromBytes("item".getBytes())), USER,
			new StorageKey("users/x/vault/y.jpg"), null, null, name, "Acme", ProductCategory.TOP, null, null, null,
			null, java.time.Instant.EPOCH);
	}

	private static ObjectStore media(RuntimeException headFailure, ClaimResult claimResult) {
		return new ObjectStore() {
			@Override
			public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
				throw new UnsupportedOperationException();
			}

			@Override
			public ObjectMetadata head(ObjectRef object) {
				if (headFailure != null) {
					throw headFailure;
				}
				return new ObjectMetadata(100, "image/jpeg", "v1");
			}

			@Override
			public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
				return new byte[0];
			}

			@Override
			public void delete(ObjectRef object) {
			}

			@Override
			public ClaimResult claim(ClaimCommand command) {
				if (command.sourceVersion() == null) {
					throw new IllegalStateException("claim must carry the head version");
				}
				return claimResult;
			}

			@Override
			public void purgeUserObjects(UserId userId) {
			}

			@Override
			public void cleanupTemp() {
			}

			@Override
			public PresignedAccess presignGet(ObjectRef object, java.time.Duration ttl) {
				throw new UnsupportedOperationException();
			}

			@Override
			public List<StoredObject> list(StorageDomain boundary, String prefix) {
				return List.of();
			}
		};
	}
}
