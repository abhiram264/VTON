package com.aifashionvault.studio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.aifashionvault.look.Look;
import com.aifashionvault.look.LookItem;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

/**
 * Turns an owned {@link Look} back into an editable Studio draft (the "Modify"
 * action behind C10 item 6 / C15 item 4).
 *
 * <p>The guide requires clone-from-Look with <em>explicit</em> missing-item
 * resolution: a source garment that has since been deleted, or that is no longer
 * owned by the requester, must never be silently dropped — the user would then be
 * editing an outfit they did not choose. Such garments are reported in
 * {@link CloneResult#unresolvedGarments()} with a reason, the remaining resolvable
 * garments still form a usable draft, and an avatar that is no longer available is
 * reported separately rather than substituted.</p>
 *
 * <p>Ownership is enforced before anything is disclosed: an unknown or foreign look
 * id yields {@link CloneResult#notFound()}.</p>
 */
public final class StudioDraftCloner {

	/**
	 * Why a look source garment cannot be reused.
	 *
	 * <p>{@code GARMENT_NOT_OWNED} is the reachable case: {@code look_item} constrains only
	 * that the referenced {@code vault_item} row exists, not that it belongs to the look's
	 * owner, so a corrupted or repaired row must never be allowed to inject another user's
	 * garment into a draft. {@code GARMENT_DELETED} is defensive against data repair that
	 * bypasses the {@code look_item -> vault_item} foreign key (an ordinary delete is
	 * already refused while a look references the item, so it is unreachable through the
	 * normal API).</p>
	 */
	public enum UnresolvedReason { GARMENT_DELETED, GARMENT_NOT_OWNED }

	public record UnresolvedGarment(StudioSlot slot, VaultItemId vaultItemId, UnresolvedReason reason) {
		public UnresolvedGarment {
			if (slot == null) {
				throw new IllegalArgumentException("slot is required");
			}
			if (vaultItemId == null) {
				throw new IllegalArgumentException("vaultItemId is required");
			}
			if (reason == null) {
				throw new IllegalArgumentException("reason is required");
			}
		}
	}

	public record CloneResult(StudioSession draft, List<UnresolvedGarment> unresolvedGarments, boolean avatarUnavailable) {
		public CloneResult {
			if (draft == null && (!unresolvedGarments.isEmpty() || avatarUnavailable)) {
				throw new IllegalArgumentException("a missing look discloses nothing");
			}
			unresolvedGarments = List.copyOf(unresolvedGarments);
		}

		/** True when the look was not found for this owner; no draft and no detail is disclosed. */
		public boolean notFound() {
			return draft == null;
		}

		public static CloneResult missingLook() {
			return new CloneResult(null, List.of(), false);
		}
	}

	private final LookStore looks;
	private final JdbcTemplate jdbc;

	public StudioDraftCloner(LookStore looks, DataSource dataSource) {
		if (looks == null) {
			throw new IllegalArgumentException("lookStore is required");
		}
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.looks = looks;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * Builds an unsaved draft from the look's frozen provenance. The caller persists it
	 * with {@link JdbcStudioStore#create(StudioSession)}.
	 *
	 * <p>{@code look_item} carries no position, so each cloned item is placed at the next
	 * free index <em>within its own slot</em> — {@code StudioItem.position} is a per-slot
	 * position (see the V006 {@code UNIQUE (studio_session_id, slot, position)} constraint),
	 * not a global list index.</p>
	 */
	public CloneResult cloneFromLook(LookId lookId, UserId ownerId, Instant now) {
		if (lookId == null) {
			throw new IllegalArgumentException("lookId is required");
		}
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		Optional<Look> found = looks.findByIdForUser(lookId, ownerId);
		if (found.isEmpty()) {
			return CloneResult.missingLook();
		}
		Look look = found.get();

		StudioSessionId sessionId = new StudioSessionId(UUID.randomUUID());
		List<StudioItem> items = new ArrayList<>();
		List<UnresolvedGarment> unresolved = new ArrayList<>();
		Map<StudioSlot, Integer> positionInSlot = new EnumMap<>(StudioSlot.class);
		for (LookItem item : look.items()) {
			UnresolvedReason reason = unresolvedReason(item.vaultItemId(), ownerId);
			if (reason == null) {
				int position = positionInSlot.merge(item.slot(), 1, Integer::sum) - 1;
				items.add(new StudioItem(new StudioItemId(UUID.randomUUID()), sessionId, item.slot(),
					item.vaultItemId(), position, now));
			}
			else {
				unresolved.add(new UnresolvedGarment(item.slot(), item.vaultItemId(), reason));
			}
		}

		boolean avatarUnavailable = look.avatarProfileId() != null
			&& !owned("SELECT EXISTS(SELECT 1 FROM avatar_profile WHERE id=? AND user_id=? AND status='ACTIVE')",
				look.avatarProfileId().value(), ownerId.value());
		StudioSession draft = new StudioSession(sessionId, ownerId,
			avatarUnavailable ? null : look.avatarProfileId(), now, now, items);
		return new CloneResult(draft, unresolved, avatarUnavailable);
	}

	private UnresolvedReason unresolvedReason(VaultItemId vaultItemId, UserId ownerId) {
		Optional<UUID> owner = jdbc.query("SELECT user_id FROM vault_item WHERE id=?",
			(rs, row) -> rs.getObject("user_id", UUID.class), vaultItemId.value()).stream().findFirst();
		if (owner.isEmpty()) {
			return UnresolvedReason.GARMENT_DELETED;
		}
		return owner.get().equals(ownerId.value()) ? null : UnresolvedReason.GARMENT_NOT_OWNED;
	}

	private boolean owned(String sql, Object... arguments) {
		return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments));
	}
}
