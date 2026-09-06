package com.aifashionvault.generation.vton;

import java.time.Instant;

public record ProviderPerformanceQuery(
	Instant windowStart,
	Instant windowEnd,
	String providerKey,
	String category,
	String currency) {

	public ProviderPerformanceQuery {
		if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
			throw new IllegalArgumentException("windowStart must be before windowEnd");
		}
		if (providerKey != null && providerKey.isBlank()
			|| category != null && category.isBlank()
			|| currency != null && currency.isBlank()) {
			throw new IllegalArgumentException("filters must be null or non-blank");
		}
	}
}
