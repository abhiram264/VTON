package com.aifashionvault.platform.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.avatar.AvatarException;
import com.aifashionvault.avatar.AvatarImage;
import com.aifashionvault.avatar.AvatarProblemCode;
import com.aifashionvault.avatar.AvatarProfile;
import com.aifashionvault.avatar.AvatarWorkflow;
import com.aifashionvault.platform.generation.GenerationAvatarInvalidation;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

@RestController
@RequestMapping("/avatars")
public class AvatarsController {

	private static final Duration DISPLAY_TTL = Duration.ofMinutes(5);

	private final ObjectProvider<AvatarWorkflow> workflows;
	private final ObjectStore mediaStore;
	private final ObjectProvider<GenerationAvatarInvalidation> invalidators;

	public AvatarsController(ObjectProvider<AvatarWorkflow> workflows, ObjectProvider<ObjectStore> mediaStores) {
		this(workflows, mediaStores, null);
	}

	@Autowired
	public AvatarsController(
		ObjectProvider<AvatarWorkflow> workflows,
		ObjectProvider<ObjectStore> mediaStores,
		ObjectProvider<GenerationAvatarInvalidation> invalidators
	) {
		this.workflows = workflows;
		ObjectStore resolved;
		try {
			resolved = mediaStores.getIfAvailable();
		}
		catch (NoUniqueBeanDefinitionException ex) {
			resolved = mediaStores.orderedStream().findFirst().orElse(null);
		}
		this.mediaStore = resolved;
		this.invalidators = invalidators;
	}

	@PostMapping
	public ResponseEntity<AvatarResourceResponse> create(
		@AuthenticationPrincipal UserId userId,
		@RequestBody AvatarUploadRequest request
	) {
		UploadId uploadId = uploadId(request);
		return resource(HttpStatus.CREATED, workflow().create(userId, uploadId));
	}

