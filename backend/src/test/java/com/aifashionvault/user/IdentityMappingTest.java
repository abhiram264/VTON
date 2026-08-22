package com.aifashionvault.user;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.IdentityMappingId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityMappingTest {

	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final IdentityMappingId MAPPING_ID = new IdentityMappingId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-02T00:00:00Z");
	private static final Instant DELETED_AT = Instant.parse("2026-01-03T00:00:00Z");

	@Test
	void userWrapsUserIdAndHasNoFirebaseUidConsentOrTenantComponents() {
		User user = new User(USER_ID, CREATED_AT, UPDATED_AT, null);

		assertThat(user.id()).isEqualTo(USER_ID);
		assertThat(user.createdAt()).isEqualTo(CREATED_AT);
		assertThat(user.updatedAt()).isEqualTo(UPDATED_AT);
		assertThat(user.deletedAt()).isNull();
		assertThat(User.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "createdAt", "updatedAt", "deletedAt")
			.doesNotContain("firebaseUid", "consent", "orgId", "tenantId");
		assertThat(User.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(UserId.class, Instant.class, Instant.class, Instant.class)
			.doesNotContain(String.class, boolean.class, Boolean.class);
	}

	@Test
	void userRejectsNullRequiredFields() {
		assertThatThrownBy(() -> new User(null, CREATED_AT, UPDATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User(USER_ID, null, UPDATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new User(USER_ID, CREATED_AT, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void markDeletedSetsDeletedAtAndUpdatedAt() {
		User user = new User(USER_ID, CREATED_AT, UPDATED_AT, null);

		User deleted = user.markDeleted(DELETED_AT);

		assertThat(deleted.id()).isEqualTo(USER_ID);
		assertThat(deleted.createdAt()).isEqualTo(CREATED_AT);
		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(deleted.updatedAt()).isEqualTo(DELETED_AT);
		assertThat(user.deletedAt()).isNull();
	}

	@Test
	void markDeletedRejectsNullAndAlreadyDeleted() {
		User user = new User(USER_ID, CREATED_AT, UPDATED_AT, null);

		assertThatThrownBy(() -> user.markDeleted(null))
			.isInstanceOf(IllegalArgumentException.class);

		User deleted = user.markDeleted(DELETED_AT);
		assertThatThrownBy(() -> deleted.markDeleted(Instant.parse("2026-01-04T00:00:00Z")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void identityMappingRejectsNullUserIdAndBlankIssuerSubject() {
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, null, "https://securetoken.google.com/app", "uid-1", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, null, "uid-1", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, "", "uid-1", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, "   ", "uid-1", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, "https://securetoken.google.com/app", null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, "https://securetoken.google.com/app", "", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdentityMapping(MAPPING_ID, USER_ID, "https://securetoken.google.com/app", "   ", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sameExternalIdentityIsIssuerAndSubjectEquality() {
		IdentityMapping left = new IdentityMapping(MAPPING_ID, USER_ID, "issuer-a", "subject-a", CREATED_AT);
		IdentityMapping sameKey = new IdentityMapping(
			new IdentityMappingId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
			new UserId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
			"issuer-a",
			"subject-a",
			CREATED_AT);
		IdentityMapping differentSubject = new IdentityMapping(MAPPING_ID, USER_ID, "issuer-a", "subject-b", CREATED_AT);
		IdentityMapping differentIssuer = new IdentityMapping(MAPPING_ID, USER_ID, "issuer-b", "subject-a", CREATED_AT);

		assertThat(left.sameExternalIdentity(sameKey)).isTrue();
		assertThat(left.sameExternalIdentity(differentSubject)).isFalse();
		assertThat(left.sameExternalIdentity(differentIssuer)).isFalse();
	}
}
