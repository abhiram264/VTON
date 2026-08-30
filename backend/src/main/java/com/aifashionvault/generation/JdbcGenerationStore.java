package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public class JdbcGenerationStore implements GenerationStore {

	private static final String JOB_COLUMNS =
		"id, user_id, studio_session_id, status, requested_mode, client_request_id, "
			+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at, "
			+ "status_version, request_fingerprint, avatar_revision, updated_at, terminal_reason, "
			+ "current_step, total_steps, render_plan_version, orchestration_deadline_at, "
			+ "retry_not_before_at, retry_policy_snapshot, retry_of_job_id";

	private static final String ATTEMPT_COLUMNS =
		"id, generation_job_id, provider, model, provider_job_id, attempt_number, "
			+ "started_at, completed_at, latency_ms, credits_used, estimated_cost, currency, "
			+ "failure_code, technical_outcome, submission_key, submit_owned_at, "
			+ "submission_indeterminate, temp_storage_key, provider_output_ref, render_step_id, "
			+ "transmission_count, provider_accepted_at, execution_deadline_at, "
			+ "submission_recovery_deadline_at, quality_policy_version, pricing_catalog_version, "
			+ "estimated_units, unit_price, estimated_cost_ceiling, actual_cost, cost_status, "
			+ "actual_billed_units, pricing_observed_at, billing_unit, "
			+ "error_mapping_contract_version, evaluator_retry_count";

	private static final String ACTIVE_STATUSES_SQL =
		"'" + GenerationJob.QUEUED + "','" + GenerationJob.PREFLIGHT + "','" + GenerationJob.SUBMITTED
			+ "','" + GenerationJob.GENERATING + "','" + GenerationJob.QUALITY_CHECK + "','"
			+ GenerationJob.RETRYING + "'";
	private static final int MAX_LOGICAL_ATTEMPTS_PER_STEP = 3;


	private static final String ACQUIRE_SUBMIT_OWNERSHIP_SQL =
		"UPDATE generation_attempt a SET submit_owned_at = ? FROM generation_job j "
			+ "WHERE a.id = ? AND a.submit_owned_at IS NULL AND a.generation_job_id = j.id "
			+ "AND j.cancelled_at IS NULL AND j.subject_deleted_at IS NULL AND j.completed_at IS NULL";

	private static final String COMPLETE_SUCCESS_IF_ACTIVE_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.COMPLETED + "', completed_at = ?, "
			+ "retry_not_before_at = NULL, updated_at = ?, status_version = status_version + 1 "
			+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
			+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL";

	private static final String COMPLETE_FAILURE_IF_ACTIVE_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', terminal_reason = ?, "
			+ "retry_not_before_at = NULL, completed_at = ?, updated_at = ?, status_version = status_version + 1 "
			+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
			+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL";

	private static final String CANCEL_IF_ACTIVE_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', "
			+ "terminal_reason = '" + GenerationJob.CANCELLED_BY_USER + "', "
			+ "retry_not_before_at = NULL, cancelled_at = ?, completed_at = ?, updated_at = ?, status_version = status_version + 1 "
			+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ")";

	private static final String MARK_SUBJECT_DELETED_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', "
			+ "terminal_reason = CASE WHEN terminal_reason IS NULL THEN '"
			+ GenerationJob.SUBJECT_DELETED + "' ELSE terminal_reason END, "
			+ "retry_not_before_at = NULL, subject_deleted_at = ?, completed_at = COALESCE(completed_at, ?), updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE id = ? AND subject_deleted_at IS NULL AND status <> '" + GenerationJob.COMPLETED + "'";

	private static final String RECORD_PROVIDER_EXECUTION_IF_ACTIVE_SQL =
		"UPDATE generation_attempt a SET provider_job_id = ?, "
			+ "provider_accepted_at = CASE WHEN CAST(? AS text) IS NULL THEN provider_accepted_at "
			+ "ELSE COALESCE(provider_accepted_at, ?) END "
			+ "FROM generation_job j "
			+ "WHERE a.id = ? AND a.generation_job_id = j.id AND j.completed_at IS NULL "
			+ "AND j.cancelled_at IS NULL AND j.subject_deleted_at IS NULL "
			+ "AND (a.provider_job_id IS NULL OR a.provider_job_id = ?)";


	private static final String MARK_SUBMITTED_SQL =
		"UPDATE generation_job j SET status = '" + GenerationJob.SUBMITTED + "', updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE j.id = ? AND j.status IN ('" + GenerationJob.QUEUED + "','"
			+ GenerationJob.PREFLIGHT + "','" + GenerationJob.RETRYING + "') "
			+ "AND j.completed_at IS NULL AND j.cancelled_at IS NULL AND j.subject_deleted_at IS NULL "
			+ "AND EXISTS (SELECT 1 FROM generation_attempt a WHERE a.generation_job_id = j.id "
			+ "AND a.provider_job_id IS NOT NULL AND a.completed_at IS NULL)";


	private static final String MARK_GENERATING_SQL =
		"UPDATE generation_job j SET status = '" + GenerationJob.GENERATING + "', updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE j.id = ? AND j.status IN ('" + GenerationJob.SUBMITTED + "','" + GenerationJob.GENERATING + "') "
			+ "AND EXISTS (SELECT 1 FROM generation_attempt a WHERE a.generation_job_id = j.id "
			+ "AND a.provider_job_id IS NOT NULL AND a.completed_at IS NULL)";

	private static final String MARK_PREFLIGHT_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.PREFLIGHT + "', updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE id = ? AND status = '" + GenerationJob.QUEUED + "' "
			+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL";

	private static final String MARK_QUALITY_CHECK_IF_ACTIVE_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.QUALITY_CHECK + "', updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE id = ? AND status = '" + GenerationJob.GENERATING + "' "
			+ "AND status_version = ? "
			+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL";

	private static final String FAIL_IF_DEADLINE_EXCEEDED_SQL =
		"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', "
			+ "terminal_reason = '" + TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name() + "', "
			+ "retry_not_before_at = NULL, completed_at = ?, updated_at = ?, status_version = status_version + 1 "
			+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
			+ "AND completed_at IS NULL AND orchestration_deadline_at <= ?";

	private static final RowMapper<GenerationAttempt> ATTEMPT_MAPPER = (rs, rowNum) -> {
		UUID submissionKey = rs.getObject("submission_key", UUID.class);
		String tempStorageKey = rs.getString("temp_storage_key");
		String providerOutputRef = rs.getString("provider_output_ref");
		return new GenerationAttempt(
			new GenerationAttemptId(rs.getObject("id", UUID.class)),
			new GenerationJobId(rs.getObject("generation_job_id", UUID.class)),
			rs.getString("provider"),
			rs.getString("model"),
			rs.getString("provider_job_id"),
			rs.getInt("attempt_number"),
			rs.getTimestamp("started_at").toInstant(),
			toInstant(rs.getTimestamp("completed_at")),
			rs.getObject("latency_ms", Integer.class),
			rs.getBigDecimal("credits_used"),
			rs.getBigDecimal("estimated_cost"),
			rs.getString("currency"),
			rs.getString("failure_code"),
			rs.getString("technical_outcome"),
			submissionKey == null ? null : new SubmissionKey(submissionKey),
			toInstant(rs.getTimestamp("submit_owned_at")),
			rs.getBoolean("submission_indeterminate"),
			tempStorageKey == null ? null : new StorageKey(tempStorageKey),
			providerOutputRef == null ? null : new ProviderOutputRef(providerOutputRef),
			rs.getObject("render_step_id", UUID.class),
			rs.getInt("transmission_count"),
			toInstant(rs.getTimestamp("provider_accepted_at")),
			toInstant(rs.getTimestamp("execution_deadline_at")),
			toInstant(rs.getTimestamp("submission_recovery_deadline_at")),
			rs.getString("quality_policy_version"),
			rs.getString("pricing_catalog_version"),
			rs.getBigDecimal("estimated_units"),
			rs.getBigDecimal("unit_price"),
			rs.getBigDecimal("estimated_cost_ceiling"),
			rs.getBigDecimal("actual_cost"),
			rs.getString("cost_status"),
			rs.getBigDecimal("actual_billed_units"),
			toInstant(rs.getTimestamp("pricing_observed_at")),
			rs.getString("billing_unit"),
			rs.getString("error_mapping_contract_version"),
			rs.getInt("evaluator_retry_count"));
	};

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final AdmissionPolicy admissionPolicy;

	public JdbcGenerationStore(DataSource dataSource) {
		this(dataSource, Clock.systemUTC(), AdmissionPolicy.DEFAULT);
	}

	public JdbcGenerationStore(DataSource dataSource, Clock clock) {
		this(dataSource, clock, AdmissionPolicy.DEFAULT);
	}

	public JdbcGenerationStore(DataSource dataSource, Clock clock, AdmissionPolicy admissionPolicy) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		if (admissionPolicy == null) {
			throw new IllegalArgumentException("admissionPolicy is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = clock;
		this.admissionPolicy = admissionPolicy;
	}

	@Override
	public void insertJob(GenerationJob job) {
		if (job == null) {
			throw new IllegalArgumentException("job is required");
		}
		transactions.execute(status -> {
			insertJobRow(job);
			insertProducts(job);
			return null;
		});
	}

	@Override
	public CreateAcceptedResult createAccepted(GenerationJob job) {
		if (job == null) {
			throw new IllegalArgumentException("job is required");
		}
		if (job.clientRequestId() == null) {
			throw new IllegalArgumentException("clientRequestId is required");
		}
		if (job.requestFingerprint() == null || job.requestFingerprint().isBlank()) {
			throw new IllegalArgumentException("requestFingerprint is required");
		}
		if (!GenerationJob.QUEUED.equals(job.status())) {
			throw new IllegalArgumentException("createAccepted requires QUEUED status");
		}
		return transactions.execute(status -> {
			jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", job.userId().value());
			Optional<GenerationJob> existing = findJobByUserAndClientRequestId(
				job.userId(),
				job.clientRequestId());
			if (existing.isPresent()) {
				return replayOrConflict(existing.get(), job.requestFingerprint());
			}
			if (!admissionPolicy.allowsProductCount(job.productVaultItemIds().size())) {
				return new CreateAcceptedResult.QuotaRejected("PRODUCT_LIMIT");
			}
			Instant now = clock.instant();
			Integer accepted24h = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_quota_reservation "
					+ "WHERE user_id = ? AND reserved_at > ? AND released_at IS NULL",
				Integer.class,
				job.userId().value(),
				Timestamp.from(now.minus(Duration.ofHours(24))));
			if (accepted24h != null && accepted24h >= admissionPolicy.acceptedJobsPer24Hours()) {
				return new CreateAcceptedResult.QuotaRejected("ROLLING_LIMIT");
			}
			Integer active = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_job WHERE user_id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ")",
				Integer.class,
				job.userId().value());
			if (active != null && active >= admissionPolicy.maxActiveJobs()) {
				return new CreateAcceptedResult.QuotaRejected("ACTIVE_LIMIT");
			}
			try {
				RenderPlan plan = RenderPlan.oneProductPerStep(loadPlannedProducts(job));
				GenerationJob accepted = acceptedJob(job, plan, now);
				insertJobRow(accepted);
				insertProducts(accepted);
				insertRenderPlan(accepted, plan, now);
				jdbc.update(
					"INSERT INTO generation_quota_reservation "
						+ "(id, user_id, generation_job_id, reserved_at) VALUES (?, ?, ?, ?)",
					UUID.randomUUID(),
					accepted.userId().value(),
					accepted.id().value(),
					Timestamp.from(now));
				jdbc.update(
					"INSERT INTO generation_work_outbox "
						+ "(id, generation_job_id, not_before, created_at, publish_state) "
						+ "VALUES (?, ?, ?, ?, ?)",
					UUID.randomUUID(),
					accepted.id().value(),
					Timestamp.from(now),
					Timestamp.from(now),
					WorkOutbox.PENDING);
				return new CreateAcceptedResult.Created(accepted);
			}
			catch (DataAccessException ex) {
				if (!isUniqueViolation(ex)) {
					throw ex;
				}
				GenerationJob winner = findJobByUserAndClientRequestId(job.userId(), job.clientRequestId())
					.orElseThrow(() -> ex);
				return replayOrConflict(winner, job.requestFingerprint());
			}
		});
	}

	@Override
	public CreateSingleItemResult createSingleItem(
		CreateSingleItemGenerationCommand command,
		FrozenSingleItemIntent intent,
		GenerationJob job
	) {
		if (command == null || intent == null || job == null) {
			throw new IllegalArgumentException("command, intent, and job are required");
		}
		if (!command.ownerId().equals(intent.ownerId()) || !command.ownerId().equals(job.userId())
			|| !intent.generationId().equals(job.id())) {
			throw new IllegalArgumentException("single-item identities must match");
		}
		if (job.clientRequestId() == null || !command.idempotencyKey().equals(job.clientRequestId().value())) {
			throw new IllegalArgumentException("job clientRequestId must match idempotencyKey");
		}
		if (!intent.avatarImageId().equals(job.avatarImageId())) {
			throw new IllegalArgumentException("job avatarImageId must match frozen intent");
		}
		if (!job.productVaultItemIds().isEmpty() || !GenerationJob.QUEUED.equals(job.status())) {
			throw new IllegalArgumentException("single-item create requires a queued job without Vault products");
		}
		if (job.requestFingerprint() == null || job.requestFingerprint().isBlank()) {
			throw new IllegalArgumentException("requestFingerprint is required");
		}
		return transactions.execute(status -> {
			jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", command.ownerId().value());
			Optional<GenerationJob> existing = findJobByUserAndClientRequestId(job.userId(), job.clientRequestId());
			if (existing.isPresent()) {
				return replaySingleItem(existing.get(), job.requestFingerprint());
			}
			jdbc.query(
				"SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
				rs -> null,
				command.productUploadId().value().toString());
			Integer claimed = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_job WHERE product_source_upload_id = ?",
				Integer.class,
				command.productUploadId().value());
			if (claimed != null && claimed > 0) {
				return new CreateSingleItemResult.ProductUploadAlreadyClaimed();
			}
			insertJobRow(job);
			jdbc.update(
				"UPDATE generation_job SET product_source_upload_id = ?, product_media_id = ?, "
					+ "render_step_ordinal = 1, render_step_count = 1 WHERE id = ?",
				command.productUploadId().value(),
				intent.productMediaId().value(),
				job.id().value());
			Instant now = clock.instant();
			jdbc.update(
				"INSERT INTO generation_work_outbox "
					+ "(id, generation_job_id, not_before, created_at, publish_state) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), job.id().value(), Timestamp.from(now), Timestamp.from(now), WorkOutbox.PENDING);
			return new CreateSingleItemResult.Created(job);
		});
	}

	@Override
	public Optional<FrozenSingleItemIntent> findFrozenSingleItemIntent(GenerationJobId generationId) {
		if (generationId == null) throw new IllegalArgumentException("generationId is required");
		return one(jdbc.query(
			"SELECT id, user_id, avatar_image_id, product_media_id, render_step_ordinal, render_step_count "
				+ "FROM generation_job WHERE id = ? AND product_media_id IS NOT NULL",
			(rs, rowNum) -> new FrozenSingleItemIntent(
				new GenerationJobId(rs.getObject("id", UUID.class)),
				new UserId(rs.getObject("user_id", UUID.class)),
				new AvatarImageId(rs.getObject("avatar_image_id", UUID.class)),
				new com.aifashionvault.shared.MediaAssetId(rs.getObject("product_media_id", UUID.class)),
				rs.getInt("render_step_ordinal"),
				rs.getInt("render_step_count")),
			generationId.value()));
	}

	@Override
	public Optional<UploadId> findProductSourceUploadId(GenerationJobId generationId, UserId ownerId) {
		if (generationId == null || ownerId == null) {
			throw new IllegalArgumentException("generationId and ownerId are required");
		}
		return one(jdbc.query(
			"SELECT product_source_upload_id FROM generation_job "
				+ "WHERE id = ? AND user_id = ? AND product_source_upload_id IS NOT NULL",
			(rs, rowNum) -> new UploadId(rs.getObject("product_source_upload_id", UUID.class)),
			generationId.value(),
			ownerId.value()));
	}

	@Override
	public Optional<GenerationJob> findJob(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		List<GenerationJob> jobs = jdbc.query(
			"SELECT " + JOB_COLUMNS + " FROM generation_job WHERE id = ?",
			(rs, rowNum) -> mapJob(rs, loadProductIds(jobId)),
			jobId.value());
		return one(jobs);
	}

	@Override
	public Optional<GenerationJob> findJobForUser(GenerationJobId jobId, UserId userId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		List<GenerationJob> jobs = jdbc.query(
			"SELECT " + JOB_COLUMNS + " FROM generation_job WHERE id = ? AND user_id = ?",
			(rs, rowNum) -> mapJob(rs, loadProductIds(jobId)),
			jobId.value(),
			userId.value());
		return one(jobs);
	}

	@Override
	public Optional<RenderPlan> findRenderPlan(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		List<RenderStep> steps = loadRenderSteps(jobId);
		if (steps.isEmpty()) {
			return Optional.empty();
		}
		Integer version = jdbc.queryForObject(
			"SELECT render_plan_version FROM generation_job WHERE id = ?",
			Integer.class,
			jobId.value());
		return Optional.of(new RenderPlan(version == null ? 1 : version, steps));
	}

	@Override
	public List<WorkOutbox> listWorkOutbox(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.query(
			"SELECT id, generation_job_id, not_before, created_at, publish_state, attempt_count, "
				+ "last_error_category, sent_at, claimed_at, claimed_until "
				+ "FROM generation_work_outbox WHERE generation_job_id = ? ORDER BY created_at, id",
			(rs, rowNum) -> new WorkOutbox(
				rs.getObject("id", UUID.class),
				new GenerationJobId(rs.getObject("generation_job_id", UUID.class)),
				rs.getTimestamp("not_before").toInstant(),
				rs.getTimestamp("created_at").toInstant(),
				rs.getString("publish_state"),
				rs.getInt("attempt_count"),
				rs.getString("last_error_category"),
				toInstant(rs.getTimestamp("sent_at")),
				toInstant(rs.getTimestamp("claimed_at")),
				toInstant(rs.getTimestamp("claimed_until"))),
			jobId.value());
	}

	@Override
	public Optional<QuotaReservation> findQuotaReservation(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return one(jdbc.query(
			"SELECT id, user_id, generation_job_id, reserved_at, released_at "
				+ "FROM generation_quota_reservation WHERE generation_job_id = ?",
			(rs, rowNum) -> new QuotaReservation(
				rs.getObject("id", UUID.class),
				new UserId(rs.getObject("user_id", UUID.class)),
				new GenerationJobId(rs.getObject("generation_job_id", UUID.class)),
				rs.getTimestamp("reserved_at").toInstant(),
				toInstant(rs.getTimestamp("released_at"))),
			jobId.value()));
	}

	@Override
	public void insertAttempt(GenerationAttempt attempt) {
		if (attempt == null) {
			throw new IllegalArgumentException("attempt is required");
		}
		jdbc.update(
			"INSERT INTO generation_attempt "
				+ "(id, generation_job_id, provider, model, provider_job_id, attempt_number, "
				+ "started_at, completed_at, latency_ms, credits_used, estimated_cost, currency, "
				+ "failure_code, technical_outcome, submission_key, submit_owned_at, "
				+ "submission_indeterminate, temp_storage_key, provider_output_ref, render_step_id, "
				+ "transmission_count, provider_accepted_at, execution_deadline_at, "
				+ "submission_recovery_deadline_at, quality_policy_version, pricing_catalog_version, "
				+ "estimated_units, unit_price, estimated_cost_ceiling, actual_cost, cost_status, "
				+ "actual_billed_units, pricing_observed_at, billing_unit, "
				+ "error_mapping_contract_version, evaluator_retry_count) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
				+ "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
				+ "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
				+ "?, ?, ?, ?, ?, ?)",
			attempt.id().value(),
			attempt.generationJobId().value(),
			attempt.provider(),
			attempt.model(),
			attempt.providerJobId(),
			attempt.attemptNumber(),
			Timestamp.from(attempt.startedAt()),
			timestampOrNull(attempt.completedAt()),
			attempt.latencyMs(),
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.submissionKey() == null ? null : attempt.submissionKey().value(),
			timestampOrNull(attempt.submitOwnedAt()),
			attempt.submissionIndeterminate(),
			attempt.tempStorageKey() == null ? null : attempt.tempStorageKey().value(),
			attempt.providerOutputRef() == null ? null : attempt.providerOutputRef().value(),
			attempt.renderStepId(),
			attempt.transmissionCount(),
			timestampOrNull(attempt.providerAcceptedAt()),
			timestampOrNull(attempt.executionDeadlineAt()),
			timestampOrNull(attempt.submissionRecoveryDeadlineAt()),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus(),
			attempt.actualBilledUnits(),
			timestampOrNull(attempt.pricingObservedAt()),
			attempt.billingUnit(),
			attempt.errorMappingContractVersion(),
			attempt.evaluatorRetryCount());
	}

	@Override
	public Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		return one(jdbc.query(
			"SELECT " + ATTEMPT_COLUMNS + " FROM generation_attempt WHERE id = ?",
			ATTEMPT_MAPPER,
			attemptId.value()));
	}

	@Override
	public List<GenerationAttempt> listAttempts(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.query(
			"SELECT " + ATTEMPT_COLUMNS + " FROM generation_attempt WHERE generation_job_id = ? "
				+ "ORDER BY attempt_number, id",
			ATTEMPT_MAPPER,
			jobId.value());
	}

	@Override
	public boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (ownedAt == null) {
			throw new IllegalArgumentException("ownedAt is required");
		}
		return jdbc.update(ACQUIRE_SUBMIT_OWNERSHIP_SQL, Timestamp.from(ownedAt), attemptId.value()) == 1;
	}

	@Override
	public void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		String providerJobValue = providerJobId == null ? null : providerJobId.value();
		jdbc.update(
			RECORD_PROVIDER_EXECUTION_IF_ACTIVE_SQL,
			providerJobValue,
			providerJobValue,
			Timestamp.from(clock.instant()),
			attemptId.value(),
			providerJobValue);
	}

	@Override
	public void markSubmissionIndeterminate(GenerationAttemptId attemptId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		jdbc.update(
			"UPDATE generation_attempt SET submission_indeterminate = TRUE WHERE id = ?",
			attemptId.value());
	}

	@Override
	public void saveQualityResult(QualityResult qualityResult) {
		if (qualityResult == null) {
			throw new IllegalArgumentException("qualityResult is required");
		}
		transactions.execute(status -> {
			int inserted = jdbc.update(
				"INSERT INTO quality_result "
					+ "(id, generation_attempt_id, decision, identity_preservation, garment_fidelity, "
					+ "color_fidelity, anatomy_valid, artifact_detected, created_at, "
					+ "quality_policy_version, reason_code) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
					+ "ON CONFLICT (generation_attempt_id) DO NOTHING",
				qualityResult.id().value(),
				qualityResult.generationAttemptId().value(),
				qualityResult.decision().name(),
				qualityResult.identityPreservation(),
				qualityResult.garmentFidelity(),
				qualityResult.colorFidelity(),
				qualityResult.anatomyValid(),
				qualityResult.artifactDetected(),
				Timestamp.from(qualityResult.createdAt()),
				qualityResult.qualityPolicyVersion(),
				qualityResult.reasonCode());
			if (inserted == 0) {
				Map<String, Object> existing = jdbc.queryForMap(
					"SELECT decision, identity_preservation, garment_fidelity, color_fidelity, "
						+ "anatomy_valid, artifact_detected, quality_policy_version, reason_code "
						+ "FROM quality_result WHERE generation_attempt_id = ? FOR UPDATE",
					qualityResult.generationAttemptId().value());
				if (!qualityResultEquivalent(existing, qualityResult)) {
					throw new IllegalStateException(
						"quality result conflict for generation attempt "
							+ qualityResult.generationAttemptId().value());
				}
			}
			return null;
		});
	}

	@Override
	public Optional<QualityResult> findQualityResult(GenerationAttemptId attemptId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		return one(jdbc.query(
			"SELECT id, decision, identity_preservation, garment_fidelity, color_fidelity, "
				+ "anatomy_valid, artifact_detected, created_at, quality_policy_version, reason_code "
				+ "FROM quality_result WHERE generation_attempt_id = ?",
			(rs, rowNum) -> new QualityResult(
				new QualityResultId(rs.getObject("id", UUID.class)),
				attemptId,
				QualityDecision.valueOf(rs.getString("decision")),
				rs.getBigDecimal("identity_preservation"),
				rs.getBigDecimal("garment_fidelity"),
				rs.getBigDecimal("color_fidelity"),
				rs.getObject("anatomy_valid", Boolean.class),
				rs.getObject("artifact_detected", Boolean.class),
				rs.getTimestamp("created_at").toInstant(),
				rs.getString("quality_policy_version"),
				rs.getString("reason_code")),
			attemptId.value()));
	}

	@Override
	public void markCancelled(GenerationJobId jobId, Instant cancelledAt) {
		cancelIfActive(jobId, cancelledAt);
	}

	@Override
	public boolean cancelIfActive(GenerationJobId jobId, Instant cancelledAt) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (cancelledAt == null) {
			throw new IllegalArgumentException("cancelledAt is required");
		}
		return jdbc.update(
			CANCEL_IF_ACTIVE_SQL,
			Timestamp.from(cancelledAt),
			Timestamp.from(cancelledAt),
			Timestamp.from(cancelledAt),
			jobId.value()) == 1;
	}

	@Override
	public void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (subjectDeletedAt == null) {
			throw new IllegalArgumentException("subjectDeletedAt is required");
		}
		jdbc.update(
			MARK_SUBJECT_DELETED_SQL,
			Timestamp.from(subjectDeletedAt),
			Timestamp.from(subjectDeletedAt),
			Timestamp.from(subjectDeletedAt),
			jobId.value());
	}

	@Override
	public void completeAttempt(GenerationAttempt attempt) {
		if (attempt == null) {
			throw new IllegalArgumentException("attempt is required");
		}
		jdbc.update(
			"UPDATE generation_attempt SET "
				+ "completed_at = COALESCE(completed_at, ?), latency_ms = COALESCE(latency_ms, ?), "
				+ "credits_used = COALESCE(?, credits_used), "
				+ "estimated_cost = COALESCE(estimated_cost, ?), currency = COALESCE(currency, ?), "
				+ "failure_code = COALESCE(failure_code, ?), technical_outcome = COALESCE(technical_outcome, ?), "
				+ "temp_storage_key = ?, provider_output_ref = ?, "
				+ "submit_owned_at = COALESCE(submit_owned_at, ?), "
				+ "submission_indeterminate = submission_indeterminate OR ?, "
				+ "transmission_count = GREATEST(transmission_count, ?), "
				+ "provider_accepted_at = COALESCE(provider_accepted_at, ?), "
				+ "execution_deadline_at = COALESCE(execution_deadline_at, ?), "
				+ "submission_recovery_deadline_at = COALESCE(submission_recovery_deadline_at, ?), "
				+ "quality_policy_version = COALESCE(quality_policy_version, ?), "
				+ "pricing_catalog_version = COALESCE(pricing_catalog_version, ?), "
				+ "estimated_units = COALESCE(estimated_units, ?), unit_price = COALESCE(unit_price, ?), "
				+ "estimated_cost_ceiling = COALESCE(estimated_cost_ceiling, ?), "
				+ "actual_cost = COALESCE(?, actual_cost), cost_status = COALESCE(?, cost_status), "
				+ "actual_billed_units = COALESCE(?, actual_billed_units), "
				+ "pricing_observed_at = COALESCE(pricing_observed_at, ?), "
				+ "billing_unit = COALESCE(billing_unit, ?) "
				+ "WHERE id = ?",
			timestampOrNull(attempt.completedAt()),
			attempt.latencyMs(),
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.tempStorageKey() == null ? null : attempt.tempStorageKey().value(),
			attempt.providerOutputRef() == null ? null : attempt.providerOutputRef().value(),
			timestampOrNull(attempt.submitOwnedAt()),
			attempt.submissionIndeterminate(),
			attempt.transmissionCount(),
			timestampOrNull(attempt.providerAcceptedAt()),
			timestampOrNull(attempt.executionDeadlineAt()),
			timestampOrNull(attempt.submissionRecoveryDeadlineAt()),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus(),
			attempt.actualBilledUnits(),
			timestampOrNull(attempt.pricingObservedAt()),
			attempt.billingUnit(),
			attempt.id().value());
	}

	@Override
	public void markJobCompleted(GenerationJobId jobId, Instant completedAt) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (completedAt == null) {
			throw new IllegalArgumentException("completedAt is required");
		}
		jdbc.update(
			"UPDATE generation_job SET status = '" + GenerationJob.COMPLETED + "', completed_at = ?, "
				+ "retry_not_before_at = NULL, updated_at = ?, status_version = status_version + 1 "
				+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ")",
			Timestamp.from(completedAt),
			Timestamp.from(completedAt),
			jobId.value());
	}

	@Override
	public void completeTerminal(GenerationAttempt attempt, Instant jobCompletedAt) {
		completeTerminalIfActive(attempt, jobCompletedAt);
	}

	@Override
	public boolean completeTerminalIfActive(GenerationAttempt attempt, Instant jobCompletedAt) {
		if (attempt == null) {
			throw new IllegalArgumentException("attempt is required");
		}
		if (jobCompletedAt == null) {
			throw new IllegalArgumentException("jobCompletedAt is required");
		}
		Boolean won = transactions.execute(status -> {
			String failureReason = terminalFailureReason(attempt);
			int updated;
			if (failureReason != null) {
				updated = jdbc.update(
					COMPLETE_FAILURE_IF_ACTIVE_SQL,
					failureReason,
					Timestamp.from(jobCompletedAt),
					Timestamp.from(jobCompletedAt),
					attempt.generationJobId().value());
			}
			else {
				updated = jdbc.update(
					COMPLETE_SUCCESS_IF_ACTIVE_SQL,
					Timestamp.from(jobCompletedAt),
					Timestamp.from(jobCompletedAt),
					attempt.generationJobId().value());
			}
			if (updated == 0) {
				return false;
			}
			completeAttempt(attempt);
			return true;
		});
		return Boolean.TRUE.equals(won);
	}

	@Override
	public void scheduleRetry(GenerationAttempt completedAttempt, GenerationAttempt nextAttempt) {
		if (completedAttempt == null) {
			throw new IllegalArgumentException("completedAttempt is required");
		}
		if (nextAttempt == null) {
			throw new IllegalArgumentException("nextAttempt is required");
		}
		transactions.execute(status -> {
			completeAttempt(completedAttempt);
			insertAttempt(nextAttempt);
			return null;
		});
	}
	@Override
	public boolean scheduleRetryIfActive(GenerationAttempt completedAttempt, GenerationAttempt nextAttempt) {
		if (completedAttempt == null) {
			throw new IllegalArgumentException("completedAttempt is required");
		}
		if (nextAttempt == null) {
			throw new IllegalArgumentException("nextAttempt is required");
		}
		if (!completedAttempt.generationJobId().equals(nextAttempt.generationJobId())) {
			throw new IllegalArgumentException("attempts must belong to the same generation job");
		}
		Boolean scheduled = transactions.execute(status -> {
			List<Integer> active = jdbc.query(
				"SELECT 1 FROM generation_job WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
					+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
					+ "FOR UPDATE",
				(rs, rowNum) -> rs.getInt(1),
				completedAttempt.generationJobId().value());
			if (active.isEmpty()) {
				return false;
			}
			completeAttempt(completedAttempt);
			insertAttempt(nextAttempt);
			return true;
		});
		return Boolean.TRUE.equals(scheduled);
	}
	@Override
	public boolean scheduleRetryIfActive(GenerationAttempt completedAttempt, Instant retryNotBeforeAt) {
		if (completedAttempt == null) {
			throw new IllegalArgumentException("completedAttempt is required");
		}
		if (completedAttempt.completedAt() == null) {
			throw new IllegalArgumentException("completedAttempt must be completed");
		}
		if (retryNotBeforeAt == null) {
			throw new IllegalArgumentException("retryNotBeforeAt is required");
		}
		Boolean scheduled = transactions.execute(status -> {
			List<Integer> active = jdbc.query(
				"SELECT 1 FROM generation_job WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
					+ "AND status <> '" + GenerationJob.RETRYING + "' "
					+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
					+ "FOR UPDATE",
				(rs, rowNum) -> rs.getInt(1),
				completedAttempt.generationJobId().value());
			if (active.isEmpty()) {
				return false;
			}
			List<Integer> predecessor = jdbc.query(
				"SELECT 1 FROM generation_attempt "
					+ "WHERE id = ? AND generation_job_id = ? FOR UPDATE",
				(rs, rowNum) -> rs.getInt(1),
				completedAttempt.id().value(),
				completedAttempt.generationJobId().value());
			if (predecessor.isEmpty()) {
				return false;
			}
			int updated = jdbc.update(
				"UPDATE generation_job SET status = '" + GenerationJob.RETRYING + "', "
					+ "retry_not_before_at = ?, updated_at = ?, status_version = status_version + 1 "
					+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
					+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL",
				Timestamp.from(retryNotBeforeAt),
				Timestamp.from(clock.instant()),
				completedAttempt.generationJobId().value());
			if (updated != 1) {
				return false;
			}
			completeAttempt(completedAttempt);
			return true;
		});
		return Boolean.TRUE.equals(scheduled);
	}

	@Override
	public boolean scheduleRetryIfActive(
		GenerationAttempt completedAttempt,
		int expectedStatusVersion,
		Instant retryNotBeforeAt
	) {
		if (completedAttempt == null) {
			throw new IllegalArgumentException("completedAttempt is required");
		}
		if (completedAttempt.completedAt() == null) {
			throw new IllegalArgumentException("completedAttempt must be completed");
		}
		if (retryNotBeforeAt == null) {
			throw new IllegalArgumentException("retryNotBeforeAt is required");
		}
		Boolean scheduled = transactions.execute(status -> {
			List<Map<String, Object>> lockedJob = jdbc.queryForList(
				"SELECT status, status_version, completed_at, cancelled_at, subject_deleted_at, "
					+ "orchestration_deadline_at FROM generation_job WHERE id = ? FOR UPDATE",
				completedAttempt.generationJobId().value());
			if (lockedJob.isEmpty()) {
				return false;
			}
			Map<String, Object> job = lockedJob.getFirst();
			String persistedStatus = (String) job.get("status");
			int persistedStatusVersion = ((Number) job.get("status_version")).intValue();
			if (!GenerationJob.ACTIVE_STATUSES.contains(persistedStatus)
				|| GenerationJob.RETRYING.equals(persistedStatus)
				|| job.get("completed_at") != null
				|| job.get("cancelled_at") != null
				|| job.get("subject_deleted_at") != null
				|| persistedStatusVersion != expectedStatusVersion) {
				return false;
			}
			Timestamp orchestrationDeadlineAt = (Timestamp) job.get("orchestration_deadline_at");
			if (orchestrationDeadlineAt == null || !retryNotBeforeAt.isBefore(orchestrationDeadlineAt.toInstant())) {
				return false;
			}
			List<Integer> predecessor = jdbc.query(
				"SELECT 1 FROM generation_attempt a "
					+ "WHERE a.id = ? AND a.generation_job_id = ? "
					+ "AND a.attempt_number = ("
					+ "SELECT MAX(current_attempt.attempt_number) FROM generation_attempt current_attempt "
					+ "WHERE current_attempt.generation_job_id = ?) FOR UPDATE",
				(rs, rowNum) -> rs.getInt(1),
				completedAttempt.id().value(),
				completedAttempt.generationJobId().value(),
				completedAttempt.generationJobId().value());
			if (predecessor.isEmpty()) {
				return false;
			}

			int updated = jdbc.update(
				"UPDATE generation_job SET status = '" + GenerationJob.RETRYING + "', "
					+ "retry_not_before_at = ?, updated_at = ?, status_version = status_version + 1 "
					+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
					+ "AND status <> '" + GenerationJob.RETRYING + "' "
					+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
					+ "AND status_version = ?",
				Timestamp.from(retryNotBeforeAt),
				Timestamp.from(clock.instant()),
				completedAttempt.generationJobId().value(),
				expectedStatusVersion);
			if (updated != 1) {
				return false;
			}
			completeAttempt(completedAttempt);
			return true;
		});
		return Boolean.TRUE.equals(scheduled);
	}

	@Override
	public boolean claimDueRetry(
		GenerationJobId jobId,
		int expectedStatusVersion,
		Instant now,
		GenerationAttempt nextAttempt
	) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		if (nextAttempt == null) {
			throw new IllegalArgumentException("nextAttempt is required");
		}
		if (!jobId.equals(nextAttempt.generationJobId())) {
			throw new IllegalArgumentException("nextAttempt must belong to the claimed generation job");
		}
		Boolean claimed = transactions.execute(status -> {
			List<Map<String, Object>> due = jdbc.queryForList(
				"SELECT total_steps, retry_policy_snapshot FROM generation_job "
					+ "WHERE id = ? AND status = '" + GenerationJob.RETRYING + "' "
					+ "AND retry_not_before_at IS NOT NULL AND retry_not_before_at <= ? "
					+ "AND status_version = ? AND completed_at IS NULL "
					+ "AND cancelled_at IS NULL AND subject_deleted_at IS NULL FOR UPDATE",
				jobId.value(),
				Timestamp.from(now),
				expectedStatusVersion);
			if (due.isEmpty()) {
				return false;
			}

			Map<String, Object> row = due.getFirst();
			int totalSteps = ((Number) row.get("total_steps")).intValue();
			String serializedPolicy = (String) row.get("retry_policy_snapshot");
			RetryPolicyResolution resolution = RetryPolicySnapshot.parseResolution(serializedPolicy);
			if (resolution.state() != RetryPolicyResolution.State.VALID) {
				return false;
			}
			RetryPolicySnapshot policy = resolution.snapshot();
			if (!policyCeilingAdmits(policy, totalSteps)) {
				return false;
			}
			Integer attemptsForStep;
			if (nextAttempt.renderStepId() == null) {
				attemptsForStep = jdbc.queryForObject(
					"SELECT COUNT(*) FROM generation_attempt "
						+ "WHERE generation_job_id = ? AND render_step_id IS NULL",
					Integer.class,
					jobId.value());
			}
			else {
				attemptsForStep = jdbc.queryForObject(
					"SELECT COUNT(*) FROM generation_attempt "
						+ "WHERE generation_job_id = ? AND render_step_id = ?",
					Integer.class,
					jobId.value(),
					nextAttempt.renderStepId());
			}
			if ((attemptsForStep == null ? 0 : attemptsForStep) >= MAX_LOGICAL_ATTEMPTS_PER_STEP) {
				return false;
			}


			Integer attemptCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_attempt WHERE generation_job_id = ?",
				Integer.class,
				jobId.value());
			long maxPaidAttempts = policy.maxPaidAttempts();
			if ((attemptCount == null ? 0 : attemptCount) >= maxPaidAttempts) {
				return false;
			}

			if (policy.snapshotVersion() == RetryPolicySnapshot.SNAPSHOT_VERSION_V2) {
				int paidAttemptsUsed = attemptCount == null ? 0 : attemptCount;
				Integer baselinesRemaining = jdbc.queryForObject(
					"SELECT COUNT(*) FROM generation_render_step s "
						+ "WHERE s.generation_job_id = ? AND NOT EXISTS ("
						+ "SELECT 1 FROM generation_attempt a WHERE a.render_step_id = s.id)",
					Integer.class,
					jobId.value());
				int baselinesStillRequiringExecution = baselinesRemaining == null ? 0 : baselinesRemaining;
				if (nextAttempt.renderStepId() != null) {
					Integer stepHasAttempt = jdbc.queryForObject(
						"SELECT COUNT(*) FROM generation_attempt "
							+ "WHERE generation_job_id = ? AND render_step_id = ?",
						Integer.class,
						jobId.value(),
						nextAttempt.renderStepId());
					if ((stepHasAttempt == null ? 0 : stepHasAttempt) == 0) {
						baselinesStillRequiringExecution -= 1;
					}
				}
				if (!GenerationRetryBudget.remainingPaidSlotsCoverBaselines(
					policy.maxPaidAttempts(),
					paidAttemptsUsed,
					baselinesStillRequiringExecution)) {
					return false;
				}
			}

			Integer maxAttemptNumber = jdbc.queryForObject(
				"SELECT MAX(attempt_number) FROM generation_attempt WHERE generation_job_id = ?",
				Integer.class,
				jobId.value());
			long expectedAttemptNumber = maxAttemptNumber == null ? 1L : (long) maxAttemptNumber + 1L;
			if (nextAttempt.attemptNumber() < 1 || nextAttempt.attemptNumber() != expectedAttemptNumber) {
				return false;
			}

			Integer existingId = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_attempt WHERE id = ?",
				Integer.class,
				nextAttempt.id().value());
			if (existingId != null && existingId > 0) {
				return false;
			}
			Integer existingNumber = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_attempt "
					+ "WHERE generation_job_id = ? AND attempt_number = ?",
				Integer.class,
				jobId.value(),
				nextAttempt.attemptNumber());
			if (existingNumber != null && existingNumber > 0) {
				return false;
			}


			int updated = jdbc.update(
				"UPDATE generation_job SET status = '" + GenerationJob.PREFLIGHT + "', "
					+ "retry_not_before_at = NULL, updated_at = ?, "
					+ "status_version = status_version + 1 "
					+ "WHERE id = ? AND status = '" + GenerationJob.RETRYING + "' "
					+ "AND retry_not_before_at IS NOT NULL AND retry_not_before_at <= ? "
					+ "AND status_version = ? AND completed_at IS NULL "
					+ "AND cancelled_at IS NULL AND subject_deleted_at IS NULL",
				Timestamp.from(now),
				jobId.value(),
				Timestamp.from(now),
				expectedStatusVersion);
			if (updated != 1) {
				return false;
			}
			insertAttempt(nextAttempt);
			return true;
		});
		return Boolean.TRUE.equals(claimed);
	}

	/**
	 * Resolves the exact fail-closed persisted policy state for a job (Q5),
	 * or {@code null} when the job does not exist. The resolution is never
	 * substituted with current defaults (R9). A row that parses but violates
	 * the settled paid-cap ceilings resolves to
	 * {@link RetryPolicyResolution.State#INVALID} exactly like the claim and
	 * read paths, so spend authorization stays consistent end to end.
	 */
	@Override
	public RetryPolicyResolution resolveRetryPolicy(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		List<RetryPolicyResolution> resolutions = jdbc.query(
			"SELECT retry_policy_snapshot, total_steps FROM generation_job WHERE id = ?",
			(rs, rowNum) -> {
				RetryPolicyResolution resolution = RetryPolicySnapshot.parseResolution(
					rs.getString("retry_policy_snapshot"));
				if (resolution.state() != RetryPolicyResolution.State.VALID) {
					return resolution;
				}
				return policyCeilingAdmits(resolution.snapshot(), rs.getInt("total_steps"))
					? resolution
					: new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
			},
			jobId.value());
		return resolutions.isEmpty() ? null : resolutions.getFirst();
	}

	@Override
	public List<GenerationJob> listDueRetrying(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		return jdbc.query(
			"SELECT " + JOB_COLUMNS + " FROM generation_job "
				+ "WHERE status = '" + GenerationJob.RETRYING + "' "
				+ "AND retry_not_before_at IS NOT NULL AND retry_not_before_at <= ? "
				+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
				+ "ORDER BY retry_not_before_at, id",
			(rs, rowNum) -> {
				GenerationJobId jobId = new GenerationJobId(rs.getObject("id", UUID.class));
				return mapJob(rs, loadProductIds(jobId));
			},
			Timestamp.from(now));
	}

	@Override
	public boolean tryRecordTransmission(GenerationAttemptId attemptId, Instant now) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		return jdbc.update(
			"UPDATE generation_attempt a SET transmission_count = transmission_count + 1 "
				+ "FROM generation_job j WHERE a.id = ? AND a.generation_job_id = j.id "
				+ "AND a.transmission_count >= 0 "
				+ "AND a.transmission_count < " + RetryPolicySnapshot.MAX_TRANSMISSIONS_PER_ATTEMPT + " "
				+ "AND a.provider_accepted_at IS NULL AND a.completed_at IS NULL "
				+ "AND j.status IN (" + ACTIVE_STATUSES_SQL + ") "
				+ "AND j.completed_at IS NULL AND j.cancelled_at IS NULL AND j.subject_deleted_at IS NULL "
				+ "AND (a.submission_recovery_deadline_at IS NULL OR a.submission_recovery_deadline_at > ?) "
				+ "AND (j.orchestration_deadline_at IS NULL OR j.orchestration_deadline_at > ?)",

			attemptId.value(),
			Timestamp.from(now),
			Timestamp.from(now)) == 1;
	}

	@Override
	public boolean advanceStepIfActive(
		GenerationJobId jobId,
		int expectedStatusVersion,
		int completedStep
	) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.update(
			"UPDATE generation_job SET current_step = current_step + 1, updated_at = ?, "
				+ "status_version = status_version + 1 "
				+ "WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
				+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
				+ "AND status_version = ? AND current_step = ? AND current_step < total_steps",
			Timestamp.from(clock.instant()),
			jobId.value(),
			expectedStatusVersion,
			completedStep) == 1;
	}



	@Override
	public boolean recordProviderExecutionIfActive(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		String providerJobValue = providerJobId == null ? null : providerJobId.value();
		return jdbc.update(
			RECORD_PROVIDER_EXECUTION_IF_ACTIVE_SQL,
			providerJobValue,
			providerJobValue,
			Timestamp.from(clock.instant()),
			attemptId.value(),
			providerJobValue) == 1;
	}

	@Override
	public boolean markSubmitted(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.update(MARK_SUBMITTED_SQL, Timestamp.from(clock.instant()), jobId.value()) == 1;
	}

	@Override
	public boolean markGenerating(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.update(MARK_GENERATING_SQL, Timestamp.from(clock.instant()), jobId.value()) == 1;
	}

	@Override
	public boolean markPreflight(GenerationJobId jobId) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		return jdbc.update(MARK_PREFLIGHT_SQL, Timestamp.from(clock.instant()), jobId.value()) == 1;
	}

	@Override
	public boolean markQualityCheckIfActive(GenerationJobId jobId, int expectedStatusVersion) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		int updated = jdbc.update(
			MARK_QUALITY_CHECK_IF_ACTIVE_SQL,
			Timestamp.from(clock.instant()),
			jobId.value(),
			expectedStatusVersion);
		if (updated == 1) {
			return true;
		}
		List<Integer> alreadyFenced = jdbc.queryForList(
			"SELECT 1 FROM generation_job WHERE id = ? AND status = '" + GenerationJob.QUALITY_CHECK + "' "
				+ "AND status_version = ? "
				+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL",
			Integer.class,
			jobId.value(),
			expectedStatusVersion);
		return !alreadyFenced.isEmpty();
	}

	@Override
	public boolean failIfDeadlineExceeded(GenerationJobId jobId, Instant now) {
		if (jobId == null) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		return jdbc.update(
			FAIL_IF_DEADLINE_EXCEEDED_SQL,
			Timestamp.from(now),
			Timestamp.from(now),
			jobId.value(),
			Timestamp.from(now)) == 1;
	}

	@Override
	public void markSubjectDeletedForAvatar(UserId userId, AvatarImageId avatarImageId, Instant when) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (avatarImageId == null) {
			throw new IllegalArgumentException("avatarImageId is required");
		}
		if (when == null) {
			throw new IllegalArgumentException("when is required");
		}
		jdbc.update(
			"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', "
				+ "terminal_reason = CASE WHEN terminal_reason IS NULL THEN '"
				+ GenerationJob.SUBJECT_DELETED + "' ELSE terminal_reason END, "
				+ "retry_not_before_at = NULL, subject_deleted_at = ?, completed_at = COALESCE(completed_at, ?), updated_at = ?, "
				+ "status_version = status_version + 1 "
				+ "WHERE user_id = ? AND avatar_image_id = ? AND subject_deleted_at IS NULL "
				+ "AND status <> '" + GenerationJob.COMPLETED + "'",
			Timestamp.from(when),
			Timestamp.from(when),
			Timestamp.from(when),
			userId.value(),
			avatarImageId.value());
	}

	@Override
	public void markAllSubjectDeleted(UserId userId, Instant when) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (when == null) {
			throw new IllegalArgumentException("when is required");
		}
		jdbc.update(
			"UPDATE generation_job SET status = '" + GenerationJob.FAILED + "', "
				+ "terminal_reason = CASE WHEN terminal_reason IS NULL THEN '"
				+ GenerationJob.SUBJECT_DELETED + "' ELSE terminal_reason END, "
				+ "retry_not_before_at = NULL, subject_deleted_at = ?, completed_at = COALESCE(completed_at, ?), updated_at = ?, "
				+ "status_version = status_version + 1 "
				+ "WHERE user_id = ? AND subject_deleted_at IS NULL AND status <> '"
				+ GenerationJob.COMPLETED + "'",
			Timestamp.from(when),
			Timestamp.from(when),
			Timestamp.from(when),
			userId.value());
	}

	@Override
	public List<GenerationJob> listJobsByUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return jdbc.query(
			"SELECT " + JOB_COLUMNS + " FROM generation_job WHERE user_id = ? ORDER BY created_at, id",
			(rs, rowNum) -> {
				GenerationJobId jobId = new GenerationJobId(rs.getObject("id", UUID.class));
				return mapJob(rs, loadProductIds(jobId));
			},
			userId.value());
	}
	@Override
	public void deleteUserLinkedGenerationData(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		transactions.execute(status -> {
			jdbc.update(
				"UPDATE look SET generation_job_id = NULL "
					+ "WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_notification_delivery "
					+ "WHERE event_id IN ("
					+ "SELECT e.id FROM generation_notification_event e "
					+ "JOIN generation_job j ON j.id = e.generation_job_id "
					+ "WHERE j.user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_notification_event WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM quality_result WHERE generation_attempt_id IN ("
					+ "SELECT a.id FROM generation_attempt a "
					+ "JOIN generation_job j ON j.id = a.generation_job_id "
					+ "WHERE j.user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_callback_inbox WHERE generation_attempt_id IN ("
					+ "SELECT a.id FROM generation_attempt a "
					+ "JOIN generation_job j ON j.id = a.generation_job_id "
					+ "WHERE j.user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_provider_capacity_slot WHERE generation_attempt_id IN ("
					+ "SELECT a.id FROM generation_attempt a "
					+ "JOIN generation_job j ON j.id = a.generation_job_id "
					+ "WHERE j.user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_work_outbox WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_quota_reservation WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_render_step_item WHERE render_step_id IN ("
					+ "SELECT s.id FROM generation_render_step s "
					+ "JOIN generation_job j ON j.id = s.generation_job_id "
					+ "WHERE j.user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_render_step WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_job_product WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_attempt WHERE generation_job_id IN ("
					+ "SELECT id FROM generation_job WHERE user_id = ?)",
				userId.value());
			jdbc.update(
				"DELETE FROM generation_job WHERE user_id = ?",
				userId.value());
			return null;
		});
	}

	private void insertJobRow(GenerationJob job) {
		jdbc.update(
			"INSERT INTO generation_job "
				+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
				+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at, "
				+ "status_version, request_fingerprint, avatar_revision, updated_at, terminal_reason, "
				+ "current_step, total_steps, render_plan_version, orchestration_deadline_at, "
				+ "retry_not_before_at, retry_policy_snapshot, retry_of_job_id) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
			job.id().value(),
			job.userId().value(),
			job.studioSessionId() == null ? null : job.studioSessionId().value(),
			job.status(),
			job.requestedMode(),
			job.clientRequestId() == null ? null : job.clientRequestId().value(),
			Timestamp.from(job.createdAt()),
			timestampOrNull(job.completedAt()),
			job.avatarImageId() == null ? null : job.avatarImageId().value(),
			timestampOrNull(job.cancelledAt()),
			timestampOrNull(job.subjectDeletedAt()),
			job.statusVersion(),
			job.requestFingerprint(),
			job.avatarRevision(),
			Timestamp.from(job.updatedAt()),
			job.terminalReason(),
			job.currentStep(),
			job.totalSteps(),
			job.renderPlanVersion(),
			Timestamp.from(job.orchestrationDeadlineAt()),
			timestampOrNull(job.retryNotBeforeAt()),
			job.retryPolicySnapshot() == null ? null : job.retryPolicySnapshot().serialize(),
			job.retryOfJobId().map(GenerationJobId::value).orElse(null));
	}

	private void insertProducts(GenerationJob job) {
		for (VaultItemId productVaultItemId : job.productVaultItemIds()) {
			jdbc.update(
				"INSERT INTO generation_job_product (generation_job_id, vault_item_id) VALUES (?, ?)",
				job.id().value(),
				productVaultItemId.value());
		}
	}

	private void insertRenderPlan(GenerationJob job, RenderPlan plan, Instant now) {
		for (RenderStep step : plan.steps()) {
			jdbc.update(
				"INSERT INTO generation_render_step "
					+ "(id, generation_job_id, render_plan_version, step_index, capability_snapshot, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?)",
				step.id(),
				job.id().value(),
				plan.version(),
				step.stepIndex(),
				step.capabilitySnapshot(),
				Timestamp.from(now));
			for (RenderStep.Item item : step.items()) {
				jdbc.update(
					"INSERT INTO generation_render_step_item "
						+ "(id, render_step_id, item_ordinal, vault_item_id, category, slot, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
					item.id(),
					step.id(),
					item.itemOrdinal(),
					item.vaultItemId().value(),
					item.category(),
					item.slot(),
					Timestamp.from(now));
			}
		}
	}

	private List<RenderPlan.PlannedProduct> loadPlannedProducts(GenerationJob job) {
		List<RenderPlan.PlannedProduct> planned = new ArrayList<>();
		for (VaultItemId productId : job.productVaultItemIds()) {
			String category = jdbc.query(
				"SELECT category FROM vault_item WHERE id = ?",
				rs -> rs.next() ? rs.getString("category") : null,
				productId.value());
			planned.add(new RenderPlan.PlannedProduct(productId, category, null));
		}
		return planned;
	}

	private List<RenderStep> loadRenderSteps(GenerationJobId jobId) {
		Map<UUID, RenderStep> steps = new LinkedHashMap<>();
		jdbc.query(
			"SELECT s.id, s.step_index, s.capability_snapshot, i.id AS item_id, i.item_ordinal, "
				+ "i.vault_item_id, i.category, i.slot "
				+ "FROM generation_render_step s "
				+ "LEFT JOIN generation_render_step_item i ON i.render_step_id = s.id "
				+ "WHERE s.generation_job_id = ? "
				+ "ORDER BY s.step_index, i.item_ordinal, i.id",
			rs -> {
				while (rs.next()) {
					UUID stepId = rs.getObject("id", UUID.class);
					int stepIndex = rs.getInt("step_index");
					String capabilitySnapshot = rs.getString("capability_snapshot");
					RenderStep step = steps.computeIfAbsent(
						stepId,
						id -> new RenderStep(id, stepIndex, capabilitySnapshot, new ArrayList<>()));
					UUID itemId = rs.getObject("item_id", UUID.class);
					if (itemId != null) {
						List<RenderStep.Item> items = new ArrayList<>(step.items());
						items.add(new RenderStep.Item(
							itemId,
							rs.getInt("item_ordinal"),
							new VaultItemId(rs.getObject("vault_item_id", UUID.class)),
							rs.getString("category"),
							rs.getString("slot")));
						steps.put(stepId, new RenderStep(step.id(), step.stepIndex(), step.capabilitySnapshot(), items));
					}
				}
				return null;
			},
			jobId.value());
		return List.copyOf(steps.values());
	}

	private Optional<GenerationJob> findJobByUserAndClientRequestId(UserId userId, ClientRequestId clientRequestId) {
		List<GenerationJob> jobs = jdbc.query(
			"SELECT " + JOB_COLUMNS + " FROM generation_job WHERE user_id = ? AND client_request_id = ?",
			(rs, rowNum) -> {
				GenerationJobId jobId = new GenerationJobId(rs.getObject("id", UUID.class));
				return mapJob(rs, loadProductIds(jobId));
			},
			userId.value(),
			clientRequestId.value());
		return one(jobs);
	}

	private CreateAcceptedResult replayOrConflict(GenerationJob existing, String requestFingerprint) {
		if (Objects.equals(existing.requestFingerprint(), requestFingerprint)) {
			return new CreateAcceptedResult.Replayed(existing);
		}
		return new CreateAcceptedResult.FingerprintConflict(existing);
	}

	private CreateSingleItemResult replaySingleItem(GenerationJob existing, String requestFingerprint) {
		if (Objects.equals(existing.requestFingerprint(), requestFingerprint)) {
			return new CreateSingleItemResult.Replayed(existing);
		}
		return new CreateSingleItemResult.FingerprintConflict(existing);
	}

	private static GenerationJob acceptedJob(GenerationJob job, RenderPlan plan, Instant now) {
		RetryPolicySnapshot policy = job.retryPolicySnapshot();
		if (policy == null) {
			throw new IllegalArgumentException("createAccepted requires a valid retry policy snapshot (Q5)");
		}
		if (policy.snapshotVersion() == RetryPolicySnapshot.SNAPSHOT_VERSION_V2
			&& policy.maxPaidAttempts() != GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS) {
			throw new IllegalArgumentException(
				"version 2 retry policy must fix the paid-capable attempt cap at "
					+ GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS + " (R5)");
		}
		return new GenerationJob(
			job.id(),
			job.userId(),
			job.studioSessionId(),
			GenerationJob.QUEUED,
			job.requestedMode(),
			job.clientRequestId(),
			job.createdAt(),
			null,
			job.avatarImageId(),
			job.productVaultItemIds(),
			null,
			null,
			job.requestFingerprint(),
			job.avatarRevision(),
			job.statusVersion(),
			now,
			null,
			1,
			plan.steps().size(),
			plan.version(),
			job.orchestrationDeadlineAt(),
			null,
			policy,
			job.retryOfJobId());
	}

	private List<VaultItemId> loadProductIds(GenerationJobId jobId) {
		return jdbc.query(
			"SELECT vault_item_id FROM generation_job_product WHERE generation_job_id = ? ORDER BY vault_item_id",
			(rs, rowNum) -> new VaultItemId(rs.getObject("vault_item_id", UUID.class)),
			jobId.value());
	}

	private static GenerationJob mapJob(ResultSet rs, List<VaultItemId> products) throws SQLException {
		UUID studioSessionId = rs.getObject("studio_session_id", UUID.class);
		String clientRequestId = rs.getString("client_request_id");
		UUID avatarImageId = rs.getObject("avatar_image_id", UUID.class);
		int totalSteps = rs.getInt("total_steps");
		String serializedPolicy = rs.getString("retry_policy_snapshot");
		RetryPolicySnapshot policy = readPolicyFailClosed(serializedPolicy, totalSteps);
		UUID retryOfJobId = rs.getObject("retry_of_job_id", UUID.class);
		return new GenerationJob(
			new GenerationJobId(rs.getObject("id", UUID.class)),
			new UserId(rs.getObject("user_id", UUID.class)),
			studioSessionId == null ? null : new StudioSessionId(studioSessionId),
			rs.getString("status"),
			rs.getString("requested_mode"),
			clientRequestId == null ? null : new ClientRequestId(clientRequestId),
			rs.getTimestamp("created_at").toInstant(),
			toInstant(rs.getTimestamp("completed_at")),
			avatarImageId == null ? null : new AvatarImageId(avatarImageId),
			products,
			toInstant(rs.getTimestamp("cancelled_at")),
			toInstant(rs.getTimestamp("subject_deleted_at")),
			rs.getString("request_fingerprint"),
			rs.getObject("avatar_revision", Integer.class),
			rs.getInt("status_version"),
			rs.getTimestamp("updated_at").toInstant(),
			rs.getString("terminal_reason"),
			rs.getInt("current_step"),
			totalSteps,
			rs.getInt("render_plan_version"),
			rs.getTimestamp("orchestration_deadline_at").toInstant(),
			toInstant(rs.getTimestamp("retry_not_before_at")),
			policy,
			retryOfJobId == null ? Optional.empty() : Optional.of(new GenerationJobId(retryOfJobId)));
	}
	/**
	 * Read-path policy resolution (R9/Q5): never substitutes current defaults
	 * for a historical row. Missing, empty, malformed, and unknown-version
	 * rows fail closed into a null snapshot; the exact reason stays observable
	 * through {@link #resolveRetryPolicy(GenerationJobId)}. The historical
	 * ceiling validation still suppresses out-of-band rows.
	 */
	private static RetryPolicySnapshot readPolicyFailClosed(String serialized, int totalSteps) {
		RetryPolicyResolution resolution = RetryPolicySnapshot.parseResolution(serialized);
		if (resolution.state() != RetryPolicyResolution.State.VALID) {
			return null;
		}
		RetryPolicySnapshot policy = resolution.snapshot();
		return policyCeilingAdmits(policy, totalSteps) ? policy : null;
	}

	/**
	 * Historical paid-cap ceiling for persisted policy rows (R9/Q5): a
	 * snapshot authorizes spend only while it keeps at least one baseline per
	 * render step and never raises the total attempt cap. Rows outside the
	 * settled band are treated exactly like unparseable rows: no spend
	 * authorization.
	 */
	private static boolean policyCeilingAdmits(RetryPolicySnapshot policy, int totalSteps) {
		long maxAllowedPaidAttempts = (long) totalSteps + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS;
		return policy.maxPaidAttempts() >= totalSteps && policy.maxPaidAttempts() <= maxAllowedPaidAttempts;
	}

	private static boolean qualityResultEquivalent(Map<String, Object> existing, QualityResult incoming) {
		return Objects.equals(existing.get("decision"), incoming.decision().name())
			&& decimalEquals(existing.get("identity_preservation"), incoming.identityPreservation())
			&& decimalEquals(existing.get("garment_fidelity"), incoming.garmentFidelity())
			&& decimalEquals(existing.get("color_fidelity"), incoming.colorFidelity())
			&& Objects.equals(existing.get("anatomy_valid"), incoming.anatomyValid())
			&& Objects.equals(existing.get("artifact_detected"), incoming.artifactDetected())
			&& Objects.equals(existing.get("quality_policy_version"), incoming.qualityPolicyVersion())
			&& Objects.equals(existing.get("reason_code"), incoming.reasonCode());
	}

	private static boolean decimalEquals(Object existing, BigDecimal incoming) {
		if (existing == null || incoming == null) {
			return existing == null && incoming == null;
		}
		return ((BigDecimal) existing).compareTo(incoming) == 0;
	}


	static String terminalFailureReason(GenerationAttempt attempt) {
		if (attempt.failureCode() != null && !attempt.failureCode().isBlank()) {
			return attempt.failureCode();
		}
		if ("FAILED".equals(attempt.technicalOutcome())) {
			return "PROVIDER_EXECUTION_FAILED";
		}
		return null;
	}

	private static boolean isUniqueViolation(DataAccessException ex) {
		if (ex instanceof DuplicateKeyException) {
			return true;
		}
		Throwable cause = ex.getMostSpecificCause();
		return cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
	}

	private static Timestamp timestampOrNull(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static <T> Optional<T> one(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
	}
}
