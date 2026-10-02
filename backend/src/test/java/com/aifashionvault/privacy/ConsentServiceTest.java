package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");
	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	private InMemoryConsentStore store;
	private ConsentService service;

	@BeforeEach
	void setUp() {
		store = new InMemoryConsentStore();
		service = new ConsentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void grantRecordsThePolicyVersionAndIsIdempotentForTheSameVersion() {
		Consent first = service.grant(OWNER, ConsentPurpose.BODY_IMAGE_PROCESSING, "2026-10-01");
		Consent replay = service.grant(OWNER, ConsentPurpose.BODY_IMAGE_PROCESSING, "2026-10-01");

		assertThat(first.version()).isEqualTo("2026-10-01");
		assertThat(first.grantedAt()).isEqualTo(NOW);
		assertThat(first.revokedAt()).isNull();
		assertThat(replay.id()).isEqualTo(first.id());
		assertThat(service.list(OWNER)).hasSize(1);
	}

	@Test
	void aNewPolicyVersionSupersedesTheActiveGrantButKeepsHistory() {
		service.grant(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v1");
		Consent second = service.grant(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v2");

		assertThat(second.version()).isEqualTo("v2");
		assertThat(service.isActive(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).isTrue();
		assertThat(service.requireActive(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER).version()).isEqualTo("v2");
		assertThat(service.list(OWNER)).hasSize(2);
		assertThat(service.list(OWNER).get(0).revokedAt()).isEqualTo(NOW);
	}

	@Test
	void withdrawalIsDistinguishableFromNeverGranted() {
		assertThat(service.withdraw(OWNER, ConsentPurpose.RETENTION)).isFalse();
		service.grant(OWNER, ConsentPurpose.RETENTION, "v1");

		assertThat(service.withdraw(OWNER, ConsentPurpose.RETENTION)).isTrue();
		assertThat(service.isActive(OWNER, ConsentPurpose.RETENTION)).isFalse();
		assertThat(service.withdraw(OWNER, ConsentPurpose.RETENTION)).isFalse();
		assertThat(service.list(OWNER)).hasSize(1);
		assertThat(service.list(OWNER).get(0).revokedAt()).isEqualTo(NOW);
	}

	@Test
	void requireActiveRefusesAWithdrawnPurpose() {
		service.grant(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v1");
		service.withdraw(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER);

		assertThatThrownBy(() -> service.requireActive(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER))
			.isInstanceOf(ConsentRequiredException.class)
			.hasFieldOrPropertyWithValue("purpose", ConsentPurpose.THIRD_PARTY_AI_TRANSFER);
	}

	@Test
	void grantsAreOwnerScoped() {
		UserId other = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		service.grant(OWNER, ConsentPurpose.BODY_IMAGE_PROCESSING, "v1");

		assertThat(service.list(other)).isEmpty();
		assertThat(service.isActive(other, ConsentPurpose.BODY_IMAGE_PROCESSING)).isFalse();
		assertThat(service.withdraw(other, ConsentPurpose.BODY_IMAGE_PROCESSING)).isFalse();
	}

	@Test
	void policyVersionMustBePresentAndBounded() {
		assertThatThrownBy(() -> service.grant(OWNER, ConsentPurpose.RETENTION, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.grant(OWNER, ConsentPurpose.RETENTION, "   "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.grant(OWNER, ConsentPurpose.RETENTION, "v".repeat(65)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(service.grant(OWNER, ConsentPurpose.RETENTION, "  v1  ").version()).isEqualTo("v1");
	}

	@Test
	void unknownWirePurposeIsNotARecognisedPurpose() {
		assertThat(ConsentPurpose.fromWire("body_image_processing")).contains(ConsentPurpose.BODY_IMAGE_PROCESSING);
		assertThat(ConsentPurpose.fromWire(" BODY_IMAGE_PROCESSING ")).contains(ConsentPurpose.BODY_IMAGE_PROCESSING);
		assertThat(ConsentPurpose.fromWire("marketing")).isEmpty();
		assertThat(ConsentPurpose.fromWire(null)).isEmpty();
	}
}
