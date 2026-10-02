package com.aifashionvault.platform.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.vault.VaultContracts.ProblemCode;
import com.aifashionvault.vault.VaultItem;

/**
 * Owner-authorized display capability for a Vault item's stored image.
 *
 * <p>Deliberately a route rather than a field on {@code VaultItemResponse}: the
 * capability is a short-lived bearer URL, so it is minted per read and re-minted
 * once it expires. Carrying it on the item representation would freeze one
 * expiry into every list payload and invite treating the URL as an identifier.
 * The response shape is the same {@link ReadCapability} used by {@code /looks},
 * so clients parse one capability type.</p>
 *
 * <p>Owner scope comes from the verified principal; a foreign, unknown or
 * malformed id is the same opaque 404 as every other Vault read, so the route
 * cannot be used to probe for other people's items.</p>
 */
@RestController
@RequestMapping("/vault/items")
public class VaultImageController {

	private static final Duration DISPLAY_TTL = Duration.ofMinutes(5);
	private static final String MEDIA_UNAVAILABLE = "VAULT_MEDIA_UNAVAILABLE";

	private final ObjectProvider<VaultService> services;
	private final ObjectProvider<ObjectStore> media;

	public VaultImageController(ObjectProvider<VaultService> services, ObjectProvider<ObjectStore> media) {
		this.services = services;
		this.media = media;
	}

	@GetMapping("/{id}/image")
	public ReadCapability image(@AuthenticationPrincipal UserId user, @PathVariable String id) {
		return capability(service().get(user, itemId(id)));
	}

	private ReadCapability capability(VaultItem item) {
		ObjectStore store = store();
		try {
			PresignedAccess access = store.presignGet(
				new ObjectRef(StorageDomain.VAULT, item.storageKey().value()), DISPLAY_TTL);
			return new ReadCapability(access.uri(), access.expiresAt());
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException
				| ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			// The item record itself is intact; only its bytes are unreadable right
			// now. Reported as unavailable rather than as a missing item so the
			// client offers a retry instead of treating the garment as deleted.
			throw new MediaUnavailable();
		}
	}

	@ExceptionHandler(VaultService.VaultProblem.class)
	ResponseEntity<Problem> problem(VaultService.VaultProblem e) {
		return ResponseEntity.status(status(e.code())).contentType(MediaType.APPLICATION_JSON)
			.body(new Problem(e.code().name()));
	}

	@ExceptionHandler(MediaUnavailable.class)
	ResponseEntity<Problem> mediaUnavailable() {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
			.body(new Problem(MEDIA_UNAVAILABLE));
	}

	private static VaultItemId itemId(String value) {
		try {
			return new VaultItemId(UUID.fromString(value));
		}
		catch (RuntimeException ex) {
			throw VaultService.problem(ProblemCode.VAULT_ITEM_NOT_FOUND);
		}
	}

	private VaultService service() {
		try {
			VaultService service = services.getIfAvailable();
			if (service != null) {
				return service;
			}
		}
		catch (NoUniqueBeanDefinitionException ignored) {
			// Fall through to the same unavailable outcome as a missing bean.
		}
		throw VaultService.problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);
	}

	private ObjectStore store() {
		ObjectStore store = media.getIfAvailable();
		if (store == null) {
			throw VaultService.problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);
		}
		return store;
	}

	private static HttpStatus status(ProblemCode code) {
		return switch (code) {
			case VAULT_ITEM_NOT_FOUND, VAULT_SOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case VAULT_ITEM_IN_USE -> HttpStatus.CONFLICT;
			case VAULT_MEDIA_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.BAD_REQUEST;
		};
	}

	record Problem(String code) {}

	private static final class MediaUnavailable extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private MediaUnavailable() {
			super("vault item image is not readable");
		}
	}
}
