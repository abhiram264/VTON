package com.aifashionvault.generation;

import java.time.Instant;

public record CostQuery(
	String providerKey,
	String modelRouteAuditIdentity,
	String currency,
	Instant windowStart,
	Instant windowEnd) {

	public CostQuery {
		if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
			throw new IllegalArgumentException("windowStart must be before windowEnd");
		}
		if (providerKey != null && providerKey.isBlank()
			|| modelRouteAuditIdentity != null && modelRouteAuditIdentity.isBlank()
			|| currency != null && currency.isBlank()) {
			throw new IllegalArgumentException("filters must be null or non-blank");
		}
	}
}
