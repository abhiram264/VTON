package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.aifashionvault.generation.CoarseGenerationPhase;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStatus;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.RenderPlan;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;

@RestController
@RequestMapping("/looks")
public class LooksController {

	private static final Duration READ_TTL = Duration.ofMinutes(5);
	private static final String NOT_FOUND = "UNAUTHORIZED_OR_NOT_FOUND";
	private static final String NOT_REGENERATABLE = "LOOK_NOT_REGENERATABLE";
	private static final String MISSING_KEY = "IDEMPOTENCY_KEY_REQUIRED";
	private static final String KEY_CONFLICT = "IDEMPOTENCY_KEY_REUSED";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final JdbcLookStore looks;
	private final ObjectStore objects;
	private final GenerationStore generations;
	private final Clock clock;

	public LooksController(
		ObjectProvider<JdbcLookStore> lookStores,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<GenerationStore> generationStores,
		ObjectProvider<Clock> clocks
	) {
		this.looks = first(lookStores);
		this.objects = first(objectStores);
		this.generations = first(generationStores);
		Clock configured = first(clocks);
		this.clock = configured == null ? Clock.systemUTC() : configured;
	}

	@GetMapping
	public List<LookResource> list(@AuthenticationPrincipal UserId ownerId) {
		return store().listForUser(ownerId).stream().map(this::toResource).toList();
	}

	@GetMapping("/{id}")
	public LookResource get(@AuthenticationPrincipal UserId ownerId, @PathVariable String id) {
		return toResource(owned(ownerId, id));
	}

	@PostMapping("/{id}/share-link")
	public ReadCapability share(@AuthenticationPrincipal UserId ownerId, @PathVariable String id) {
		return capability(owned(ownerId, id));
	}

