package com.aifashionvault.platform.studio;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.aifashionvault.collection.Collection;
import com.aifashionvault.collection.CollectionStore;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.studio.JdbcStudioStore;
import com.aifashionvault.studio.StudioItem;
import com.aifashionvault.studio.StudioSession;
import com.aifashionvault.studio.agent.AgentProblemCode;
import com.aifashionvault.studio.agent.StudioAgent;
import com.aifashionvault.studio.agent.StudioAgentTools;
import com.aifashionvault.studio.agent.ToolValue;
import com.aifashionvault.studio.agent.VaultItemSummary;
import com.aifashionvault.studio.agent.VaultItemValue;
import com.aifashionvault.studio.agent.VaultItemsValue;
import com.aifashionvault.studio.agent.CollectionValue;
import com.aifashionvault.studio.agent.StudioItemValue;
import com.aifashionvault.studio.agent.StudioStateValue;
import com.aifashionvault.vault.JdbcVaultStore;
import com.aifashionvault.vault.VaultContracts;
import com.aifashionvault.vault.VaultItem;

/**
 * Implements the {@link StudioAgentTools} port over the real stores, so the
 * constrained natural-language Studio surface (C21) mutates the SAME owned,
 * revisioned draft the manual controls mutate.
 *
 * <p>Every lookup is owner-scoped at the store: {@code findByIdForUser} /
 * {@code listForUser} take the caller's id, and a record owned by someone else
 * is treated exactly like a record that does not exist
 * ({@link AgentProblemCode#UNAUTHORIZED_OR_NOT_FOUND}). Nothing here trusts an id
 * from the untrusted text: the actor is always the verified principal, and the
 * session is always the one the controller already proved is owned.</p>
 *
 * <p>Mutations are applied to the {@link StudioSession} value type — the same
 * full-body conflict and duplicate-garment rules the manual path enforces — and
 * persisted through {@link JdbcStudioStore#save} with the draft's own
 * {@code updatedAt} as the optimistic token, so a stale NL command loses to a
 * concurrent manual edit exactly like a stale manual command does.</p>
 */
public final class StudioAgentToolAdapter implements StudioAgentTools {

	private final JdbcStudioStore studio;
	private final JdbcVaultStore vault;
	private final CollectionStore collections;

	public StudioAgentToolAdapter(JdbcStudioStore studio, JdbcVaultStore vault, CollectionStore collections) {
		this.studio = studio;
		this.vault = vault;
		this.collections = collections;
	}

	@Override
	public ToolValue searchVault(UserId actorId, StudioSessionId sessionId, String query, String category) {
		requireOwnedSession(actorId, sessionId);
		ProductCategory parsed = parseCategory(category);
		List<VaultItemSummary> items = vault.listForUser(
				actorId,
				new VaultContracts.Filters(parsed, null, null, null, query, null, null))
			.stream()
			.limit(50)
			.map(StudioAgentToolAdapter::summary)
			.toList();
		return new VaultItemsValue(items);
	}

