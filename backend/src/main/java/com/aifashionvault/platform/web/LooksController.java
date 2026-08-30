package com.aifashionvault.platform.web;

import java.time.Duration;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;

@RestController
@RequestMapping("/looks")
public class LooksController {

	private static final Duration READ_TTL = Duration.ofMinutes(5);
	private static final String NOT_FOUND = "UNAUTHORIZED_OR_NOT_FOUND";

	private final JdbcLookStore looks;
	private final ObjectStore objects;

	public LooksController(ObjectProvider<JdbcLookStore> lookStores, ObjectProvider<ObjectStore> objectStores) {
		this.looks = first(lookStores);
		this.objects = first(objectStores);
	}

	@GetMapping("/{id}")
	public LookResource get(@AuthenticationPrincipal UserId ownerId, @PathVariable String id) {
		Look look = store().findByIdForUser(lookId(id), ownerId).orElseThrow(NotFound::new);
		if (look.generationJobId() == null) {
			throw new NotFound();
		}
		PresignedAccess access;
		try {
			access = objectStore().presignGet(
				new ObjectRef(StorageBoundary.LOOK, look.storageKey().value()),
				READ_TTL);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
			throw new NotFound();
		}
		return new LookResource(
			look.id().value(),
			look.generationJobId().value(),
			look.createdAt(),
			new ReadCapability(access.uri(), access.expiresAt()));
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
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.contentType(MediaType.APPLICATION_JSON)
			.body(new GenerationProblemResponse(NOT_FOUND));
	}

	private JdbcLookStore store() {
		if (looks == null) {
			throw new NotFound();
		}
		return looks;
	}

	private ObjectStore objectStore() {
		if (objects == null) {
			throw new NotFound();
		}
		return objects;
	}

	private static LookId lookId(String value) {
		try {
			return new LookId(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			throw new NotFound();
		}
	}

	private static <T> T first(ObjectProvider<T> provider) {
		try {
			return provider.getIfAvailable();
		}
		catch (NoUniqueBeanDefinitionException ex) {
			return provider.orderedStream().findFirst().orElse(null);
		}
	}

	private static final class NotFound extends RuntimeException {
	}
}
