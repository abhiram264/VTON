package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class JdbcProviderPerformanceStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final MathContext DIVISION = MathContext.DECIMAL128;

	private static final Instant DAY_ONE = Instant.parse("2026-03-30T10:15:00Z");
	private static final Instant DAY_TWO = Instant.parse("2026-03-31T08:45:00Z");
	private static final Instant WINDOW_START = Instant.parse("2026-03-30T00:00:00Z");
	private static final Instant WINDOW_END = Instant.parse("2026-04-01T00:00:00Z");

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

	private JdbcProviderPerformanceStore store;

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
		jdbc.update("TRUNCATE TABLE users, generation_provider_performance_daily CASCADE");
		store = new JdbcProviderPerformanceStore(dataSource, CLOCK);
	}

	@Test
	void recomputeAndSnapshotAggregateTwoUtcDaysPerCategory() {
		seedTwoDayMatrix();

		store.recompute(WINDOW_START, WINDOW_END);
		ProviderPerformanceSnapshot snapshot = store.snapshot(fullWindowQuery());

		assertThat(snapshot.asOf()).isEqualTo(NOW);
		List<ProviderPerformanceRow> rows = snapshot.rows();
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(ProviderPerformanceRow::providerKey, ProviderPerformanceRow::category)
			.containsExactly(tuple("provider-a", "footwear"), tuple("provider-a", "tops"));

		ProviderPerformanceRow footwear = rows.get(0);
		assertThat(footwear.modelOrMode()).isEqualTo("m1");
		assertThat(footwear.currency()).isEqualTo("USD");
		assertThat(footwear.sampleCount()).isEqualTo(2);
		assertThat(footwear.technicalSuccessCount()).isEqualTo(2);
		assertThat(footwear.qualityApprovedCount()).isEqualTo(1);
		assertThat(footwear.fidelity()).isEqualByComparingTo(new BigDecimal("0.85"));
		assertThat(footwear.qualityPassRate()).isEqualByComparingTo(new BigDecimal("0.5"));
		assertThat(footwear.meanLatencyMs()).isEqualTo(2000L);
		assertThat(footwear.failRate()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(footwear.costPerSuccess()).isEqualByComparingTo(new BigDecimal("1.6"));

		ProviderPerformanceRow tops = rows.get(1);
		assertThat(tops.currency()).isEqualTo("USD");
		assertThat(tops.sampleCount()).isEqualTo(3);
		assertThat(tops.technicalSuccessCount()).isEqualTo(2);
		assertThat(tops.qualityApprovedCount()).isEqualTo(2);
		assertThat(tops.fidelity()).isEqualByComparingTo(new BigDecimal("0.8"));
		assertThat(tops.qualityPassRate()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(tops.meanLatencyMs()).isEqualTo(1050L);
		assertThat(tops.failRate())
			.isEqualByComparingTo(BigDecimal.ONE.divide(BigDecimal.valueOf(3), DIVISION));
		assertThat(tops.costPerSuccess()).isEqualByComparingTo(new BigDecimal("0.725"));
	}

	@Test
	void snapshotFiltersByProviderCategoryAndCurrency() {
		seedTwoDayMatrix();
		store.recompute(WINDOW_START, WINDOW_END);

		assertThat(store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, "provider-a", null, null))
			.rows()).hasSize(2);
		assertThat(store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, "provider-b", null, null))
			.rows()).isEmpty();

		List<ProviderPerformanceRow> tops = store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, null, "tops", null))
			.rows();
		assertThat(tops).hasSize(1);
		assertThat(tops.get(0).category()).isEqualTo("tops");
		assertThat(store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, null, "footwear", null))
			.rows()).hasSize(1);

		assertThat(store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, null, null, "USD"))
			.rows()).hasSize(2);
		assertThat(store
			.snapshot(new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, null, null, "EUR"))
			.rows()).isEmpty();
	}

	@Test
	void emptyDatabaseRecomputeAndSnapshotStayClean() {
		store.recompute(WINDOW_START, WINDOW_END);

		ProviderPerformanceSnapshot snapshot = store.snapshot(fullWindowQuery());

		assertThat(snapshot.rows()).isEmpty();
		assertThat(snapshot.asOf()).isEqualTo(NOW);
	}

	@Test
	void unknownAndEstimatedCostsFormSeparateCurrencyBuckets() {
		UUID userId = insertUser();
		UUID vaultItem = insertVaultItem(userId, null);
		UUID jobId = insertJob(userId);
		UUID stepId = insertRenderStep(jobId, 1);
		insertRenderStepItem(stepId, 1, vaultItem, "tops");
		insertAttempt(jobId, 1, "provider-a", "m1", stepId, DAY_ONE, 400, "SUCCESS",
			"UNKNOWN", null, null, null);
		insertAttempt(jobId, 2, "provider-a", "m1", stepId, DAY_ONE, 450, "FAILED",
			"UNKNOWN", null, null, null);
		UUID estimated = insertAttempt(jobId, 3, "provider-a", "m1", stepId, DAY_ONE, 100, "SUCCESS",
			"ESTIMATED", new BigDecimal("0.25"), null, "EUR");
		insertQualityResult(estimated, "PASS",
			new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));

		store.recompute(WINDOW_START, WINDOW_END);
		List<ProviderPerformanceRow> rows = store.snapshot(fullWindowQuery()).rows();

		assertThat(rows).hasSize(2);
		ProviderPerformanceRow unknown = rows.get(0);
		assertThat(unknown.currency()).isNull();
		assertThat(unknown.costPerSuccess()).isNull();
		assertThat(unknown.sampleCount()).isEqualTo(2);
		assertThat(unknown.technicalSuccessCount()).isEqualTo(1);
		assertThat(unknown.failRate()).isEqualByComparingTo(new BigDecimal("0.5"));
		assertThat(unknown.fidelity()).isNull();
		assertThat(unknown.qualityPassRate()).isNull();
		assertThat(unknown.meanLatencyMs()).isNull();

		ProviderPerformanceRow estimatedRow = rows.get(1);
		assertThat(estimatedRow.currency()).isEqualTo("EUR");
		assertThat(estimatedRow.sampleCount()).isEqualTo(1);
		assertThat(estimatedRow.technicalSuccessCount()).isEqualTo(1);
		assertThat(estimatedRow.qualityApprovedCount()).isEqualTo(1);
		assertThat(estimatedRow.costPerSuccess()).isEqualByComparingTo(new BigDecimal("0.25"));
		assertThat(estimatedRow.fidelity()).isEqualByComparingTo(new BigDecimal("0.5"));
		assertThat(estimatedRow.meanLatencyMs()).isEqualTo(100L);
	}

	@Test
	void categoryProvenanceFallsBackFromStepItemToVaultItemToUnattributed() {
		UUID userId = insertUser();
		UUID productVaultItem = insertVaultItem(userId, "footwear");
		UUID firstStepVaultItem = insertVaultItem(userId, "tops");
		UUID secondStepVaultItem = insertVaultItem(userId, "dresses");
		UUID singleVaultItem = insertVaultItem(userId, "shoes");

		UUID stepJob = insertJob(userId);
		insertJobProduct(stepJob, productVaultItem);
		UUID stepId = insertRenderStep(stepJob, 1);
		insertRenderStepItem(stepId, 1, firstStepVaultItem, "tops");
		insertRenderStepItem(stepId, 2, secondStepVaultItem, "dresses");
		insertAttempt(stepJob, 1, "provider-a", "m1", stepId, DAY_ONE, 300, "SUCCESS",
			"UNKNOWN", null, null, null);

		UUID singleJob = insertJob(userId);
		insertJobProduct(singleJob, singleVaultItem);
		insertAttempt(singleJob, 1, "provider-a", "m1", null, DAY_ONE, 310, "SUCCESS",
			"UNKNOWN", null, null, null);

		UUID bareJob = insertJob(userId);
		insertAttempt(bareJob, 1, "provider-a", "m1", null, DAY_ONE, 320, "SUCCESS",
			"UNKNOWN", null, null, null);

		store.recompute(WINDOW_START, WINDOW_END);
		List<ProviderPerformanceRow> rows = store.snapshot(fullWindowQuery()).rows();

		assertThat(rows).extracting(ProviderPerformanceRow::category)
			.containsExactly("shoes", "tops", "unattributed");
		assertThat(rows).allSatisfy(row -> assertThat(row.sampleCount()).isEqualTo(1));
		long sourceCompleted = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_attempt WHERE completed_at IS NOT NULL", Long.class);
		assertThat(rows.stream().mapToLong(ProviderPerformanceRow::sampleCount).sum())
			.isEqualTo(sourceCompleted)
			.isEqualTo(3);
	}

	@Test
	void latencyNullSafetyCountsOnlyApprovedAttemptsWithLatency() {
		UUID userId = insertUser();
		UUID vaultItem = insertVaultItem(userId, null);
		UUID jobId = insertJob(userId);
		UUID stepId = insertRenderStep(jobId, 1);
		insertRenderStepItem(stepId, 1, vaultItem, "tops");
		UUID withoutLatency = insertAttempt(jobId, 1, "provider-a", "m1", stepId, DAY_ONE, null, "SUCCESS",
			"UNKNOWN", null, null, null);
		insertQualityResult(withoutLatency, "PASS",
			new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));
		UUID withLatency = insertAttempt(jobId, 2, "provider-a", "m1", stepId, DAY_ONE, 1234, "SUCCESS",
			"UNKNOWN", null, null, null);
		insertQualityResult(withLatency, "PASS",
			new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));

		store.recompute(WINDOW_START, WINDOW_END);
		ProviderPerformanceRow row = store.snapshot(fullWindowQuery()).rows().get(0);

		assertThat(row.meanLatencyMs()).isEqualTo(1234L);
		assertThat(jdbc.queryForObject(
			"SELECT latency_sample_count FROM generation_provider_performance_daily", Long.class))
			.isEqualTo(1L);
		assertThat(jdbc.queryForObject(
			"SELECT latency_sum_ms FROM generation_provider_performance_daily", Long.class))
			.isEqualTo(1234L);
	}

	@Test
	void recomputeTwiceWritesIdenticalTableState() {
		seedTwoDayMatrix();

		store.recompute(WINDOW_START, WINDOW_END);
		List<ProviderPerformanceRow> firstRows = store.snapshot(fullWindowQuery()).rows();
		long firstRowCount = countDailyRows();
		long firstSampleSum = sumSampleCounts();
		assertThat(firstRows).isNotEmpty();

		store.recompute(WINDOW_START, WINDOW_END);

		assertThat(store.snapshot(fullWindowQuery()).rows()).isEqualTo(firstRows);
		assertThat(countDailyRows()).isEqualTo(firstRowCount);
		assertThat(sumSampleCounts()).isEqualTo(firstSampleSum);
	}

	@Test
	void failedReplacementInsertPreservesPriorRollup() {
		seedTwoDayMatrix();
		store.recompute(WINDOW_START, WINDOW_END);
		List<ProviderPerformanceRow> priorRows = store.snapshot(fullWindowQuery()).rows();

		UUID userId = insertUser();
		UUID jobId = insertJob(userId);
		insertAttempt(jobId, 1, "provider-b", "m1", null, DAY_ONE, 100, "SUCCESS",
			"FINAL", null, new BigDecimal("-1.00"), "USD");

		assertThatThrownBy(() -> store.recompute(WINDOW_START, WINDOW_END))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(store.snapshot(fullWindowQuery()).rows()).isEqualTo(priorRows);
	}
	@Test
	void v024RejectsInvalidProviderAggregateCounts() {
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO generation_provider_performance_daily "
				+ "(provider_key, model_or_mode, category, bucket_day, currency_key, sample_count, "
				+ "technical_success_count, quality_evaluated_count, quality_approved_count, fidelity_axis_sum, "
				+ "fidelity_sample_count, latency_sum_ms, latency_sample_count, known_cost_total, known_cost_count, "
				+ "last_recomputed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			"invalid-provider", "m", "tops", java.sql.Date.valueOf(DAY_ONE.atZone(ZoneOffset.UTC).toLocalDate()), "USD",
			0L, -1L, 0L, 0L, BigDecimal.ZERO, 0L, 0L, 0L, null, 0L, Timestamp.from(NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void recomputeAfterSourceDeletionShrinksTheBucket() {
		UUID userId = insertUser();
		UUID vaultItem = insertVaultItem(userId, null);
		UUID jobId = insertJob(userId);
		UUID stepId = insertRenderStep(jobId, 1);
		insertRenderStepItem(stepId, 1, vaultItem, "tops");
		UUID kept = insertAttempt(jobId, 1, "provider-a", "m1", stepId, DAY_ONE, 700, "SUCCESS",
			"FINAL", null, new BigDecimal("0.30"), "USD");
		insertQualityResult(kept, "PASS",
			new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));
		UUID deleted = insertAttempt(jobId, 2, "provider-a", "m1", stepId, DAY_ONE, 750, "SUCCESS",
			"FINAL", null, new BigDecimal("0.30"), "USD");
		insertQualityResult(deleted, "PASS",
			new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));

		store.recompute(WINDOW_START, WINDOW_END);
		assertThat(store.snapshot(fullWindowQuery()).rows()).hasSize(1);
		assertThat(store.snapshot(fullWindowQuery()).rows().get(0).sampleCount()).isEqualTo(2);

		jdbc.update("DELETE FROM quality_result WHERE generation_attempt_id = ?", deleted);
		jdbc.update("DELETE FROM generation_attempt WHERE id = ?", deleted);

		store.recompute(WINDOW_START, WINDOW_END);
		ProviderPerformanceRow row = store.snapshot(fullWindowQuery()).rows().get(0);
		assertThat(row.sampleCount()).isEqualTo(1);
		assertThat(row.qualityApprovedCount()).isEqualTo(1);
		long remaining = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_attempt WHERE completed_at IS NOT NULL", Long.class);
		assertThat(row.sampleCount()).isEqualTo(remaining);
	}

	@Test
	void recomputeRejectsInvalidWindows() {
		assertThatThrownBy(() -> store.recompute(WINDOW_END, WINDOW_START))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.recompute(WINDOW_START, WINDOW_START))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.recompute(null, WINDOW_END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.recompute(WINDOW_START, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private ProviderPerformanceQuery fullWindowQuery() {
		return new ProviderPerformanceQuery(WINDOW_START, WINDOW_END, null, null, null);
	}

	private void seedTwoDayMatrix() {
		UUID userId = insertUser();
		UUID vaultTop = insertVaultItem(userId, null);
		UUID vaultFoot = insertVaultItem(userId, null);

		UUID dayOneJob = insertJob(userId);
		UUID dayOneStep = insertRenderStep(dayOneJob, 1);
		insertRenderStepItem(dayOneStep, 1, vaultTop, "tops");
		UUID first = insertAttempt(dayOneJob, 1, "provider-a", "m1", dayOneStep, DAY_ONE, 1200, "SUCCESS",
			"FINAL", null, new BigDecimal("0.50"), "USD");
		insertQualityResult(first, "PASS",
			new BigDecimal("0.9"), new BigDecimal("0.8"), new BigDecimal("0.7"));
		UUID second = insertAttempt(dayOneJob, 2, "provider-a", "m1", dayOneStep, DAY_ONE, 900, "SUCCESS",
			"FINAL", null, new BigDecimal("0.70"), "USD");
		insertQualityResult(second, "PASS",
			new BigDecimal("0.6"), new BigDecimal("0.9"), new BigDecimal("0.9"));
		insertAttempt(dayOneJob, 3, "provider-a", "m1", dayOneStep, DAY_ONE, 500, "FAILED",
			"FINAL", null, new BigDecimal("0.25"), "USD");

		UUID dayTwoJob = insertJob(userId);
		UUID dayTwoStep = insertRenderStep(dayTwoJob, 1);
		insertRenderStepItem(dayTwoStep, 1, vaultFoot, "footwear");
		UUID fourth = insertAttempt(dayTwoJob, 1, "provider-a", "m1", dayTwoStep, DAY_TWO, 2000, "SUCCESS",
			"FINAL", null, new BigDecimal("1.20"), "USD");
		insertQualityResult(fourth, "PASS",
			new BigDecimal("0.75"), new BigDecimal("0.85"), new BigDecimal("0.95"));
		UUID fifth = insertAttempt(dayTwoJob, 2, "provider-a", "m1", dayTwoStep, DAY_TWO, 800, "SUCCESS",
			"FINAL", null, new BigDecimal("0.40"), "USD");
		insertQualityResult(fifth, "FAIL", null, null, null);
	}

	private long countDailyRows() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM generation_provider_performance_daily", Long.class);
	}

	private long sumSampleCounts() {
		return jdbc.queryForObject(
			"SELECT COALESCE(SUM(sample_count), 0) FROM generation_provider_performance_daily", Long.class);
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return userId;
	}

	private UUID insertVaultItem(UUID userId, String category) {
		UUID vaultItemId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO vault_item (id, user_id, storage_key, category, created_at) VALUES (?, ?, ?, ?, ?)",
			vaultItemId,
			userId,
			"vault/" + vaultItemId + ".jpg",
			category,
			Timestamp.from(NOW));
		return vaultItemId;
	}

	private UUID insertJob(UUID userId) {
		UUID jobId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO generation_job (id, user_id, status, created_at) VALUES (?, ?, 'QUEUED', ?)",
			jobId,
			userId,
			Timestamp.from(NOW));
		return jobId;
	}

	private void insertJobProduct(UUID jobId, UUID vaultItemId) {
		jdbc.update(
			"INSERT INTO generation_job_product (generation_job_id, vault_item_id) VALUES (?, ?)",
			jobId,
			vaultItemId);
	}

	private UUID insertRenderStep(UUID jobId, int stepIndex) {
		UUID stepId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO generation_render_step (id, generation_job_id, render_plan_version, step_index, created_at) "
				+ "VALUES (?, ?, 1, ?, ?)",
			stepId,
			jobId,
			stepIndex,
			Timestamp.from(NOW));
		return stepId;
	}

	private void insertRenderStepItem(UUID stepId, int ordinal, UUID vaultItemId, String category) {
		jdbc.update(
			"INSERT INTO generation_render_step_item (id, render_step_id, item_ordinal, vault_item_id, category, "
				+ "created_at) VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(),
			stepId,
			ordinal,
			vaultItemId,
			category,
			Timestamp.from(NOW));
	}

	private UUID insertAttempt(UUID jobId, int attemptNumber, String provider, String model, UUID renderStepId,
			Instant completedAt, Integer latencyMs, String technicalOutcome, String costStatus,
			BigDecimal estimatedCost, BigDecimal actualCost, String currency) {
		UUID attemptId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO generation_attempt (id, generation_job_id, provider, model, attempt_number, started_at, "
				+ "completed_at, latency_ms, estimated_cost, currency, technical_outcome, submission_key, "
				+ "actual_cost, cost_status, render_step_id) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			attemptId,
			jobId,
			provider,
			model,
			attemptNumber,
			Timestamp.from(completedAt.minusSeconds(60)),
			Timestamp.from(completedAt),
			latencyMs,
			estimatedCost,
			currency,
			technicalOutcome,
			UUID.randomUUID(),
			actualCost,
			costStatus,
			renderStepId);
		return attemptId;
	}

	private void insertQualityResult(UUID attemptId, String decision, BigDecimal identityPreservation,
			BigDecimal garmentFidelity, BigDecimal colorFidelity) {
		jdbc.update(
			"INSERT INTO quality_result (id, generation_attempt_id, decision, identity_preservation, "
				+ "garment_fidelity, color_fidelity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(),
			attemptId,
			decision,
			identityPreservation,
			garmentFidelity,
			colorFidelity,
			Timestamp.from(NOW));
	}
}
