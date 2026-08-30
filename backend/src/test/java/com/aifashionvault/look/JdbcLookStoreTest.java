package com.aifashionvault.look;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class JdbcLookStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

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

	private JdbcLookStore store;

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
		jdbc.update("TRUNCATE TABLE look, generation_job, users CASCADE");
		store = new JdbcLookStore(dataSource);
	}

	@Test
	void insertPersistsPassLookAndFindsByIdAndGenerationJobId() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = new LookId(UUID.randomUUID());
		StorageKey storageKey = storageKey(userId, lookId);
		Look created = look(lookId, userId, jobId, storageKey);

		Look stored = store.insert(created);

		assertThat(stored).isEqualTo(created);
		assertThat(store.findById(lookId)).contains(created);
		assertThat(store.findByGenerationJobId(jobId)).contains(created);
		assertThat(jdbc.queryForObject(
			"SELECT quality_status FROM look WHERE id = ?",
			String.class,
			lookId.value())).isEqualTo(QualityDecision.PASS.name());
		assertThat(count("look_item")).isZero();
	}

	@Test
	void secondInsertForSameGenerationJobIdReplaysExistingLook() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId firstId = new LookId(UUID.randomUUID());
		LookId replayId = new LookId(UUID.randomUUID());
		Look first = look(firstId, userId, jobId, storageKey(userId, firstId));
		Look replay = look(replayId, userId, jobId, storageKey(userId, replayId));

		Look stored = store.insert(first);
		Look second = store.insert(replay);

		assertThat(stored.id()).isEqualTo(firstId);
		assertThat(second.id()).isEqualTo(firstId);
		assertThat(second).isEqualTo(stored);
		assertThat(count("look")).isEqualTo(1);
		assertThat(store.findByGenerationJobId(jobId).orElseThrow().id()).isEqualTo(firstId);
		assertThat(jdbc.queryForObject(
			"SELECT id FROM look WHERE generation_job_id = ?",
			UUID.class,
			jobId.value())).isEqualTo(firstId.value());
	}

	@Test
	void persistedStorageKeyIsTextKeyNotHttpUrl() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = new LookId(UUID.randomUUID());
		StorageKey storageKey = storageKey(userId, lookId);

		store.insert(look(lookId, userId, jobId, storageKey));

		String persisted = jdbc.queryForObject(
			"SELECT storage_key FROM look WHERE id = ?",
			String.class,
			lookId.value());
		assertThat(persisted).isEqualTo(storageKey.value());
		assertThat(persisted.toLowerCase(Locale.ROOT)).doesNotStartWith("http://");
		assertThat(persisted.toLowerCase(Locale.ROOT)).doesNotStartWith("https://");
	}

	@Test
	void findersReturnEmptyWhenLookIsMissing() {
		assertThat(store.findById(new LookId(UUID.randomUUID()))).isEmpty();
		assertThat(store.findByGenerationJobId(new GenerationJobId(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void insertIfJobActiveReturnsEmptyWhenJobSubjectDeleted() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		jdbc.update(
			"UPDATE generation_job SET status = 'FAILED', completed_at = ?, subject_deleted_at = ?, "
				+ "terminal_reason = 'SUBJECT_DELETED' WHERE id = ?",
			Timestamp.from(NOW),
			Timestamp.from(NOW),
			jobId.value());
		LookId lookId = new LookId(UUID.randomUUID());
		Look created = look(lookId, userId, jobId, storageKey(userId, lookId));

		assertThat(store.insertIfJobActive(created)).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM look WHERE generation_job_id = ?",
			Integer.class,
			jobId.value())).isZero();
	}

	@Test
	void insertIfJobActiveInsertsPassLookWhenJobIsActive() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = new LookId(UUID.randomUUID());
		Look created = look(lookId, userId, jobId, storageKey(userId, lookId));

		assertThat(store.insertIfJobActive(created)).contains(created);
		assertThat(store.findByGenerationJobId(jobId)).contains(created);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM generation_job WHERE id = ?",
			String.class,
			jobId.value())).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject(
			"SELECT type FROM generation_notification_event WHERE generation_job_id = ?",
			String.class,
			jobId.value())).isEqualTo("LOOK_READY");
	}

	@Test
	void ownerScopedLogicalDeleteHidesLookImmediatelyAndIsIdempotent() {
		UserId ownerId = insertUser();
		UserId otherId = insertUser();
		GenerationJobId jobId = insertJob(ownerId);
		LookId lookId = new LookId(UUID.randomUUID());
		Look created = look(lookId, ownerId, jobId, storageKey(ownerId, lookId));
		store.insert(created);

		assertThat(store.findByIdForUser(lookId, otherId)).isEmpty();
		assertThat(store.logicallyDeleteForUser(lookId, otherId)).isFalse();
		assertThat(store.logicallyDeleteForUser(lookId, ownerId)).isTrue();
		assertThat(store.findById(lookId)).isEmpty();
		assertThat(store.findByGenerationJobId(jobId)).isEmpty();
		assertThat(store.findByIdForUser(lookId, ownerId)).isEmpty();
		assertThat(store.findByIdForCleanup(lookId, ownerId)).contains(created);
		assertThat(store.logicallyDeleteForUser(lookId, ownerId)).isTrue();
	}

	@Test
	void listForUserReturnsOnlyOwnedUndeletedLooksNewestThenId() {
		UserId owner = insertUser();
		UserId foreign = insertUser();
		LookId olderId = new LookId(UUID.fromString("10000000-0000-0000-0000-000000000000"));
		LookId newerLowId = new LookId(UUID.fromString("20000000-0000-0000-0000-000000000000"));
		LookId newerHighId = new LookId(UUID.fromString("30000000-0000-0000-0000-000000000000"));
		Look older = look(olderId, owner, insertJob(owner), storageKey(owner, olderId));
		Look newerLow = look(newerLowId, owner, insertJob(owner), storageKey(owner, newerLowId));
		Look newerHigh = look(newerHighId, owner, insertJob(owner), storageKey(owner, newerHighId));
		store.insert(older);
		store.insert(newerLow);
		store.insert(newerHigh);
		jdbc.update("UPDATE look SET created_at = ? WHERE id = ?", Timestamp.from(NOW.minusSeconds(1)), olderId.value());
		LookId foreignId = new LookId(UUID.randomUUID());
		store.insert(look(foreignId, foreign, insertJob(foreign), storageKey(foreign, foreignId)));
		store.logicallyDeleteForUser(newerLowId, owner);

		assertThat(store.listForUser(owner)).extracting(Look::id).containsExactly(newerHighId, olderId);
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

	private GenerationJobId insertJob(UserId userId) {
		GenerationJobId jobId = new GenerationJobId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO generation_job "
				+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
				+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
				+ "VALUES (?, ?, NULL, 'QUEUED', NULL, NULL, ?, NULL, NULL, NULL, NULL)",
			jobId.value(),
			userId.value(),
			Timestamp.from(NOW));
		return jobId;
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private static Look look(LookId lookId, UserId userId, GenerationJobId jobId, StorageKey storageKey) {
		return new Look(
			lookId,
			userId,
			null,
			jobId,
			null,
			storageKey,
			null,
			null,
			null,
			QualityDecision.PASS,
			NOW,
			List.of());
	}

	private static StorageKey storageKey(UserId userId, LookId lookId) {
		return new StorageKey("users/" + userId.value() + "/looks/" + lookId.value() + ".jpg");
	}
}
