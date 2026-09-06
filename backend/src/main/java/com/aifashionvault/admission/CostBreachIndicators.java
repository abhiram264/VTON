package com.aifashionvault.admission;

import java.util.Set;

public record CostBreachIndicators(
	SoftBreachDimension softBreach,
	CostSpikeAssessment spike,
	Set<CostBreachAction> actions) {

	public CostBreachIndicators {
		if (actions == null) {
			throw new IllegalArgumentException("actions must be non-null");
		}
		actions = Set.copyOf(actions);
		if (actions.isEmpty() && (softBreach != null || spike != null && spike.suspected())) {
			throw new IllegalArgumentException(
				"actions must not be empty when a soft breach or suspected spike is present");
		}
	}
}