	@PostMapping("/{id}/regenerate")
	public ResponseEntity<GenerationResponse> regenerate(
		@AuthenticationPrincipal UserId ownerId,
		@PathVariable String id,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
	) {
		String key = validKey(idempotencyKey);
		Look look = owned(ownerId, id);
		GenerationStore generationStore = generationStore();
		GenerationJob source = generationStore.findJobForUser(look.generationJobId(), ownerId)
			.orElseThrow(NotRegeneratable::new);
		RenderPlan plan = generationStore.findRenderPlan(source.id()).orElseThrow(NotRegeneratable::new);
		if (!consistent(look, source, plan)) {
			throw new NotRegeneratable();
		}

		Instant now = clock.instant();
		String fingerprint = ownerId.value() + "\nlook:" + look.id().value();
		RetryPolicySnapshot policy = source.retryPolicySnapshot() == null
			? RetryPolicySnapshot.forStepCountV2(source.totalSteps())
			: source.retryPolicySnapshot();
		GenerationJob requested = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()), ownerId, source.studioSessionId(), GenerationJob.QUEUED,
			source.requestedMode(), new ClientRequestId(key), now, null, source.avatarImageId(),
			source.productVaultItemIds(), null, null, fingerprint, source.avatarRevision(), 0, now, null,
			1, source.totalSteps(), source.renderPlanVersion(), now.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null, policy, Optional.of(source.id()));
		GenerationJob accepted = switch (generationStore.createAccepted(requested)) {
			case GenerationStore.CreateAcceptedResult.Created created -> created.job();
			case GenerationStore.CreateAcceptedResult.Replayed replayed -> replayed.job();
			case GenerationStore.CreateAcceptedResult.FingerprintConflict ignored -> throw new KeyConflict();
			case GenerationStore.CreateAcceptedResult.QuotaRejected rejected -> throw new AdmissionRejected(rejected.reason());
		};
		GenerationResponse body = response(accepted);
		return ResponseEntity.accepted()
			.location(URI.create("/generations/" + body.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(body);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserId ownerId, @PathVariable String id) {
		LookId lookId = lookId(id);
		Look look = store().findByIdForCleanup(lookId, ownerId).orElseThrow(NotFound::new);
		if (!store().logicallyDeleteForUser(lookId, ownerId)) {
			throw new NotFound();
		}
		try {
			objectStore().delete(new ObjectRef(StorageBoundary.LOOK, look.storageKey().value()));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			// Absence is the converged cleanup state.
		}
	}

	@ExceptionHandler(NotFound.class)
	ResponseEntity<GenerationProblemResponse> notFound() {
		return problem(HttpStatus.NOT_FOUND, NOT_FOUND);
	}

	@ExceptionHandler(NotRegeneratable.class)
	ResponseEntity<GenerationProblemResponse> notRegeneratable() {
		return problem(HttpStatus.CONFLICT, NOT_REGENERATABLE);
	}

	@ExceptionHandler(MissingKey.class)
	ResponseEntity<GenerationProblemResponse> missingKey() {
		return problem(HttpStatus.BAD_REQUEST, MISSING_KEY);
	}

	@ExceptionHandler(KeyConflict.class)
	ResponseEntity<GenerationProblemResponse> keyConflict() {
		return problem(HttpStatus.CONFLICT, KEY_CONFLICT);
	}

	@ExceptionHandler(AdmissionRejected.class)
	ResponseEntity<GenerationProblemResponse> admissionRejected(AdmissionRejected ex) {
		return problem(HttpStatus.TOO_MANY_REQUESTS, ex.reason);
	}

	private Look owned(UserId ownerId, String id) {
		Look look = store().findByIdForUser(lookId(id), ownerId).orElseThrow(NotFound::new);
		if (look.generationJobId() == null) {
			throw new NotFound();
		}
		return look;
	}

	private LookResource toResource(Look look) {
		ReadCapability image = capability(look);
		return new LookResource(
			look.id().value(),
			look.avatarProfileId() == null ? null : look.avatarProfileId().value(),
			look.generationJobId().value(),
			look.studioSessionId() == null ? null : look.studioSessionId().value(),
			json(look.renderPlan()),
			look.provider(),
			look.providerModel(),
			look.qualityStatus(),
			look.createdAt(),
			look.items().stream().map(item -> new LookItemResource(item.vaultItemId().value(), item.slot())).toList(),
			image);
	}

	private ReadCapability capability(Look look) {
		try {
			PresignedAccess access = objectStore().presignGet(
				new ObjectRef(StorageBoundary.LOOK, look.storageKey().value()), READ_TTL);
			return new ReadCapability(access.uri(), access.expiresAt());
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
			throw new NotFound();
		}
	}

	private static JsonNode json(String value) {
		if (value == null) {
			return null;
		}
		try {
			return JSON.readTree(value);
		}
		catch (JacksonException ex) {
			throw new NotFound();
		}
	}

	private static boolean consistent(Look look, GenerationJob source, RenderPlan plan) {
		if (source.avatarImageId() == null || source.userId() == null || plan.version() != source.renderPlanVersion()
			|| plan.steps().size() != source.totalSteps()) {
			return false;
		}
		List<UUID> persistedItems = look.items().stream().map(item -> item.vaultItemId().value()).sorted().toList();
		List<UUID> sourceItems = source.productVaultItemIds().stream().map(item -> item.value()).sorted().toList();
		return persistedItems.isEmpty() || persistedItems.equals(sourceItems);
	}

	private static GenerationResponse response(GenerationJob job) {
		return new GenerationResponse(job.id().value(), GenerationStatus.valueOf(job.status()),
			CoarseGenerationPhase.fromDurable(job.status(), job.terminalReason()), job.statusVersion(),
			job.createdAt(), job.updatedAt(), null, null);
	}

	private JdbcLookStore store() {
		if (looks == null) throw new NotFound();
		return looks;
	}

	private ObjectStore objectStore() {
		if (objects == null) throw new NotFound();
		return objects;
	}

	private GenerationStore generationStore() {
		if (generations == null) throw new NotRegeneratable();
		return generations;
	}

	private static String validKey(String value) {
		if (value == null || value.isBlank()) throw new MissingKey();
		String key = value.strip();
		if (key.length() > 255) throw new MissingKey();
		return key;
	}

	private static LookId lookId(String value) {
		try {
			return new LookId(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			throw new NotFound();
		}
	}

	private static ResponseEntity<GenerationProblemResponse> problem(HttpStatus status, String code) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
			.body(new GenerationProblemResponse(code));
	}

	private static <T> T first(ObjectProvider<T> provider) {
		try {
			return provider.getIfAvailable();
		}
		catch (NoUniqueBeanDefinitionException ex) {
			return provider.orderedStream().findFirst().orElse(null);
		}
	}

	private static final class NotFound extends RuntimeException {}
	private static final class NotRegeneratable extends RuntimeException {}
	private static final class MissingKey extends RuntimeException {}
	private static final class KeyConflict extends RuntimeException {}
	private static final class AdmissionRejected extends RuntimeException {
		private final String reason;
		private AdmissionRejected(String reason) { this.reason = reason; }
	}
}
