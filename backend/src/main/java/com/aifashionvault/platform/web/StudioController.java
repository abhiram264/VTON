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

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.featureflag.FeatureFlagName;
import com.aifashionvault.featureflag.FeatureFlags;
import com.aifashionvault.platform.studio.StudioDraftCloner;
import com.aifashionvault.studio.JdbcStudioStore;
import com.aifashionvault.studio.StudioItem;
import com.aifashionvault.studio.StudioSession;
import com.aifashionvault.studio.agent.AgentProblemCode;
import com.aifashionvault.studio.agent.StudioAgent;
import com.aifashionvault.studio.agent.StudioAgentTools;
import com.aifashionvault.studio.agent.StudioCommandPlanner;

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

	/**
	 * Plain-language explanations for a refused natural-language command. The
	 * message states what happened; it never implies the draft changed.
	 */
	private static final java.util.Map<String, String> MESSAGE_BY_PROBLEM = java.util.Map.of(
		AgentProblemCode.UNAUTHORIZED_OR_NOT_FOUND.name(),
		"No item in your Vault matches that, or it is not yours.",
		AgentProblemCode.STATE_CONFLICT.name(),
		"That slot conflicts with an item already in the draft. Use \"replace\" or remove it first.",
		AgentProblemCode.DOMAIN_VALIDATION_FAILED.name(),
		"That change is not allowed in this draft. Nothing was changed.",
		AgentProblemCode.DEPENDENCY_UNAVAILABLE.name(),
		"The Studio data store is unavailable. Nothing was changed.",
		AgentProblemCode.MALFORMED_CALL.name(),
		"That command could not be understood. Nothing was changed."
	);

	private final ObjectProvider<JdbcStudioStore> stores;
	private final ObjectProvider<StudioDraftCloner> cloners;
	private final ObjectProvider<StudioAgentTools> agentTools;
	private final ObjectProvider<StudioCommandPlanner> planners;
	private final ObjectProvider<FeatureFlags> flagsProvider;
	private final Clock clock;

	public StudioController(
		ObjectProvider<JdbcStudioStore> stores,
		ObjectProvider<StudioDraftCloner> cloners,
		ObjectProvider<StudioAgentTools> agentTools,
		ObjectProvider<StudioCommandPlanner> planners,
		ObjectProvider<FeatureFlags> flagsProvider,
		ObjectProvider<Clock> clocks
	) {
		this.stores = stores;
		this.cloners = cloners;
		this.agentTools = agentTools;
		this.planners = planners;
		this.flagsProvider = flagsProvider;
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
		JdbcStudioStore.SaveResult result = store().save(stamped, expected);
		return switch (result.status()) {
			case SAVED -> response(result.session());
			case STALE -> throw new Stale();
			case NOT_FOUND -> throw new NotFound();
		};
	}

	@PostMapping("/{id}/natural-language-command")
	public StudioNaturalLanguageResponse naturalLanguageCommand(
		@AuthenticationPrincipal UserId owner,
		@PathVariable String id,
		@RequestBody ObjectNode body
	) {
		if (!naturalLanguageEnabled()) {
			throw new CapabilityDisabled();
		}
		if (body == null) {
			throw new Invalid();
		}
		StudioSession current = owned(owner, id);
		StudioCommandPlanner planner = planner();
		StudioCommandPlanner.Plan plan = planner.plan(owner, current.id(), requiredText(body, "text"));
		return switch (plan) {
			case StudioCommandPlanner.Resolved resolved -> applied(owner, current, resolved);
			case StudioCommandPlanner.NeedsSelection selection -> StudioNaturalLanguageResponse.confirm(
				response(current), selection.prompt(),
				selection.candidates().stream()
					.map(candidate -> new CandidateResponse(
						candidate.id().value().toString(),
						candidate.label(),
						selection.slot() == null ? null : selection.slot().name()))
					.toList());
			case StudioCommandPlanner.Explanation explanation -> StudioNaturalLanguageResponse.explained(
				response(current), explanation.message());
			case StudioCommandPlanner.Refused refused -> StudioNaturalLanguageResponse.refused(
				response(current), refused.problem().name(), refused.message());
		};
	}

	/**
	 * Runs the planner's calls through the allowlisted agent and answers with the
	 * authoritative draft. A refused or partial execution reports the failure and
	 * returns the draft as it actually stands, never a fabricated success.
	 *
	 * <p>The refusal message is derived from the draft itself: if the execution
	 * failed after the draft had already moved, saying "nothing was changed" would
	 * be a lie. The planner currently plans at most one mutation, so this should
	 * never trigger — it is here so the honesty of the message does not depend on
	 * that invariant holding forever.</p>
	 */
	private StudioNaturalLanguageResponse applied(
		UserId owner,
		StudioSession before,
		StudioCommandPlanner.Resolved resolved
	) {
		StudioAgent.AgentExecution execution = new StudioAgent(tools()).execute(owner, before.id(), resolved.calls());
		StudioSession after = owned(owner, before.id().value().toString());
		if (!execution.completed()) {
			StudioAgent.ToolCallResult failure = execution.results().get(execution.results().size() - 1);
			String problem = failure.problem() == null
				? AgentProblemCode.MALFORMED_CALL.name()
				: failure.problem().name();
			boolean changed = !after.updatedAt().equals(before.updatedAt());
			String message = changed
				? "Part of that command was applied before it failed. Review the draft."
				: MESSAGE_BY_PROBLEM.getOrDefault(
					problem, "That command could not be applied. The draft is unchanged.");
			return StudioNaturalLanguageResponse.refused(response(after), problem, message);
		}
		return StudioNaturalLanguageResponse.applied(response(after));
	}

	@PostMapping("/from-look")
	public ResponseEntity<CloneResponse> fromLook(
		@AuthenticationPrincipal UserId owner,
		@RequestBody(required = false) ObjectNode body
	) {
		if (body == null) {
			throw new Invalid();
		}
		LookId lookId = new LookId(uuid(body, "lookId"));
		StudioDraftCloner.CloneResult result = cloner().cloneFromLook(lookId, owner, clock.instant());
		if (result.notFound()) {
			// A foreign or unknown Look discloses nothing — same posture as the owner-scoped read.
			throw new NotFound();
		}
		StudioSession persisted = store().create(result.draft());
		return ResponseEntity.status(HttpStatus.CREATED).body(cloneResponse(persisted, result));
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

	/**
	 * The capability is off in this release. Distinct from {@code STUDIO_UNAVAILABLE}
	 * (a missing dependency): this one means the route is not part of the release,
	 * so a client must stop offering it rather than retry.
	 */
	@ExceptionHandler(CapabilityDisabled.class)
	ResponseEntity<Problem> capabilityDisabled() {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "STUDIO_NATURAL_LANGUAGE_DISABLED");
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

	private StudioDraftCloner cloner() {
		StudioDraftCloner cloner;
		try {
			cloner = cloners.getIfAvailable();
		} catch (NoUniqueBeanDefinitionException ex) {
			throw new Unavailable();
		}
		if (cloner == null) {
			throw new Unavailable();
		}
		return cloner;
	}

	private StudioAgentTools tools() {
		StudioAgentTools tools;
		try {
			tools = agentTools.getIfAvailable();
		} catch (NoUniqueBeanDefinitionException ex) {
			throw new Unavailable();
		}
		if (tools == null) {
			throw new Unavailable();
		}
		return tools;
	}

	private StudioCommandPlanner planner() {
		StudioCommandPlanner planner;
		try {
			planner = planners.getIfAvailable();
		} catch (NoUniqueBeanDefinitionException ex) {
			throw new Unavailable();
		}
		if (planner == null) {
			throw new Unavailable();
		}
		return planner;
	}

	/**
	 * The backend is the capability authority (C22): a release that declares
	 * natural-language Studio unsupported must refuse the route even if a client
	 * ignores the flags. A missing flag service is therefore DISABLED, not enabled —
	 * the capability fails closed.
	 */
	private boolean naturalLanguageEnabled() {
		FeatureFlags flags;
		try {
			flags = flagsProvider.getIfAvailable();
		} catch (NoUniqueBeanDefinitionException ex) {
			return false;
		}
		return flags != null && flags.isEnabled(FeatureFlagName.NATURAL_LANGUAGE_STUDIO);
	}

	private static CloneResponse cloneResponse(StudioSession session, StudioDraftCloner.CloneResult result) {
		return new CloneResponse(
			session.id().value().toString(),
			session.avatarProfileId() == null ? null : session.avatarProfileId().value().toString(),
			session.items().stream().map(item -> item.vaultItemId().value().toString()).toList(),
			session.updatedAt(),
			result.unresolvedGarments().stream()
				.map(unresolved -> new UnresolvedGarmentResponse(
					unresolved.slot().name(),
					unresolved.vaultItemId().value().toString(),
					unresolved.reason().name()))
				.toList(),
			result.avatarUnavailable()
		);
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

	/** Clone response adds the explicit missing-item report C10 item 6 / C15 item 4 require. */
	public record CloneResponse(
		String sessionId,
		String avatarProfileId,
		List<String> itemIds,
		Instant updatedAt,
		List<UnresolvedGarmentResponse> unresolved,
		boolean avatarUnavailable
	) {
	}

	/**
	 * Natural-language outcome. The session fields are the same shape every other
	 * Studio route returns, so the existing client keeps working; {@code outcome}
	 * tells a client that understands it whether anything actually changed.
	 *
	 * <p>{@code CONFIRM_REQUIRED} means the text was ambiguous: nothing was
	 * mutated and {@code candidates} lists the owned items the user must choose
	 * between. {@code REFUSED} means the command was understood but could not be
	 * applied — the returned draft is the unchanged truth, never a partial write.</p>
	 */
	public record StudioNaturalLanguageResponse(
		String sessionId,
		String avatarProfileId,
		List<String> itemIds,
		Instant updatedAt,
		String outcome,
		String message,
		List<CandidateResponse> candidates
	) {
		static StudioNaturalLanguageResponse applied(StudioSessionResponse state) {
			return new StudioNaturalLanguageResponse(
				state.sessionId(), state.avatarProfileId(), state.itemIds(), state.updatedAt(),
				"APPLIED", null, List.of());
		}

		static StudioNaturalLanguageResponse confirm(
			StudioSessionResponse state,
			String prompt,
			List<CandidateResponse> candidates
		) {
			return new StudioNaturalLanguageResponse(
				state.sessionId(), state.avatarProfileId(), state.itemIds(), state.updatedAt(),
				"CONFIRM_REQUIRED", prompt, candidates);
		}

		static StudioNaturalLanguageResponse explained(StudioSessionResponse state, String message) {
			return new StudioNaturalLanguageResponse(
				state.sessionId(), state.avatarProfileId(), state.itemIds(), state.updatedAt(),
				"EXPLAINED", message, List.of());
		}

		static StudioNaturalLanguageResponse refused(StudioSessionResponse state, String problem, String message) {
			return new StudioNaturalLanguageResponse(
				state.sessionId(), state.avatarProfileId(), state.itemIds(), state.updatedAt(),
				"REFUSED", message, List.of());
		}
	}

	/**
	 * One owned garment the ambiguous command could mean. {@code slot} is the slot
	 * the command TARGETED, taken from the planner's own resolution, so the
	 * confirmation re-send never has to re-derive it from the garment's category on
	 * the client.
	 */
	public record CandidateResponse(String vaultItemId, String label, String slot) {
	}

	public record UnresolvedGarmentResponse(String slot, String vaultItemId, String reason) {
	}

	record Problem(String code) {
	}

	private static final class NotFound extends RuntimeException {
	}

	private static final class Stale extends RuntimeException {
	}

	private static final class Unavailable extends RuntimeException {
	}

	private static final class CapabilityDisabled extends RuntimeException {
	}

	private static final class Invalid extends RuntimeException {
	}
}
