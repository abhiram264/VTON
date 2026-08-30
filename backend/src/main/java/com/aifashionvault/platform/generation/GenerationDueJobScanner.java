package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.platform.queue.WorkOutboxStore;

/**
 * Bounded lost-wakeup repair scanner (Q42).
 *
 * <p>Each tick reads canonical PostgreSQL due state via
 * {@link GenerationStore#listDueRetrying(Instant)} and re-enqueues ONLY the
 * opaque job identifier through the durable outbox
 * ({@link WorkOutboxStore#insertSuccessorIfActive}); the existing
 * {@code GenerationWorkOutboxRelay} performs the actual SQS publication. The
 * scanner repairs committed-job/failed-enqueue and post-restore lost wake-ups
 * without replacing SQS as the delivery path (ADR-004/ADR-005).</p>
 *
 * <p>Frozen boundaries:</p>
 * <ul>
 * <li>Never calls the SQS queue directly and never creates or modifies
 * attempts or job rows: the reloaded job state decides everything downstream
 * (Q27/Q38/Q40).</li>
 * <li>Terminal jobs are skipped defensively even though {@code listDueRetrying}
 * already excludes them.</li>
 * <li>Per-tick work is bounded by {@link #MAX_JOBS_PER_SCAN}; remaining due
 * jobs are repaired on later ticks.</li>
 * <li>Jobs past their orchestration deadline are still re-enqueued: the
 * processor tick owns deadline termination ({@code failIfDeadlineExceeded}),
 * so the scanner only repairs wake-ups and never makes termination
 * decisions.</li>
 * <li>Duplicate re-enqueue is harmless by design: delivery is at-least-once
 * and every receive reloads database truth; the outbox
 * {@code insertSuccessorIfActive} active-job guard is the dedupe seam.</li>
 * </ul>
 */
public final class GenerationDueJobScanner {

	/** Hard bound on jobs repaired per scan tick. */
	static final int MAX_JOBS_PER_SCAN = 100;
	/** Fixed delay between scan ticks. */
	static final long SCAN_FIXED_DELAY_SECONDS = 30L;

	private static final Logger log = LoggerFactory.getLogger(GenerationDueJobScanner.class);

	private final GenerationStore generationStore;
	private final WorkOutboxStore workOutboxStore;
	private final Clock clock;

	public GenerationDueJobScanner(
		GenerationStore generationStore,
		WorkOutboxStore workOutboxStore,
		Clock clock
	) {
		this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
		this.workOutboxStore = Objects.requireNonNull(workOutboxStore, "workOutboxStore");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Scheduled(initialDelay = SCAN_FIXED_DELAY_SECONDS, fixedDelay = SCAN_FIXED_DELAY_SECONDS, timeUnit = TimeUnit.SECONDS)
	void scheduledScan() {
		try {
			scanDueJobs();
		}
		catch (RuntimeException ex) {
			log.info("generation code=DUE_JOB_SCAN_FAILED error={}", ex.getMessage());
		}
	}

	/**
	 * Runs one bounded repair pass.
	 *
	 * @return the number of wake-ups re-enqueued through the outbox
	 */
	int scanDueJobs() {
		Instant now = clock.instant();
		List<GenerationJob> dueJobs = generationStore.listDueRetrying(now);
		int repaired = 0;
		for (GenerationJob job : dueJobs) {
			if (repaired >= MAX_JOBS_PER_SCAN) {
				break;
			}
			if (!GenerationJob.RETRYING.equals(job.status())) {
				log.info("generation jobId={} code=DUE_JOB_SCAN_SKIPPED status={}", job.id().value(), job.status());
				continue;
			}
			Instant notBefore = job.retryNotBeforeAt() != null ? job.retryNotBeforeAt() : now;
			if (insertWakeUp(job, notBefore)) {
				repaired++;
			}
		}
		return repaired;
	}

	private boolean insertWakeUp(GenerationJob job, Instant notBefore) {
		try {
			boolean inserted = workOutboxStore.insertSuccessorIfActive(job.id().value(), notBefore);
			if (inserted) {
				log.info("generation jobId={} code=DUE_JOB_WAKE_REPAIRED", job.id().value());
			}
			return inserted;
		}
		catch (RuntimeException ex) {
			log.info("generation jobId={} code=DUE_JOB_WAKE_REPAIR_FAILED error={}", job.id().value(), ex.getMessage());
			return false;
		}
	}
}
