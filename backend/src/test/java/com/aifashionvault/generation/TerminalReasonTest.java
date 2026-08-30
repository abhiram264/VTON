package com.aifashionvault.generation;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.TerminalReason;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalReasonTest {

	@Test
	void valuesMatchTheFrozenTerminalReasonsInDeclarationOrder() {
		assertThat(TerminalReason.values())
			.extracting(Enum::name)
			.containsExactly(
				"INPUT_INVALID",
				"UNSUPPORTED_ITEM",
				"CONTENT_POLICY_REJECTED",
				"PROVIDER_AUTH_CONFIGURATION",
				"PROVIDER_QUOTA_EXHAUSTED",
				"PROVIDER_RATE_LIMIT_EXHAUSTED",
				"PROVIDER_UNAVAILABLE",
				"PROVIDER_TIMEOUT_EXHAUSTED",
				"SUBMISSION_OUTCOME_INDETERMINATE",
				"REMOTE_EXECUTION_NOT_FOUND",
				"PROVIDER_PROTOCOL_ERROR",
				"PROVIDER_UNKNOWN_FAILURE",
				"QUALITY_REJECTED",
				"QUALITY_RETRY_EXHAUSTED",
				"QUALITY_EVALUATOR_UNAVAILABLE",
				"RETRY_BUDGET_EXHAUSTED",
				"RETRY_POLICY_MISSING",
				"RETRY_POLICY_INVALID",
				"RETRY_POLICY_UNSUPPORTED_VERSION",
				"ORCHESTRATION_DEADLINE_EXCEEDED",
				"CANCELLED_BY_USER",
				"USER_DELETED",
				"INPUT_MEDIA_DELETED",
				"INPUT_MEDIA_MISSING",
				"INPUT_SNAPSHOT_STALE",
				"STORAGE_UNAVAILABLE",
				"COST_BUDGET_BLOCKED",
				"NO_ELIGIBLE_PROVIDER",
				"INTERNAL_INVARIANT_FAILURE");
	}
}
