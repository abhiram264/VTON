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

    private final GenerationStore store;
    private final JdbcTemplate jdbc;
    private final GenerationApiProperties properties;
    private final Clock clock;
    private final GenerationMediaStore mediaStore;
    private final com.aifashionvault.admission.GenerationRateLimiter rateLimiter;

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore) {
        this(store, jdbc, properties, clock, mediaStore, null);
    }

    public GenerationCreationService(GenerationStore store, JdbcTemplate jdbc, GenerationApiProperties properties, Clock clock,
        GenerationMediaStore mediaStore, com.aifashionvault.admission.GenerationRateLimiter rateLimiter) {
        this.store = store;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.mediaStore = mediaStore;
        this.rateLimiter = rateLimiter;
    }

    public GenerationResponse create(UserId userId, String idempotencyKey, GenerationRequest request) {
        return create(userId, idempotencyKey, request, null, null);
    }

    public GenerationResponse create(UserId userId, String idempotencyKey, GenerationRequest request, String deviceId, String ipAddress) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw problem(HttpStatus.BAD_REQUEST, MISSING_IDEMPOTENCY_KEY);
        if (idempotencyKey.strip().length() > 255) throw problem(HttpStatus.BAD_REQUEST, MISSING_IDEMPOTENCY_KEY);
        if (request == null || request.productUploadId() == null) throw problem(HttpStatus.BAD_REQUEST, PRODUCT_UPLOAD_MISSING);
        if (properties.isAdmissionPaused()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, ADMISSION_PAUSED);
        ResolvedAvatar avatar = resolveCurrentAvatar(userId);
        String key = idempotencyKey.strip();
        String fingerprint = fingerprint(userId, avatar.imageId(), request.productUploadId());
        for (GenerationJob existing : store.listJobsByUser(userId)) {
            if (existing.clientRequestId() != null && key.equals(existing.clientRequestId().value())) {
                if (!fingerprint.equals(existing.requestFingerprint())) throw problem(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_CONFLICT);
                return toResponse(existing);
            }
        }
        Instant now = clock.instant();
        UUID generationId = UUID.randomUUID();
        MediaAssetId productMediaId = new MediaAssetId(UUID.randomUUID());
        try {
            ClaimResult claim = mediaStore.claimProductInput(new ProductInputClaimCommand(
                userId, new UploadId(request.productUploadId()), new GenerationJobId(generationId), productMediaId));
            if (claim instanceof ClaimResult.Conflict) throw problem(HttpStatus.CONFLICT, PRODUCT_UPLOAD_ALREADY_CLAIMED);
        }
        catch (ObjectStoreExceptions.StagedObjectMissingException ex) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, PRODUCT_UPLOAD_MISSING); }
        catch (ObjectStoreExceptions.ImageValidationException | IllegalArgumentException ex) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, MALFORMED_REQUEST); }
        if (rateLimiter != null) {
            String safeDevice = deviceId == null || deviceId.isBlank() ? "http-session" : deviceId;
            String safeIp = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress;
            var decision = rateLimiter.admit(new com.aifashionvault.admission.GenerationAdmissionRequest(userId, safeDevice, safeIp,
                com.aifashionvault.admission.GenerationRiskTier.ORDINARY, clock.instant()));
            if (!decision.allowed()) throw problem(HttpStatus.TOO_MANY_REQUESTS, decision.reason());
        }
        GenerationJob job = new GenerationJob(
            new GenerationJobId(generationId), userId, null, GenerationJob.QUEUED, "STANDARD",
            new ClientRequestId(key), now, null, avatar.imageId(), List.of(), null, null,
            fingerprint, avatar.revision(), 0, now, null, 1, 1, 1,
            now.plus(GenerationJob.ORCHESTRATION_DEADLINE), null,
            RetryPolicySnapshot.forStepCountV2(1), Optional.empty());
        CreateSingleItemGenerationCommand command = new CreateSingleItemGenerationCommand(userId, new UploadId(request.productUploadId()), key);
        FrozenSingleItemIntent intent = new FrozenSingleItemIntent(job.id(), userId, avatar.imageId(), productMediaId, 1, 1);
        return switch (store.createSingleItem(command, intent, job)) {
            case GenerationStore.CreateSingleItemResult.Created created -> toResponse(created.job());
            case GenerationStore.CreateSingleItemResult.Replayed replayed -> toResponse(replayed.job());
            case GenerationStore.CreateSingleItemResult.FingerprintConflict ignored -> throw problem(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_CONFLICT);
            case GenerationStore.CreateSingleItemResult.ProductUploadAlreadyClaimed ignored -> throw problem(HttpStatus.CONFLICT, PRODUCT_UPLOAD_ALREADY_CLAIMED);
        };
    }

	public GenerationResponse get(UserId userId, String generationId) { return toResponse(requireOwnedJob(userId, generationId)); }

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
