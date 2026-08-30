package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
		ObjectProvider<JdbcLookStore> stores = mock(ObjectProvider.class);
		ObjectProvider<ObjectStore> objectStores = mock(ObjectProvider.class);
		when(stores.getIfAvailable()).thenReturn(store);
		when(objectStores.getIfAvailable()).thenReturn(objects);
		return new LooksController(stores, objectStores);
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
}
