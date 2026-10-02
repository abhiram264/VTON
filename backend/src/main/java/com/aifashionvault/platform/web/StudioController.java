package com.aifashionvault.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import com.aifashionvault.studio.JdbcStudioStore;
import com.aifashionvault.studio.JdbcStudioStore.SaveResult;
import com.aifashionvault.studio.JdbcStudioStore.SaveStatus;
import com.aifashionvault.studio.StudioItem;
import com.aifashionvault.studio.StudioSession;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

/**
 * Mounts the Studio HTTP surface the mobile client already calls (C10):
 * {@code POST /studio/sessions}, {@code GET /studio/sessions/{id}},
 * {@code GET /studio/sessions/active} and
 * {@code POST /studio/sessions/{id}/commands}.
 *
 * <p>Every route is owner-scoped through {@link JdbcStudioStore}; a session owned
 * by another account is a 404, never a silent success. Mutations carry an
 * {@code expectedUpdatedAt} optimistic token and answer 409 when the draft moved
 * on, so a stale device cannot silently win. Route params are navigation hints —
 * the draft is the authority, and the active draft is restored by the server.</p>
 *
 * <p>The response shape is exactly what {@code mobile/src/features/studio/studioApi.ts}
 * parses ({@code sessionId}, {@code avatarProfileId}, {@code itemIds}); {@code updatedAt}
 * is added so the client can carry the optimistic token back on the next command.
 * No store bean means the honest 503 {@code STUDIO_UNAVAILABLE} posture — never a
 * silent in-memory default.</p>
 */
@RestController
@RequestMapping("/studio/sessions")
public class StudioController {

	private final ObjectProvider<JdbcStudioStore> stores;
	private final Clock clock;

	public StudioController(ObjectProvider<JdbcStudioStore> stores, ObjectProvider<Clock> clocks) {
		this.stores = stores;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@PostMapping
	public ResponseEntity<StudioSessionResponse> create(
		@AuthenticationPrincipal UserId owner,
		@RequestBody(required = false) ObjectNode body
	) {
		AvatarProfileId avatar = optionalAvatar(body);
		Instant now = clock.instant();
		StudioSession session = new StudioSession(
			new StudioSessionId(UUID.randomUUID()),
			owner,
			avatar,
			now,
			now,
			List.of()
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(response(store().create(session)));
	}

	@GetMapping("/active")
	public StudioSessionResponse active(@AuthenticationPrincipal UserId owner) {
		return response(store().findLatestForUser(owner).orElseThrow(NotFound::new));
	}

	@GetMapping("/{id}")
	public StudioSessionResponse get(@AuthenticationPrincipal UserId owner, @PathVariable String id) {
		return response(owned(owner, id));
	}

	@PostMapping("/{id}/commands")
	public StudioSessionResponse command(
		@AuthenticationPrincipal UserId owner,
		@PathVariable String id,
		@RequestBody ObjectNode body
	) {
		if (body == null) {
			throw new Invalid();
		}
		StudioSession current = owned(owner, id);
		Instant expected = expectedUpdatedAt(body);
		if (!expected.equals(current.updatedAt())) {
			throw new Stale();
		}
		StudioSession next = apply(current, body);
		StudioSession stamped = new StudioSession(
			next.id(), next.userId(), next.avatarProfileId(), next.createdAt(), clock.instant(), next.items()
		);
		SaveResult result = store().save(stamped, expected);
		return switch (result.status()) {
			case SAVED -> response(result.session());
			case STALE -> throw new Stale();
			case NOT_FOUND -> throw new NotFound();
		};
	}

	@ExceptionHandler(NotFound.class)
	ResponseEntity<Problem> notFound() {
		return problem(HttpStatus.NOT_FOUND, "STUDIO_SESSION_NOT_FOUND");
	}

	@ExceptionHandler(Stale.class)
	ResponseEntity<Problem> stale() {
		return problem(HttpStatus.CONFLICT, "STUDIO_STALE_REVISION");
	}

	@ExceptionHandler(Unavailable.class)
	ResponseEntity<Problem> unavailable() {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "STUDIO_UNAVAILABLE");
	}

	@ExceptionHandler({
		Invalid.class,
		IllegalArgumentException.class,
		org.springframework.http.converter.HttpMessageNotReadableException.class
	})
	ResponseEntity<Problem> invalid() {
		return problem(HttpStatus.BAD_REQUEST, "STUDIO_INVALID_REQUEST");
	}

	private StudioSession apply(StudioSession session, ObjectNode body) {
		String command = requiredText(body, "command");
		return switch (command) {
			case "addItem" -> session.addItem(item(session, body, new StudioItemId(UUID.randomUUID())));
			case "replaceItem" -> session.replaceItem(item(session, body, optionalItemId(body)));
			case "removeItem" -> session.removeItem(requiredItemId(body));
			case "clearSlot" -> session.clearSlot(slot(body));
			case "setAvatar" -> session.setAvatar(optionalAvatar(body));
			case "clearOutfit" -> session.clearOutfit();
			default -> throw new Invalid();
		};
	}

	private static StudioItem item(StudioSession session, ObjectNode body, StudioItemId itemId) {
		return new StudioItem(
			itemId,
			session.id(),
			slot(body),
			new VaultItemId(uuid(body, "vaultItemId")),
			position(body),
			session.updatedAt()
		);
	}

	private StudioSession owned(UserId owner, String id) {
		return store().findForUser(sessionId(id), owner).orElseThrow(NotFound::new);
	}

	private JdbcStudioStore store() {
		JdbcStudioStore store;
		try {
			store = stores.getIfAvailable();
		} catch (NoUniqueBeanDefinitionException ex) {
			throw new Unavailable();
		}
		if (store == null) {
			throw new Unavailable();
		}
		return store;
	}

	private static StudioSessionResponse response(StudioSession session) {
		return new StudioSessionResponse(
			session.id().value().toString(),
			session.avatarProfileId() == null ? null : session.avatarProfileId().value().toString(),
			session.items().stream().map(item -> item.vaultItemId().value().toString()).toList(),
			session.updatedAt()
		);
	}

	private static StudioSessionId sessionId(String text) {
		try {
			return new StudioSessionId(UUID.fromString(text));
		} catch (RuntimeException ex) {
			throw new NotFound();
		}
	}

	private static Instant expectedUpdatedAt(ObjectNode body) {
		JsonNode node = body.get("expectedUpdatedAt");
		if (node == null || !node.isString()) {
			throw new Invalid();
		}
		try {
			return Instant.parse(node.stringValue().strip());
		} catch (RuntimeException ex) {
			throw new Invalid();
		}
	}

	private static AvatarProfileId optionalAvatar(ObjectNode body) {
		if (body == null) {
			return null;
		}
		JsonNode node = body.get("avatarProfileId");
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new Invalid();
		}
		return new AvatarProfileId(uuidFrom(node.stringValue()));
	}

