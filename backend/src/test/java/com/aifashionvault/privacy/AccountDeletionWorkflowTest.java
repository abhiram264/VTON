package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

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

	private static final class FakeStore implements AccountDeletionWorkflowStore {
		private final UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
		private AccountDeletionStatus status;

		@Override public AccountDeletionStatus request(UserId userId, Instant now) {
			if (status == null) status = new AccountDeletionStatus(id, userId, AccountDeletionStep.REQUESTED,
					AccountDeletionState.REQUESTED, 0, now, now, null, null);
			return status;
		}
		@Override public Optional<Lease> acquire(UserId userId, Instant now, Instant expiresAt) {
			return status.state() == AccountDeletionState.COMPLETE ? Optional.empty()
					: Optional.of(new Lease(status, UUID.randomUUID()));
		}
		@Override public AccountDeletionStatus advance(UUID workflowId, UUID token, AccountDeletionStep expected,
				AccountDeletionStep next, Instant now) {
			status = new AccountDeletionStatus(id, USER, next,
					next == AccountDeletionStep.COMPLETE ? AccountDeletionState.COMPLETE : AccountDeletionState.RUNNING,
					status.attemptCount(), status.requestedAt(), now, next == AccountDeletionStep.COMPLETE ? now : null, null);
			return status;
		}
		@Override public AccountDeletionStatus fail(UUID workflowId, UUID token, AccountDeletionStep expected,
				String code, Instant now) {
			status = new AccountDeletionStatus(id, USER, expected, AccountDeletionState.RETRYABLE_FAILURE,
					status.attemptCount() + 1, status.requestedAt(), now, null, code);
			return status;
		}
		@Override public AccountDeletionStatus status(UserId userId) { return status; }
	}
}
