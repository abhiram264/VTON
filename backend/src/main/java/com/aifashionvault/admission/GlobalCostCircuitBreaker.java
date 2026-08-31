package com.aifashionvault.admission;

public interface GlobalCostCircuitBreaker {
	AbuseDecision authorize(ProviderExecutionBudgetRequest request);
}
