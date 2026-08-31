package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.aifashionvault.shared.UserId;

public record ProviderExecutionBudgetRequest(
	UserId userId,
	String providerKey,
	String modelRouteAuditIdentity,
	BigDecimal projectedCostCeiling,
	String currency,
	Instant now) {

	public ProviderExecutionBudgetRequest {
		Objects.requireNonNull(userId, "userId");
		providerKey = requireText(providerKey, "providerKey");
		modelRouteAuditIdentity = requireText(modelRouteAuditIdentity, "modelRouteAuditIdentity");
		if (projectedCostCeiling == null || projectedCostCeiling.signum() <= 0) {
			throw new IllegalArgumentException("projectedCostCeiling must be positive");
		}
		currency = requireText(currency, "currency");
		Objects.requireNonNull(now, "now");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be non-blank");
		}
		return value;
	}
}
