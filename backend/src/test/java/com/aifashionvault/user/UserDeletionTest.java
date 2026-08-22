package com.aifashionvault.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.IdentityMappingId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDeletionTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final IdentityMappingId IDENTITY_MAPPING_ID = new IdentityMappingId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final AvatarProfileId AVATAR_PROFILE_ID = new AvatarProfileId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final AvatarImageId AVATAR_IMAGE_ID = new AvatarImageId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
	private static final ShareImportId SHARE_IMPORT_ID = new ShareImportId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
	private static final VaultItemId VAULT_ITEM_ID = new VaultItemId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
	private static final CollectionId COLLECTION_ID = new CollectionId(UUID.fromString("77777777-7777-7777-7777-777777777777"));
	private static final StudioSessionId STUDIO_SESSION_ID = new StudioSessionId(UUID.fromString("88888888-8888-8888-8888-888888888888"));
	private static final GenerationJobId GENERATION_JOB_ID = new GenerationJobId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
	private static final LookId LOOK_ID = new LookId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final NotificationId NOTIFICATION_ID = new NotificationId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final ConsentId CONSENT_ID = new ConsentId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-02T00:00:00Z");
	private static final Instant DELETED_AT = Instant.parse("2026-01-03T00:00:00Z");

	private static final List<String> NON_CASCADE_RELATIONSHIPS = List.of(
		"users",
		"identity_mapping",
		"avatar_profile",
		"avatar_image",
		"share_import",
		"vault_item",
		"collection",
		"studio_session",
		"generation_job",
		"look",
		"notification",
		"consent",
		"COLLECTION_ITEM",
		"unknown");

	@Test
	void markDeletedReturnsUserWithDeletedAtSet() {
		User user = new User(USER_ID, CREATED_AT, UPDATED_AT, null);

		User deleted = UserDeletion.markDeleted(user, DELETED_AT);

		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(deleted.id()).isEqualTo(USER_ID);
		assertThat(deleted.createdAt()).isEqualTo(CREATED_AT);
		assertThat(deleted.updatedAt()).isEqualTo(DELETED_AT);
		assertThat(user.deletedAt()).isNull();
		assertThat(deleted).isEqualTo(user.markDeleted(DELETED_AT));
	}

	@Test
	void mayCascadeIsTrueOnlyForCollectionItem() {
		assertThat(UserDeletion.mayCascade("collection_item")).isTrue();
		for (String relationshipName : NON_CASCADE_RELATIONSHIPS) {
			assertThat(UserDeletion.mayCascade(relationshipName))
				.as(relationshipName)
				.isFalse();
		}
	}

	@Test
	void mayCascadeRejectsNull() {
		assertThatThrownBy(() -> UserDeletion.mayCascade(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void markDeletedDoesNotRemoveOwnedAggregateIds() {
		User user = new User(USER_ID, CREATED_AT, UPDATED_AT, null);
		Set<UserId> userIds = new HashSet<>(Set.of(USER_ID));
		Set<IdentityMappingId> identityMappingIds = new HashSet<>(Set.of(IDENTITY_MAPPING_ID));
		Set<AvatarProfileId> avatarProfileIds = new HashSet<>(Set.of(AVATAR_PROFILE_ID));
		Set<AvatarImageId> avatarImageIds = new HashSet<>(Set.of(AVATAR_IMAGE_ID));
		Set<ShareImportId> shareImportIds = new HashSet<>(Set.of(SHARE_IMPORT_ID));
		Set<VaultItemId> vaultItemIds = new HashSet<>(Set.of(VAULT_ITEM_ID));
		Set<CollectionId> collectionIds = new HashSet<>(Set.of(COLLECTION_ID));
		Set<StudioSessionId> studioSessionIds = new HashSet<>(Set.of(STUDIO_SESSION_ID));
		Set<GenerationJobId> generationJobIds = new HashSet<>(Set.of(GENERATION_JOB_ID));
		Set<LookId> lookIds = new HashSet<>(Set.of(LOOK_ID));
		Set<NotificationId> notificationIds = new HashSet<>(Set.of(NOTIFICATION_ID));
		Set<ConsentId> consentIds = new HashSet<>(Set.of(CONSENT_ID));

		User deleted = UserDeletion.markDeleted(user, DELETED_AT);

		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(userIds).containsExactly(USER_ID);
		assertThat(identityMappingIds).containsExactly(IDENTITY_MAPPING_ID);
		assertThat(avatarProfileIds).containsExactly(AVATAR_PROFILE_ID);
		assertThat(avatarImageIds).containsExactly(AVATAR_IMAGE_ID);
		assertThat(shareImportIds).containsExactly(SHARE_IMPORT_ID);
		assertThat(vaultItemIds).containsExactly(VAULT_ITEM_ID);
		assertThat(collectionIds).containsExactly(COLLECTION_ID);
		assertThat(studioSessionIds).containsExactly(STUDIO_SESSION_ID);
		assertThat(generationJobIds).containsExactly(GENERATION_JOB_ID);
		assertThat(lookIds).containsExactly(LOOK_ID);
		assertThat(notificationIds).containsExactly(NOTIFICATION_ID);
		assertThat(consentIds).containsExactly(CONSENT_ID);
	}
}
