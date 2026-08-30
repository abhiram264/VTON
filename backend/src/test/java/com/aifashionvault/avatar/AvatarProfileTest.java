package com.aifashionvault.avatar;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarProfileTest {

	private static final UUID PROFILE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID IMAGE_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID UPLOAD_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");
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
			1,
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
			1,
			CREATED_AT,
			UPDATED_AT);

		assertThat(profile.userId()).isEqualTo(userId);
		assertThat(AvatarProfile.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "userId", "primaryImageId", "status", "revision", "createdAt", "updatedAt")
			.doesNotContain("orgId", "tenantId", "imageUrl");
		assertThat(AvatarImage.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"profileId",
				"storageKey",
				"width",
				"height",
				"pose",
				"qualityStatus",
				"createdAt",
				"sourceUploadId",
				"lifecycleState",
				"warnings",
				"preflightPolicyVersion")
			.doesNotContain("orgId", "tenantId", "imageUrl", "base64");
	}

	@Test
	void primaryImageIdMayBeNull() {
		AvatarProfile profile = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			1,
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
			1,
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
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			null,
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"   ",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			1,
			null,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			1,
			CREATED_AT,
			null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void statusMustBeActiveOrDeleting() {
		AvatarProfile active = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			1,
			CREATED_AT,
			UPDATED_AT);
		AvatarProfile deleting = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"DELETING",
			1,
			CREATED_AT,
			UPDATED_AT);

		assertThat(active.status()).isEqualTo("ACTIVE");
		assertThat(deleting.status()).isEqualTo("DELETING");
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"DELETED",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"CREATING",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"PROCESSING",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"FAILED",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"READY",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"PENDING",
			1,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void revisionMustBeAtLeastOne() {
		AvatarProfile profile = new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			1,
			CREATED_AT,
			UPDATED_AT);

		assertThat(profile.revision()).isEqualTo(1);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			0,
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProfile(
			new AvatarProfileId(PROFILE_UUID),
			new UserId(USER_UUID),
			null,
			"ACTIVE",
			-1,
			CREATED_AT,
			UPDATED_AT))
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
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
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
			Instant.class,
			UploadId.class,
			AvatarImageLifecycle.class,
			List.class,
			String.class))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			new StorageKey("https://example.invalid/users/u/avatars/a.jpg"),
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
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
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);

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
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);

		assertThat(image.width()).isNull();
		assertThat(image.height()).isNull();
		assertThat(image.pose()).isNull();
	}

	@Test
	void nonNullDimensionsMustBePositive() {
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			0,
			1024,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			768,
			-1,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);

		AvatarImage nullWidth = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			1024,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
		AvatarImage nullHeight = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			768,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);

		assertThat(nullWidth.width()).isNull();
		assertThat(nullWidth.height()).isEqualTo(1024);
		assertThat(nullHeight.width()).isEqualTo(768);
		assertThat(nullHeight.height()).isNull();
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
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"  ",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void qualityStatusMustBeAccepted() {
		AvatarImage accepted = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);

		assertThat(accepted.qualityStatus()).isEqualTo("ACCEPTED");
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"UNCHECKED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"PENDING",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sourceUploadIdMayBeNullOrSet() {
		AvatarImage withoutUpload = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
		UploadId uploadId = new UploadId(UPLOAD_UUID);
		AvatarImage withUpload = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			uploadId,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);

		assertThat(withoutUpload.sourceUploadId()).isNull();
		assertThat(withUpload.sourceUploadId()).isEqualTo(uploadId);
	}

	@Test
	void preflightPolicyVersionMayBeNullButNotBlank() {
		AvatarImage legacy = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
		AvatarImage inspected = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			"policy-model-bundle-v7");

		assertThat(legacy.preflightPolicyVersion()).isNull();
		assertThat(inspected.preflightPolicyVersion()).isEqualTo("policy-model-bundle-v7");
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			"\t "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void lifecycleStateIsActiveOrDeletionPending() {
		AvatarImage active = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
		AvatarImage deletionPending = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.DELETION_PENDING,
			List.of(),
			null);

		assertThat(active.lifecycleState()).isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(deletionPending.lifecycleState()).isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			null,
			List.of(),
			null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(AvatarImageLifecycle.values())
			.extracting(Enum::name)
			.containsExactly("ACTIVE", "DELETION_PENDING");
	}

	@Test
	void warningsAreNeverNullAndImmutable() {
		assertThatThrownBy(() -> new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			null,
			null))
			.isInstanceOf(IllegalArgumentException.class);

		AvatarImage emptyWarnings = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
		assertThat(emptyWarnings.warnings()).isEmpty();

		List<String> mutable = new ArrayList<>();
		mutable.add("borderline-blur");
		AvatarImage image = new AvatarImage(
			new AvatarImageId(IMAGE_UUID),
			new AvatarProfileId(PROFILE_UUID),
			STORAGE_KEY,
			null,
			null,
			null,
			"ACCEPTED",
			CREATED_AT,
			null,
			AvatarImageLifecycle.ACTIVE,
			mutable,
			null);
		mutable.add("mutated");

		assertThat(image.warnings()).containsExactly("borderline-blur");
		assertThatThrownBy(() -> image.warnings().add("extra"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void avatarProblemCodesMatchFrozenLiterals() {
		assertThat(AvatarProblemCode.values())
			.extracting(Enum::name)
			.containsExactlyInAnyOrder(
				"AVATAR_UNREADABLE",
				"AVATAR_UNSUPPORTED_FORMAT",
				"AVATAR_TYPE_MISMATCH",
				"AVATAR_RESOLUTION_INSUFFICIENT",
				"AVATAR_BYTES_EXCEEDED",
				"AVATAR_DIMENSIONS_EXCEEDED",
				"AVATAR_PIXELS_EXCEEDED",
				"AVATAR_ASPECT_RATIO",
				"AVATAR_TOO_BLURRED",
				"AVATAR_TOO_DARK",
				"AVATAR_CONFLICT",
				"AVATAR_STALE_REVISION",
				"AVATAR_NOT_FOUND",
				"AVATAR_UPLOAD_NOT_CLAIMABLE",
				"AVATAR_MEDIA_MISSING",
				"AVATAR_PERSON_NOT_SINGLE",
				"AVATAR_FACE_NOT_VISIBLE",
				"AVATAR_BODY_NOT_VISIBLE",
				"AVATAR_PREFLIGHT_UNAVAILABLE");
	}

	@Test
	void avatarExceptionMessageIsOnlyCodeName() {
		for (AvatarProblemCode code : AvatarProblemCode.values()) {
			AvatarException exception = new AvatarException(code);

			assertThat(exception.code()).isEqualTo(code);
			assertThat(exception.getMessage()).isEqualTo(code.name());
			assertThat(exception.getMessage()).doesNotContain("storage key", "http", "signed", "base64");
		}
		assertThatThrownBy(() -> new AvatarException(null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
