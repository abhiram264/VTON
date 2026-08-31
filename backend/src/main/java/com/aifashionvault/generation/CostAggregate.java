package com.aifashionvault.generation;

import java.math.BigDecimal;

public record CostAggregate(
	BigDecimal totalCost,
	BigDecimal costPerRequest,
	BigDecimal costPerProviderSuccess,
	BigDecimal costPerQualityApprovedLook,
	BigDecimal costPerActiveUser,
	BigDecimal costPerRetainedUser,
	long requestCount,
	long providerSuccessCount,
	long qualityApprovedLookCount,
	long activeUserCount,
	long retainedUserCount,
	long knownCostCount,
	String currency) {
}
