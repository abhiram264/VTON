package com.aifashionvault.generation.benchmark;

import java.math.BigDecimal;

import com.aifashionvault.shared.QualityDecision;

public record BenchmarkCaseResult(
	String runId,
	String caseId,
	String providerKey,
	String modelOrMode,
	String category,
	long latencyMs,
	Outcome outcome,
	BigDecimal cost,
	String currency,
	BigDecimal humanRating,
	BigDecimal identityFidelity,
	BigDecimal garmentFidelity,
	BigDecimal colorFidelity,
	boolean artifacts,
	QualityDecision qualityGateResult) {

	public BenchmarkCaseResult {
		BenchmarkContractRules.requireNonBlank(runId, "runId");
		BenchmarkContractRules.requireNonBlank(caseId, "caseId");
		BenchmarkContractRules.requireNonBlank(providerKey, "providerKey");
		BenchmarkContractRules.requireNonBlank(modelOrMode, "modelOrMode");
		BenchmarkContractRules.requireNonBlank(category, "category");
		if (latencyMs < 0) {
			throw new IllegalArgumentException("latencyMs must not be negative");
		}
		if (outcome == null) {
			throw new IllegalArgumentException("outcome is required");
		}
		BenchmarkContractRules.requireQualityDecisionConsistency(outcome, qualityGateResult);
		BenchmarkContractRules.requireNonNegative(cost, "cost");
		if (cost != null) {
			BenchmarkContractRules.requireNonBlank(currency, "currency");
		}
		else if (currency != null) {
			throw new IllegalArgumentException("currency is only meaningful with a determinable cost");
		}
		BenchmarkContractRules.requireNonNegative(humanRating, "humanRating");
		BenchmarkContractRules.requireUnitInterval(identityFidelity, "identityFidelity");
		BenchmarkContractRules.requireUnitInterval(garmentFidelity, "garmentFidelity");
		BenchmarkContractRules.requireUnitInterval(colorFidelity, "colorFidelity");
	}
}