	private static StudioSlot slot(ObjectNode body) {
		String raw = requiredText(body, "slot");
		try {
			return StudioSlot.valueOf(raw.toUpperCase(Locale.ROOT));
		} catch (RuntimeException ex) {
			throw new Invalid();
		}
	}

	private static int position(ObjectNode body) {
		JsonNode node = body.get("position");
		if (node == null || node.isNull()) {
			return 0;
		}
		if (!node.isNumber() || !node.canConvertToInt()) {
			throw new Invalid();
		}
		int value = node.intValue();
		if (value < 0) {
			throw new Invalid();
		}
		return value;
	}

	private static StudioItemId requiredItemId(ObjectNode body) {
		return new StudioItemId(uuid(body, "itemId"));
	}

	/**
	 * replaceItem targets an existing item either by id or by slot: the domain
	 * replaces the sole item in a single-item slot when the ids differ, so a missing
	 * itemId mints a fresh id and lets slot matching resolve the target.
	 */
	private static StudioItemId optionalItemId(ObjectNode body) {
		JsonNode node = body.get("itemId");
		if (node == null || node.isNull()) {
			return new StudioItemId(UUID.randomUUID());
		}
		if (!node.isString()) {
			throw new Invalid();
		}
		return new StudioItemId(uuidFrom(node.stringValue()));
	}

	private static UUID uuid(ObjectNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || !node.isString()) {
			throw new Invalid();
		}
		return uuidFrom(node.stringValue());
	}

	private static UUID uuidFrom(String value) {
		try {
			return UUID.fromString(value.strip());
		} catch (RuntimeException ex) {
			throw new Invalid();
		}
	}

	private static String requiredText(ObjectNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || !node.isString() || node.stringValue().isBlank()) {
			throw new Invalid();
		}
		return node.stringValue().strip();
	}

	private static ResponseEntity<Problem> problem(HttpStatus status, String code) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Problem(code));
	}

	public record StudioSessionResponse(String sessionId, String avatarProfileId, List<String> itemIds, Instant updatedAt) {
	}

	record Problem(String code) {
	}

	private static final class NotFound extends RuntimeException {
	}

	private static final class Stale extends RuntimeException {
	}

	private static final class Unavailable extends RuntimeException {
	}

	private static final class Invalid extends RuntimeException {
	}
}
