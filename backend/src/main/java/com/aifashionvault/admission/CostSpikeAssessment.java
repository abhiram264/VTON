package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Instant;

public record CostSpikeAssessment(
	boolean suspected,
	SoftBreachDimension dimension,
	BigDecimal currentWindowSpend,
	BigDecimal baselineMeanWindowSpend,
	long currentWindowExecutions,
	Instant observationWindowStart,
	Instant observationWindowEnd) {

	public CostSpikeAssessment {
		if (suspected == (dimension == null)) {
			throw new IllegalArgumentException("suspected must match dimension");
		}
		if (dimension == SoftBreachDimension.USER) {
			throw new IllegalArgumentException("dimension must be GLOBAL or PROVIDER");
		}
		if (currentWindowSpend == null || currentWindowSpend.signum() < 0) {
			throw new IllegalArgumentException("currentWindowSpend must be non-negative");
		}
		if (baselineMeanWindowSpend == null || baselineMeanWindowSpend.signum() < 0) {
			throw new IllegalArgumentException("baselineMeanWindowSpend must be non-negative");
		}
		if (currentWindowExecutions < 0) {
			throw new IllegalArgumentException("currentWindowExecutions must be non-negative");
		}
		if (observationWindowStart == null || observationWindowEnd == null
			|| !observationWindowStart.isBefore(observationWindowEnd)) {
			throw new IllegalArgumentException("observationWindowStart must be before observationWindowEnd");
		}
	}
}
