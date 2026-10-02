package com.aifashionvault.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.RenderPlan;
import com.aifashionvault.generation.RenderStep;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C19: every generation entry point passes through the SAME admission policy.
 *
 * <p>{@code POST /generations} refuses to create a job when the server is globally paused
 * ({@code vault.generation.admission-paused}) or when no image provider key is configured
 * ({@link GenerationCreationService}). {@code POST /looks/{id}/regenerate} enqueues the
 * same provider-cost job, so it must refuse for the same two reasons, with the same status
 * (503) and the same problem codes, and must not create a job when it refuses.
 */
class LooksControllerAdmissionPolicyTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final LookId LOOK_ID = new LookId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final StorageKey KEY = new StorageKey("users/11111111-1111-1111-1111-111111111111/looks/result.jpg");

	private static final class Fixture {
		final JdbcLookStore looks = mock(JdbcLookStore.class);
		final ObjectStore objects = mock(ObjectStore.class);
		final GenerationStore generations = mock(GenerationStore.class);
		final LooksController controller;

		Fixture(boolean admissionPaused, boolean providerAvailable) {
			GenerationApiProperties properties = new GenerationApiProperties();
			properties.setAdmissionPaused(admissionPaused);
			this.controller = new LooksController(providers(looks), providers(objects), providers(generations),
				providers(Clock.fixed(NOW, ZoneOffset.UTC)), null, properties, providerAvailable);
		}
	}

	@Test
	void pausedServerRefusesRegenerateWithServiceUnavailableAndCreatesNoJob() {
		Fixture fixture = new Fixture(true, true);

		LooksController.AdmissionRejected rejected = catchAdmissionRejected(fixture.controller);

		ResponseEntity<GenerationProblemResponse> response = fixture.controller.admissionRejected(rejected);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody().code()).isEqualTo(GenerationCreationService.ADMISSION_PAUSED);
		verify(fixture.generations, never()).createAccepted(any());
		// The verdict precedes the look lookup: it cannot depend on which look was named.
		verify(fixture.looks, never()).findByIdForUser(any(), any());
	}

	@Test
	void missingProviderKeyRefusesRegenerateWithServiceUnavailableAndCreatesNoJob() {
		Fixture fixture = new Fixture(false, false);

		LooksController.AdmissionRejected rejected = catchAdmissionRejected(fixture.controller);

		ResponseEntity<GenerationProblemResponse> response = fixture.controller.admissionRejected(rejected);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody().code()).isEqualTo(GenerationCreationService.GENERATION_PROVIDER_UNAVAILABLE);
		verify(fixture.generations, never()).createAccepted(any());
		verify(fixture.looks, never()).findByIdForUser(any(), any());
	}

	/**
	 * Ordering parity with POST /generations: the idempotency key is validated before the
	 * admission policy, so a keyless request is a 400 even while the server is paused.
	 */
	@Test
	void missingIdempotencyKeyStillPrecedesAdmissionPolicy() {
		Fixture fixture = new Fixture(true, false);

		assertThatThrownBy(() -> fixture.controller.regenerate(OWNER, LOOK_ID.value().toString(), null))
			.isNotInstanceOf(LooksController.AdmissionRejected.class);
		verify(fixture.generations, never()).createAccepted(any());
	}

	/**
	 * The status change that carried the pause/provider codes must not have reclassified
	 * the pre-existing quota verdict, which stays a 429.
	 */
	@Test
	void quotaRejectionStillAnswersTooManyRequests() {
		Fixture fixture = new Fixture(false, true);

		ResponseEntity<GenerationProblemResponse> response = fixture.controller.admissionRejected(
			new LooksController.AdmissionRejected("USER_DAILY_LIMIT"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().code()).isEqualTo("USER_DAILY_LIMIT");
	}

	/** The gate must not over-block: an unpaused server with a provider still accepts. */
	@Test
	void unpausedServerWithProviderStillAcceptsRegenerate() {
		Fixture fixture = new Fixture(false, true);
		AvatarImageId avatar = new AvatarImageId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
		when(fixture.looks.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(fixture.generations.findJobForUser(JOB_ID, OWNER)).thenReturn(Optional.of(sourceJob(avatar)));
		when(fixture.generations.findRenderPlan(JOB_ID)).thenReturn(Optional.of(
			new RenderPlan(1, List.of(new RenderStep(UUID.randomUUID(), 1, null, List.of())))));
		when(fixture.generations.createAccepted(any())).thenAnswer(invocation ->
			new GenerationStore.CreateAcceptedResult.Created(invocation.getArgument(0)));

		ResponseEntity<GenerationResponse> response = fixture.controller
			.regenerate(OWNER, LOOK_ID.value().toString(), "replay-key");

		assertThat(response.getStatusCode().value()).isEqualTo(202);
		verify(fixture.generations).createAccepted(any());
	}

	/**
	 * C19 acceptance: a server-side global pause blocks NEW admission while history
	 * and deletion remain usable. The tests above prove the blocking half; this proves
	 * the other half — a paused server must still let an owner read their existing Looks
	 * and delete them, otherwise the pause would strand a user's data.
	 */
	@Test
	void pausedServerStillServesHistoryAndDeletion() {
		Fixture fixture = new Fixture(true, true);
		when(fixture.looks.listForUser(OWNER)).thenReturn(List.of(look()));
		when(fixture.looks.findByIdForUser(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(fixture.looks.findByIdForCleanup(LOOK_ID, OWNER)).thenReturn(Optional.of(look()));
		when(fixture.looks.logicallyDeleteForUser(LOOK_ID, OWNER)).thenReturn(true);
		when(fixture.objects.presignGet(any(), any())).thenReturn(new com.aifashionvault.platform.objectstore.PresignedAccess(
			new com.aifashionvault.platform.objectstore.ObjectRef(
				com.aifashionvault.shared.StorageDomain.LOOK, KEY.value()),
			"GET",
			java.net.URI.create("https://objects.invalid/look.jpg"),
			java.util.Map.of(),
			NOW.plusSeconds(300)));

		// History: the list and the single-Look read both still work while paused.
		assertThat(fixture.controller.list(OWNER)).hasSize(1);
		assertThat(fixture.controller.get(OWNER, LOOK_ID.value().toString()).id())
			.isEqualTo(LOOK_ID.value());

		// Deletion: the owner can still remove their Look while paused.
		fixture.controller.delete(OWNER, LOOK_ID.value().toString());
		verify(fixture.looks).logicallyDeleteForUser(LOOK_ID, OWNER);

		// And none of that created a chargeable job.
		verify(fixture.generations, never()).createAccepted(any());
	}

	private static LooksController.AdmissionRejected catchAdmissionRejected(LooksController controller) {
		try {
			controller.regenerate(OWNER, LOOK_ID.value().toString(), "replay-key");
			throw new AssertionError("regenerate was admitted while the admission policy blocked it");
		}
		catch (LooksController.AdmissionRejected rejected) {
			return rejected;
		}
	}

	private static <T> ObjectProvider<T> providers(T value) {
		@SuppressWarnings("unchecked")
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(value);
		return provider;
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
