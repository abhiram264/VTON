package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LooksControllerTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final LookId LOOK_ID = new LookId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final StorageKey KEY = new StorageKey("users/11111111-1111-1111-1111-111111111111/looks/result.jpg");

	@Test
	void getMintsShortLivedCapabilityOnlyAfterOwnerScopedLookup() {
		JdbcLookStore store = mock(JdbcLookStore.class);
		ObjectStore objects = mock(ObjectStore.class);
		when(store.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(objects.presignGet(new ObjectRef(StorageBoundary.LOOK, KEY.value()), java.time.Duration.ofMinutes(5)))
			.thenReturn(new PresignedAccess(
				new ObjectRef(StorageBoundary.LOOK, KEY.value()),
				"GET",
				URI.create("https://private.invalid/read?signature=secret"),
				Map.of(),
				NOW.plusSeconds(300)));

		LookResource resource = controller(store, objects).get(OWNER, LOOK_ID.value().toString());

		assertThat(resource.id()).isEqualTo(LOOK_ID.value());
		assertThat(resource.generationId()).isEqualTo(JOB_ID.value());
		assertThat(resource.image().expiresAt()).isEqualTo(NOW.plusSeconds(300));
	}

	@Test
	void foreignOrDeletedLookDoesNotMintCapability() {
		JdbcLookStore store = mock(JdbcLookStore.class);
		ObjectStore objects = mock(ObjectStore.class);
		when(store.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller(store, objects).get(OWNER, LOOK_ID.value().toString()))
			.isInstanceOf(RuntimeException.class);
		verify(objects, never()).presignGet(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void listAndShareMintOwnerAuthorizedCapabilitiesWithoutExposingStorageIdentity() {
		JdbcLookStore store = mock(JdbcLookStore.class);
		ObjectStore objects = mock(ObjectStore.class);
		when(store.listForUser(OWNER)).thenReturn(List.of(look()));
		when(store.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(objects.presignGet(new ObjectRef(StorageBoundary.LOOK, KEY.value()), java.time.Duration.ofMinutes(5)))
			.thenReturn(new PresignedAccess(new ObjectRef(StorageBoundary.LOOK, KEY.value()), "GET",
				URI.create("https://private.invalid/read?signature=secret"), Map.of(), NOW.plusSeconds(300)));

		List<LookResource> listed = controller(store, objects).list(OWNER);
		ReadCapability shared = controller(store, objects).share(OWNER, LOOK_ID.value().toString());

		assertThat(listed).hasSize(1);
		assertThat(listed.getFirst().avatarId()).isNull();
		assertThat(listed.getFirst().qualityStatus()).isEqualTo(QualityDecision.PASS);
		assertThat(shared.url()).isEqualTo(URI.create("https://private.invalid/read?signature=secret"));
		verify(objects, times(2)).presignGet(new ObjectRef(StorageBoundary.LOOK, KEY.value()), java.time.Duration.ofMinutes(5));
	}

	@Test
	void regenerateCreatesDistinctQueuedJobFromFrozenSourceAndReplaysAtomically() {
		JdbcLookStore looks = mock(JdbcLookStore.class);
		ObjectStore objects = mock(ObjectStore.class);
		GenerationStore generations = mock(GenerationStore.class);
		AvatarImageId avatar = new AvatarImageId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
		GenerationJob source = sourceJob(avatar);
		when(looks.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(generations.findJobForUser(JOB_ID, OWNER)).thenReturn(Optional.of(source));
		when(generations.findRenderPlan(JOB_ID)).thenReturn(Optional.of(
			new com.aifashionvault.generation.RenderPlan(1, List.of(
				new com.aifashionvault.generation.RenderStep(UUID.randomUUID(), 1, null, List.of())))));
		when(generations.createAccepted(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
			new GenerationStore.CreateAcceptedResult.Created(invocation.getArgument(0)));

		ResponseEntity<GenerationResponse> response = controller(looks, objects, generations)
			.regenerate(OWNER, LOOK_ID.value().toString(), " replay-key ");

		assertThat(response.getStatusCode().value()).isEqualTo(202);
		assertThat(response.getHeaders().getLocation()).hasToString("/generations/" + response.getBody().id());
		org.mockito.ArgumentCaptor<GenerationJob> accepted = org.mockito.ArgumentCaptor.forClass(GenerationJob.class);
		verify(generations).createAccepted(accepted.capture());
		assertThat(accepted.getValue().id()).isNotEqualTo(JOB_ID);
		assertThat(accepted.getValue().retryOfJobId()).contains(JOB_ID);
		assertThat(accepted.getValue().avatarImageId()).isEqualTo(avatar);
		assertThat(accepted.getValue().clientRequestId()).isEqualTo(new ClientRequestId("replay-key"));
		assertThat(accepted.getValue().status()).isEqualTo(GenerationJob.QUEUED);
	}

	@Test
	void deleteMarksHiddenBeforePhysicalCleanupAndCanRetryCleanup() {
		JdbcLookStore store = mock(JdbcLookStore.class);
		ObjectStore objects = mock(ObjectStore.class);
		when(store.findByIdForCleanup(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(store.logicallyDeleteForUser(LOOK_ID, OWNER)).thenReturn(true);

		controller(store, objects).delete(OWNER, LOOK_ID.value().toString());

		verify(store).logicallyDeleteForUser(LOOK_ID, OWNER);
		verify(objects).delete(new ObjectRef(StorageBoundary.LOOK, KEY.value()));
	}

	@SuppressWarnings("unchecked")
	private static LooksController controller(JdbcLookStore store, ObjectStore objects) {
		return controller(store, objects, mock(GenerationStore.class));
	}

	@SuppressWarnings("unchecked")
	private static LooksController controller(JdbcLookStore store, ObjectStore objects, GenerationStore generations) {
		ObjectProvider<JdbcLookStore> stores = mock(ObjectProvider.class);
		ObjectProvider<ObjectStore> objectStores = mock(ObjectProvider.class);
		ObjectProvider<GenerationStore> generationStores = mock(ObjectProvider.class);
		ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
		when(stores.getIfAvailable()).thenReturn(store);
		when(objectStores.getIfAvailable()).thenReturn(objects);
		when(generationStores.getIfAvailable()).thenReturn(generations);
		when(clocks.getIfAvailable()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
		return new LooksController(stores, objectStores, generationStores, clocks);
	}

	private static Look look() {
		return new Look(
			LOOK_ID,
			OWNER,
			null,
			JOB_ID,
			null,
			KEY,
			null,
			null,
			null,
			QualityDecision.PASS,
			NOW,
			List.of());
	}

	private static GenerationJob sourceJob(AvatarImageId avatar) {
		return new GenerationJob(
			JOB_ID, OWNER, null, GenerationJob.COMPLETED, "STANDARD", null, NOW.minusSeconds(60), NOW,
			avatar, List.of(), null, null, "source", 1, 3, NOW, null, 1, 1, 1,
			NOW.plusSeconds(1200), null, RetryPolicySnapshot.forStepCountV2(1), Optional.empty());
	}
}
