package com.aifashionvault.platform.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class FlywayFoundationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final List<String> DOMAIN_TABLES = List.of(
		"users",
		"identity_mapping",
		"avatar_profile",
		"avatar_image",
		"share_import",
		"source_reference",
		"vault_item",
		"collection",
		"collection_item",
		"studio_session",
		"studio_item",
		"generation_job",
		"generation_attempt",
		"quality_result",
		"look",
		"look_item",
		"notification",
		"consent");

	private static final List<String> USER_OWNED_TABLES = List.of(
		"identity_mapping",
		"avatar_profile",
		"share_import",
		"vault_item",
		"collection",
		"studio_session",
		"generation_job",
		"look",
		"notification",
		"consent");

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Environment environment;

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

	@Test
	void flywayAppliesV001ThroughV010OnEmptyDatabase() {
		List<Map<String, Object>> history = jdbc.queryForList(
			"SELECT version, success FROM flyway_schema_history "
				+ "WHERE version IS NOT NULL ORDER BY installed_rank");

		assertThat(history).hasSize(10);
		assertThat(history)
			.extracting(row -> Integer.parseInt(row.get("version").toString()))
			.containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		assertThat(history)
			.extracting(row -> row.get("success"))
			.containsOnly(true);
	}

	@Test
	void domainTablePrimaryKeysAreUuidNotIdentity() {
		assertThat(publicTables()).containsAll(DOMAIN_TABLES);

		String placeholders = placeholders(DOMAIN_TABLES.size());
		List<Map<String, Object>> pkColumns = jdbc.queryForList(
			"SELECT kcu.table_name, kcu.column_name, cols.data_type, cols.udt_name, "
				+ "cols.is_identity, cols.column_default "
				+ "FROM information_schema.table_constraints tc "
				+ "JOIN information_schema.key_column_usage kcu "
				+ "ON tc.constraint_schema = kcu.constraint_schema "
				+ "AND tc.constraint_name = kcu.constraint_name "
				+ "AND tc.table_name = kcu.table_name "
				+ "JOIN information_schema.columns cols "
				+ "ON cols.table_schema = kcu.table_schema "
				+ "AND cols.table_name = kcu.table_name "
				+ "AND cols.column_name = kcu.column_name "
				+ "WHERE tc.table_schema = 'public' "
				+ "AND tc.constraint_type = 'PRIMARY KEY' "
				+ "AND kcu.table_name IN (" + placeholders + ")",
			DOMAIN_TABLES.toArray());

		assertThat(pkColumns)
			.extracting(row -> String.valueOf(row.get("table_name")))
			.containsAll(DOMAIN_TABLES);
		assertThat(pkColumns).isNotEmpty();
		for (Map<String, Object> column : pkColumns) {
			assertThat(column.get("udt_name")).as("%s.%s type", column.get("table_name"), column.get("column_name"))
				.isEqualTo("uuid");
			assertThat(column.get("data_type")).isEqualTo("uuid");
			assertThat(column.get("is_identity")).isEqualTo("NO");
			Object columnDefault = column.get("column_default");
			if (columnDefault != null) {
				assertThat(columnDefault.toString().toLowerCase()).doesNotContain("nextval");
			}
		}
	}

	@Test
	void identityMappingHasUniqueIssuerSubjectAndUserFk() {
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
		assertThat(foreignKeyDefs("identity_mapping"))
			.anyMatch(definition -> definition.contains("FOREIGN KEY (user_id)")
				&& definition.contains("REFERENCES users(id)"));
	}

	@Test
	void domainTablesHaveNoByteaColumns() {
		String placeholders = placeholders(DOMAIN_TABLES.size());
		List<Map<String, Object>> byteaColumns = jdbc.queryForList(
			"SELECT table_name, column_name "
				+ "FROM information_schema.columns "
				+ "WHERE table_schema = 'public' "
				+ "AND table_name IN (" + placeholders + ") "
				+ "AND (data_type = 'bytea' OR udt_name = 'bytea')",
			DOMAIN_TABLES.toArray());

		assertThat(byteaColumns).isEmpty();
	}

	@Test
	void userOwnedTablesHaveNotNullUserIdFkToUsers() {
		String placeholders = placeholders(USER_OWNED_TABLES.size());
		List<Map<String, Object>> userIdColumns = jdbc.queryForList(
			"SELECT table_name, data_type, udt_name, is_nullable "
				+ "FROM information_schema.columns "
				+ "WHERE table_schema = 'public' "
				+ "AND column_name = 'user_id' "
				+ "AND table_name IN (" + placeholders + ")",
			USER_OWNED_TABLES.toArray());

		assertThat(userIdColumns)
			.extracting(row -> String.valueOf(row.get("table_name")))
			.containsExactlyInAnyOrderElementsOf(USER_OWNED_TABLES);
		for (Map<String, Object> column : userIdColumns) {
			assertThat(column.get("udt_name")).as("%s.user_id type", column.get("table_name")).isEqualTo("uuid");
			assertThat(column.get("data_type")).isEqualTo("uuid");
			assertThat(column.get("is_nullable")).isEqualTo("NO");
		}
		for (String table : USER_OWNED_TABLES) {
			assertThat(foreignKeyDefs(table))
				.as("%s.user_id FK", table)
				.anyMatch(definition -> definition.contains("FOREIGN KEY (user_id)")
					&& definition.contains("REFERENCES users(id)"));
		}
	}

	@Test
	void dataSourceJdbcUrlEqualsVaultDbUrl() throws Exception {
		String vaultDbUrl = environment.getProperty("vault.db.url");
		assertThat(vaultDbUrl).isNotBlank();
		assertThat(vaultDbUrl).isNotEqualTo("jdbc:postgresql://127.0.0.1:5432/postgres");
		assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(vaultDbUrl);
		assertThat(dataSource.unwrap(HikariDataSource.class).getJdbcUrl()).isEqualTo(vaultDbUrl);
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(vaultDbUrl);
		}
	}

	@Test
	void failedMigrationBlocksStartup() throws Exception {
		Path directory = Files.createTempDirectory("flyway-invalid-");
		try (PostgreSQLContainer failing = newPostgres()) {
			Files.writeString(directory.resolve("V001__invalid.sql"), "THIS IS NOT VALID SQL;");
			failing.start();
			Flyway flyway = Flyway.configure()
				.dataSource(failing.getJdbcUrl(), failing.getUsername(), failing.getPassword())
				.locations(filesystemLocation(directory))
				.load();
			assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
		}
		finally {
			deleteRecursively(directory);
		}
	}

	@Test
	void reapplyingMigrationsOnSecondEmptyDatabaseMatchesChecksums() {
		List<Map<String, Object>> original = jdbc.queryForList(
			"SELECT version, checksum FROM flyway_schema_history "
				+ "WHERE version IS NOT NULL ORDER BY installed_rank");
		assertThat(original).hasSize(10);

		try (PostgreSQLContainer second = newPostgres()) {
			second.start();
			Flyway.configure()
				.dataSource(second.getJdbcUrl(), second.getUsername(), second.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();

			JdbcTemplate replay = new JdbcTemplate(new DriverManagerDataSource(
				second.getJdbcUrl(),
				second.getUsername(),
				second.getPassword()));
			List<Map<String, Object>> replayed = replay.queryForList(
				"SELECT version, checksum FROM flyway_schema_history "
					+ "WHERE version IS NOT NULL ORDER BY installed_rank");

			assertThat(replayed).hasSize(10);
			assertThat(replayed)
				.usingRecursiveFieldByFieldElementComparator()
				.containsExactlyElementsOf(original);
		}
	}

	@Test
	void notificationHasNoSensitiveImageUrlColumns() {
		List<String> columns = columnNames("notification");
		assertThat(columns)
			.doesNotContain("avatar_url", "signed_s3_url", "image_url", "base64_image");
	}

	@Test
	void consentIsHistoricalAndUsersHasNoConsentBoolean() {
		assertThat(columnNames("consent")).contains("purpose", "version", "granted_at", "revoked_at");
		List<Map<String, Object>> userColumns = jdbc.queryForList(
			"SELECT column_name, data_type "
				+ "FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = 'users'");
		assertThat(userColumns)
			.extracting(row -> String.valueOf(row.get("column_name")))
			.doesNotContain("consent");
		assertThat(userColumns)
			.noneMatch(row -> "consent".equalsIgnoreCase(String.valueOf(row.get("column_name")))
				&& "boolean".equals(row.get("data_type")));
	}

	@Test
	void generationJobHasUuidPkAndUniqueClientRequestId() {
		Map<String, Object> idColumn = jdbc.queryForMap(
			"SELECT cols.data_type, cols.udt_name, cols.is_identity "
				+ "FROM information_schema.table_constraints tc "
				+ "JOIN information_schema.key_column_usage kcu "
				+ "ON tc.constraint_schema = kcu.constraint_schema "
				+ "AND tc.constraint_name = kcu.constraint_name "
				+ "AND tc.table_name = kcu.table_name "
				+ "JOIN information_schema.columns cols "
				+ "ON cols.table_schema = kcu.table_schema "
				+ "AND cols.table_name = kcu.table_name "
				+ "AND cols.column_name = kcu.column_name "
				+ "WHERE tc.table_schema = 'public' "
				+ "AND tc.table_name = 'generation_job' "
				+ "AND tc.constraint_type = 'PRIMARY KEY' "
				+ "AND kcu.column_name = 'id'");
		assertThat(idColumn.get("udt_name")).isEqualTo("uuid");
		assertThat(idColumn.get("data_type")).isEqualTo("uuid");
		assertThat(idColumn.get("is_identity")).isEqualTo("NO");

		List<String> uniqueColumnSets = jdbc.queryForList(
			"SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position) "
				+ "FROM information_schema.table_constraints tc "
				+ "JOIN information_schema.key_column_usage kcu "
				+ "ON tc.constraint_schema = kcu.constraint_schema "
				+ "AND tc.constraint_name = kcu.constraint_name "
				+ "AND tc.table_name = kcu.table_name "
				+ "WHERE tc.table_schema = 'public' "
				+ "AND tc.table_name = 'generation_job' "
				+ "AND tc.constraint_type = 'UNIQUE' "
				+ "GROUP BY tc.constraint_name",
			String.class);
		assertThat(uniqueColumnSets).contains("client_request_id");
	}

	private List<String> publicTables() {
		return jdbc.queryForList(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
			String.class);
	}

	private List<String> columnNames(String table) {
		return jdbc.queryForList(
			"SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = ?",
			String.class,
			table);
	}

	private List<String> foreignKeyDefs(String table) {
		return jdbc.queryForList(
			"SELECT pg_get_constraintdef(oid) FROM pg_constraint "
				+ "WHERE conrelid = ?::regclass AND contype = 'f'",
			String.class,
			"public." + table);
	}

	private static PostgreSQLContainer newPostgres() {
		return new PostgreSQLContainer(POSTGRES_IMAGE)
			.withDatabaseName("postgres")
			.withStartupTimeout(STARTUP_TIMEOUT);
	}

	private static String filesystemLocation(Path directory) {
		return "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
	}

	private static String placeholders(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}

	private static void deleteRecursively(Path directory) throws Exception {
		if (!Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
	}
}
