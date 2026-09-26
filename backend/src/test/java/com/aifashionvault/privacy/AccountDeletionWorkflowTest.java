package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.privacy.AccountDeletionWorkflowStore.Lease;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionWorkflowTest {

	private static final UserId USER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

	@Test
	void runsDisableFirstThenEveryDestructiveStepInExactOrderAndReplayHasNoEffects() {
		FakeStore store = new FakeStore();
		List<AccountDeletionStep> effects = new ArrayList<>();
		DefaultAccountDeletionWorkflow workflow = workflow(store, effects, null);

		AccountDeletionStatus result = workflow.request(USER);
		assertThat(effects).containsExactly(AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS,
				AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS,
				AccountDeletionStep.DELETE_TEMPORARY_GENERATIONS, AccountDeletionStep.REVOKE_AUTH,
				AccountDeletionStep.REMOVE_PUSH_TOKENS, AccountDeletionStep.ANONYMIZE_OPERATIONAL_DATA);
		assertThat(result.state()).isEqualTo(AccountDeletionState.COMPLETE);
		workflow.request(USER);
		assertThat(effects).hasSize(8);
	}

	@Test
	void failureRetainsCheckpointWithSafeCodeAndRetryRepeatsThatStep() {
		FakeStore store = new FakeStore();
		List<AccountDeletionStep> effects = new ArrayList<>();
		DefaultAccountDeletionWorkflow first = workflow(store, effects, AccountDeletionStep.DELETE_VAULT);

		AccountDeletionStatus failed = first.request(USER);
		assertThat(failed.nextStep()).isEqualTo(AccountDeletionStep.DELETE_VAULT);
		assertThat(failed.state()).isEqualTo(AccountDeletionState.RETRYABLE_FAILURE);
		assertThat(failed.lastErrorCode()).isEqualTo("VAULT_DELETE_FAILED");
		assertThat(failed.attemptCount()).isEqualTo(1);

		AccountDeletionStatus complete = workflow(store, effects, null).resume(USER);
		assertThat(complete.state()).isEqualTo(AccountDeletionState.COMPLETE);
		assertThat(effects).containsExactly(AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS,
				AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS,
				AccountDeletionStep.DELETE_TEMPORARY_GENERATIONS, AccountDeletionStep.REVOKE_AUTH,
				AccountDeletionStep.REMOVE_PUSH_TOKENS, AccountDeletionStep.ANONYMIZE_OPERATIONAL_DATA);
	}

	@Test
	void strictCasRejectsMismatchedCheckpointUpdates() {
		FakeStore store = new FakeStore();
		store.request(USER, NOW);
		Lease first = store.acquire(USER, NOW, NOW.plusSeconds(300)).orElseThrow();
		UUID otherWorkflow = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UUID otherToken = UUID.fromString("44444444-4444-4444-4444-444444444444");

		assertThatThrownBy(() -> store.advance(otherWorkflow, first.token(), AccountDeletionStep.REQUESTED,
				AccountDeletionStep.DELETE_AVATARS, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(first.status().workflowId(), otherToken,
				AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, NOW))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(first.status().workflowId(), first.token(),
				AccountDeletionStep.DELETE_VAULT, AccountDeletionStep.DELETE_LOOKS, NOW))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> store.advance(first.status().workflowId(), first.token(),
				AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_VAULT, NOW))
				.isInstanceOf(IllegalStateException.class);
		assertThat(store.status(USER).nextStep()).isEqualTo(AccountDeletionStep.REQUESTED);
	}

	@Test
	void staleL1CannotOverwriteCheckpointAfterExactExpiryTakeoverByL2() {
		FakeStore store = new FakeStore();
		store.request(USER, NOW);
		Instant l1ExpiresAt = NOW.plusSeconds(300);
		Lease l1 = store.acquire(USER, NOW, l1ExpiresAt).orElseThrow();

		assertThat(store.acquire(USER, NOW.plusSeconds(1), NOW.plusSeconds(301))).isEmpty();
		Lease l2 = store.acquire(USER, l1ExpiresAt, l1ExpiresAt.plusSeconds(300)).orElseThrow();
		assertThat(l2.token()).isNotEqualTo(l1.token());

		assertThatThrownBy(() -> store.advance(l1.status().workflowId(), l1.token(), AccountDeletionStep.REQUESTED,
				AccountDeletionStep.DELETE_AVATARS, l1ExpiresAt)).isInstanceOf(IllegalStateException.class);
		AccountDeletionStatus advanced = store.advance(l2.status().workflowId(), l2.token(),
				AccountDeletionStep.REQUESTED, AccountDeletionStep.DELETE_AVATARS, l1ExpiresAt);
		assertThat(advanced.nextStep()).isEqualTo(AccountDeletionStep.DELETE_AVATARS);
	}

	private static DefaultAccountDeletionWorkflow workflow(FakeStore store, List<AccountDeletionStep> effects,
			AccountDeletionStep failure) {
		List<PrivacyDeletionPort> ports = new ArrayList<>();
		for (AccountDeletionStep step : AccountDeletionStep.values()) {
			if (step == AccountDeletionStep.COMPLETE) continue;
			ports.add(new PrivacyDeletionPort() {
				@Override public AccountDeletionStep step() { return step; }
				@Override public void execute(UserId userId) {
					effects.add(step);
					if (step == failure) throw new PrivacyDeletionException("VAULT_DELETE_FAILED", new RuntimeException());
				}
			});
		}
		return new DefaultAccountDeletionWorkflow(store, ports, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	// Orchestration-only strict CAS double; owner scoping and physical purge are exercised by their real seams.
	private static final class FakeStore implements AccountDeletionWorkflowStore {
		private final UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
		private AccountDeletionStatus status;
		private UUID leaseToken;
		private Instant leaseAcquiredAt;
		private Instant leaseExpiresAt;
		private long leaseSequence;

		@Override public AccountDeletionStatus request(UserId userId, Instant now) {
			if (status == null) status = new AccountDeletionStatus(id, userId, AccountDeletionStep.REQUESTED,
					AccountDeletionState.REQUESTED, 0, now, now, null, null);
			return status;
		}
		@Override public Optional<Lease> acquire(UserId userId, Instant now, Instant expiresAt) {
			if (status.state() == AccountDeletionState.COMPLETE) return Optional.empty();
			if (leaseToken != null && now.isBefore(leaseExpiresAt)) return Optional.empty();
			if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("lease must expire after acquisition");
			leaseToken = new UUID(0, ++leaseSequence);
			leaseAcquiredAt = now;
			leaseExpiresAt = expiresAt;
			return Optional.of(new Lease(status, leaseToken));
		}
		@Override public AccountDeletionStatus advance(UUID workflowId, UUID token, AccountDeletionStep expected,
				AccountDeletionStep next, Instant now) {
			requireLease(workflowId, token, expected, now);
			if (next != successor(expected)) throw new IllegalStateException("unexpected deletion successor");
			boolean complete = next == AccountDeletionStep.COMPLETE;
			status = new AccountDeletionStatus(id, status.userId(), next,
					complete ? AccountDeletionState.COMPLETE : AccountDeletionState.RUNNING,
					status.attemptCount(), status.requestedAt(), now, complete ? now : null, null);
			if (complete) releaseLease();
			return status;
		}
		@Override public AccountDeletionStatus fail(UUID workflowId, UUID token, AccountDeletionStep expected,
				String code, Instant now) {
			requireLease(workflowId, token, expected, now);
			status = new AccountDeletionStatus(id, status.userId(), expected, AccountDeletionState.RETRYABLE_FAILURE,
					status.attemptCount() + 1, status.requestedAt(), now, null, code);
			releaseLease();
			return status;
		}
		@Override public AccountDeletionStatus status(UserId userId) { return status; }

		private void requireLease(UUID workflowId, UUID token, AccountDeletionStep expected, Instant now) {
			if (workflowId == null || !id.equals(workflowId) || token == null || !token.equals(leaseToken)
					|| expected != status.nextStep()) {
				throw new IllegalStateException("stale deletion checkpoint");
			}
			if (now.isBefore(leaseAcquiredAt) || !now.isBefore(leaseExpiresAt)) {
				throw new IllegalStateException("expired deletion lease");
			}
		}

		private static AccountDeletionStep successor(AccountDeletionStep step) {
			AccountDeletionStep[] steps = AccountDeletionStep.values();
			if (step == AccountDeletionStep.COMPLETE) throw new IllegalArgumentException("workflow is complete");
			return steps[step.ordinal() + 1];
		}

		private void releaseLease() {
			leaseToken = null;
			leaseAcquiredAt = null;
			leaseExpiresAt = null;
		}
	}
}
