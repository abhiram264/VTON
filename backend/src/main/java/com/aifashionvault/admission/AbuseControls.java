package com.aifashionvault.admission;

public interface AbuseControls {
	GenerationAdmissionDecision admitGeneration(GenerationAdmissionRequest request);

	AbuseDecision authorizeProviderExecution(ProviderExecutionBudgetRequest request);
}
