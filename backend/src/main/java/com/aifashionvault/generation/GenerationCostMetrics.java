package com.aifashionvault.generation;

public interface GenerationCostMetrics {

	void recordAttempt(GenerationCostFact fact);

	CostAggregate aggregate(CostQuery query);
}
