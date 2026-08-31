package com.aifashionvault.platform.persistence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
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

	private static final String PUBLISHED_V002 = """
		CREATE TABLE avatar_profile (
		    id uuid PRIMARY KEY,
		    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
		    primary_image_id uuid NULL,
		    status text NOT NULL,
		    created_at timestamptz NOT NULL,
		    updated_at timestamptz NOT NULL
		);

		CREATE INDEX avatar_profile_user_id_idx ON avatar_profile (user_id);

		CREATE TABLE avatar_image (
		    id uuid PRIMARY KEY,
		    profile_id uuid NOT NULL REFERENCES avatar_profile (id),
		    storage_key text NOT NULL,
		    width int NULL,
		    height int NULL,
		    pose text NULL,
		    quality_status text NOT NULL,
		    created_at timestamptz NOT NULL
		);

		CREATE INDEX avatar_image_profile_id_idx ON avatar_image (profile_id);

		ALTER TABLE avatar_profile
		    ADD CONSTRAINT avatar_profile_primary_image_id_fkey
		    FOREIGN KEY (primary_image_id) REFERENCES avatar_image (id);
		""";
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final List<String> DOMAIN_TABLES = List.of(
		"users",
		"identity_mapping",
		"avatar_profile",
		"avatar_image",
		"avatar_consumed_upload",
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
		"generation_job_product",
		"generation_render_step",
		"generation_render_step_item",
		"generation_work_outbox",
		"generation_callback_inbox",
		"generation_notification_event",
		"generation_notification_delivery",
		"generation_quota_reservation",
		"generation_provider_capacity_slot",
		"look",
		"look_item",
		"notification",
		"consent");

	private static final List<String> USER_OWNED_TABLES = List.of(
		"identity_mapping",
		"avatar_profile",
		"avatar_consumed_upload",
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
    void flywayAppliesV001ThroughV022OnEmptyDatabase() {
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(22);
        assertThat(history).extracting(row -> Integer.parseInt(row.get("version").toString()))
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
        assertThat(history).extracting(row -> row.get("success")).containsOnly(true);
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
            assertThat(original).hasSize(22);

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

            assertThat(replayed).hasSize(22);
			assertThat(replayed)
				.usingRecursiveFieldByFieldElementComparator()
				.containsExactlyElementsOf(original);
		}
	}

	@Test
    void publishedV002ValidatesAndUpgradesToV022WithoutChecksumRepair() throws Exception {
		Path publishedMigrations = Files.createTempDirectory("flyway-published-v002-");
		try (PostgreSQLContainer legacy = newPostgres()) {
			byte[] currentV002 = migrationBytes("V002__create_avatar_profiles.sql");
			assertThat(new String(currentV002, StandardCharsets.UTF_8).replace("\r\n", "\n"))
				.isEqualTo(PUBLISHED_V002);
			Files.write(
				publishedMigrations.resolve("V001__create_users.sql"),
				migrationBytes("V001__create_users.sql"));
			Files.write(
				publishedMigrations.resolve("V002__create_avatar_profiles.sql"),
				PUBLISHED_V002.getBytes(StandardCharsets.UTF_8));

			legacy.start();
			Flyway.configure()
				.dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
				.locations(filesystemLocation(publishedMigrations))
				.load()
				.migrate();

			JdbcTemplate upgraded = new JdbcTemplate(new DriverManagerDataSource(
				legacy.getJdbcUrl(),
				legacy.getUsername(),
				legacy.getPassword()));
			UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
			UUID profileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
			UUID imageId = UUID.fromString("33333333-3333-3333-3333-333333333333");
			Timestamp now = Timestamp.from(Instant.parse("2026-01-15T10:00:00Z"));
			upgraded.update(
				"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
				userId,
				now,
				now);
			upgraded.update(
				"INSERT INTO avatar_profile "
					+ "(id, user_id, primary_image_id, status, created_at, updated_at) "
					+ "VALUES (?, ?, NULL, 'ACTIVE', ?, ?)",
				profileId,
				userId,
				now,
				now);
			upgraded.update(
				"INSERT INTO avatar_image "
					+ "(id, profile_id, storage_key, width, height, pose, quality_status, created_at) "
					+ "VALUES (?, ?, 'legacy/avatar.jpg', NULL, NULL, NULL, 'PENDING', ?)",
				imageId,
				profileId,
				now);
			upgraded.update(
				"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
				imageId,
				profileId);

			Flyway current = Flyway.configure()
				.dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
				.locations("classpath:db/migration")
				.load();
			current.migrate();
			current.validate();

			assertThat(upgraded.queryForList(
				"SELECT version FROM flyway_schema_history "
					+ "WHERE version IS NOT NULL ORDER BY installed_rank",
				String.class))
                .containsExactly("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019", "020", "021", "022");
			assertThat(upgraded.queryForMap(
				"SELECT revision, lifecycle_state, warnings::text AS warnings, "
					+ "source_upload_id, preflight_policy_version "
					+ "FROM avatar_profile JOIN avatar_image "
					+ "ON avatar_image.profile_id = avatar_profile.id "
					+ "WHERE avatar_profile.id = ?",
				profileId))
				.containsEntry("revision", 1)
				.containsEntry("lifecycle_state", "ACTIVE")
				.containsEntry("warnings", "{}")
				.containsEntry("source_upload_id", null)
				.containsEntry("preflight_policy_version", null);
			assertThat(upgraded.queryForList(
				"SELECT column_name FROM information_schema.columns "
					+ "WHERE table_schema = 'public' AND table_name = 'avatar_consumed_upload' "
					+ "ORDER BY ordinal_position",
				String.class))
				.containsExactly("user_id", "upload_id", "consumed_at");
			assertThat(constraintDefinitions(upgraded, "avatar_profile"))
				.anyMatch(definition -> definition.contains("CHECK")
					&& definition.contains("ACTIVE")
					&& definition.contains("DELETING"))
				.anyMatch(definition -> definition.contains("UNIQUE (user_id)"))
				.anyMatch(definition -> definition.contains("FOREIGN KEY (primary_image_id, id)")
					&& definition.contains("REFERENCES avatar_image(id, profile_id)"));
			assertThat(constraintDefinitions(upgraded, "avatar_image"))
				.anyMatch(definition -> definition.contains("CHECK")
					&& definition.contains("width")
					&& definition.contains("> 0"))
				.anyMatch(definition -> definition.contains("CHECK")
					&& definition.contains("height")
					&& definition.contains("> 0"))
				.anyMatch(definition -> definition.contains("UNIQUE (source_upload_id)"))
				.anyMatch(definition -> definition.contains("UNIQUE (id, profile_id)"))
				.anyMatch(definition -> definition.contains("UNIQUE (storage_key)"));
			assertThat(constraintDefinitions(upgraded, "avatar_consumed_upload"))
				.anyMatch(definition -> definition.contains("PRIMARY KEY (user_id, upload_id)"))
				.anyMatch(definition -> definition.contains("FOREIGN KEY (user_id)")
					&& definition.contains("REFERENCES users(id) ON DELETE CASCADE"));
		}
		finally {
			deleteRecursively(publishedMigrations);
		}
	}

	@Test
	void notificationHasNoSensitiveImageUrlColumns() {
		List<String> columns = columnNames("notification");
		assertThat(columns)
			.doesNotContain("avatar_url", "signed_s3_url", "image_url", "base64_image");
	}

	@Test
	void generationAndLookTablesHaveNoMediaColumns() {
		List<String> tables = List.of(
			"generation_job",
			"generation_attempt",
			"generation_job_product",
			"look");
		String placeholders = placeholders(tables.size());
		List<Map<String, Object>> columns = jdbc.queryForList(
			"SELECT table_name, column_name, data_type, udt_name "
				+ "FROM information_schema.columns "
				+ "WHERE table_schema = 'public' "
				+ "AND table_name IN (" + placeholders + ")",
			tables.toArray());

		assertThat(columns)
			.extracting(row -> String.valueOf(row.get("table_name")))
			.containsAll(tables);
		assertThat(columns)
			.noneMatch(row -> "bytea".equals(row.get("data_type"))
				|| "bytea".equals(row.get("udt_name")));
		assertThat(columns)
			.extracting(row -> String.valueOf(row.get("column_name")).toLowerCase())
			.noneMatch(name -> name.contains("url")
				|| name.contains("base64")
				|| name.contains("bytea")
				|| name.contains("image_bytes")
				|| name.contains("media_bytes"));
	}
	@Test
	void generationProvenanceColumnsAreNullableAndTyped() {
		List<Map<String, Object>> columns = jdbc.queryForList(
			"SELECT table_name, column_name, data_type, udt_name, is_nullable "
				+ "FROM information_schema.columns "
				+ "WHERE table_schema = 'public' "
				+ "AND ((table_name = 'generation_attempt' AND column_name IN "
				+ "('actual_billed_units', 'pricing_observed_at', 'billing_unit')) "
				+ "OR (table_name = 'quality_result' AND column_name IN "
				+ "('quality_policy_version', 'reason_code')) "
				+ "OR (table_name = 'generation_job' AND column_name IN "
				+ "('retry_not_before_at', 'retry_policy_snapshot')))");

		assertThat(columns)
			.extracting(row -> String.valueOf(row.get("table_name")) + "."
				+ String.valueOf(row.get("column_name")))
			.containsExactlyInAnyOrder(
				"generation_attempt.actual_billed_units",
				"generation_attempt.pricing_observed_at",
				"generation_attempt.billing_unit",
				"quality_result.quality_policy_version",
				"quality_result.reason_code",
				"generation_job.retry_not_before_at",
				"generation_job.retry_policy_snapshot");
		assertThat(columns).allMatch(row -> "YES".equals(row.get("is_nullable")));
		assertThat(columns).anyMatch(row -> "generation_attempt".equals(row.get("table_name"))
			&& "actual_billed_units".equals(row.get("column_name"))
			&& "numeric".equals(row.get("data_type"))
			&& "numeric".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "generation_attempt".equals(row.get("table_name"))
			&& "pricing_observed_at".equals(row.get("column_name"))
			&& "timestamp with time zone".equals(row.get("data_type"))
			&& "timestamptz".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "generation_attempt".equals(row.get("table_name"))
			&& "billing_unit".equals(row.get("column_name"))
			&& "text".equals(row.get("data_type"))
			&& "text".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "generation_job".equals(row.get("table_name"))
			&& "retry_not_before_at".equals(row.get("column_name"))
			&& "timestamp with time zone".equals(row.get("data_type"))
			&& "timestamptz".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "generation_job".equals(row.get("table_name"))
			&& "retry_policy_snapshot".equals(row.get("column_name"))
			&& "text".equals(row.get("data_type"))
			&& "text".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "quality_result".equals(row.get("table_name"))
			&& "quality_policy_version".equals(row.get("column_name"))
			&& "text".equals(row.get("data_type"))
			&& "text".equals(row.get("udt_name")));
		assertThat(columns).anyMatch(row -> "quality_result".equals(row.get("table_name"))
			&& "reason_code".equals(row.get("column_name"))
			&& "text".equals(row.get("data_type"))
			&& "text".equals(row.get("udt_name")));
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
	void generationJobHasUuidPkAndUniqueUserClientRequestId() {
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
		assertThat(uniqueColumnSets).contains("user_id,client_request_id");
		assertThat(uniqueColumnSets).doesNotContain("client_request_id");
	}

	@Test
	void singleItemManualProductV018EnforcesFrozenUniquenessAndShape() {
		assertThat(constraintDefinitions(jdbc, "generation_job"))
			.anyMatch(definition -> definition.contains("UNIQUE (user_id, client_request_id)"))
			.anyMatch(definition -> definition.contains("UNIQUE (product_source_upload_id)"))
			.anyMatch(definition -> definition.contains("render_step_ordinal")
				&& definition.contains("render_step_count")
				&& definition.contains("= 1"));
		assertThat(constraintDefinitions(jdbc, "generation_attempt"))
			.anyMatch(definition -> definition.contains("UNIQUE (generation_job_id, attempt_number)"));
		assertThat(constraintDefinitions(jdbc, "quality_result"))
			.anyMatch(definition -> definition.contains("UNIQUE (generation_attempt_id)"));
		assertThat(constraintDefinitions(jdbc, "look"))
			.anyMatch(definition -> definition.contains("UNIQUE (generation_job_id)"));
	}

	@Test
	void generationJobSystemV013EnforcesCanonicalLifecycleAndIndexes() {
		List<String> jobChecks = constraintDefinitions(jdbc, "generation_job");
		assertThat(jobChecks)
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("QUEUED")
				&& definition.contains("PREFLIGHT")
				&& definition.contains("SUBMITTED")
				&& definition.contains("GENERATING")
				&& definition.contains("QUALITY_CHECK")
				&& definition.contains("RETRYING")
				&& definition.contains("COMPLETED")
				&& definition.contains("FAILED"))
			.anyMatch(definition -> definition.contains("UNIQUE (user_id, client_request_id)"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("completed_at")
				&& definition.contains("COMPLETED")
				&& definition.contains("FAILED"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("cancelled_at")
				&& definition.contains("FAILED"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("subject_deleted_at")
				&& definition.contains("FAILED"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("total_steps")
				&& definition.contains(">= 1"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("current_step")
				&& definition.contains("total_steps"));
		assertThat(jobChecks)
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("retry_not_before_at")
				&& definition.contains("RETRYING"));

		List<String> jobIndexes = jdbc.queryForList(
			"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'generation_job'",
			String.class);
		assertThat(jobIndexes)
			.anyMatch(definition -> definition.contains("(user_id")
				&& definition.contains("created_at DESC"))
			.anyMatch(definition -> definition.contains("status")
				&& definition.contains("updated_at")
				&& definition.toLowerCase().contains("where"))
			.anyMatch(definition -> definition.contains("orchestration_deadline_at")
				&& definition.toLowerCase().contains("where"));
		assertThat(jobIndexes)
			.anyMatch(definition -> definition.contains("retry_not_before_at")
				&& definition.contains("RETRYING")
				&& definition.toLowerCase().contains("where"));

		List<String> stepChecks = constraintDefinitions(jdbc, "generation_render_step");
		assertThat(stepChecks)
			.anyMatch(definition -> definition.contains("UNIQUE (generation_job_id, step_index)"));
		List<String> stepItemChecks = constraintDefinitions(jdbc, "generation_render_step_item");
		assertThat(stepItemChecks)
			.anyMatch(definition -> definition.contains("UNIQUE (render_step_id, item_ordinal)"));

		List<String> attemptIndexes = jdbc.queryForList(
			"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'generation_attempt'",
			String.class);
		assertThat(attemptIndexes)
			.anyMatch(definition -> definition.toUpperCase().contains("UNIQUE")
				&& definition.contains("submission_key")
				&& definition.toLowerCase().contains("where"))
			.anyMatch(definition -> definition.toUpperCase().contains("UNIQUE")
				&& definition.contains("provider")
				&& definition.contains("provider_job_id")
				&& definition.toLowerCase().contains("where"));

		List<String> attemptChecks = constraintDefinitions(jdbc, "generation_attempt");
		assertThat(attemptChecks)
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("temp_storage_key")
				&& definition.contains("http://")
				&& definition.contains("https://"))
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("provider_output_ref")
				&& definition.contains("http://")
				&& definition.contains("https://"));
		assertThat(attemptChecks)
			.anyMatch(definition -> definition.contains("CHECK")
				&& definition.contains("transmission_count")
				&& definition.contains(">= 0")
				&& definition.contains("<= 3"));

		List<String> outboxIndexes = jdbc.queryForList(
			"SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'generation_work_outbox'",
			String.class);
		assertThat(outboxIndexes)
			.anyMatch(definition -> definition.contains("not_before")
				&& definition.toLowerCase().contains("where")
				&& definition.contains("PENDING"));

		List<String> deliveryChecks = constraintDefinitions(jdbc, "generation_notification_delivery");
		assertThat(deliveryChecks)
			.anyMatch(definition -> definition.contains("UNIQUE (event_id, device_identity)"));

		assertThat(publicTables())
			.contains(
				"generation_work_outbox",
				"generation_callback_inbox",
				"generation_notification_event",
				"generation_notification_delivery",
				"generation_quota_reservation",
				"generation_provider_capacity_slot");
		assertThat(jdbc.queryForList(
			"SELECT table_name FROM information_schema.views WHERE table_schema = 'public'",
			String.class))
			.contains("generation_legacy_submit_owned_without_provider_job");
	}

	@Test
    void v012BaselineMigratesToV022WithoutDataLoss() {
		try (PostgreSQLContainer legacy = newPostgres()) {
			legacy.start();
			Flyway.configure()
				.dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
				.locations("classpath:db/migration")
				.target("12")
				.load()
				.migrate();

			JdbcTemplate upgraded = new JdbcTemplate(new DriverManagerDataSource(
				legacy.getJdbcUrl(),
				legacy.getUsername(),
				legacy.getPassword()));
			UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
			UUID otherUserId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
			UUID productId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
			UUID singleJobId = UUID.fromString("11111111-1111-4111-8111-111111111111");
			UUID runningJobId = UUID.fromString("22222222-2222-4222-8222-222222222222");
			UUID succeededJobId = UUID.fromString("33333333-3333-4333-8333-333333333333");
			UUID cancelledJobId = UUID.fromString("44444444-4444-4444-8444-444444444444");
			UUID keptAttemptId = UUID.fromString("55555555-5555-4555-8555-555555555555");
			UUID ownedAttemptId = UUID.fromString("66666666-6666-4666-8666-666666666666");
			UUID presetKeyAttemptId = UUID.fromString("77777777-7777-4777-8777-777777777777");
			UUID presetSubmissionKey = UUID.fromString("88888888-8888-4888-8888-888888888888");
			UUID qualityResultId = UUID.fromString("99999999-9999-4999-8999-999999999999");
			Timestamp now = Timestamp.from(Instant.parse("2026-04-01T12:00:00Z"));
			Timestamp completed = Timestamp.from(Instant.parse("2026-04-01T12:10:00Z"));
			Timestamp cancelled = Timestamp.from(Instant.parse("2026-04-01T12:05:00Z"));

			upgraded.update(
				"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
				userId,
				now,
				now);
			upgraded.update(
				"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
				otherUserId,
				now,
				now);
			upgraded.update(
				"INSERT INTO vault_item (id, user_id, storage_key, created_at) VALUES (?, ?, 'vault/single', ?)",
				productId,
				userId,
				now);
			upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'QUEUED', 'try-on', 'req-single', ?, NULL, NULL, NULL, NULL)",
				singleJobId,
				userId,
				now);
			upgraded.update(
				"INSERT INTO generation_job_product (generation_job_id, vault_item_id) VALUES (?, ?)",
				singleJobId,
				productId);
			upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'RUNNING', 'try-on', 'req-running', ?, NULL, NULL, NULL, NULL)",
				runningJobId,
				userId,
				now);
			upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'SUCCEEDED', 'try-on', 'req-succeeded', ?, ?, NULL, NULL, NULL)",
				succeededJobId,
				userId,
				now,
				completed);
			upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'QUEUED', 'try-on', 'req-cancelled', ?, NULL, NULL, ?, NULL)",
				cancelledJobId,
				userId,
				now,
				cancelled);
			upgraded.update(
				"INSERT INTO generation_attempt "
					+ "(id, generation_job_id, provider, model, provider_job_id, attempt_number, "
					+ "started_at, completed_at, latency_ms, credits_used, estimated_cost, currency, "
					+ "failure_code, technical_outcome, submission_key, submit_owned_at, "
					+ "submission_indeterminate, temp_storage_key, provider_output_ref) "
					+ "VALUES (?, ?, 'fashn', 'model-a', 'provider-job-keep', 1, ?, NULL, NULL, 3.25, NULL, NULL, "
					+ "NULL, NULL, NULL, NULL, false, 'temp/key', 'output/ref')",
				keptAttemptId,
				singleJobId,
				now);
			upgraded.update(
				"INSERT INTO generation_attempt "
					+ "(id, generation_job_id, provider, model, provider_job_id, attempt_number, "
					+ "started_at, completed_at, latency_ms, credits_used, estimated_cost, currency, "
					+ "failure_code, technical_outcome, submission_key, submit_owned_at, "
					+ "submission_indeterminate, temp_storage_key, provider_output_ref) "
					+ "VALUES (?, ?, 'fashn', 'model-a', NULL, 1, ?, NULL, NULL, NULL, NULL, NULL, "
					+ "NULL, NULL, NULL, ?, true, NULL, NULL)",
				ownedAttemptId,
				runningJobId,
				now,
				now);
			upgraded.update(
				"INSERT INTO generation_attempt "
					+ "(id, generation_job_id, provider, model, provider_job_id, attempt_number, "
					+ "started_at, completed_at, latency_ms, credits_used, estimated_cost, currency, "
					+ "failure_code, technical_outcome, submission_key, submit_owned_at, "
					+ "submission_indeterminate, temp_storage_key, provider_output_ref) "
					+ "VALUES (?, ?, 'fashn', 'model-a', 'provider-job-preset', 1, ?, NULL, NULL, NULL, NULL, NULL, "
					+ "NULL, NULL, ?, NULL, false, NULL, NULL)",
				presetKeyAttemptId,
				succeededJobId,
				now,
				presetSubmissionKey);
			upgraded.update(
				"INSERT INTO quality_result "
					+ "(id, generation_attempt_id, decision, identity_preservation, garment_fidelity, "
					+ "color_fidelity, anatomy_valid, artifact_detected, created_at) "
					+ "VALUES (?, ?, 'PASS', NULL, NULL, NULL, NULL, NULL, ?)",
				qualityResultId,
				keptAttemptId,
				now);

			Flyway.configure()
				.dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
				.locations("classpath:db/migration")
				.load()
				.migrate();

			assertThat(upgraded.queryForList(
				"SELECT version, success FROM flyway_schema_history "
					+ "WHERE version IS NOT NULL ORDER BY installed_rank"))
				.extracting(row -> Integer.parseInt(row.get("version").toString()))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_job",
				Integer.class)).isEqualTo(4);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_attempt",
				Integer.class)).isEqualTo(3);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_job "
					+ "WHERE retry_not_before_at IS NULL AND retry_policy_snapshot IS NULL",
				Integer.class)).isEqualTo(4);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_attempt WHERE transmission_count = 0",
				Integer.class)).isEqualTo(3);
			assertThat(upgraded.queryForObject(
				"SELECT provider_job_id FROM generation_attempt WHERE id = ?",
				String.class,
				keptAttemptId)).isEqualTo("provider-job-keep");
			assertThat(upgraded.queryForMap(
				"SELECT status, current_step, total_steps FROM generation_job WHERE id = ?",
				singleJobId))
				.containsEntry("status", "QUEUED")
				.containsEntry("current_step", 1)
				.containsEntry("total_steps", 1);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_render_step WHERE generation_job_id = ?",
				Integer.class,
				singleJobId)).isEqualTo(1);
			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_render_step_item i "
					+ "JOIN generation_render_step s ON s.id = i.render_step_id "
					+ "WHERE s.generation_job_id = ?",
				Integer.class,
				singleJobId)).isEqualTo(1);
			assertThat(upgraded.queryForObject(
				"SELECT status FROM generation_job WHERE id = ?",
				String.class,
				runningJobId)).isEqualTo("GENERATING");
			assertThat(upgraded.queryForObject(
				"SELECT status FROM generation_job WHERE id = ?",
				String.class,
				succeededJobId)).isEqualTo("COMPLETED");
			assertThat(upgraded.queryForMap(
				"SELECT status, terminal_reason, completed_at FROM generation_job WHERE id = ?",
				cancelledJobId))
				.containsEntry("status", "FAILED")
				.containsEntry("terminal_reason", "CANCELLED_BY_USER");
			assertThat(upgraded.queryForObject(
				"SELECT completed_at FROM generation_job WHERE id = ?",
				Timestamp.class,
				cancelledJobId)).isNotNull();
			assertThat(upgraded.queryForObject(
				"SELECT submission_key FROM generation_attempt WHERE id = ?",
				UUID.class,
				keptAttemptId)).isEqualTo(keptAttemptId);
			assertThat(upgraded.queryForObject(
				"SELECT submission_key FROM generation_attempt WHERE id = ?",
				UUID.class,
				presetKeyAttemptId)).isEqualTo(presetSubmissionKey);
			assertThat(upgraded.queryForMap(
				"SELECT actual_billed_units, pricing_observed_at, billing_unit "
					+ "FROM generation_attempt WHERE id = ?",
				keptAttemptId))
				.containsEntry("actual_billed_units", new BigDecimal("3.25"))
				.containsEntry("pricing_observed_at", null)
				.containsEntry("billing_unit", null);
			assertThat(upgraded.queryForMap(
				"SELECT actual_billed_units, pricing_observed_at, billing_unit "
					+ "FROM generation_attempt WHERE id = ?",
				ownedAttemptId))
				.containsEntry("actual_billed_units", null)
				.containsEntry("pricing_observed_at", null)
				.containsEntry("billing_unit", null);
			assertThat(upgraded.queryForMap(
				"SELECT quality_policy_version, reason_code FROM quality_result WHERE id = ?",
				qualityResultId))
				.containsEntry("quality_policy_version", null)
				.containsEntry("reason_code", null);

			assertThat(upgraded.queryForObject(
				"SELECT COUNT(*) FROM generation_legacy_submit_owned_without_provider_job "
					+ "WHERE generation_attempt_id = ?",
				Integer.class,
				ownedAttemptId)).isEqualTo(1);

			upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'QUEUED', 'try-on', 'req-single', ?, NULL, NULL, NULL, NULL)",
				UUID.fromString("99999999-9999-4999-8999-999999999999"),
				otherUserId,
				now);
			assertThatThrownBy(() -> upgraded.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'QUEUED', 'try-on', 'req-single', ?, NULL, NULL, NULL, NULL)",
				UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
				userId,
				now)).hasMessageContaining("generation_job_user_id_client_request_id_key");
		}
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

	private static List<String> constraintDefinitions(JdbcTemplate template, String table) {
		return template.queryForList(
			"SELECT pg_get_constraintdef(oid) FROM pg_constraint "
				+ "WHERE conrelid = ?::regclass",
			String.class,
			"public." + table);
	}

	private static byte[] migrationBytes(String filename) throws Exception {
		try (var input = FlywayFoundationTest.class.getResourceAsStream("/db/migration/" + filename)) {
			assertThat(input).as("migration resource %s", filename).isNotNull();
			return input.readAllBytes();
		}
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
