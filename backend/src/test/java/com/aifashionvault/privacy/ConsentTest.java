package com.aifashionvault.privacy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentTest {

	private static final ConsentId ID = new ConsentId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId USER_ID = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant GRANTED_AT = Instant.parse("2026-01-15T10:00:00Z");
	private static final Instant REVOKED_AT = Instant.parse("2026-02-01T12:00:00Z");

	@Test
	void constructsWithNullRevokedAt() {
		Consent consent = new Consent(ID, USER_ID, "avatar-processing", "1.0", GRANTED_AT, null);

		assertThat(consent.id()).isEqualTo(ID);
		assertThat(consent.userId()).isEqualTo(USER_ID);
		assertThat(consent.purpose()).isEqualTo("avatar-processing");
		assertThat(consent.version()).isEqualTo("1.0");
		assertThat(consent.grantedAt()).isEqualTo(GRANTED_AT);
		assertThat(consent.revokedAt()).isNull();
	}

	@Test
	void constructsWithRevokedAtSet() {
		Consent consent = new Consent(ID, USER_ID, "avatar-processing", "1.0", GRANTED_AT, REVOKED_AT);

		assertThat(consent.revokedAt()).isEqualTo(REVOKED_AT);
	}

	@Test
	void rejectsBlankPurpose() {
		assertThatThrownBy(() -> new Consent(ID, USER_ID, "", "1.0", GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Consent(ID, USER_ID, "   ", "1.0", GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Consent(ID, USER_ID, null, "1.0", GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankVersion() {
		assertThatThrownBy(() -> new Consent(ID, USER_ID, "avatar-processing", "", GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Consent(ID, USER_ID, "avatar-processing", "   ", GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Consent(ID, USER_ID, "avatar-processing", null, GRANTED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void userHasNoConsentComponentWhenVisible() {
		Class<?> user;
		try {
			user = Class.forName("com.aifashionvault.user.User");
		}
		catch (ClassNotFoundException ex) {
			return;
		}
		RecordComponent[] components = user.getRecordComponents();
		if (components != null) {
			assertThat(components)
				.extracting(RecordComponent::getName)
				.doesNotContain("consent");
		}
	}
}
