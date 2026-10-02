package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.privacy.AccountDeletionWorkflowStore.Lease;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcAccountDeletionWorkflowStoreTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));
	private static final UserId USER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
	private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();

	private JdbcTemplate jdbc;
	private JdbcAccountDeletionWorkflowStore store;

	@BeforeAll
	static void database() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	@AfterAll
	static void stopDatabase() {
		POSTGRES.stop();
		TimeZone.setDefault(ORIGINAL_TIME_ZONE);
	}

	@BeforeEach
	void reset() {
		DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
			POSTGRES.getPassword());
		this.jdbc = new JdbcTemplate(dataSource);
		this.store = new JdbcAccountDeletionWorkflowStore(dataSource);
		jdbc.update("DELETE FROM account_deletion_workflow");
		jdbc.update("DELETE FROM users");
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			USER.value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
	}

	@Test
	void requestIsIdempotentAndPersistsOneRow() {
		AccountDeletionStatus first = store.request(USER, NOW);
		AccountDeletionStatus second = store.request(USER, NOW.plusSeconds(30));

		assertThat(first.workflowId()).isEqualTo(second.workflowId());
		assertThat(first.nextStep()).isEqualTo(AccountDeletionStep.REQUESTED);
		assertThat(first.state()).isEqualTo(AccountDeletionState.REQUESTED);
		assertThat(first.attemptCount()).isZero();
		assertThat(first.completedAt()).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_workflow WHERE user_id = ?",
			Integer.class, USER.value())).isEqualTo(1);
	}

	@Test
	void acquireGrantsOneLeaseAndRefusesWhileHeld() {
		store.request(USER, NOW);
		Instant expiresAt = NOW.plusSeconds(300);

		Lease held = store.acquire(USER, NOW, expiresAt).orElseThrow();
		assertThat(store.acquire(USER, NOW.plusSeconds(1), NOW.plusSeconds(301))).isEmpty();

		Lease takeover = store.acquire(USER, expiresAt, expiresAt.plusSeconds(300)).orElseThrow();
		assertThat(takeover.token()).isNotEqualTo(held.token());
	}

	@Test
	void staleLeaseCannotAdvanceAfterTakeover() {
		store.request(USER, NOW);
		Instant expiresAt = NOW.plusSeconds(300);
		Lease l1 = store.acquire(USER, NOW, expiresAt).orElseThrow();
		Lease l2 = store.acquire(USER, expiresAt, expiresAt.plusSeconds(300)).orElseThrow();

		assertThatThrownBy(() -> store.advance(l1.status().workflowId(), l1.token(),
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, expiresAt))
			.isInstanceOf(IllegalStateException.class);
		assertThat(store.status(USER).nextStep()).isEqualTo(AccountDeletionStep.REQUESTED);

		AccountDeletionStatus advanced = store.advance(l2.status().workflowId(), l2.token(),
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, expiresAt);
		assertThat(advanced.nextStep()).isEqualTo(AccountDeletionStep.DELETE_AVATARS);
	}

	@Test
	void advanceIsCompareAndSet() {
		store.request(USER, NOW);
		Lease held = store.acquire(USER, NOW, NOW.plusSeconds(300)).orElseThrow();
		UUID otherWorkflow = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UUID otherToken = UUID.fromString("44444444-4444-4444-4444-444444444444");

		assertThatThrownBy(() -> store.advance(otherWorkflow, held.token(),
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, NOW))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(held.status().workflowId(), otherToken,
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, NOW))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(held.status().workflowId(), held.token(),
			AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS, NOW))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(held.status().workflowId(), held.token(),
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_VAULT, NOW))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(held.status().workflowId(), held.token(),
			AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, NOW.plusSeconds(301)))
			.isInstanceOf(IllegalStateException.class);

		assertThat(store.status(USER).nextStep()).isEqualTo(AccountDeletionStep.REQUESTED);
	}

	@Test
	void advancingToCompleteSetsCompletionAndReleasesLease() {
		store.request(USER, NOW);
		Instant expiresAt = NOW.plusSeconds(300);
		Instant advanceAt = NOW.plusSeconds(1);
		Lease held = store.acquire(USER, NOW, expiresAt).orElseThrow();

		AccountDeletionStatus status = held.status();
		AccountDeletionStep[] steps = AccountDeletionStep.values();
		for (int index = 0; index < steps.length - 1; index++) {
			status = store.advance(status.workflowId(), held.token(), steps[index], steps[index + 1], advanceAt);
		}

		assertThat(status.state()).isEqualTo(AccountDeletionState.COMPLETE);
		assertThat(status.nextStep()).isEqualTo(AccountDeletionStep.COMPLETE);
		assertThat(status.completedAt()).isEqualTo(advanceAt);
		assertThat(store.acquire(USER, advanceAt, advanceAt.plusSeconds(300))).isEmpty();
	}

	@Test
	void failRecordsRetryableFailureAndStaysResumable() {
		store.request(USER, NOW);
		Instant expiresAt = NOW.plusSeconds(300);
		Lease held = store.acquire(USER, NOW, expiresAt).orElseThrow();

		AccountDeletionStatus failed = store.fail(held.status().workflowId(), held.token(),
			AccountDeletionStep.REQUESTED, "VAULT_DELETE_FAILED", NOW.plusSeconds(1));

		assertThat(failed.state()).isEqualTo(AccountDeletionState.RETRYABLE_FAILURE);
		assertThat(failed.nextStep()).isEqualTo(AccountDeletionStep.REQUESTED);
		assertThat(failed.attemptCount()).isEqualTo(1);
		assertThat(failed.lastErrorCode()).isEqualTo("VAULT_DELETE_FAILED");
		assertThat(failed.completedAt()).isNull();
		assertThat(store.acquire(USER, NOW.plusSeconds(2), NOW.plusSeconds(302))).isPresent();
	}

	@Test
	void workflowRunsAndResumesAgainstTheRealStore() {
		List<AccountDeletionStep> effects = new ArrayList<>();
		DefaultAccountDeletionWorkflow workflow = workflow(effects, null);

		AccountDeletionStatus result = workflow.request(USER);

		assertThat(result.state()).isEqualTo(AccountDeletionState.COMPLETE);
		assertThat(effects).containsExactly(AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS,
			AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS,
			AccountDeletionStep.DELETE_TEMPORARY_GENERATIONS, AccountDeletionStep.REVOKE_AUTH,
			AccountDeletionStep.REMOVE_PUSH_TOKENS, AccountDeletionStep.ANONYMIZE_OPERATIONAL_DATA);

		workflow.request(USER);
		assertThat(effects).hasSize(8);
	}

	@Test
	void workflowFailureIsCheckpointedAndResumesFromTheFailedStep() {
		List<AccountDeletionStep> effects = new ArrayList<>();
		AccountDeletionStatus failed = workflow(effects, AccountDeletionStep.DELETE_VAULT).request(USER);

		assertThat(failed.state()).isEqualTo(AccountDeletionState.RETRYABLE_FAILURE);
		assertThat(failed.nextStep()).isEqualTo(AccountDeletionStep.DELETE_VAULT);
		assertThat(failed.lastErrorCode()).isEqualTo("VAULT_DELETE_FAILED");
		assertThat(failed.attemptCount()).isEqualTo(1);

		AccountDeletionStatus complete = workflow(effects, null).resume(USER);

		assertThat(complete.state()).isEqualTo(AccountDeletionState.COMPLETE);
		assertThat(effects).containsExactly(AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS,
			AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS,
			AccountDeletionStep.DELETE_TEMPORARY_GENERATIONS, AccountDeletionStep.REVOKE_AUTH,
			AccountDeletionStep.REMOVE_PUSH_TOKENS, AccountDeletionStep.ANONYMIZE_OPERATIONAL_DATA);
	}

	private DefaultAccountDeletionWorkflow workflow(List<AccountDeletionStep> effects, AccountDeletionStep failure) {
		List<PrivacyDeletionPort> ports = new ArrayList<>();
		for (AccountDeletionStep step : AccountDeletionStep.values()) {
			if (step == AccountDeletionStep.COMPLETE) {
				continue;
			}
			ports.add(new PrivacyDeletionPort() {
				@Override
				public AccountDeletionStep step() {
					return step;
				}

				@Override
				public void execute(UserId userId) {
					effects.add(step);
					if (step == failure) {
						throw new PrivacyDeletionException("VAULT_DELETE_FAILED", new RuntimeException());
					}
				}
			});
		}
		return new DefaultAccountDeletionWorkflow(store, ports, Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
