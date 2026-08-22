package com.aifashionvault.look;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookTest {

	private static final LookId LOOK_ID = new LookId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final UserId USER_ID = new UserId(
		UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final AvatarProfileId AVATAR_PROFILE_ID = new AvatarProfileId(
		UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final GenerationJobId GENERATION_JOB_ID = new GenerationJobId(
		UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final StudioSessionId STUDIO_SESSION_ID = new StudioSessionId(
		UUID.fromString("44444444-4444-4444-4444-444444444444"));
	private static final VaultItemId VAULT_ITEM_ID = new VaultItemId(
		UUID.fromString("55555555-5555-5555-5555-555555555555"));
	private static final StorageKey STORAGE_KEY = new StorageKey(
		"users/11111111-1111-1111-1111-111111111111/looks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg");
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

	@Test
	void passQualityDecisionConstructsLook() {
		LookItem item = new LookItem(LOOK_ID, VAULT_ITEM_ID, StudioSlot.UPPER_BODY);

		Look look = look(QualityDecision.PASS, List.of(item));

		assertThat(look.id()).isEqualTo(LOOK_ID);
		assertThat(look.userId()).isEqualTo(USER_ID);
		assertThat(look.avatarProfileId()).isEqualTo(AVATAR_PROFILE_ID);
		assertThat(look.generationJobId()).isEqualTo(GENERATION_JOB_ID);
		assertThat(look.studioSessionId()).isEqualTo(STUDIO_SESSION_ID);
		assertThat(look.storageKey()).isEqualTo(STORAGE_KEY);
		assertThat(look.renderPlan()).isEqualTo("{\"items\":1}");
		assertThat(look.provider()).isEqualTo("fashn");
		assertThat(look.providerModel()).isEqualTo("tryon-v1");
		assertThat(look.qualityStatus()).isEqualTo(QualityDecision.PASS);
		assertThat(look.createdAt()).isEqualTo(CREATED_AT);
		assertThat(look.items()).containsExactly(item);
	}

	@Test
	void retryAndFailQualityDecisionThrow() {
		assertThatThrownBy(() -> look(QualityDecision.RETRY, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> look(QualityDecision.FAIL, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> look(null, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void mediaIsStorageKeyAndUrlCannotBeStored() {
		assertThat(Look.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl", "image_url", "url");
		assertThat(Look.class.getRecordComponents())
			.filteredOn(component -> component.getName().equals("storageKey"))
			.extracting(RecordComponent::getType)
			.containsExactly(StorageKey.class);
		assertThat(Look.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(byte[].class);

		assertThatThrownBy(() -> Look.class.getConstructor(
			LookId.class,
			UserId.class,
			AvatarProfileId.class,
			GenerationJobId.class,
			StudioSessionId.class,
			String.class,
			String.class,
			String.class,
			String.class,
			QualityDecision.class,
			Instant.class,
			List.class))
			.isInstanceOf(NoSuchMethodException.class);

		assertThatThrownBy(() -> new Look(
			LOOK_ID,
			USER_ID,
			AVATAR_PROFILE_ID,
			GENERATION_JOB_ID,
			STUDIO_SESSION_ID,
			new StorageKey("https://example.invalid/users/u/looks/l.jpg"),
			null,
			null,
			null,
			QualityDecision.PASS,
			CREATED_AT,
			List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void lookItemMayOmitSlot() {
		LookItem item = new LookItem(LOOK_ID, VAULT_ITEM_ID, null);

		assertThat(item.slot()).isNull();
		assertThat(item.lookId()).isEqualTo(LOOK_ID);
		assertThat(item.vaultItemId()).isEqualTo(VAULT_ITEM_ID);
	}

	@Test
	void lookItemRequiresLookIdAndVaultItemIdAndHasNoLookItemId() {
		assertThatThrownBy(() -> new LookItem(null, VAULT_ITEM_ID, StudioSlot.HEAD))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LookItem(LOOK_ID, null, StudioSlot.HEAD))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(LookItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("lookId", "vaultItemId", "slot");
		assertThat(LookItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(LookId.class, VaultItemId.class, StudioSlot.class);
		assertThat(componentTypeNames(LookItem.class)).doesNotContain("LookItemId");
	}

	@Test
	void itemsHoldConstituentVaultItemReferences() {
		LookItem withSlot = new LookItem(LOOK_ID, VAULT_ITEM_ID, StudioSlot.FEET);
		LookItem withoutSlot = new LookItem(
			LOOK_ID,
			new VaultItemId(UUID.fromString("66666666-6666-6666-6666-666666666666")),
			null);

		Look look = look(QualityDecision.PASS, List.of(withSlot, withoutSlot));

		assertThat(look.items()).containsExactly(withSlot, withoutSlot);
		assertThat(look.items())
			.extracting(LookItem::lookId)
			.containsOnly(LOOK_ID);
		assertThat(look.items())
			.extracting(LookItem::vaultItemId)
			.containsExactly(withSlot.vaultItemId(), withoutSlot.vaultItemId());
		assertThat(componentTypeNames(Look.class)).doesNotContain("VaultItem");
		assertThat(componentTypeNames(LookItem.class)).doesNotContain("VaultItem");
		assertThat(componentTypeNames(Look.class))
			.doesNotContain("QualityResult", "GenerationAttempt");
	}

	@Test
	void userIdAndStorageKeyAreRequiredAndThereIsNoOrgOrTenant() {
		assertThatThrownBy(() -> new Look(
			LOOK_ID,
			null,
			AVATAR_PROFILE_ID,
			GENERATION_JOB_ID,
			STUDIO_SESSION_ID,
			STORAGE_KEY,
			null,
			null,
			null,
			QualityDecision.PASS,
			CREATED_AT,
			List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Look(
			LOOK_ID,
			USER_ID,
			AVATAR_PROFILE_ID,
			GENERATION_JOB_ID,
			STUDIO_SESSION_ID,
			null,
			null,
			null,
			null,
			QualityDecision.PASS,
			CREATED_AT,
			List.of()))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(Look.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.contains("userId")
			.doesNotContain("orgId", "org_id", "tenantId", "tenant_id");
		assertThat(Look.class.getPackageName()).isEqualTo("com.aifashionvault.look");
		assertThat(LookItem.class.getPackageName()).isEqualTo("com.aifashionvault.look");
	}

	@Test
	void itemsAreStoredAsUnmodifiableCopy() {
		LookItem item = new LookItem(LOOK_ID, VAULT_ITEM_ID, StudioSlot.BAG);
		List<LookItem> mutable = new ArrayList<>();
		mutable.add(item);

		Look look = look(QualityDecision.PASS, mutable);
		mutable.clear();

		assertThat(look.items()).containsExactly(item);
		assertThatThrownBy(() -> look.items().add(new LookItem(LOOK_ID, VAULT_ITEM_ID, null)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void optionalProvenanceFieldsMayBeNull() {
		Look look = new Look(
			LOOK_ID,
			USER_ID,
			null,
			null,
			null,
			STORAGE_KEY,
			null,
			null,
			null,
			QualityDecision.PASS,
			CREATED_AT,
			List.of());

		assertThat(look.avatarProfileId()).isNull();
		assertThat(look.generationJobId()).isNull();
		assertThat(look.studioSessionId()).isNull();
		assertThat(look.renderPlan()).isNull();
		assertThat(look.provider()).isNull();
		assertThat(look.providerModel()).isNull();
		assertThat(look.userId()).isEqualTo(USER_ID);
		assertThat(look.storageKey()).isEqualTo(STORAGE_KEY);
		assertThat(look.qualityStatus()).isEqualTo(QualityDecision.PASS);
		assertThat(look.items()).isEmpty();
	}

	@Test
	void recordShapesMatchFrozenLookAndLookItem() {
		assertThat(Look.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"userId",
				"avatarProfileId",
				"generationJobId",
				"studioSessionId",
				"storageKey",
				"renderPlan",
				"provider",
				"providerModel",
				"qualityStatus",
				"createdAt",
				"items");
		assertThat(Look.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				LookId.class,
				UserId.class,
				AvatarProfileId.class,
				GenerationJobId.class,
				StudioSessionId.class,
				StorageKey.class,
				String.class,
				String.class,
				String.class,
				QualityDecision.class,
				Instant.class,
				List.class);
	}

	private static Look look(QualityDecision qualityStatus, List<LookItem> items) {
		return new Look(
			LOOK_ID,
			USER_ID,
			AVATAR_PROFILE_ID,
			GENERATION_JOB_ID,
			STUDIO_SESSION_ID,
			STORAGE_KEY,
			"{\"items\":1}",
			"fashn",
			"tryon-v1",
			qualityStatus,
			CREATED_AT,
			items);
	}

	private static List<String> componentTypeNames(Class<?> type) {
		List<String> names = new ArrayList<>();
		for (RecordComponent component : type.getRecordComponents()) {
			names.add(component.getName());
			names.add(component.getType().getSimpleName());
		}
		return names;
	}
}
