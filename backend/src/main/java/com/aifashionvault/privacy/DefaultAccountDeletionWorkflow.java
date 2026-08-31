package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aifashionvault.shared.UserId;

public final class DefaultAccountDeletionWorkflow implements AccountDeletionWorkflow {

	private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
	private static final Map<AccountDeletionStep, AccountDeletionStep> NEXT = nextSteps();

	private final AccountDeletionWorkflowStore store;
	private final Map<AccountDeletionStep, PrivacyDeletionPort> ports;
	private final Clock clock;

	public DefaultAccountDeletionWorkflow(AccountDeletionWorkflowStore store, List<PrivacyDeletionPort> ports, Clock clock) {
		this.store = Objects.requireNonNull(store, "store is required");
		this.clock = Objects.requireNonNull(clock, "clock is required");
		Objects.requireNonNull(ports, "ports are required");
		this.ports = new EnumMap<>(AccountDeletionStep.class);
		for (PrivacyDeletionPort port : ports) {
			PrivacyDeletionPort prior = this.ports.put(Objects.requireNonNull(port, "port is required").step(), port);
			if (prior != null) {
				throw new IllegalArgumentException("duplicate deletion step: " + port.step());
			}
		}
		if (!this.ports.keySet().equals(NEXT.keySet())) {
			throw new IllegalArgumentException("every deletion step requires exactly one port");
		}
	}

	@Override
	public AccountDeletionStatus request(UserId userId) {
		UserId owner = Objects.requireNonNull(userId, "userId is required");
		AccountDeletionStatus status = store.request(owner, clock.instant());
		return status.state() == AccountDeletionState.COMPLETE ? status : resume(owner);
	}

	@Override
	public AccountDeletionStatus resume(UserId userId) {
		UserId owner = Objects.requireNonNull(userId, "userId is required");
		Instant now = clock.instant();
		var lease = store.acquire(owner, now, now.plus(LEASE_DURATION));
		if (lease.isEmpty()) {
			return store.status(owner);
		}
		AccountDeletionWorkflowStore.Lease held = lease.orElseThrow();
		AccountDeletionStatus status = held.status();
		while (status.nextStep() != AccountDeletionStep.COMPLETE) {
			AccountDeletionStep step = status.nextStep();
			try {
				ports.get(step).execute(owner);
				status = store.advance(status.workflowId(), held.token(), step, NEXT.get(step), clock.instant());
			}
			catch (RuntimeException failure) {
				String safeCode = failure instanceof PrivacyDeletionException privacyFailure
						? privacyFailure.safeCode() : "PRIVACY_STEP_FAILED";
				return store.fail(status.workflowId(), held.token(), step, safeCode, clock.instant());
			}
		}
		return status;
	}

	private static Map<AccountDeletionStep, AccountDeletionStep> nextSteps() {
		AccountDeletionStep[] steps = AccountDeletionStep.values();
		Map<AccountDeletionStep, AccountDeletionStep> result = new EnumMap<>(AccountDeletionStep.class);
		for (int index = 0; index < steps.length - 1; index++) {
			result.put(steps[index], steps[index + 1]);
		}
		return Map.copyOf(result);
	}
}
