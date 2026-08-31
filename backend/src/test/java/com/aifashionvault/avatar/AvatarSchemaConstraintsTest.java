package com.aifashionvault.avatar;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarSchemaConstraintsTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final Timestamp NOW_TS = Timestamp.from(NOW);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()));
	}

	@BeforeEach
	void clearAvatarTables() {
		jdbc.update("TRUNCATE TABLE avatar_image, avatar_profile, users CASCADE");
	}

	@Test
	void flywayHistoryIsExactlyVersionsOneThroughTwenty() {
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history).hasSize(22);
        assertThat(history).extracting(row -> Integer.parseInt(row.get("version").toString()))
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);

		assertThat(history)
			.extracting(row -> row.get("success"))
			.containsOnly(true);
	}

	@Test
	void duplicateUserIdOnAvatarProfileFailsWithUniqueViolation() {
		UUID userId = insertUser();
		insertProfile(userId);

		assertThatThrownBy(() -> insertProfile(userId))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23505"));
	}

	@Test
	void duplicateNonNullSourceUploadIdFailsWithUniqueViolation() {
		UUID profileId = insertProfile(insertUser());
		UUID sourceUploadId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		insertImageWithSourceUpload(profileId, sourceUploadId);

		assertThatThrownBy(() -> insertImageWithSourceUpload(profileId, sourceUploadId))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23505"));
	}

	@Test
	void primaryImageOwnedByAnotherProfileFailsCompositeForeignKey() {
		UUID profileA = insertProfile(insertUser());
		UUID profileB = insertProfile(insertUser());
		UUID imageOnA = insertImage(profileA);

		assertThatThrownBy(() -> jdbc.update(
			"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
			imageOnA,
			profileB))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23503"));
	}

	@Test
	void primaryImageOwnedBySameProfileSucceeds() {
		UUID profileId = insertProfile(insertUser());
		UUID imageId = insertImage(profileId);

		jdbc.update(
			"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
			imageId,
			profileId);

		assertThat(jdbc.queryForObject(
			"SELECT primary_image_id FROM avatar_profile WHERE id = ?",
			UUID.class,
			profileId)).isEqualTo(imageId);
	}

	@Test
	void poseAndQualityStatusColumnsStillExist() {
		List<String> columns = jdbc.queryForList(
			"SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = 'avatar_image'",
			String.class);

		assertThat(columns).contains("pose", "quality_status");
	}

	@Test
	void preflightPolicyVersionIsNullableText() {
		Map<String, Object> column = jdbc.queryForMap(
			"SELECT data_type, is_nullable FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = 'avatar_image' "
				+ "AND column_name = 'preflight_policy_version'");

		assertThat(column)
			.containsEntry("data_type", "text")
			.containsEntry("is_nullable", "YES");
	}

	@Test
	void rawInsertOmittingPreflightPolicyVersionLeavesItNull() {
		UUID profileId = insertProfile(insertUser());
		UUID imageId = insertImage(profileId);

		assertThat(jdbc.queryForMap(
			"SELECT preflight_policy_version FROM avatar_image WHERE id = ?",
			imageId).get("preflight_policy_version")).isNull();
	}

	@Test
	void preflightPolicyVersionMustBeNonblankWhenPresent() {
		UUID profileId = insertProfile(insertUser());
		String policyVersion = " semantic-v1 ";
		UUID imageId = insertImageWithPolicyVersion(profileId, policyVersion);

		assertThat(jdbc.queryForObject(
			"SELECT preflight_policy_version FROM avatar_image WHERE id = ?",
			String.class,
			imageId)).isEqualTo(policyVersion);

		for (String blank : List.of("", " ", "   ")) {
			assertThatThrownBy(() -> insertImageWithPolicyVersion(profileId, blank))
				.as("policy version %s", blank)
				.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
		}
	}

	@Test
	void poseRemainsNullable() {
		UUID profileId = insertProfile(insertUser());
		UUID imageId = insertImageWith(profileId, "users/" + profileId + "/avatars/pose-null.jpg", 768, 1024, null);

		assertThat(jdbc.queryForMap("SELECT pose FROM avatar_image WHERE id = ?", imageId).get("pose")).isNull();
	}

	@Test
	void deletingProfileStatusIsAllowed() {
		UUID profileId = insertProfile(insertUser(), "DELETING");

		assertThat(jdbc.queryForObject(
			"SELECT status FROM avatar_profile WHERE id = ?",
			String.class,
			profileId)).isEqualTo("DELETING");
	}

	@Test
	void invalidProfileStatusesFailWithCheckViolation() {
		for (String status : List.of("DELETED", "CREATING", "FAILED")) {
			assertThatThrownBy(() -> insertProfile(insertUser(), status))
				.as("status %s", status)
				.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
		}
	}

	@Test
	void duplicateStorageKeyFailsWithUniqueViolation() {
		UUID profileA = insertProfile(insertUser());
		UUID profileB = insertProfile(insertUser());
		String storageKey = "users/shared/avatars/same-key.jpg";
		insertImageWith(profileA, storageKey, 768, 1024, "front");

		assertThatThrownBy(() -> insertImageWith(profileB, storageKey, 768, 1024, "front"))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23505"));
	}

	@Test
	void nonPositiveDimensionsFailWithCheckViolation() {
		UUID profileId = insertProfile(insertUser());

		assertThatThrownBy(() -> insertImageWith(profileId, uniqueStorageKey(profileId), 0, 1024, "front"))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
		assertThatThrownBy(() -> insertImageWith(profileId, uniqueStorageKey(profileId), -1, 1024, "front"))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
		assertThatThrownBy(() -> insertImageWith(profileId, uniqueStorageKey(profileId), 768, 0, "front"))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
		assertThatThrownBy(() -> insertImageWith(profileId, uniqueStorageKey(profileId), 768, -1, "front"))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23514"));
	}

	@Test
	void nullWidthAndHeightRemainInsertable() {
		UUID profileId = insertProfile(insertUser());
		UUID imageId = insertImageWith(profileId, uniqueStorageKey(profileId), null, null, "front");

		Map<String, Object> dimensions = jdbc.queryForMap(
			"SELECT width, height FROM avatar_image WHERE id = ?",
			imageId);
		assertThat(dimensions.get("width")).isNull();
		assertThat(dimensions.get("height")).isNull();
	}

	@Test
	void consumedUploadTombstonesContainOnlyIdentifiersAndTimestampAndCascadeWithUser() {
		UUID userId = insertUser();
		UUID uploadId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_consumed_upload (user_id, upload_id, consumed_at) VALUES (?, ?, ?)",
			userId,
			uploadId,
			NOW_TS);

		assertThat(jdbc.queryForList(
			"SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = 'avatar_consumed_upload' "
				+ "ORDER BY ordinal_position",
			String.class))
			.containsExactly("user_id", "upload_id", "consumed_at");
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO avatar_consumed_upload (user_id, upload_id, consumed_at) VALUES (?, ?, ?)",
			userId,
			uploadId,
			NOW_TS))
			.satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo("23505"));

		jdbc.update("DELETE FROM users WHERE id = ?", userId);

		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM avatar_consumed_upload WHERE user_id = ?",
			Integer.class,
			userId)).isZero();
	}

	private static UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId,
			NOW_TS,
			NOW_TS);
		return userId;
	}

	private static UUID insertProfile(UUID userId) {
		return insertProfile(userId, "ACTIVE");
	}

	private static UUID insertProfile(UUID userId, String status) {
		UUID profileId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_profile (id, user_id, primary_image_id, status, created_at, updated_at) "
				+ "VALUES (?, ?, NULL, ?, ?, ?)",
			profileId,
			userId,
			status,
			NOW_TS,
			NOW_TS);
		return profileId;
	}

	private static UUID insertImage(UUID profileId) {
		return insertImageWith(profileId, uniqueStorageKey(profileId), 768, 1024, "front");
	}

	private static UUID insertImageWith(
		UUID profileId,
		String storageKey,
		Integer width,
		Integer height,
		String pose) {
		UUID imageId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_image "
				+ "(id, profile_id, storage_key, width, height, pose, quality_status, created_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)",
			imageId,
			profileId,
			storageKey,
			width,
			height,
			pose,
			NOW_TS);
		return imageId;
	}

	private static UUID insertImageWithPolicyVersion(UUID profileId, String policyVersion) {
		UUID imageId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_image "
				+ "(id, profile_id, storage_key, width, height, pose, quality_status, "
				+ "preflight_policy_version, created_at) "
				+ "VALUES (?, ?, ?, 768, 1024, 'front', 'PASSED', ?, ?)",
			imageId,
			profileId,
			uniqueStorageKey(profileId),
			policyVersion,
			NOW_TS);
		return imageId;
	}

	private static String uniqueStorageKey(UUID profileId) {
		return "users/" + profileId + "/avatars/" + UUID.randomUUID() + ".jpg";
	}

	private static UUID insertImageWithSourceUpload(UUID profileId, UUID sourceUploadId) {
		UUID imageId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_image "
				+ "(id, profile_id, storage_key, width, height, pose, quality_status, source_upload_id, created_at) "
				+ "VALUES (?, ?, ?, 768, 1024, 'front', 'PENDING', ?, ?)",
			imageId,
			profileId,
			"users/" + profileId + "/avatars/" + imageId + ".jpg",
			sourceUploadId,
			NOW_TS);
		return imageId;
	}

	private static String sqlState(Throwable thrown) {
		Throwable current = thrown;
		while (current != null) {
			if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
				return sqlException.getSQLState();
			}
			current = current.getCause();
		}
		return null;
	}
}