	@Override
	public ToolValue getVaultItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId) {
		requireOwnedSession(actorId, sessionId);
		VaultItem item = ownedVaultItem(actorId, vaultItemId);
		return new VaultItemValue(summary(item));
	}

	@Override
	public ToolValue listCollection(UserId actorId, StudioSessionId sessionId, CollectionId collectionId) {
		requireOwnedSession(actorId, sessionId);
		Collection collection = collections.find(actorId, collectionId)
			.orElseThrow(() -> notFound());
		List<VaultItemSummary> items = vault.listForUser(
				actorId,
				new VaultContracts.Filters(null, collectionId.value(), null, null, null, null, null))
			.stream()
			.map(StudioAgentToolAdapter::summary)
			.toList();
		return new CollectionValue(collectionId, collection.name(), items);
	}

	@Override
	public ToolValue addItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId, StudioSlot slot, int position) {
		StudioSession current = requireOwnedSession(actorId, sessionId);
		ownedVaultItem(actorId, vaultItemId);
		StudioItem item = new StudioItem(
			new StudioItemId(UUID.randomUUID()), sessionId, slot, vaultItemId, position, current.updatedAt());
		return persist(current.addItem(item));
	}

	@Override
	public ToolValue removeItem(UserId actorId, StudioSessionId sessionId, StudioSlot slot, Integer position) {
		StudioSession current = requireOwnedSession(actorId, sessionId);
		List<StudioItem> matches = current.items().stream()
			.filter(item -> item.slot() == slot)
			.filter(item -> position == null || item.position() == position)
			.sorted(Comparator.comparingInt(StudioItem::position).reversed())
			.toList();
		if (matches.isEmpty()) {
			throw notFound();
		}
		// A positional removal targets one item; a slot-only removal clears the slot.
		StudioSession next = position == null
			? current.clearSlot(slot)
			: current.removeItem(matches.get(0).id());
		return persist(next);
	}

	@Override
	public ToolValue replaceItem(UserId actorId, StudioSessionId sessionId, VaultItemId vaultItemId, StudioSlot slot, Integer position) {
		StudioSession current = requireOwnedSession(actorId, sessionId);
		ownedVaultItem(actorId, vaultItemId);
		StudioItemId target = current.items().stream()
			.filter(item -> item.slot() == slot)
			.filter(item -> position == null || item.position() == position)
			.map(StudioItem::id)
			.findFirst()
			.orElseThrow(() -> notFound());
		StudioItem replacement = new StudioItem(
			target, sessionId, slot, vaultItemId, position == null ? 0 : position, current.updatedAt());
		return persist(current.replaceItem(replacement));
	}

	@Override
	public ToolValue setAvatar(UserId actorId, StudioSessionId sessionId, AvatarProfileId avatarProfileId) {
		StudioSession current = requireOwnedSession(actorId, sessionId);
		return persist(current.setAvatar(avatarProfileId));
	}

	@Override
	public ToolValue getStudioState(UserId actorId, StudioSessionId sessionId) {
		return state(requireOwnedSession(actorId, sessionId));
	}

	/**
	 * Spending is deliberately NOT reachable from untrusted text. Generation is
	 * admitted by {@code POST /generations/multi}, which owns the consent, quota,
	 * admission-pause and provider-availability checks; a free-text command that
	 * could reserve spend would bypass that review. C21's frozen vocabulary
	 * (find / add / replace / remove / clear / explain) never emits this call, so
	 * this arm exists only to answer the port honestly.
	 */
	@Override
	public ToolValue generateLook(UserId actorId, StudioSessionId sessionId, String idempotencyKey) {
		requireOwnedSession(actorId, sessionId);
		throw new StudioAgent.AgentApplicationException(AgentProblemCode.DEPENDENCY_UNAVAILABLE);
	}

	/** Same reasoning as {@link #generateLook}: no text command may publish a Look. */
	@Override
	public ToolValue saveLook(UserId actorId, StudioSessionId sessionId, GenerationJobId generationJobId) {
		requireOwnedSession(actorId, sessionId);
		throw new StudioAgent.AgentApplicationException(AgentProblemCode.DEPENDENCY_UNAVAILABLE);
	}

	private StudioSession requireOwnedSession(UserId actorId, StudioSessionId sessionId) {
		return studio.findForUser(sessionId, actorId)
			.orElseThrow(() -> notFound());
	}

	private VaultItem ownedVaultItem(UserId actorId, VaultItemId vaultItemId) {
		return vault.findByIdForUser(vaultItemId, actorId)
			.orElseThrow(() -> notFound());
	}

	private ToolValue persist(StudioSession next) {
		JdbcStudioStore.SaveResult result = studio.save(next, next.updatedAt());
		return switch (result.status()) {
			case SAVED -> state(result.session());
			case STALE -> throw new StudioAgent.AgentApplicationException(AgentProblemCode.STATE_CONFLICT);
			case NOT_FOUND -> throw notFound();
		};
	}

	private static StudioStateValue state(StudioSession session) {
		List<StudioItemValue> items = session.items().stream()
			.sorted(Comparator.comparingInt(StudioItem::position))
			.map(item -> new StudioItemValue(item.id(), item.slot(), item.vaultItemId(), item.position()))
			.toList();
		return new StudioStateValue(session.id(), session.avatarProfileId(), items);
	}

	private static VaultItemSummary summary(VaultItem item) {
		return new VaultItemSummary(
			item.id(),
			item.name(),
			item.brand(),
			item.category() == null ? null : item.category().name(),
			item.subcategory(),
			item.color(),
			item.pattern());
	}

	private static ProductCategory parseCategory(String category) {
		if (category == null || category.isBlank()) {
			return null;
		}
		try {
			return ProductCategory.valueOf(category.strip().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * A missing record and a record owned by someone else are the same answer: the
	 * caller must not be able to tell them apart.
	 */
	private static StudioAgent.AgentApplicationException notFound() {
		return new StudioAgent.AgentApplicationException(AgentProblemCode.UNAUTHORIZED_OR_NOT_FOUND);
	}

}
