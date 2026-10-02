package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentServiceTest {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));
	private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

	private JdbcTemplate jdbc;
	private ConsentService consents;
	private UserId owner;
	private UserId stranger;
	private final AtomicReference<Instant> now = new AtomicReference<>(T0);

	@BeforeAll
	static void database() {
		POSTGRES.start();
	}

	@BeforeEach
	void reset() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).load().migrate();
		jdbc = new JdbcTemplate(source);
		jdbc.update("TRUNCATE consent, users CASCADE");
		owner = user();
		stranger = user();
		now.set(T0);
		// A settable clock: two grants at the same instant have no meaningful
		// order, so a later policy revision must be granted at a later time.
		Clock clock = new Clock() {
			@Override
			public Instant instant() {
				return now.get();
			}

			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}
		};
		consents = new ConsentService(new JdbcConsentStore(source), clock, "2026-10-03");
	}

	@Test
	void aGrantedPurposeIsActiveAndRepeatedGrantIsIdempotent() {
		Consent granted = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		assertThat(granted.version()).isEqualTo("2026-10-03");
		assertThat(granted.purpose()).isEqualTo("body_image_processing");
		assertThat(granted.grantedAt()).isEqualTo(T0);
		assertThat(consents.isActive(owner, ConsentPurpose.BODY_IMAGE_PROCESSING)).isTrue();

		Consent again = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		assertThat(again.id()).isEqualTo(granted.id());
		assertThat(countConsents(owner)).isEqualTo(1);
	}

	@Test
	void grantingOnePurposeDoesNotConferAnother() {
		consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		assertThat(consents.isActive(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).isFalse();
	}

	@Test
	void withdrawalStopsNewProcessingAndKeepsTheRecord() {
		consents.grant(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER);

		assertThat(consents.withdraw(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).isTrue();

		assertThat(consents.isActive(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).isFalse();
		// The grant history survives: the record is revoked, not deleted.
		assertThat(countConsents(owner)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM consent WHERE user_id = ? AND revoked_at IS NOT NULL",
			Long.class, owner.value())).isEqualTo(1);
	}

	@Test
	void withdrawingAPurposeThatWasNeverGrantedReportsNothingWasRevoked() {
		assertThat(consents.withdraw(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).isFalse();
	}

	@Test
	void regrantingAfterWithdrawalIsANewActiveRecord() {
		Consent first = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);
		consents.withdraw(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		Consent second = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(consents.isActive(owner, ConsentPurpose.BODY_IMAGE_PROCESSING)).isTrue();
		assertThat(countConsents(owner)).isEqualTo(2);
	}

	@Test
	void aNewPolicyVersionIsRecordedAsADistinctGrant() {
		Consent original = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);
		now.set(T0.plus(Duration.ofDays(30)));

		Consent revised = consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING, "2026-11-01");

		assertThat(revised.id()).isNotEqualTo(original.id());
		assertThat(revised.version()).isEqualTo("2026-11-01");
		assertThat(consents.isActive(owner, ConsentPurpose.BODY_IMAGE_PROCESSING)).isTrue();
		assertThat(store().active(owner, "body_image_processing").orElseThrow().version()).isEqualTo("2026-11-01");
	}

	@Test
	void consentIsOwnerScopedAndNeverLeaksToAnotherUser() {
		consents.grant(owner, ConsentPurpose.BODY_IMAGE_PROCESSING);

		assertThat(consents.isActive(stranger, ConsentPurpose.BODY_IMAGE_PROCESSING)).isFalse();
		assertThat(store().active(stranger, "body_image_processing")).isEmpty();

		// A stranger's withdrawal must not revoke the owner's grant.
		assertThat(consents.withdraw(stranger, ConsentPurpose.BODY_IMAGE_PROCESSING)).isFalse();
		assertThat(consents.isActive(owner, ConsentPurpose.BODY_IMAGE_PROCESSING)).isTrue();
	}

	@Test
	void anUnknownUserHoldsNoConsent() {
		assertThat(consents.isActive(new UserId(UUID.randomUUID()), ConsentPurpose.BODY_IMAGE_PROCESSING)).isFalse();
	}

	private ConsentStore store() {
		return new JdbcConsentStore(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private long countConsents(UserId user) {
		return jdbc.queryForObject("SELECT count(*) FROM consent WHERE user_id = ?", Long.class, user.value());
	}

	private UserId user() {
		UserId id = new UserId(UUID.randomUUID());
		jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,now(),now())", id.value());
		return id;
	}
}
