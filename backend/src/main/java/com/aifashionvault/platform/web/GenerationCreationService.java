package com.aifashionvault.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aifashionvault.generation.CoarseGenerationPhase;
import com.aifashionvault.generation.CreateSingleItemGenerationCommand;
import com.aifashionvault.generation.FrozenSingleItemIntent;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.MediaAssetId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public class GenerationCreationService {

	static final String MISSING_IDEMPOTENCY_KEY = "IDEMPOTENCY_KEY_REQUIRED";
	static final String MALFORMED_REQUEST = "PRODUCT_UPLOAD_INVALID";
	static final String NOT_FOUND = "UNAUTHORIZED_OR_NOT_FOUND";
	static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_REUSED";
	static final String MY_MODEL_MISSING = "MY_MODEL_MISSING";
	static final String MY_MODEL_MEDIA_MISSING = "MY_MODEL_MEDIA_MISSING";
	static final String PRODUCT_UPLOAD_MISSING = "PRODUCT_UPLOAD_MISSING";
	static final String PRODUCT_UPLOAD_ALREADY_CLAIMED = "PRODUCT_UPLOAD_ALREADY_CLAIMED";
	static final String GENERATION_NOT_CANCELLABLE = "GENERATION_NOT_CANCELLABLE";
	static final String ADMISSION_PAUSED = "ADMISSION_PAUSED";
	static final String CREATE_OUTCOME_UNKNOWN = "CREATE_OUTCOME_UNKNOWN";
	static final String GENERATION_MEDIA_UNAVAILABLE = "GENERATION_MEDIA_UNAVAILABLE";
	static final String GENERATION_PROVIDER_UNAVAILABLE = "GENERATION_PROVIDER_UNAVAILABLE";

	/**
	 * Upper bound on the recovery list. The bound is a response-size guard, not a
	 * quota: it keeps one owner's history from growing a single response without
	 * limit. Recovery only needs the most recent window.
	 */
	static final int MAX_RECENT_GENERATIONS = 50;

    private final GenerationStore store;
    private final JdbcTemplate jdbc;
    private final GenerationApiProperties properties;
    private final Clock clock;
    private final GenerationMediaStore mediaStore;
    private final com.aifashionvault.admission.GenerationRateLimiter rateLimiter;
    private final com.aifashionvault.platform.generation.GenerationMetrics metrics;
    private final boolean providerAvailable;

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore) {
        this(store, jdbc, properties, clock, mediaStore, null, null);
    }

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore, com.aifashionvault.admission.GenerationRateLimiter rateLimiter) {
        this(store, jdbc, properties, clock, mediaStore, rateLimiter, null);
    }

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore, com.aifashionvault.admission.GenerationRateLimiter rateLimiter,
        com.aifashionvault.platform.generation.GenerationMetrics metrics) {
        this(store, jdbc, properties, clock, mediaStore, rateLimiter, metrics, true);
    }

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore, com.aifashionvault.admission.GenerationRateLimiter rateLimiter,
        com.aifashionvault.platform.generation.GenerationMetrics metrics, boolean providerAvailable) {
        this.store = store;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.mediaStore = mediaStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.providerAvailable = providerAvailable;
    }

    public GenerationResponse create(UserId userId, String idempotencyKey, GenerationRequest request) {
        return create(userId, idempotencyKey, request, null, null);
    }

    public GenerationResponse create(UserId userId, String idempotencyKey, GenerationRequest request, String deviceId, String ipAddress) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw problem(HttpStatus.BAD_REQUEST, MISSING_IDEMPOTENCY_KEY);
        if (idempotencyKey.strip().length() > 255) throw problem(HttpStatus.BAD_REQUEST, MISSING_IDEMPOTENCY_KEY);
        if (request == null || request.productUploadId() == null) throw problem(HttpStatus.BAD_REQUEST, PRODUCT_UPLOAD_MISSING);
        if (properties.isAdmissionPaused()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, ADMISSION_PAUSED);
        if (!providerAvailable) throw problem(HttpStatus.SERVICE_UNAVAILABLE, GENERATION_PROVIDER_UNAVAILABLE);
        ResolvedAvatar avatar = resolveCurrentAvatar(userId);
        String key = idempotencyKey.strip();
        String fingerprint = fingerprint(userId, avatar.imageId(), request.productUploadId());
        for (GenerationJob existing : store.listJobsByUser(userId)) {
            if (existing.clientRequestId() != null && key.equals(existing.clientRequestId().value())) {
                if (!fingerprint.equals(existing.requestFingerprint())) throw problem(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_CONFLICT);
                return toResponse(existing);
            }
        }
        if (mediaStore == null) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, GENERATION_MEDIA_UNAVAILABLE);
        }
        Instant now = clock.instant();
        UUID generationId = UUID.randomUUID();
        MediaAssetId productMediaId = new MediaAssetId(UUID.randomUUID());
		ProductInputClaimCommand claimCommand = new ProductInputClaimCommand(
			userId, new UploadId(request.productUploadId()), new GenerationJobId(generationId), productMediaId);
		preflight(claimCommand);
		com.aifashionvault.admission.GenerationRateLimiter.Reservation reservation = null;
		if (rateLimiter != null) {
			String safeDevice = deviceId == null || deviceId.isBlank() ? "http-session" : deviceId;
			String safeIp = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
			reservation = rateLimiter.reserve(new com.aifashionvault.admission.GenerationAdmissionRequest(userId, safeDevice, safeIp,
				com.aifashionvault.admission.GenerationRiskTier.ORDINARY, clock.instant()));
			if (!reservation.decision().allowed()) throw problem(HttpStatus.TOO_MANY_REQUESTS, reservation.decision().reason());
		}
		boolean claimed = false;
		boolean accepted = false;
		try {
			ClaimResult claim = mediaStore.claimProductInput(claimCommand);
			if (claim instanceof ClaimResult.Conflict) throw problem(HttpStatus.CONFLICT, PRODUCT_UPLOAD_ALREADY_CLAIMED);
			claimed = claim instanceof ClaimResult.Copied;
			GenerationJob job = new GenerationJob(
				new GenerationJobId(generationId), userId, null, GenerationJob.QUEUED, "STANDARD",
				new ClientRequestId(key), now, null, avatar.imageId(), List.of(), null, null,
				fingerprint, avatar.revision(), 0, now, null, 1, 1, 1,
				now.plus(GenerationJob.ORCHESTRATION_DEADLINE), null,
				RetryPolicySnapshot.forStepCountV2(1), Optional.empty());
			CreateSingleItemGenerationCommand command = new CreateSingleItemGenerationCommand(userId, new UploadId(request.productUploadId()), key);
			FrozenSingleItemIntent intent = new FrozenSingleItemIntent(job.id(), userId, avatar.imageId(), productMediaId, 1, 1);
			GenerationResponse response = switch (store.createSingleItem(command, intent, job)) {
				case GenerationStore.CreateSingleItemResult.Created created -> {
					if (reservation != null) {
						rateLimiter.commit(reservation);
						reservation = null;
					}
					claimed = false;
					if (metrics != null) {
						metrics.recordGenerationCount();
					}
					accepted = true;
					yield toResponse(created.job());
				}
				case GenerationStore.CreateSingleItemResult.Replayed replayed -> {
					RuntimeException cleanup = null;
					if (claimed && mediaStore != null) {
						claimed = false;
						try { mediaStore.releaseProductInput(claimCommand); }
						catch (RuntimeException compensation) { cleanup = compensation; }
					}
					if (reservation != null) {
						com.aifashionvault.admission.GenerationRateLimiter.Reservation replayReservation = reservation;
						reservation = null;
						try { rateLimiter.release(replayReservation); }
						catch (RuntimeException compensation) {
							if (cleanup == null) cleanup = compensation;
							else cleanup.addSuppressed(compensation);
						}
					}
					if (cleanup != null) throw cleanup;
					yield toResponse(replayed.job());
				}
				case GenerationStore.CreateSingleItemResult.FingerprintConflict ignored -> throw problem(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_CONFLICT);
				case GenerationStore.CreateSingleItemResult.ProductUploadAlreadyClaimed ignored -> throw problem(HttpStatus.CONFLICT, PRODUCT_UPLOAD_ALREADY_CLAIMED);
			};
			return response;
		}
		catch (RuntimeException failure) {
			if (accepted) {
				throw failure;
			}
			boolean durableJobExists;
			try {
				durableJobExists = durableJobExists(userId, key, fingerprint);
			}
			catch (RuntimeException lookupEx) {
				Problem unknown = problem(HttpStatus.SERVICE_UNAVAILABLE, CREATE_OUTCOME_UNKNOWN);
				unknown.addSuppressed(lookupEx);
				unknown.initCause(failure);
				throw unknown;
			}
			if (durableJobExists) {
				Problem unknown = problem(HttpStatus.SERVICE_UNAVAILABLE, CREATE_OUTCOME_UNKNOWN);
				unknown.initCause(failure);
				throw unknown;
			}
			if (claimed) releaseClaim(claimCommand, failure);
			if (reservation != null) releaseReservation(reservation, failure);
			throw failure;
		}
 	}

	private void preflight(ProductInputClaimCommand command) {
		if (mediaStore == null) {
			throw problem(HttpStatus.SERVICE_UNAVAILABLE, GENERATION_MEDIA_UNAVAILABLE);
		}
		try { mediaStore.preflightProductInput(command); }
		catch (ObjectStoreExceptions.StagedObjectMissingException ex) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, PRODUCT_UPLOAD_MISSING); }
		catch (ObjectStoreExceptions.ImageValidationException | IllegalArgumentException ex) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, MALFORMED_REQUEST); }
	}

	private void releaseClaim(ProductInputClaimCommand command, RuntimeException failure) {
		if (mediaStore == null) return;
		try { mediaStore.releaseProductInput(command); }
		catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
	}

	private void releaseReservation(com.aifashionvault.admission.GenerationRateLimiter.Reservation reservation, RuntimeException failure) {
		try { rateLimiter.release(reservation); }
		catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
	}

	private boolean durableJobExists(UserId userId, String key, String fingerprint) {
		for (GenerationJob existing : store.listJobsByUser(userId)) {
			if (existing.clientRequestId() != null
					&& key.equals(existing.clientRequestId().value())
					&& fingerprint.equals(existing.requestFingerprint())) {
				return true;
			}
		}
		return false;
	}

	public GenerationResponse get(UserId userId, String generationId) { return toResponse(requireOwnedJob(userId, generationId)); }

	/**
	 * Recent generations for one owner, newest first and bounded.
	 *
	 * <p>This is the server-backed recovery surface the client needs: after a
	 * process restart the app must rebuild its in-flight/recent state from the
	 * server, not from a process-local variable that died with the process.
	 * Owner scoping comes from the store query, so a caller can only ever see
	 * its own rows.</p>
	 */
	public List<GenerationResponse> list(UserId userId) {
		if (userId == null) {
			throw problem(HttpStatus.UNAUTHORIZED, NOT_FOUND);
		}
		List<GenerationJob> jobs = store.listJobsByUser(userId);
		int from = Math.max(0, jobs.size() - MAX_RECENT_GENERATIONS);
		List<GenerationResponse> recent = new java.util.ArrayList<>(jobs.size() - from);
		for (int index = jobs.size() - 1; index >= from; index--) {
			recent.add(toResponse(jobs.get(index)));
		}
		return List.copyOf(recent);
	}

	public GenerationResponse cancel(UserId userId, String generationId) {
		GenerationJob job = requireOwnedJob(userId, generationId);
		if (GenerationJob.FAILED.equals(job.status()) && "CANCELLED_BY_USER".equals(job.terminalReason())) {
			return toResponse(job);
		}
		if (!GenerationJob.ACTIVE_STATUSES.contains(job.status())) throw problem(HttpStatus.CONFLICT, GENERATION_NOT_CANCELLABLE);
		store.cancelIfActive(job.id(), clock.instant());
		return toResponse(requireOwnedJob(userId, generationId));
	}

	private ResolvedAvatar resolveCurrentAvatar(UserId userId) {
		List<ResolvedAvatar> profiles = jdbc.query(
			"SELECT p.primary_image_id, p.revision FROM avatar_profile p WHERE p.user_id = ? AND p.status = 'ACTIVE'",
			(rs, row) -> new ResolvedAvatar(rs.getObject("primary_image_id", UUID.class), rs.getInt("revision")), userId.value());
		if (profiles.isEmpty()) throw problem(HttpStatus.NOT_FOUND, MY_MODEL_MISSING);
		ResolvedAvatar avatar = profiles.getFirst();
		if (avatar.imageId() == null) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, MY_MODEL_MEDIA_MISSING);
		Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM avatar_image WHERE id = ?", Integer.class, avatar.imageId().value());
		if (exists == null || exists != 1) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, MY_MODEL_MEDIA_MISSING);
		return avatar;
	}

	private GenerationJob requireOwnedJob(UserId userId, String id) {
		try { return store.findJobForUser(new GenerationJobId(UUID.fromString(id)), userId).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, NOT_FOUND)); }
		catch (IllegalArgumentException ex) { throw problem(HttpStatus.NOT_FOUND, NOT_FOUND); }
	}

	private GenerationResponse toResponse(GenerationJob job) {
		String lookId = null;
		if (GenerationJob.COMPLETED.equals(job.status())) {
			List<UUID> ids = jdbc.query("SELECT id FROM look WHERE generation_job_id = ? AND user_id = ?", (rs, row) -> rs.getObject("id", UUID.class), job.id().value(), job.userId().value());
			if (!ids.isEmpty()) lookId = ids.getFirst().toString();
		}
		String failure = GenerationJob.FAILED.equals(job.status()) ? job.terminalReason() : null;
		return new GenerationResponse(job.id().value(), com.aifashionvault.generation.GenerationStatus.valueOf(job.status()),
			CoarseGenerationPhase.fromDurable(job.status(), job.terminalReason()), job.statusVersion(), job.createdAt(), job.updatedAt(), lookId, failure);
	}


	private static String fingerprint(UserId userId, AvatarImageId avatar, UUID product) { return userId.value()+"\\n"+avatar.value()+"\\n"+product; }
	private static Problem problem(HttpStatus status, String code) { return new Problem(status, code); }
	private record ResolvedAvatar(UUID imageUuid, int revision) { AvatarImageId imageId() { return imageUuid == null ? null : new AvatarImageId(imageUuid); } }
	public static final class Problem extends RuntimeException { private final HttpStatus status; private final String code; Problem(HttpStatus s,String c){super(c);status=s;code=c;} public HttpStatus status(){return status;} public String code(){return code;} }
}
