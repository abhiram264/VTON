package com.aifashionvault.privacy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConsentStoreTest {

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final UserId OWNER = userId("11111111-1111-1111-1111-111111111111");
	private static final UserId OTHER = userId("22222222-2222-2222-2222-222222222222");
	private static final Instant T0 = Instant.parse("2026-10-03T09:00:00Z");

	private JdbcTemplate jdbc;
	private JdbcConsentStore store;

	@BeforeAll
	static void database() {
		POSTGRES.start();
	}

	@BeforeEach
	void reset() {
		DriverManagerDataSource source = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
		jdbc = new JdbcTemplate(source);
		jdbc.update("TRUNCATE consent, users CASCADE");
		insertUser(OWNER);
		insertUser(OTHER);
		store = new JdbcConsentStore(source);
	}

	@Test
	void grantsAreOwnerScopedOrderedAndForeignOwnersCannotReadOrRevokeThem() {
		ConsentId first = consentId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		ConsentId second = consentId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		store.grant(OWNER, second, ConsentPurpose.RETENTION, "v1", T0.plusSeconds(5));
		store.grant(OWNER, first, ConsentPurpose.BODY_IMAGE_PROCESSING, "v1", T0);
		store.grant(OTHER, consentId("cccccccc-cccc-cccc-cccc-cccccccccccc"), ConsentPurpose.RETENTION, "v1", T0);

		assertThat(store.list(OWNER)).extracting(Consent::id).containsExactly(first, second);
		assertThat(store.active(OTHER, ConsentPurpose.BODY_IMAGE_PROCESSING)).isEmpty();
		assertThat(store.withdraw(OTHER, ConsentPurpose.BODY_IMAGE_PROCESSING, T0.plusSeconds(1))).isFalse();
		assertThat(store.active(OWNER, ConsentPurpose.BODY_IMAGE_PROCESSING)).isPresent();
	}

	@Test
	void reGrantingTheSameVersionIsIdempotentAndANewVersionSupersedesTheActiveGrant() {
		ConsentId id = consentId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		Consent granted = store.grant(OWNER, id, ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v1", T0);
		Consent replay = store.grant(OWNER, consentId("dddddddd-dddd-dddd-dddd-dddddddddddd"),
			ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v1", T0.plusSeconds(60));

		assertThat(replay.id()).isEqualTo(granted.id());
		assertThat(replay.grantedAt()).isEqualTo(T0);
		assertThat(store.list(OWNER)).hasSize(1);

		Consent superseding = store.grant(OWNER, consentId("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
			ConsentPurpose.THIRD_PARTY_AI_TRANSFER, "v2", T0.plusSeconds(120));

		assertThat(superseding.version()).isEqualTo("v2");
		assertThat(store.active(OWNER, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)).contains(superseding);
		assertThat(store.list(OWNER)).hasSize(2);
		assertThat(store.list(OWNER).get(0).revokedAt()).isEqualTo(T0.plusSeconds(120));
	}

	@Test
	void withdrawalRevokesOnlyTheActiveGrantAndIsDistinguishableFromNeverGranted() {
		store.grant(OWNER, consentId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), ConsentPurpose.RETENTION, "v1", T0);

		assertThat(store.withdraw(OWNER, ConsentPurpose.BODY_IMAGE_PROCESSING, T0.plusSeconds(1))).isFalse();
		assertThat(store.withdraw(OWNER, ConsentPurpose.RETENTION, T0.plusSeconds(2))).isTrue();
		assertThat(store.withdraw(OWNER, ConsentPurpose.RETENTION, T0.plusSeconds(3))).isFalse();
		assertThat(store.active(OWNER, ConsentPurpose.RETENTION)).isEmpty();
		assertThat(store.list(OWNER)).hasSize(1);
		assertThat(store.list(OWNER).get(0).revokedAt()).isEqualTo(T0.plusSeconds(2));
	}

	@Test
	void regrantAfterWithdrawalCreatesAFreshActiveGrantAndKeepsHistory() {
		store.grant(OWNER, consentId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), ConsentPurpose.RETENTION, "v1", T0);
		store.withdraw(OWNER, ConsentPurpose.RETENTION, T0.plusSeconds(10));

		Consent regranted = store.grant(OWNER, consentId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
			ConsentPurpose.RETENTION, "v1", T0.plusSeconds(20));

		assertThat(regranted.revokedAt()).isNull();
		assertThat(store.active(OWNER, ConsentPurpose.RETENTION)).contains(regranted);
		assertThat(store.list(OWNER)).hasSize(2);
	}

	private void insertUser(UserId id) {
		jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,?,?)",
			id.value(), Timestamp.from(T0), Timestamp.from(T0));
	}

	private static UserId userId(String value) {
		return new UserId(UUID.fromString(value));
	}

	private static ConsentId consentId(String value) {
		return new ConsentId(UUID.fromString(value));
	}
}
