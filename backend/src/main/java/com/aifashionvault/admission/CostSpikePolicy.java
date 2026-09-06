package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

public record CostSpikePolicy(
	Duration observationWindow,
	int baselineWindowCount,
	BigDecimal spikeFactor,
	long minimumWindowExecutions,
	Set<SoftBreachDimension> spikeDimensions) {

	public static final CostSpikePolicy DEFAULT = new CostSpikePolicy(Duration.ofHours(1), 24, new BigDecimal("3.0"), 5,
		Set.of(SoftBreachDimension.GLOBAL, SoftBreachDimension.PROVIDER));

	public CostSpikePolicy {
		if (observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()) {
			throw new IllegalArgumentException("observationWindow must be positive");
		}
		if (baselineWindowCount < 1) {
			throw new IllegalArgumentException("baselineWindowCount must be at least 1");
		}
		if (spikeFactor == null || spikeFactor.signum() <= 0) {
			throw new IllegalArgumentException("spikeFactor must be positive");
		}
		if (minimumWindowExecutions < 0) {
			throw new IllegalArgumentException("minimumWindowExecutions must be non-negative");
		}
		if (spikeDimensions == null || spikeDimensions.isEmpty()) {
			throw new IllegalArgumentException("spikeDimensions must not be empty");
		}
		if (spikeDimensions.contains(SoftBreachDimension.USER)) {
			throw new IllegalArgumentException("spikeDimensions must not contain USER");
		}
		spikeDimensions = Set.copyOf(spikeDimensions);
	}
}
