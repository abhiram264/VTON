package com.aifashionvault.generation;

import java.math.BigDecimal;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

public record GenerationCostFact(
	GenerationJobId generationId,
	GenerationAttemptId attemptId,
	String providerKey,
	String modelRouteAuditIdentity,
	String technicalOutcome,
	String qualityOutcome,
	BigDecimal billedCredits,
	BigDecimal billedUnits,
	BigDecimal cost,
	String currency,
	String costStatus,
	boolean qualityApprovedLook,
	boolean activeUser,
	boolean retainedUser) {

	public GenerationCostFact {
		if (generationId == null || attemptId == null) {
			throw new IllegalArgumentException("generationId and attemptId are required");
		}
		if (providerKey == null || providerKey.isBlank()) {
			throw new IllegalArgumentException("providerKey is required");
		}
		if (modelRouteAuditIdentity == null || modelRouteAuditIdentity.isBlank()) {
			throw new IllegalArgumentException("modelRouteAuditIdentity is required");
		}
		if (!"SUCCESS".equals(technicalOutcome) && !"FAILED".equals(technicalOutcome)) {
			throw new IllegalArgumentException("technicalOutcome must be SUCCESS or FAILED");
		}
		if (qualityOutcome != null
			&& !"PASS".equals(qualityOutcome)
			&& !"RETRY".equals(qualityOutcome)
			&& !"FAIL".equals(qualityOutcome)) {
			throw new IllegalArgumentException("qualityOutcome must be PASS, RETRY, FAIL, or null");
		}
		if (!PricingSnapshot.isCostStatus(costStatus) || costStatus == null) {
			throw new IllegalArgumentException("costStatus must be ESTIMATED, FINAL, or UNKNOWN");
		}
		if (billedCredits != null && billedCredits.signum() < 0
			|| billedUnits != null && billedUnits.signum() < 0
			|| cost != null && cost.signum() < 0) {
			throw new IllegalArgumentException("billing values must be non-negative");
		}
		if (PricingSnapshot.COST_UNKNOWN.equals(costStatus)) {
			if (cost != null) {
				throw new IllegalArgumentException("UNKNOWN cost must not have a monetary value");
			}
		}
		else if (cost == null || currency == null || currency.isBlank()) {
			throw new IllegalArgumentException("known cost requires cost and currency");
		}
		if (qualityApprovedLook && (!"SUCCESS".equals(technicalOutcome) || !"PASS".equals(qualityOutcome))) {
			throw new IllegalArgumentException("qualityApprovedLook requires technical SUCCESS and quality PASS");
		}
	}
}
