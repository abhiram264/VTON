package com.aifashionvault.avatar;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarProfileTest {

	private static final UUID PROFILE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID IMAGE_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-15T11:00:00Z");
	private static final StorageKey STORAGE_KEY = new StorageKey(
		"users/22222222-2222-2222-2222-222222222222/avatars/33333333-3333-3333-3333-333333333333.jpg");

	@Test
	void requiresUserId() {
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			null,
			null,
			"ACTIVE",
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ownsProfileByUserIdOnly() {
		UserId userId = new UserId(USER_UUID);
		AvatarProfile profile = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			userId,
			null,
			"ACTIVE",
			CREATED_AT,
			UPDATED_AT);

		assertThat(profile.userId()).isEqualTo(userId);
		assertThat(AvatarProfile.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.contains("userId")
			.doesNotContain("orgId", "tenantId");
		assertThat(AvatarImage.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("orgId", "tenantId");
	}

	@Test
	void primaryImageIdMayBeNull() {
		AvatarProfile profile = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			CREATED_AT,
			UPDATED_AT);

		assertThat(profile.primaryImageId()).isNull();
	}

	@Test
	void primaryImageIdMayBeSet() {
		AvatarImageId imageId = new AvatarImageId(IMAGE_UUID);
		AvatarProfile profile = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			imageId,
			"ACTIVE",
			CREATED_AT,
			UPDATED_AT);

		assertThat(profile.primaryImageId()).isEqualTo(imageId);
	}

	@Test
	void requiresNonBlankStatusAndRequiredFields() {
		assertThatThrownBy(() -> new AvatarProfile(
			null,
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			null,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"   ",
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			null,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			CREATED_AT,
			null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresStorageKey() {
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			null,
			null,
			null,
			null,
			"PENDING",
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void urlCannotBeUsedAsImageHandle() {
		assertThatThrownBy(() -> AvatarImage.class.getConstructor(
			AvatarImageId.class,
			AvatarProfileId.class,
			String.class,
			Integer.class,
			Integer.class,
			String.class,
			String.class,
			Instant.class))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			new StorageKey("https://example.invalid/users/u/avatars/a.jpg"),
			null,
			null,
			null,
			"PENDING",
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void storesStorageKeyOnly() {
		AvatarImage image = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			768,
			1024,
			"front",
			"PENDING",
			CREATED_AT);

		assertThat(image.storageKey()).isEqualTo(STORAGE_KEY);
		assertThat(image.width()).isEqualTo(768);
		assertThat(image.height()).isEqualTo(1024);
		assertThat(image.pose()).isEqualTo("front");
		assertThat(AvatarImage.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.contains(StorageKey.class)
			.doesNotContain(byte[].class);
		assertThat(AvatarProfile.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl");
		assertThat(AvatarImage.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl", "base64");
	}

	@Test
	void imageDimensionsAndPoseMayBeNull() {
		AvatarImage image = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"PENDING",
			CREATED_AT);

		assertThat(image.width()).isNull();
		assertThat(image.height()).isNull();
		assertThat(image.pose()).isNull();
	}

	@Test
	void requiresNonBlankQualityStatus() {
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			null,
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"  ",
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
