package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.shared.UserId;

public final class AggregateBackedCostCircuitBreaker implements GlobalCostCircuitBreaker {
	private final GenerationCostMetrics costMetrics;
	private final AbuseBudgetPolicy policy;
	private final CostBreachPolicy breachPolicy;
	private final CostSpikeDetector spikeDetector;

	public AggregateBackedCostCircuitBreaker(GenerationCostMetrics costMetrics, AbuseBudgetPolicy policy) {
		this(costMetrics, policy, CostBreachPolicy.DEFAULT);
	}

	public AggregateBackedCostCircuitBreaker(GenerationCostMetrics costMetrics, AbuseBudgetPolicy policy,
		CostBreachPolicy breachPolicy) {
		this.costMetrics = Objects.requireNonNull(costMetrics, "costMetrics");
		this.policy = Objects.requireNonNull(policy, "policy");
		this.breachPolicy = Objects.requireNonNull(breachPolicy, "breachPolicy");
		this.spikeDetector = new AggregateBackedCostSpikeDetector(costMetrics, breachPolicy.spikePolicy());
	}

	@Override
	public AbuseDecision authorize(ProviderExecutionBudgetRequest request) {
		Objects.requireNonNull(request, "request");
		var start = request.now().minus(policy.window());
		var end = request.now();
		var providerCeiling = policy.providerCeilings().get(request.providerKey());
		if (!policy.currency().equals(request.currency()) || providerCeiling == null) {
			return decision(false, AbuseDecisionReason.COST_UNKNOWN, null, null, request.currency(), start, end,
				null);
		}

		var global = aggregate(null, null, null, start, end);
		var provider = aggregate(null, request.providerKey(), null, start, end);
		var user = aggregate(request.userId(), request.providerKey(), request.modelRouteAuditIdentity(), start, end);
		if (!known(global) || !known(provider) || !known(user)) {
			return decision(false, AbuseDecisionReason.COST_UNKNOWN, null, null, request.currency(), start, end,
				null);
		}

		var spike = spikeDetector
			.assess(new CostSpikeQuery(request.providerKey(), policy.currency(), request.now()));
		var projection = request.projectedCostCeiling();
		if (exhausted(global.totalCost(), projection, policy.globalCeiling())) {
			return decision(false, AbuseDecisionReason.GLOBAL_COST_CIRCUIT_OPEN, global.totalCost(),
				policy.globalCeiling(), request.currency(), start, end,
				hardIndicators(firstSoftBreach(global, provider, user, projection, providerCeiling), spike));
		}
		if (exhausted(provider.totalCost(), projection, providerCeiling)) {
			return decision(false, AbuseDecisionReason.PROVIDER_BUDGET_EXHAUSTED, provider.totalCost(),
				providerCeiling, request.currency(), start, end,
				hardIndicators(firstSoftBreach(global, provider, user, projection, providerCeiling), spike));
		}
		if (exhausted(user.totalCost(), projection, policy.perUserCeiling())) {
			return decision(false, AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED, user.totalCost(),
				policy.perUserCeiling(), request.currency(), start, end,
				hardIndicators(firstSoftBreach(global, provider, user, projection, providerCeiling), spike));
		}
		return decision(true, AbuseDecisionReason.ALLOWED, user.totalCost(), policy.perUserCeiling(),
			request.currency(), start, end,
			indicators(firstSoftBreach(global, provider, user, projection, providerCeiling), spike));
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

	private SoftBreachDimension firstSoftBreach(CostAggregate global, CostAggregate provider, CostAggregate user,
		BigDecimal projection, BigDecimal providerCeiling) {
		if (soft(global, projection, policy.globalCeiling(), breachPolicy.globalSoftCeilingFraction())) {
			return SoftBreachDimension.GLOBAL;
		}
		if (soft(provider, projection, providerCeiling, breachPolicy.providerSoftCeilingFraction())) {
			return SoftBreachDimension.PROVIDER;
		}
		if (soft(user, projection, policy.perUserCeiling(), breachPolicy.perUserSoftCeilingFraction())) {
			return SoftBreachDimension.USER;
		}
		return null;
	}

	private boolean soft(CostAggregate aggregate, BigDecimal projected, BigDecimal ceiling, BigDecimal fraction) {
		return known(aggregate) && exhausted(aggregate.totalCost(), projected, ceiling.multiply(fraction));
	}

	private static CostBreachIndicators hardIndicators(SoftBreachDimension softBreach, CostSpikeAssessment spike) {
		return new CostBreachIndicators(softBreach, spike, Set.of(CostBreachAction.values()));
	}

	private static CostBreachIndicators indicators(SoftBreachDimension softBreach, CostSpikeAssessment spike) {
		var suspected = spike != null && spike.suspected();
		if (softBreach == null && !suspected) {
			return new CostBreachIndicators(null, spike, Set.of());
		}
		var actions = new LinkedHashSet<CostBreachAction>();
		actions.add(CostBreachAction.DISABLE_HD_GENERATION);
		actions.add(CostBreachAction.RESTRICT_MULTI_ITEM);
		actions.add(CostBreachAction.PREFER_CHEAPER_PROVIDER);
		if (suspected) {
			actions.add(CostBreachAction.TEMPORARILY_QUEUE);
		}
		return new CostBreachIndicators(softBreach, spike, actions);
	}

	private static AbuseDecision decision(boolean allowed, AbuseDecisionReason reason, BigDecimal observed,
		BigDecimal ceiling, String currency, Instant start, Instant end, CostBreachIndicators indicators) {
		return new AbuseDecision(allowed, reason, observed, ceiling, currency, start, end, indicators);
	}
}
