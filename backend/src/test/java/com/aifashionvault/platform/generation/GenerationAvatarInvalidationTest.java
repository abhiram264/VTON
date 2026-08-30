package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class GenerationAvatarInvalidationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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

	private GenerationAvatarInvalidation invalidation;

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
	void setUp() {
		jdbc.update(
			"TRUNCATE TABLE generation_job, avatar_image, avatar_profile, users CASCADE");
		invalidation = new GenerationAvatarInvalidation(dataSource);
	}

	@Test
	void afterAvatarDeletedMarksMatchingJobsAndLeavesOtherUsersUntouched() {
		UserId owner = insertUser();
		UserId otherUser = insertUser();
		AvatarProfileId profileId = insertProfile(owner);
		AvatarProfileId otherProfileId = insertProfile(otherUser);
		UUID matchingImage = insertImage(profileId, owner);
		UUID secondMatchingImage = insertImage(profileId, owner);
		UUID otherUserImage = insertImage(otherProfileId, otherUser);
		UUID matchingJob = insertJob(owner, matchingImage, null);
		UUID secondMatchingJob = insertJob(owner, secondMatchingImage, null);
		UUID otherUsersImageOnOwnerJob = insertJob(owner, otherUserImage, null);
		UUID otherUserJob = insertJob(otherUser, otherUserImage, null);
		UUID alreadyDeletedJob = insertJob(owner, matchingImage, NOW.minusSeconds(30));
		UUID noAvatarJob = insertJob(owner, null, null);

		invalidation.afterAvatarDeleted(owner, profileId, NOW);

		assertThat(subjectDeletedAt(matchingJob)).isEqualTo(NOW);
		assertThat(subjectDeletedAt(secondMatchingJob)).isEqualTo(NOW);
		assertThat(subjectDeletedAt(alreadyDeletedJob)).isEqualTo(NOW.minusSeconds(30));
		assertThat(subjectDeletedAt(otherUsersImageOnOwnerJob)).isNull();
		assertThat(subjectDeletedAt(otherUserJob)).isNull();
		assertThat(subjectDeletedAt(noAvatarJob)).isNull();
	}

	@Test
	void afterAvatarDeletedAcceptsClock() {
		UserId owner = insertUser();
		AvatarProfileId profileId = insertProfile(owner);
		UUID matchingJob = insertJob(owner, insertImage(profileId, owner), null);

		invalidation.afterAvatarDeleted(owner, profileId, CLOCK);

		assertThat(subjectDeletedAt(matchingJob)).isEqualTo(NOW);
	}

	@Test
	void rejectsNullConstructorAndMethodArgs() {
		assertThatThrownBy(() -> new GenerationAvatarInvalidation((DataSource) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("dataSource is required");
		assertThatThrownBy(() -> invalidation.afterAvatarDeleted(null, insertProfile(insertUser()), NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId is required");
		assertThatThrownBy(() -> invalidation.afterAvatarDeleted(insertUser(), null, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("profileId is required");
		assertThatThrownBy(() -> invalidation.afterAvatarDeleted(insertUser(), insertProfile(insertUser()), (Instant) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("when is required");
		assertThatThrownBy(() -> invalidation.afterAvatarDeleted(insertUser(), insertProfile(insertUser()), (Clock) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("clock is required");
	}

	private Instant subjectDeletedAt(UUID jobId) {
		Timestamp timestamp = jdbc.queryForObject(
			"SELECT subject_deleted_at FROM generation_job WHERE id = ?",
			Timestamp.class,
			jobId);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private UserId insertUser() {
		UserId userId = new UserId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return userId;
	}

	private AvatarProfileId insertProfile(UserId userId) {
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) "
				+ "VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)",
			profileId.value(),
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return profileId;
	}

	private UUID insertImage(AvatarProfileId profileId, UserId userId) {
		UUID imageId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			imageId,
			profileId.value(),
			"users/" + userId.value() + "/avatars/" + imageId + ".jpg",
			Timestamp.from(NOW));
		return imageId;
	}

	private UUID insertJob(UserId userId, UUID avatarImageId, Instant subjectDeletedAt) {
		UUID jobId = UUID.randomUUID();
		if (subjectDeletedAt == null) {
			jdbc.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
					+ "VALUES (?, ?, NULL, 'QUEUED', NULL, ?, ?, NULL, ?, NULL, NULL)",
				jobId,
				userId.value(),
				"req-" + jobId,
				Timestamp.from(NOW),
				avatarImageId);
		}
		else {
			jdbc.update(
				"INSERT INTO generation_job "
					+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
					+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at, "
					+ "terminal_reason) "
					+ "VALUES (?, ?, NULL, 'FAILED', NULL, ?, ?, ?, ?, NULL, ?, 'SUBJECT_DELETED')",
				jobId,
				userId.value(),
				"req-" + jobId,
				Timestamp.from(NOW),
				Timestamp.from(subjectDeletedAt),
				avatarImageId,
				Timestamp.from(subjectDeletedAt));
		}
		return jobId;
	}
}
