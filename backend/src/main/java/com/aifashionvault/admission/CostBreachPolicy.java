package com.aifashionvault.admission;

import java.math.BigDecimal;

public record CostBreachPolicy(
	BigDecimal globalSoftCeilingFraction,
	BigDecimal providerSoftCeilingFraction,
	BigDecimal perUserSoftCeilingFraction,
	CostSpikePolicy spikePolicy) {

	public static final CostBreachPolicy DEFAULT = new CostBreachPolicy(new BigDecimal("0.80"),
		new BigDecimal("0.80"), new BigDecimal("0.80"), CostSpikePolicy.DEFAULT);

	public CostBreachPolicy {
		requireFraction(globalSoftCeilingFraction, "globalSoftCeilingFraction");
		requireFraction(providerSoftCeilingFraction, "providerSoftCeilingFraction");
		requireFraction(perUserSoftCeilingFraction, "perUserSoftCeilingFraction");
		if (spikePolicy == null) {
			throw new IllegalArgumentException("spikePolicy must be non-null");
		}
	}

	private static void requireFraction(BigDecimal fraction, String name) {
		if (fraction == null || fraction.signum() <= 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(name + " must be in (0, 1]");
		}
	}
}
