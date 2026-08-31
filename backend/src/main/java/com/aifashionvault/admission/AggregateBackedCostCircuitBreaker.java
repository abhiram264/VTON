package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.shared.UserId;

public final class AggregateBackedCostCircuitBreaker implements GlobalCostCircuitBreaker {
	private final GenerationCostMetrics costMetrics;
	private final AbuseBudgetPolicy policy;

	public AggregateBackedCostCircuitBreaker(GenerationCostMetrics costMetrics, AbuseBudgetPolicy policy) {
		this.costMetrics = Objects.requireNonNull(costMetrics, "costMetrics");
		this.policy = Objects.requireNonNull(policy, "policy");
	}

	@Override
	public AbuseDecision authorize(ProviderExecutionBudgetRequest request) {
		Objects.requireNonNull(request, "request");
		var start = request.now().minus(policy.window());
		var end = request.now();
		var providerCeiling = policy.providerCeilings().get(request.providerKey());
		if (!policy.currency().equals(request.currency()) || providerCeiling == null) {
			return decision(false, AbuseDecisionReason.COST_UNKNOWN, null, null, request.currency(), start, end);
		}

		var global = aggregate(null, null, null, start, end);
		var projection = request.projectedCostCeiling();
		if (known(global) && exhausted(global.totalCost(), projection, policy.globalCeiling())) {
			return decision(false, AbuseDecisionReason.GLOBAL_COST_CIRCUIT_OPEN, global.totalCost(),
				policy.globalCeiling(), request.currency(), start, end);
		}

		var provider = aggregate(null, request.providerKey(), request.modelRouteAuditIdentity(), start, end);
		if (known(provider) && exhausted(provider.totalCost(), projection, providerCeiling)) {
			return decision(false, AbuseDecisionReason.PROVIDER_BUDGET_EXHAUSTED, provider.totalCost(),
				providerCeiling, request.currency(), start, end);
		}

		var user = aggregate(request.userId(), request.providerKey(), request.modelRouteAuditIdentity(), start, end);
		if (known(user) && exhausted(user.totalCost(), projection, policy.perUserCeiling())) {
			return decision(false, AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED, user.totalCost(),
				policy.perUserCeiling(), request.currency(), start, end);
		}
		if (!known(global) || !known(provider) || !known(user)) {
			return decision(false, AbuseDecisionReason.COST_UNKNOWN, null, null, request.currency(), start, end);
		}
		return decision(true, AbuseDecisionReason.ALLOWED, user.totalCost(), policy.perUserCeiling(),
			request.currency(), start, end);
	}

	private CostAggregate aggregate(UserId userId, String provider, String model, Instant start, Instant end) {
		return costMetrics.aggregate(new CostQuery(userId, provider, model, policy.currency(), start, end));
	}

	private boolean known(CostAggregate aggregate) {
		return aggregate != null && aggregate.totalCost() != null && aggregate.totalCost().signum() >= 0
			&& policy.currency().equals(aggregate.currency());
	}

	private static boolean exhausted(BigDecimal observed, BigDecimal projected, BigDecimal ceiling) {
		return observed.add(projected).compareTo(ceiling) >= 0;
	}

	private static AbuseDecision decision(boolean allowed, AbuseDecisionReason reason, BigDecimal observed,
		BigDecimal ceiling, String currency, Instant start, Instant end) {
		return new AbuseDecision(allowed, reason, observed, ceiling, currency, start, end);
	}
}
