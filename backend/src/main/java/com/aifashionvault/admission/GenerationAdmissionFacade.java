package com.aifashionvault.admission;

import java.util.Objects;

public final class GenerationAdmissionFacade implements AbuseControls {
	private final GenerationRateLimiter rateLimiter;
	private final GlobalCostCircuitBreaker costCircuitBreaker;

	public GenerationAdmissionFacade(GenerationRateLimiter rateLimiter, GlobalCostCircuitBreaker costCircuitBreaker) {
		this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
		this.costCircuitBreaker = Objects.requireNonNull(costCircuitBreaker, "costCircuitBreaker");
	}

	@Override
	public GenerationAdmissionDecision admitGeneration(GenerationAdmissionRequest request) {
		GenerationRateLimiter.Reservation reservation = rateLimiter.reserve(Objects.requireNonNull(request, "request"));
		if (reservation.decision().allowed()) rateLimiter.commit(reservation);
		return reservation.decision();
	}

	@Override
	public AbuseDecision authorizeProviderExecution(ProviderExecutionBudgetRequest request) {
		return costCircuitBreaker.authorize(Objects.requireNonNull(request, "request"));
	}
}
