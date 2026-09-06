package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;

/**
 * Tunable threshold policy for benchmark release gating (Plan §52, Plan §23).
 */
public record ReleaseGateThresholds(
		BigDecimal fidelityMaxRegressionPercent,
		BigDecimal identityMaxRegressionPercent,
		BigDecimal p50MaxRegressionPercent,
		BigDecimal p95MaxRegressionPercent,
		BigDecimal failRateCeiling,
		BigDecimal failRateMaxIncreaseAbsolute,
		BigDecimal qualityPassRateFloor,
		BigDecimal costPerSuccessMaxIncreasePercent,
		long p50CeilingMs,
		long p95CeilingMs,
		long minSampleSize) {

	public ReleaseGateThresholds {
		validateNotNullAndUnitInterval(fidelityMaxRegressionPercent, "fidelityMaxRegressionPercent");
		validateNotNullAndUnitInterval(identityMaxRegressionPercent, "identityMaxRegressionPercent");
		validateNotNullAndUnitInterval(p50MaxRegressionPercent, "p50MaxRegressionPercent");
		validateNotNullAndUnitInterval(p95MaxRegressionPercent, "p95MaxRegressionPercent");
		validateNotNullAndUnitInterval(failRateCeiling, "failRateCeiling");
		validateNotNullAndUnitInterval(failRateMaxIncreaseAbsolute, "failRateMaxIncreaseAbsolute");
		validateNotNullAndUnitInterval(qualityPassRateFloor, "qualityPassRateFloor");
		if (costPerSuccessMaxIncreasePercent != null) {
			validateUnitInterval(costPerSuccessMaxIncreasePercent, "costPerSuccessMaxIncreasePercent");
		}
		if (p50CeilingMs <= 0) {
			throw new IllegalArgumentException("p50CeilingMs must be positive, got: " + p50CeilingMs);
		}
		if (p95CeilingMs <= 0) {
			throw new IllegalArgumentException("p95CeilingMs must be positive, got: " + p95CeilingMs);
		}
		if (minSampleSize < 1) {
			throw new IllegalArgumentException("minSampleSize must be at least 1, got: " + minSampleSize);
		}
	}

	private static void validateNotNullAndUnitInterval(BigDecimal value, String fieldName) {
		if (value == null) {
			throw new IllegalArgumentException(fieldName + " must not be null");
		}
		validateUnitInterval(value, fieldName);
	}

	private static void validateUnitInterval(BigDecimal value, String fieldName) {
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(fieldName + " must be in [0, 1], got: " + value);
		}
	}

	public static ReleaseGateThresholds defaults() {
		return new ReleaseGateThresholds(
				new BigDecimal("0.02"),
				new BigDecimal("0.01"),
				new BigDecimal("0.05"),
				new BigDecimal("0.10"),
				new BigDecimal("0.10"),
				new BigDecimal("0.02"),
				new BigDecimal("0.90"),
				new BigDecimal("0.10"),
				15000L,
				30000L,
				30L);
	}
}
