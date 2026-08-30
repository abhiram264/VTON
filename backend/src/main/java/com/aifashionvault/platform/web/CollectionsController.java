package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.node.ObjectNode;

import com.aifashionvault.collection.*;
import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

@RestController
@RequestMapping("/collections")
public class CollectionsController {
	private final CollectionStore store;
	private final Clock clock;

	public CollectionsController(ObjectProvider<CollectionStore> stores, ObjectProvider<Clock> clocks) {
		this.store = stores.getIfAvailable();
		Clock configured = clocks.getIfAvailable();
		this.clock = configured == null ? Clock.systemUTC() : configured;
	}

	@PostMapping
	public ResponseEntity<CollectionResponse> create(@AuthenticationPrincipal UserId owner, @RequestBody ObjectNode body) {
		Collection created = requiredStore().create(owner, new CollectionId(UUID.randomUUID()), name(body), clock.instant());
		CollectionResponse response = response(created);
		return ResponseEntity.created(URI.create("/collections/" + response.id())).contentType(MediaType.APPLICATION_JSON).body(response);
	}

	@GetMapping public List<CollectionResponse> list(@AuthenticationPrincipal UserId owner) { return requiredStore().list(owner).stream().map(CollectionsController::response).toList(); }
	@GetMapping("/{id}") public CollectionResponse get(@AuthenticationPrincipal UserId owner, @PathVariable String id) { return response(owned(owner, id)); }

	@PatchMapping("/{id}")
	public CollectionResponse rename(@AuthenticationPrincipal UserId owner, @PathVariable String id, @RequestBody ObjectNode body) {
		CollectionId collectionId = collectionId(id);
		return response(requiredStore().rename(owner, collectionId, name(body), clock.instant()).orElseThrow(NotFound::new));
	}

	@DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserId owner, @PathVariable String id) {
		if (!requiredStore().delete(owner, collectionId(id))) throw new NotFound();
	}

	@PostMapping("/{id}/items")
	public CollectionResponse add(@AuthenticationPrincipal UserId owner, @PathVariable String id, @RequestBody ObjectNode body) {
		CollectionId collectionId = collectionId(id);
		if (!body.has("vaultItemId") || !body.get("vaultItemId").isString()) throw new Invalid();
		VaultItemId itemId;
		try { itemId = new VaultItemId(UUID.fromString(body.get("vaultItemId").stringValue())); }
		catch (RuntimeException ex) {
			if (requiredStore().find(owner, collectionId).isEmpty()) throw new NotFound();
			throw new ItemNotFound();
		}
		return membership(requiredStore().addItem(owner, collectionId, itemId, clock.instant()));
	}

	@DeleteMapping("/{id}/items/{vaultItemId}") @ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@AuthenticationPrincipal UserId owner, @PathVariable String id, @PathVariable String vaultItemId) {
		CollectionId collectionId = collectionId(id);
		VaultItemId item;
		try { item = new VaultItemId(UUID.fromString(vaultItemId)); }
		catch (RuntimeException ex) {
			if (requiredStore().find(owner, collectionId).isEmpty()) throw new NotFound();
			return;
		}
		if (requiredStore().removeItem(owner, collectionId, item, clock.instant()).outcome() == MembershipOutcome.COLLECTION_NOT_FOUND) throw new NotFound();
	}

	@ExceptionHandler(NotFound.class) ResponseEntity<Problem> notFound() { return problem(HttpStatus.NOT_FOUND, "COLLECTION_NOT_FOUND"); }
	@ExceptionHandler(ItemNotFound.class) ResponseEntity<Problem> itemNotFound() { return problem(HttpStatus.NOT_FOUND, "VAULT_ITEM_NOT_FOUND"); }
	@ExceptionHandler({Invalid.class, org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<Problem> invalid() { return problem(HttpStatus.BAD_REQUEST, "INVALID_COLLECTION"); }

	private Collection owned(UserId owner, String id) { return requiredStore().find(owner, collectionId(id)).orElseThrow(NotFound::new); }
	private CollectionStore requiredStore() { if (store == null) throw new IllegalStateException("collection store unavailable"); return store; }
	private static CollectionId collectionId(String text) { try { return new CollectionId(UUID.fromString(text)); } catch (RuntimeException ex) { throw new NotFound(); } }
	private static String name(ObjectNode body) {
		if (!body.has("name") || !body.get("name").isString()) throw new Invalid();
		String value = body.get("name").stringValue().strip();
		if (value.isEmpty() || value.codePointCount(0, value.length()) > 120) throw new Invalid();
		return value;
	}
	private static CollectionResponse membership(MembershipResult result) { return switch (result.outcome()) { case CHANGED, UNCHANGED -> response(result.collection()); case COLLECTION_NOT_FOUND -> throw new NotFound(); case VAULT_ITEM_NOT_FOUND -> throw new ItemNotFound(); }; }
	private static CollectionResponse response(Collection collection) { return new CollectionResponse(collection.id().value().toString(), collection.name(), collection.createdAt(), collection.updatedAt(), collection.items().stream().map(i -> new CollectionItemResponse(i.vaultItemId().value().toString(), i.createdAt())).toList()); }
	private static ResponseEntity<Problem> problem(HttpStatus status, String code) { return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Problem(code)); }

	public record CollectionResponse(String id, String name, Instant createdAt, Instant updatedAt, List<CollectionItemResponse> items) {}
	public record CollectionItemResponse(String vaultItemId, Instant createdAt) {}
	record Problem(String code) {}
	private static final class NotFound extends RuntimeException {}
	private static final class ItemNotFound extends RuntimeException {}
	private static final class Invalid extends RuntimeException {}
}