	@GetMapping
	public List<AvatarResourceResponse> list(@AuthenticationPrincipal UserId userId) {
		return workflow().list(userId).stream().map(this::toResource).toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<AvatarResourceResponse> get(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String id
	) {
		AvatarProfileId profileId = profileId(id);
		AvatarProfile profile = workflow().get(userId, profileId)
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND));
		return resource(HttpStatus.OK, profile);
	}

	@PatchMapping("/{id}")
	public ResponseEntity<AvatarResourceResponse> replace(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String id,
		@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
		@RequestBody AvatarUploadRequest request
	) {
		AvatarProfileId profileId = profileId(id);
		int revision = requireRevision(userId, profileId, ifMatch);
		return resource(HttpStatus.OK, workflow().replace(userId, profileId, revision, uploadId(request)));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String id,
		@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
	) {
		AvatarProfileId profileId = profileId(id);
		Integer revision = parseQuotedRevision(ifMatch);
		workflow().delete(userId, profileId, revision);
		GenerationAvatarInvalidation invalidator = invalidator();
		if (invalidator != null) {
			invalidator.afterAvatarDeleted(userId, profileId, Instant.now());
		}
	}

	@ExceptionHandler(AvatarException.class)
	ResponseEntity<AvatarProblemResponse> avatarProblem(AvatarException ex) {
		return problem(ex.code());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<AvatarProblemResponse> notReadable() {
		return problem(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<AvatarProblemResponse> illegalArgument() {
		return problem(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
	}

	private AvatarWorkflow workflow() {
		AvatarWorkflow workflow = this.workflows.getIfAvailable();
		if (workflow == null) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
		return workflow;
	}

	private GenerationAvatarInvalidation invalidator() {
		if (this.invalidators == null) {
			return null;
		}
		try {
			return this.invalidators.getIfAvailable();
		}
		catch (NoUniqueBeanDefinitionException ex) {
			return this.invalidators.orderedStream().findFirst().orElse(null);
		}
	}

	private int requireRevision(UserId userId, AvatarProfileId profileId, String ifMatch) {
		Integer revision = parseQuotedRevision(ifMatch);
		if (revision != null) {
			return revision;
		}
		if (workflow().get(userId, profileId).isEmpty()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
		}
		throw new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
	}

	private ResponseEntity<AvatarResourceResponse> resource(HttpStatus status, AvatarProfile profile) {
		AvatarResourceResponse body = toResource(profile);
		return ResponseEntity.status(status)
			.header(HttpHeaders.ETAG, "\"" + body.revision() + "\"")
			.body(body);
	}

	private AvatarResourceResponse toResource(AvatarProfile profile) {
		AvatarImage image = workflow().currentImage(profile)
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING));
		if (image.width() == null || image.height() == null) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
		if (this.mediaStore == null) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
		PresignedAccess access;
		try {
			access = this.mediaStore.presignGet(
				new ObjectRef(StorageDomain.AVATAR, image.storageKey().value()),
				DISPLAY_TTL);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException
				| ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
		return new AvatarResourceResponse(
			profile.id().value().toString(),
			image.id().value().toString(),
			profile.revision(),
			image.width(),
			image.height(),
			mediaType(image.storageKey().value()),
			access.uri().toString(),
			access.expiresAt(),
			image.warnings());
	}

	private static AvatarProfileId profileId(String id) {
		try {
			return new AvatarProfileId(UUID.fromString(id));
		}
		catch (IllegalArgumentException ex) {
			throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
		}
	}

	private static UploadId uploadId(AvatarUploadRequest request) {
		try {
			return new UploadId(UUID.fromString(request.uploadId().strip()));
		}
		catch (IllegalArgumentException ex) {
			throw new AvatarException(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
		}
	}

	private static Integer parseQuotedRevision(String ifMatch) {
		if (ifMatch == null) {
			return null;
		}
		String stripped = ifMatch.strip();
		if (stripped.length() < 3 || stripped.charAt(0) != '"' || stripped.charAt(stripped.length() - 1) != '"') {
			return null;
		}
		String inner = stripped.substring(1, stripped.length() - 1);
		if (inner.isEmpty()) {
			return null;
		}
		for (int i = 0; i < inner.length(); i++) {
			if (!Character.isDigit(inner.charAt(i))) {
				return null;
			}
		}
		try {
			return Integer.parseInt(inner);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String mediaType(String storageKey) {
		return storageKey.endsWith(".png") ? "image/png" : "image/jpeg";
	}

	private static ResponseEntity<AvatarProblemResponse> problem(AvatarProblemCode code) {
		return ResponseEntity.status(statusFor(code))
			.contentType(MediaType.APPLICATION_JSON)
			.body(new AvatarProblemResponse(code.name()));
	}

	private static HttpStatus statusFor(AvatarProblemCode code) {
		return switch (code) {
			case AVATAR_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case AVATAR_STALE_REVISION -> HttpStatus.PRECONDITION_FAILED;
			case AVATAR_CONFLICT -> HttpStatus.CONFLICT;
			case AVATAR_MEDIA_MISSING -> HttpStatus.SERVICE_UNAVAILABLE;
			case AVATAR_PREFLIGHT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case AVATAR_UPLOAD_NOT_CLAIMABLE -> HttpStatus.BAD_REQUEST;
			case AVATAR_UNREADABLE,
				AVATAR_UNSUPPORTED_FORMAT,
				AVATAR_TYPE_MISMATCH,
				AVATAR_RESOLUTION_INSUFFICIENT,
				AVATAR_BYTES_EXCEEDED,
				AVATAR_DIMENSIONS_EXCEEDED,
				AVATAR_PIXELS_EXCEEDED,
				AVATAR_ASPECT_RATIO,
				AVATAR_TOO_BLURRED,
				AVATAR_TOO_DARK,
				AVATAR_PERSON_NOT_SINGLE,
				AVATAR_FACE_NOT_VISIBLE,
				AVATAR_BODY_NOT_VISIBLE -> HttpStatus.UNPROCESSABLE_CONTENT;
		};
	}
}
