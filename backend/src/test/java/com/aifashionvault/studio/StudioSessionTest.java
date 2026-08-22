package com.aifashionvault.studio;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioSessionTest {

	private static final StudioSessionId SESSION_ID = new StudioSessionId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final UserId USER_ID = new UserId(
		UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final AvatarProfileId AVATAR_ID = new AvatarProfileId(
		UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-08-22T01:00:00Z");

	@Test
	void recordShapesExcludeConversationTranscriptAndPrompt() {
		assertThat(StudioSession.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "userId", "avatarProfileId", "createdAt", "updatedAt", "items")
			.noneMatch(StudioSessionTest::isConversationTranscriptOrPrompt);
		assertThat(StudioSession.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				StudioSessionId.class,
				UserId.class,
				AvatarProfileId.class,
				Instant.class,
				Instant.class,
				List.class);
		assertThat(StudioItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "studioSessionId", "slot", "vaultItemId", "position", "createdAt")
			.noneMatch(StudioSessionTest::isConversationTranscriptOrPrompt);
		assertThat(StudioItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				StudioItemId.class,
				StudioSessionId.class,
				StudioSlot.class,
				VaultItemId.class,
				int.class,
				Instant.class);
	}

	@Test
	void userIdIsRequiredAndAvatarProfileIdMayBeNull() {
		assertThatThrownBy(() -> new StudioSession(SESSION_ID, null, AVATAR_ID, CREATED_AT, UPDATED_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);

		StudioSession session = emptySession();

		assertThat(session.userId()).isEqualTo(USER_ID);
		assertThat(session.avatarProfileId()).isNull();
		assertThat(session.items()).isEmpty();
	}

	@Test
	void addItemKeepsCompatibleSlotsIncludingFullBodyAndFeet() {
		StudioItem head = item(StudioSlot.HEAD, 0);
		StudioItem fullBody = item(StudioSlot.FULL_BODY, 0);
		StudioItem feet = item(StudioSlot.FEET, 0);
		StudioItem bag = item(StudioSlot.BAG, 0);

		StudioSession session = emptySession()
			.addItem(head)
			.addItem(fullBody)
			.addItem(feet)
			.addItem(bag);

		assertThat(session.items()).containsExactly(head, fullBody, feet, bag);
		assertThat(session.id()).isEqualTo(SESSION_ID);
		assertThat(session.userId()).isEqualTo(USER_ID);
		assertThat(session.createdAt()).isEqualTo(CREATED_AT);
		assertThat(session.updatedAt()).isEqualTo(UPDATED_AT);
	}

	@Test
	void addItemRejectsFullBodyWithUpperOrLowerBody() {
		StudioSession withFullBody = emptySession().addItem(item(StudioSlot.FULL_BODY, 0));
		StudioSession withUpper = emptySession().addItem(item(StudioSlot.UPPER_BODY, 0));
		StudioSession withLower = emptySession().addItem(item(StudioSlot.LOWER_BODY, 0));

		assertThatThrownBy(() -> withFullBody.addItem(item(StudioSlot.UPPER_BODY, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> withFullBody.addItem(item(StudioSlot.LOWER_BODY, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> withUpper.addItem(item(StudioSlot.FULL_BODY, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> withLower.addItem(item(StudioSlot.FULL_BODY, 0)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constructorRejectsConflictingItems() {
		assertThatThrownBy(() -> new StudioSession(
			SESSION_ID,
			USER_ID,
			null,
			CREATED_AT,
			UPDATED_AT,
			List.of(item(StudioSlot.FULL_BODY, 0), item(StudioSlot.UPPER_BODY, 0))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonMultiSlotRejectsSecondItemAndReplaceItemSwapsOccupant() {
		StudioItem first = item(StudioSlot.HEAD, 0);
		StudioItem second = item(StudioSlot.HEAD, 0);
		StudioSession session = emptySession().addItem(first);

		assertThatThrownBy(() -> session.addItem(second))
			.isInstanceOf(IllegalArgumentException.class);

		StudioSession replaced = session.replaceItem(second);

		assertThat(replaced.items()).containsExactly(second);
		assertThat(session.items()).containsExactly(first);
	}

	@Test
	void jewelleryAndAccessoryAllowMultipleDistinctPositions() {
		StudioItem ring = item(StudioSlot.JEWELLERY, 0);
		StudioItem necklace = item(StudioSlot.JEWELLERY, 1);
		StudioItem watch = item(StudioSlot.ACCESSORY, 0);
		StudioItem belt = item(StudioSlot.ACCESSORY, 1);

		StudioSession session = emptySession()
			.addItem(ring)
			.addItem(necklace)
			.addItem(watch)
			.addItem(belt);

		assertThat(session.items()).containsExactly(ring, necklace, watch, belt);
		assertThatThrownBy(() -> session.addItem(item(StudioSlot.JEWELLERY, 0)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void removeItemClearSlotAndClearOutfitEmptyTheSelection() {
		StudioItem head = item(StudioSlot.HEAD, 0);
		StudioItem ring = item(StudioSlot.JEWELLERY, 0);
		StudioItem necklace = item(StudioSlot.JEWELLERY, 1);
		StudioSession session = emptySession()
			.setAvatar(AVATAR_ID)
			.addItem(head)
			.addItem(ring)
			.addItem(necklace);

		StudioSession withoutRing = session.removeItem(ring.id());
		assertThat(withoutRing.items()).containsExactly(head, necklace);

		StudioSession clearedJewellery = withoutRing.clearSlot(StudioSlot.JEWELLERY);
		assertThat(clearedJewellery.items()).containsExactly(head);

		StudioSession emptyOutfit = clearedJewellery.clearOutfit();
		assertThat(emptyOutfit.items()).isEmpty();
		assertThat(emptyOutfit.avatarProfileId()).isEqualTo(AVATAR_ID);
		assertThat(emptyOutfit.clearSlot(StudioSlot.HEAD).items()).isEmpty();
	}

	@Test
	void setAvatarReplacesOrClearsAvatarProfileId() {
		StudioSession withAvatar = emptySession().setAvatar(AVATAR_ID);
		assertThat(withAvatar.avatarProfileId()).isEqualTo(AVATAR_ID);

		StudioSession cleared = withAvatar.setAvatar(null);
		assertThat(cleared.avatarProfileId()).isNull();
		assertThat(cleared.items()).isEmpty();
	}

	@Test
	void itemsAreAnUnmodifiableCopy() {
		StudioItem head = item(StudioSlot.HEAD, 0);
		List<StudioItem> mutable = new ArrayList<>();
		mutable.add(head);
		StudioSession session = new StudioSession(SESSION_ID, USER_ID, null, CREATED_AT, UPDATED_AT, mutable);

		mutable.clear();

		assertThat(session.items()).containsExactly(head);
		assertThatThrownBy(() -> session.items().add(item(StudioSlot.FEET, 0)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studioItemRequiresIdentitySlotAndVaultReference() {
		StudioItemId itemId = new StudioItemId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
		VaultItemId vaultItemId = new VaultItemId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

		assertThatThrownBy(() -> new StudioItem(null, SESSION_ID, StudioSlot.HEAD, vaultItemId, 0, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioItem(itemId, null, StudioSlot.HEAD, vaultItemId, 0, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioItem(itemId, SESSION_ID, null, vaultItemId, 0, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioItem(itemId, SESSION_ID, StudioSlot.HEAD, null, 0, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StudioItem(itemId, SESSION_ID, StudioSlot.HEAD, vaultItemId, 0, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static boolean isConversationTranscriptOrPrompt(String name) {
		String lower = name.toLowerCase();
		return lower.contains("conversation") || lower.contains("transcript") || lower.contains("prompt");
	}

	private static StudioSession emptySession() {
		return new StudioSession(SESSION_ID, USER_ID, null, CREATED_AT, UPDATED_AT, List.of());
	}

	private static StudioItem item(StudioSlot slot, int position) {
		return new StudioItem(
			new StudioItemId(UUID.randomUUID()),
			SESSION_ID,
			slot,
			new VaultItemId(UUID.randomUUID()),
			position,
			CREATED_AT);
	}
}
