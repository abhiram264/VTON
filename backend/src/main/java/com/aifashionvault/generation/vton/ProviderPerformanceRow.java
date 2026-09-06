package com.aifashionvault.generation.vton;

import java.math.BigDecimal;

public record ProviderPerformanceRow(
	String providerKey,
	String modelOrMode,
	String category,
	BigDecimal fidelity,
	BigDecimal qualityPassRate,
	Long meanLatencyMs,
	BigDecimal failRate,
	BigDecimal costPerSuccess,
	String currency,
	long sampleCount,
	long technicalSuccessCount,
	long qualityApprovedCount) {

	public ProviderPerformanceRow {
		requireNonBlank(providerKey, "providerKey");
		requireNonBlank(modelOrMode, "modelOrMode");
		requireNonBlank(category, "category");
		requireUnitInterval(fidelity, "fidelity");
		requireUnitInterval(qualityPassRate, "qualityPassRate");
		if (failRate == null
			|| failRate.compareTo(BigDecimal.ZERO) < 0
			|| failRate.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("failRate must be within [0,1]");
		}
		if (meanLatencyMs != null && meanLatencyMs < 0) {
			throw new IllegalArgumentException("meanLatencyMs must not be negative");
		}
		if (costPerSuccess != null && costPerSuccess.compareTo(BigDecimal.ZERO) < 0) {
			throw new IllegalArgumentException("costPerSuccess must not be negative");
		}
		if (sampleCount < 0) {
			throw new IllegalArgumentException("sampleCount must not be negative");
		}
		if (technicalSuccessCount < 0) {
			throw new IllegalArgumentException("technicalSuccessCount must not be negative");
		}
		if (qualityApprovedCount < 0) {
			throw new IllegalArgumentException("qualityApprovedCount must not be negative");
		}
		if (technicalSuccessCount > sampleCount) {
			throw new IllegalArgumentException("technicalSuccessCount must not exceed sampleCount");
		}
		if (qualityApprovedCount > technicalSuccessCount) {
			throw new IllegalArgumentException("qualityApprovedCount must not exceed technicalSuccessCount");
		}
		if (qualityApprovedCount == 0 && costPerSuccess != null) {
			throw new IllegalArgumentException("costPerSuccess is undefined without quality-approved successes");
		}
	}

	private static void requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private static void requireUnitInterval(BigDecimal value, String field) {
		if (value != null
			&& (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
			throw new IllegalArgumentException(field + " must be within [0,1]");
		}
	}
}
