package com.aifashionvault.generation.benchmark;

import java.math.BigDecimal;

public record BenchmarkMatrixRow(
	String providerKey,
	String modelOrMode,
	String category,
	BigDecimal fidelity,
	long p50LatencyMs,
	BigDecimal failRate,
	BigDecimal costPerSuccess,
	long sampleCount,
	long successCount) {

	public BenchmarkMatrixRow {
		BenchmarkContractRules.requireNonBlank(providerKey, "providerKey");
		BenchmarkContractRules.requireNonBlank(modelOrMode, "modelOrMode");
		BenchmarkContractRules.requireNonBlank(category, "category");
		BenchmarkContractRules.requireUnitInterval(fidelity, "fidelity");
		if (p50LatencyMs < 0) {
			throw new IllegalArgumentException("p50LatencyMs must not be negative");
		}
		BenchmarkContractRules.requireUnitInterval(failRate, "failRate");
		BenchmarkContractRules.requireNonNegative(costPerSuccess, "costPerSuccess");
		if (successCount == 0 && costPerSuccess != null) {
			throw new IllegalArgumentException("costPerSuccess is undefined without quality-approved successes");
		}
		if (sampleCount < 0) {
			throw new IllegalArgumentException("sampleCount must not be negative");
		}
		if (successCount < 0) {
			throw new IllegalArgumentException("successCount must not be negative");
		}
		if (successCount > sampleCount) {
			throw new IllegalArgumentException("successCount must not exceed sampleCount");
		}
	}
}
