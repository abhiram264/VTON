package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.time.Instant;

public record PricingSnapshot(
	String providerKey,
	String modelRouteAuditIdentity,
	String pricingCatalogVersion,
	Instant observedAt,
	String billingUnit,
	BigDecimal estimatedUnits,
	BigDecimal unitPrice,
	String currency,
	BigDecimal estimatedCostCeiling) {

	public static final String COST_ESTIMATED = "ESTIMATED";
	public static final String COST_FINAL = "FINAL";
	public static final String COST_UNKNOWN = "UNKNOWN";

	public PricingSnapshot {
		if (providerKey == null || providerKey.isBlank()) {
			throw new IllegalArgumentException("providerKey is required");
		}
		if (observedAt == null) {
			throw new IllegalArgumentException("observedAt is required");
		}
		if (currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("currency is required");
		}
	}

	public static boolean isCostStatus(String costStatus) {
		return costStatus == null
			|| COST_ESTIMATED.equals(costStatus)
			|| COST_FINAL.equals(costStatus)
			|| COST_UNKNOWN.equals(costStatus);
	}

	public BigDecimal estimatedCost() {
		if (estimatedUnits == null || unitPrice == null) {
			return null;
		}
		return estimatedUnits.multiply(unitPrice);
	}
}
