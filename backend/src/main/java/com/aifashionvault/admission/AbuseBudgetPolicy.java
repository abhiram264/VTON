package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

public record AbuseBudgetPolicy(
	BigDecimal globalCeiling,
	BigDecimal perUserCeiling,
	Map<String, BigDecimal> providerCeilings,
	String currency,
	Duration window) {

	public AbuseBudgetPolicy {
		requirePositive(globalCeiling, "globalCeiling");
		requirePositive(perUserCeiling, "perUserCeiling");
		if (providerCeilings == null || providerCeilings.isEmpty()) {
			throw new IllegalArgumentException("providerCeilings must not be empty");
		}
		providerCeilings = Map.copyOf(providerCeilings);
		providerCeilings.forEach((provider, ceiling) -> {
			if (provider == null || provider.isBlank()) {
				throw new IllegalArgumentException("provider key must be non-blank");
			}
			requirePositive(ceiling, "provider ceiling");
		});
		if (currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("currency must be non-blank");
		}
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("window must be positive");
		}
	}

	private static void requirePositive(BigDecimal value, String name) {
		if (value == null || value.signum() <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
