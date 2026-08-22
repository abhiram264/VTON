package com.aifashionvault.user;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.IdentityMappingId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class IdentityProvisioningTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String ISSUER = "https://securetoken.google.com/ai-fashion-vault";
	private static final Instant DELETED_AT = Instant.parse("2026-03-01T00:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private IdentityProvisioning identityProvisioning;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearIdentityTables() {
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
	}

	@Test
	void injectedIdentityProvisioningIsJdbcAndResolves() {
		assertThat(identityProvisioning).isNotNull().isInstanceOf(JdbcIdentityProvisioning.class);

		IdentityResolution resolution = identityProvisioning.resolve(ISSUER, "spring-bean-login");

		assertThat(resolution).isInstanceOf(IdentityResolution.Active.class);
		UserId userId = ((IdentityResolution.Active) resolution).userId();
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("identity_mapping")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE issuer = ? AND subject = ?",
			UUID.class, ISSUER, "spring-bean-login")).isEqualTo(userId.value());
	}

	@Test
	void identityProvisioningIsNotRegisteredWhenDataSourceIsAbsent() {
		new ApplicationContextRunner()
			.withUserConfiguration(IdentityProvisioningConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanProvider(IdentityProvisioning.class).getIfAvailable()).isNull();
			});
	}

	@Test
	void firstResolveCreatesOneUserAndMappingAndReturnsActive() {
		IdentityResolution resolution = identityProvisioning.resolve(ISSUER, "first-login");

		assertThat(resolution).isInstanceOf(IdentityResolution.Active.class);
		UserId userId = ((IdentityResolution.Active) resolution).userId();
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("identity_mapping")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE issuer = ? AND subject = ?",
			UUID.class, ISSUER, "first-login")).isEqualTo(userId.value());
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, userId.value()))
			.isNull();
	}

	@Test
	void secondResolveReturnsSameUserIdAndDoesNotInsertAnotherUser() {
		IdentityResolution first = identityProvisioning.resolve(ISSUER, "repeat-login");
		IdentityResolution second = identityProvisioning.resolve(ISSUER, "repeat-login");

		assertThat(first).isEqualTo(second);
		assertThat(first).isInstanceOf(IdentityResolution.Active.class);
		assertThat(((IdentityResolution.Active) first).userId())
			.isEqualTo(((IdentityResolution.Active) second).userId());
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("identity_mapping")).isEqualTo(1);
	}

	@Test
	void firstResolvePersistsClockInstantOnUserAndMapping() {
		Instant now = Instant.parse("2026-06-01T12:00:00Z");
		JdbcIdentityProvisioning provisioning = new JdbcIdentityProvisioning(
			dataSource,
			Clock.fixed(now, ZoneOffset.UTC));

		IdentityResolution.Active active = (IdentityResolution.Active) provisioning.resolve(ISSUER, "clock-login");

		Timestamp expected = Timestamp.from(now);
		assertThat(jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?", Timestamp.class, active.userId().value()))
			.isEqualTo(expected);
		assertThat(jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", Timestamp.class, active.userId().value()))
			.isEqualTo(expected);
		assertThat(jdbc.queryForObject(
			"SELECT created_at FROM identity_mapping WHERE issuer = ? AND subject = ?",
			Timestamp.class,
			ISSUER,
			"clock-login")).isEqualTo(expected);
	}

	@Test
	void concurrentFirstResolveCreatesExactlyOneUserAndMapping() throws Exception {
		String subject = "concurrent-login";
		int callers = 8;
		CyclicBarrier start = new CyclicBarrier(callers);
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		try {
			List<Future<IdentityResolution>> futures = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				futures.add(pool.submit(() -> {
					start.await(30, TimeUnit.SECONDS);
					return identityProvisioning.resolve(ISSUER, subject);
				}));
			}

			List<UserId> userIds = new ArrayList<>();
			for (Future<IdentityResolution> future : futures) {
				IdentityResolution resolution = future.get(60, TimeUnit.SECONDS);
				assertThat(resolution).isInstanceOf(IdentityResolution.Active.class);
				userIds.add(((IdentityResolution.Active) resolution).userId());
			}

			assertThat(userIds.stream().distinct()).hasSize(1);
			assertThat(count("users")).isEqualTo(1);
			assertThat(count("identity_mapping")).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void uniqueIssuerSubjectConflictRollsBackAndRetriesLookup() throws Exception {
		String subject = "conflict-login";
		UserId existingUserId = new UserId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
		IdentityMappingId mappingId = new IdentityMappingId(UUID.fromString("ffffffff-1111-2222-3333-444444444444"));

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection blocker = dataSource.getConnection()) {
			blocker.setAutoCommit(false);
			insertUser(blocker, existingUserId, CREATED_AT);
			insertMapping(blocker, mappingId, existingUserId, ISSUER, subject, CREATED_AT);

			Future<IdentityResolution> raced = pool.submit((Callable<IdentityResolution>) () ->
				identityProvisioning.resolve(ISSUER, subject));
			awaitLockWait();
			blocker.commit();

			IdentityResolution resolution = raced.get(30, TimeUnit.SECONDS);
			assertThat(resolution).isEqualTo(new IdentityResolution.Active(existingUserId));
			assertThat(count("users")).isEqualTo(1);
			assertThat(count("identity_mapping")).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void identityMappingUniqueIssuerSubjectIsTheConflictSeam() {
		List<String> uniqueColumnSets = jdbc.queryForList(
			"SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position) "
				+ "FROM information_schema.table_constraints tc "
				+ "JOIN information_schema.key_column_usage kcu "
				+ "ON tc.constraint_schema = kcu.constraint_schema "
				+ "AND tc.constraint_name = kcu.constraint_name "
				+ "AND tc.table_name = kcu.table_name "
				+ "WHERE tc.table_schema = 'public' "
				+ "AND tc.table_name = 'identity_mapping' "
				+ "AND tc.constraint_type = 'UNIQUE' "
				+ "GROUP BY tc.constraint_name",
			String.class);

		assertThat(uniqueColumnSets).contains("issuer,subject");
		List<String> tables = jdbc.queryForList(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
			String.class);
		assertThat(tables)
			.doesNotContain("identity_lock", "provisioning_lock", "identity_mapping_lock");
	}

	@Test
	void beginDeletionSetsDeletedAtAndUpdatedAtWithoutRewritingMapping() {
		IdentityResolution.Active active = (IdentityResolution.Active) identityProvisioning.resolve(ISSUER, "delete-me");
		Map<String, Object> mappingBefore = mappingRow(ISSUER, "delete-me");

		User deleted = identityProvisioning.beginDeletion(active.userId(), DELETED_AT);

		assertThat(deleted.id()).isEqualTo(active.userId());
		assertThat(deleted.deletedAt()).isEqualTo(DELETED_AT);
		assertThat(deleted.updatedAt()).isEqualTo(DELETED_AT);
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, active.userId().value()))
			.isEqualTo(Timestamp.from(DELETED_AT));
		assertThat(jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", Timestamp.class, active.userId().value()))
			.isEqualTo(Timestamp.from(DELETED_AT));
		assertThat(mappingRow(ISSUER, "delete-me")).isEqualTo(mappingBefore);
	}

	@Test
	void resolveAfterBeginDeletionReturnsDeletedAndDoesNotInsert() {
		IdentityResolution.Active active = (IdentityResolution.Active) identityProvisioning.resolve(ISSUER, "deleted-login");
		identityProvisioning.beginDeletion(active.userId(), DELETED_AT);

		IdentityResolution resolution = identityProvisioning.resolve(ISSUER, "deleted-login");

		assertThat(resolution).isEqualTo(new IdentityResolution.Deleted(active.userId()));
		assertThat(count("users")).isEqualTo(1);
		assertThat(count("identity_mapping")).isEqualTo(1);
	}

	@Test
	void beginDeletionRejectsAlreadyDeletedUser() {
		IdentityResolution.Active active = (IdentityResolution.Active) identityProvisioning.resolve(ISSUER, "already-deleted");
		identityProvisioning.beginDeletion(active.userId(), DELETED_AT);

		assertThatThrownBy(() -> identityProvisioning.beginDeletion(active.userId(), Instant.parse("2026-04-01T00:00:00Z")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, active.userId().value()))
			.isEqualTo(Timestamp.from(DELETED_AT));
	}

	private Map<String, Object> mappingRow(String issuer, String subject) {
		return jdbc.queryForMap(
			"SELECT id, user_id, issuer, subject, created_at FROM identity_mapping WHERE issuer = ? AND subject = ?",
			issuer,
			subject);
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private void awaitLockWait() throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(10);
		while (Instant.now().isBefore(deadline)) {
			Integer waiting = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_stat_activity "
					+ "WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()",
				Integer.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("resolve did not wait on UNIQUE(issuer, subject)");
	}

	private static void insertUser(Connection connection, UserId userId, Instant at) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)")) {
			statement.setObject(1, userId.value());
			statement.setTimestamp(2, Timestamp.from(at));
			statement.setTimestamp(3, Timestamp.from(at));
			statement.executeUpdate();
		}
	}

	private static void insertMapping(
		Connection connection,
		IdentityMappingId mappingId,
		UserId userId,
		String issuer,
		String subject,
		Instant at) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT INTO identity_mapping (id, user_id, issuer, subject, created_at) VALUES (?, ?, ?, ?, ?)")) {
			statement.setObject(1, mappingId.value());
			statement.setObject(2, userId.value());
			statement.setString(3, issuer);
			statement.setString(4, subject);
			statement.setTimestamp(5, Timestamp.from(at));
			statement.executeUpdate();
		}
	}
}
