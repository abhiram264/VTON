package com.aifashionvault.platform;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.IdentityProvisioningConfiguration;
import com.aifashionvault.user.IdentityResolution;
import com.aifashionvault.user.JdbcIdentityProvisioning;
import com.aifashionvault.user.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionOrchestratorTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String ISSUER = "https://securetoken.google.com/ai-fashion-vault";
	private static final Instant DELETED_AT = Instant.parse("2026-03-01T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
	}

	@BeforeEach
	void clearIdentityTables() {
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void beginDeletionPurgesMediaBeforeMarkingDeleted() {
		RecordingPurge recording = new RecordingPurge(jdbc);
		JdbcIdentityProvisioning provisioning = new JdbcIdentityProvisioning(
			dataSource,
			CLOCK,
			List.of(recording));
		UserId userId = provisionUser(provisioning, "purge-first");

		User deleted = provisioning.beginDeletion(userId, DELETED_AT);

		assertThat(recording.userIds).containsExactly(userId);
		assertThat(recording.deletedAtOnPurge).containsExactly((Timestamp) null);
		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(deletedAt(userId)).isEqualTo(Timestamp.from(DELETED_AT));
	}

	@Test
	void beginDeletionWithThrowingPurgeLeavesDeletedAtNull() {
		AccountMediaPurge throwing = userId -> {
			throw new IllegalStateException("purge failed");
		};
		JdbcIdentityProvisioning provisioning = new JdbcIdentityProvisioning(
			dataSource,
			CLOCK,
			List.of(throwing));
		UserId userId = provisionUser(provisioning, "purge-throws");

		assertThatThrownBy(() -> provisioning.beginDeletion(userId, DELETED_AT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("purge failed");
		assertThat(deletedAt(userId)).isNull();
	}

	@Test
	void beginDeletionWithEmptyPurgeListStillDeletesUser() {
		JdbcIdentityProvisioning provisioning = new JdbcIdentityProvisioning(dataSource, CLOCK);
		UserId userId = provisionUser(provisioning, "empty-purge");

		User deleted = provisioning.beginDeletion(userId, DELETED_AT);

		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(deletedAt(userId)).isEqualTo(Timestamp.from(DELETED_AT));
	}

	@Test
	void factoryBeanReturnsSingleJdbcIdentityProvisioningAndNullWithoutDataSource() {
		new ApplicationContextRunner()
			.withUserConfiguration(IdentityProvisioningConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanProvider(IdentityProvisioning.class).getIfAvailable()).isNull();
			});

		new ApplicationContextRunner()
			.withUserConfiguration(IdentityProvisioningConfiguration.class)
			.withBean(DataSource.class, () -> dataSource)
			.run(context -> {
				assertThat(context).hasNotFailed();
				IdentityProvisioning provisioning = context.getBeanProvider(IdentityProvisioning.class).getIfAvailable();
				assertThat(provisioning).isNotNull().isInstanceOf(JdbcIdentityProvisioning.class);
				assertThat(context.getBeansOfType(IdentityProvisioning.class)).hasSize(1);
			});
	}

	@Test
	void factoryBeanBeginDeletionInvokesRegisteredPurgeBeforeDeletedAt() {
		RecordingPurge recording = new RecordingPurge(jdbc);
		new ApplicationContextRunner()
			.withUserConfiguration(IdentityProvisioningConfiguration.class)
			.withBean(DataSource.class, () -> dataSource)
			.withBean(AccountMediaPurge.class, () -> recording)
			.run(context -> {
				IdentityProvisioning provisioning = context.getBean(IdentityProvisioning.class);
				assertThat(provisioning).isInstanceOf(JdbcIdentityProvisioning.class);
				UserId userId = provisionUser((JdbcIdentityProvisioning) provisioning, "factory-purge");

				User deleted = provisioning.beginDeletion(userId, DELETED_AT);

				assertThat(recording.userIds).containsExactly(userId);
				assertThat(recording.deletedAtOnPurge).containsExactly((Timestamp) null);
				assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
				assertThat(deletedAt(userId)).isEqualTo(Timestamp.from(DELETED_AT));
			});
	}

	private UserId provisionUser(JdbcIdentityProvisioning provisioning, String subject) {
		IdentityResolution resolution = provisioning.resolve(ISSUER, subject);
		assertThat(resolution).isInstanceOf(IdentityResolution.Active.class);
		return ((IdentityResolution.Active) resolution).userId();
	}

	private Timestamp deletedAt(UserId userId) {
		return jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, userId.value());
	}

	private static final class RecordingPurge implements AccountMediaPurge {

		private final JdbcTemplate jdbc;
		private final List<UserId> userIds = new ArrayList<>();
		private final List<Timestamp> deletedAtOnPurge = new ArrayList<>();

		private RecordingPurge(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		@Override
		public void purgeForUser(UserId userId) {
			userIds.add(userId);
			deletedAtOnPurge.add(jdbc.queryForObject(
				"SELECT deleted_at FROM users WHERE id = ?",
				Timestamp.class,
				userId.value()));
		}
	}
}
