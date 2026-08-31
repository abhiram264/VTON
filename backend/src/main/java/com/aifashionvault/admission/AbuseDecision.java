package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AbuseDecision(
	boolean allowed,
	AbuseDecisionReason reason,
	BigDecimal observedSpend,
	BigDecimal ceiling,
	String currency,
	Instant windowStart,
	Instant windowEnd) {

	public AbuseDecision {
		Objects.requireNonNull(reason, "reason");
		if (allowed != (reason == AbuseDecisionReason.ALLOWED)) {
			throw new IllegalArgumentException("allowed must match reason");
		}
		if (observedSpend != null && observedSpend.signum() < 0) {
			throw new IllegalArgumentException("observedSpend must be non-negative");
		}
		if (ceiling != null && ceiling.signum() <= 0) {
			throw new IllegalArgumentException("ceiling must be positive");
		}
		if (currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("currency must be non-blank");
		}
		if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
			throw new IllegalArgumentException("windowStart must be before windowEnd");
		}
	}
}
